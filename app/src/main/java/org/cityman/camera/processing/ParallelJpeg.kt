package org.cityman.camera.processing

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * JPEG encoding on several cores without native code. The image is cut into horizontal strips
 * whose height is a whole number of MCU rows; each strip is encoded by the system encoder on its
 * own thread, and the strips' entropy-coded data are joined into one baseline JPEG with restart
 * markers between them (each strip starts with fresh DC predictions, which is exactly what a
 * restart does). Falls back to a single encode if the strips' tables differ.
 */
object ParallelJpeg {
    private const val TAG = "ArcoJpeg"
    private val pool = Executors.newFixedThreadPool(4)

    /** NV21 pixels, encoded in parallel strips (the YUV encoder uses the standard tables). */
    fun encodeNv21(nv21: ByteArray, w: Int, h: Int, quality: Int, out: OutputStream) {
        val joined = runCatching { encodeYuvStrips(nv21, w, h, quality) }.onFailure { Log.w(TAG, "parallel encode", it) }.getOrNull()
        if (joined != null) {
            out.write(joined)
        } else {
            android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
                .compressToJpeg(android.graphics.Rect(0, 0, w, h), quality, out)
        }
    }

    private fun encodeYuvStrips(nv21: ByteArray, w: Int, h: Int, quality: Int): ByteArray? {
        val image = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
        val parts = stripBounds(w, h) ?: return fail("one strip")
        val bytes = parts.map { (y0, y1) ->
            pool.submit(Callable {
                val bos = ByteArrayOutputStream(w * (y1 - y0) / 2)
                image.compressToJpeg(android.graphics.Rect(0, y0, w, y1), quality, bos)
                bos.toByteArray()
            })
        }.map { it.get() }
        return join(bytes, w, h, parts[0].second - parts[0].first)
    }

    /** Strip edges: whole MCU rows, few enough MCUs per strip for a 16-bit restart interval. */
    private fun stripBounds(w: Int, h: Int): List<Pair<Int, Int>>? {
        val mcu = 16
        val mcusPerRow = (w + mcu - 1) / mcu
        var strips = 4
        var stripRows = ((h + strips - 1) / strips + mcu - 1) / mcu * mcu
        while (mcusPerRow.toLong() * (stripRows / mcu) > 65535) {
            strips++
            stripRows = ((h + strips - 1) / strips + mcu - 1) / mcu * mcu
        }
        val list = (0 until strips).map { it * stripRows }.filter { it < h }.map { it to minOf(h, it + stripRows) }
        return if (list.size < 2) null else list
    }

    private fun join(parts: List<ByteArray>, w: Int, h: Int, stripRows: Int): ByteArray? {
        val mcu = 16
        val first = parse(parts[0]) ?: return fail("cannot parse strip 0")
        if (first.hSamp != 2 || first.vSamp != 2) return fail("sampling ${first.hSamp}x${first.vSamp}")
        val others = parts.drop(1).mapIndexed { i, b -> parse(b) ?: return fail("cannot parse strip ${i + 1}") }
        for (o in others) if (!o.tables.contentEquals(first.tables)) return fail("tables differ")
        val mcusPerRow = (w + mcu - 1) / mcu
        val out = ByteArrayOutputStream(parts.sumOf { it.size } + 64)
        val p0 = parts[0]
        out.write(p0, 0, first.sofHeightPos)
        out.write((h shr 8) and 0xff)
        out.write(h and 0xff)
        out.write(p0, first.sofHeightPos + 2, first.sosPos - first.sofHeightPos - 2)
        val interval = mcusPerRow * (stripRows / mcu)
        out.write(byteArrayOf(0xFF.toByte(), 0xDD.toByte(), 0, 4, (interval shr 8).toByte(), interval.toByte()))
        out.write(p0, first.sosPos, first.dataStart - first.sosPos)
        val all = listOf(first) + others
        for (i in parts.indices) {
            out.write(parts[i], all[i].dataStart, all[i].dataEnd - all[i].dataStart)
            if (i < parts.size - 1) {
                out.write(0xFF)
                out.write(0xD0 + (i % 8))
            }
        }
        out.write(0xFF)
        out.write(0xD9)
        return out.toByteArray()
    }

    fun encode(bitmap: Bitmap, quality: Int, out: OutputStream) {
        val joined = runCatching { encodeStrips(bitmap, quality) }.onFailure { Log.w(TAG, "parallel encode", it) }.getOrNull()
        if (joined != null) out.write(joined) else bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    }

    private fun encodeStrips(bitmap: Bitmap, quality: Int): ByteArray? {
        val w = bitmap.width
        val h = bitmap.height
        val mcu = 16 // 4:2:0 subsampling; checked against the encoder's SOF below
        val mcusPerRow = (w + mcu - 1) / mcu
        var strips = 4
        var stripRows = ((h + strips - 1) / strips + mcu - 1) / mcu * mcu
        // The restart interval (MCUs per strip) must fit in 16 bits.
        while (mcusPerRow.toLong() * (stripRows / mcu) > 65535) {
            strips++
            stripRows = ((h + strips - 1) / strips + mcu - 1) / mcu * mcu
        }
        val starts = (0 until strips).map { it * stripRows }.filter { it < h }
        if (starts.size < 2) return fail("one strip")
        val parts = starts.map { y0 ->
            pool.submit(Callable {
                val hh = minOf(stripRows, h - y0)
                val strip = Bitmap.createBitmap(bitmap, 0, y0, w, hh)
                val bos = ByteArrayOutputStream(w * hh / 2)
                strip.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                if (strip !== bitmap) strip.recycle()
                bos.toByteArray()
            })
        }.map { it.get() }

        val first = parse(parts[0]) ?: return fail("cannot parse strip 0")
        if (first.hSamp != 2 || first.vSamp != 2) return fail("sampling ${first.hSamp}x${first.vSamp}")
        val others = parts.drop(1).mapIndexed { i, b -> parse(b) ?: return fail("cannot parse strip ${i + 1}") }
        // Same tables everywhere, or the joined stream would be wrong.
        for (o in others) {
            if (!o.tables.contentEquals(first.tables)) return fail("tables differ")
        }

        val out = ByteArrayOutputStream(parts.sumOf { it.size } + 64)
        val p0 = parts[0]
        // Header of the first strip up to the SOS, with the full height and a restart interval.
        out.write(p0, 0, first.sofHeightPos)
        out.write((h shr 8) and 0xff)
        out.write(h and 0xff)
        out.write(p0, first.sofHeightPos + 2, first.sosPos - first.sofHeightPos - 2)
        val interval = mcusPerRow * (stripRows / mcu)
        out.write(byteArrayOf(0xFF.toByte(), 0xDD.toByte(), 0, 4, (interval shr 8).toByte(), interval.toByte()))
        out.write(p0, first.sosPos, first.dataStart - first.sosPos)
        val all = listOf(first) + others
        for (i in parts.indices) {
            val info = all[i]
            out.write(parts[i], info.dataStart, info.dataEnd - info.dataStart)
            if (i < parts.size - 1) {
                out.write(0xFF)
                out.write(0xD0 + (i % 8))
            }
        }
        out.write(0xFF)
        out.write(0xD9)
        return out.toByteArray()
    }

    private fun fail(why: String): ByteArray? {
        Log.w(TAG, "single-threaded encode: $why")
        return null
    }

    private class Info(
        val sofHeightPos: Int,
        val sosPos: Int,
        val dataStart: Int,
        val dataEnd: Int,
        val hSamp: Int,
        val vSamp: Int,
        /** DQT and DHT segments, which must match between strips. */
        val tables: ByteArray,
    )

    /** Finds the SOF0 height field, the SOS, the entropy data and the tables of a baseline JPEG. */
    private fun parse(b: ByteArray): Info? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        var sofHeight = -1
        var hs = 0
        var vs = 0
        val tables = ByteArrayOutputStream()
        while (i + 4 <= b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val marker = b[i + 1].toInt() and 0xff
            val len = ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
            when (marker) {
                0xC0 -> {
                    sofHeight = i + 5
                    // first component (Y) sampling factors
                    val f = b[i + 11].toInt() and 0xff
                    hs = f shr 4
                    vs = f and 0x0f
                }
                0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF -> return null
                0xDB, 0xC4 -> tables.write(b, i, len + 2)
                0xDD -> return null // already has restart markers
                0xDA -> {
                    if (sofHeight < 0) return null
                    val dataStart = i + 2 + len
                    var end = b.size - 2
                    while (end > dataStart && !(b[end] == 0xFF.toByte() && b[end + 1] == 0xD9.toByte())) end--
                    if (end <= dataStart) return null
                    return Info(sofHeight, i, dataStart, end, hs, vs, tables.toByteArray())
                }
            }
            i += 2 + len
        }
        return null
    }
}
