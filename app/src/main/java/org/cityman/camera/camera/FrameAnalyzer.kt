package org.cityman.camera.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import kotlin.math.abs

/** What the analysis of the latest viewfinder frame found. */
class Analysis(
    /** 64-bin luma histogram, normalised so the tallest bin is 1. */
    val histogram: FloatArray,
    /** Share of pixels at or near white (luma ≥ 250). */
    val clipped: Float,
    /** Zebra / focus peaking marks in sensor orientation (transparent elsewhere), or null. */
    val overlay: Bitmap?,
)

/**
 * Looks at a small YUV copy of the viewfinder (about 10 times a second): luma histogram, how much
 * is blown out, and the zebra and focus peaking overlay. Runs on its own thread.
 */
class FrameAnalyzer(val size: Size, private val sink: (Analysis) -> Unit) {
    private val thread = HandlerThread("arco-analysis").apply { start() }
    private val handler = Handler(thread.looper)
    val reader: ImageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)

    /** Skip frames while a photo is captured and processed, to leave the CPU to that. */
    @Volatile var paused = false
    @Volatile var peakingColor: Int = 0xFFFFB300.toInt()
    @Volatile var zebra = false
    @Volatile var peaking = false
    @Volatile var latestClipped = -1f
        private set

    private var last = 0L
    // Two overlay bitmaps, alternated so the UI never draws one that is being written.
    private val ow = size.width / 2
    private val oh = size.height / 2
    private val overlays = arrayOf(
        Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888),
    )
    private var overlayIndex = 0
    private val pixels = IntArray(ow * oh)
    private val luma = ByteArray(size.width * size.height)

    init {
        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (paused || now - last < 100) return@setOnImageAvailableListener
                last = now
                val plane = image.planes[0]
                val buf = plane.buffer
                val stride = plane.rowStride
                val w = size.width
                val h = size.height
                for (y in 0 until h) {
                    buf.position(y * stride)
                    buf.get(luma, y * w, w)
                }
                analyse(w, h)
            } finally {
                image.close()
            }
        }, handler)
    }

    private fun analyse(w: Int, h: Int) {
        val hist = IntArray(64)
        var clipped = 0
        var count = 0
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val v = luma[y * w + x].toInt() and 0xff
                hist[v shr 2]++
                if (v >= 250) clipped++
                count++
            }
        }
        val max = hist.max().coerceAtLeast(1)
        val histogram = FloatArray(64) { hist[it].toFloat() / max }
        val clip = clipped.toFloat() / count
        latestClipped = clip

        var overlay: Bitmap? = null
        if (zebra || peaking) {
            // Half resolution: each overlay pixel looks at a 2×2 block.
            val accent = peakingColor
            for (oy in 0 until oh) {
                for (ox in 0 until ow) {
                    val x = ox * 2
                    val y = oy * 2
                    val v = luma[y * w + x].toInt() and 0xff
                    var c = 0
                    if (peaking && x in 2 until w - 2 && y in 2 until h - 2) {
                        val lap = 4 * v - (luma[y * w + x - 2].toInt() and 0xff) - (luma[y * w + x + 2].toInt() and 0xff) -
                            (luma[(y - 2) * w + x].toInt() and 0xff) - (luma[(y + 2) * w + x].toInt() and 0xff)
                        if (abs(lap) > 90) c = accent
                    }
                    if (zebra && v >= 245 && ((ox + oy) / 3) % 2 == 0) c = 0xB0FF2060.toInt()
                    pixels[oy * ow + ox] = c
                }
            }
            overlayIndex = 1 - overlayIndex
            overlay = overlays[overlayIndex]
            overlay.setPixels(pixels, 0, ow, 0, 0, ow, oh)
        }
        sink(Analysis(histogram, clip, overlay))
    }

    fun release() {
        reader.setOnImageAvailableListener(null, null)
        handler.post {
            reader.close()
            thread.quitSafely()
        }
    }
}
