package org.cityman.camera.processing

import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.LensShadingMap
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.opengl.GLES31
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Which steps of the finishing stage run; all of them can be turned off in the settings. */
data class ProcessingOptions(
    val denoise: Boolean = true,
    /** Stronger contrast and colour than the default natural look. */
    val vivid: Boolean = false,
    val localTone: Boolean = true,
    val chromaDenoise: Boolean = true,
    val sharpen: Boolean = true,
)

/** Fixed facts about one burst, known before the first frame arrives. */
data class BurstSetup(
    val width: Int,
    val height: Int,
    /** Quad channel (0 R, 1 G even row, 2 G odd row, 3 B) at raw (0,0), (1,0), (0,1), (1,1). */
    val chanAt: IntArray,
    val black: FloatArray,
    val white: Float,
    /** Digital gain to apply after merging (under-exposure and ISO cap compensation). */
    val gain: Float,
    val rotation: Int,
    /** Pixels dropped on each side of the output (RAW wider than the active array). */
    val crop: Int = 0,
    val iso: Int,
    val options: ProcessingOptions,
    /** Frames in the burst: the sharpest of the first three becomes the reference. */
    val frames: Int = 8,
    /** How far the burst was under-exposed (EV): high in backlit scenes, which get more tone compression. */
    val underEv: Float = 0.7f,
)

/**
 * Merges a burst of RAW frames on the GPU: tile alignment on a grey pyramid (coarse to fine),
 * robust temporal merge against the first frame, then demosaic, colour, tone mapping and
 * sharpening. Frames are merged as they arrive, so memory stays constant with burst length.
 * Must be used from one thread (the GL context is current there).
 */
class MultiFrameProcessor {
    private var gl: GlContext? = null
    private val programs = HashMap<String, Program>()

    private var setup: BurstSetup? = null
    private var raw: Tex? = null
    private var ref: Tex? = null
    private var alt: Tex? = null
    /** The first frames, kept until the reference is chosen. */
    private var held: MutableList<Tex> = ArrayList()
    private var sharpness: MutableList<Double> = ArrayList()
    private var refChosen = false
    private var spare: Tex? = null
    private var refPyr: List<Tex> = emptyList()
    private var altPyr: List<Tex> = emptyList()
    private var align: List<Tex> = emptyList()
    /** Two accumulators used alternately (images in ES 3.1 cannot be read and written as rgba16f). */
    private var accs: Array<Tex>? = null
    private var accCur = 0
    private var decision: Tex? = null
    private var weight: Tex? = null
    private var histBuffer = 0
    private var noise = floatArrayOf(1e-4f, 1e-6f)
    private var frames = 0
    private var startedAt = 0L

    private val laps = ArrayList<String>()
    private var lapStart = 0L

    /** Stage timing (waits for the GPU) when profiling is on. */
    private fun lap(name: String) {
        if (!PROFILE) return
        GLES31.glFinish()
        val now = SystemClock.elapsedRealtime()
        if (laps.isNotEmpty() || lapStart != 0L) laps += "$name=${now - lapStart}"
        lapStart = now
    }

    private fun program(name: String, src: String) = programs.getOrPut(name) { Program(name, src) }

    private fun ensureGl() {
        if (gl == null) gl = GlContext()
    }

    fun begin(s: BurstSetup) {
        ensureGl()
        releaseFrames()
        setup = s
        frames = 0
        startedAt = SystemClock.elapsedRealtime()
        val qw = s.width / 2
        val qh = s.height / 2
        raw = Tex(s.width, s.height, GLES31.GL_R16UI)
        held = ArrayList()
        sharpness = ArrayList()
        refChosen = false
        ref = null
        alt = null
        spare = null
        refPyr = pyramid(qw, qh)
        altPyr = pyramid(qw, qh)
        align = LEVELS.indices.map { i ->
            val lvl = refPyr[i]
            val t = LEVELS[i].tile
            Tex((lvl.width + t - 1) / t, (lvl.height + t - 1) / t, GLES31.GL_RGBA16I)
        }
        accs = arrayOf(Tex(qw, qh, GLES31.GL_RGBA16F), Tex(qw, qh, GLES31.GL_RGBA16F))
        accCur = 0
        decision = Tex((qw + 1) / 2, (qh + 1) / 2, GLES31.GL_RGBA16F)
        weight = Tex(qw, qh, GLES31.GL_R32F)
        Log.i(TAG, "burst ${s.width}x${s.height} gain=${s.gain} rot=${s.rotation} iso=${s.iso}")
    }

    /** Uploads and merges one RAW16 frame. The caller closes the image afterwards. */
    fun addFrame(image: Image) {
        val s = setup ?: return
        val t0 = SystemClock.elapsedRealtime()
        lapStart = t0
        laps += "start"
        val plane = image.planes[0]
        val r = raw!!
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 2)
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ROW_LENGTH, plane.rowStride / plane.pixelStride)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, r.id)
        GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, s.width, s.height, GLES31.GL_RED_INTEGER, GLES31.GL_UNSIGNED_SHORT, plane.buffer)
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ROW_LENGTH, 0)
        Gl.check("upload")
        lap("upload")

        if (!refChosen) {
            // Hold the first frames and score their sharpness; merge once the reference is known.
            val q = Tex(r.width / 2, r.height / 2, GLES31.GL_RGBA16F)
            pack(r, q, s)
            held += q
            sharpness += measureSharpness(q)
            lap("pack+sharpness")
            if (held.size >= min(HOLD, s.frames)) chooseReference(s)
        } else {
            val target = alt!!
            pack(r, target, s)
            lap("pack")
            gray(target, altPyr)
            lap("pyramid")
            alignLevels()
            lap("align")
            merge()
            lap("merge")
        }
        frames++
        GLES31.glFinish()
        Gl.check("frame $frames")
        Log.i(TAG, "frame $frames: ${SystemClock.elapsedRealtime() - t0} ms ${laps.joinToString(" ")}")
        laps.clear()
    }

    /** Picks the sharpest held frame as reference, then merges the other held frames into it. */
    private fun chooseReference(s: BurstSetup) {
        if (refChosen || held.isEmpty()) return
        val best = sharpness.indices.maxBy { sharpness[it] }
        Log.i(TAG, "reference frame $best of ${held.size}, sharpness ${sharpness.map { String.format(java.util.Locale.US, "%.3g", it) }}")
        ref = held[best]
        gray(ref!!, refPyr)
        run {
            val p = program("init", Shaders.INIT)
            p.use()
            ref!!.bindImage(0, GLES31.GL_READ_ONLY)
            accs!![0].bindImage(1, GLES31.GL_WRITE_ONLY)
            accCur = 0
            weight!!.bindImage(2, GLES31.GL_WRITE_ONLY)
            p.dispatch(ref!!.width, ref!!.height)
        }
        var noiseDone = false
        val others = held.filterIndexed { i, _ -> i != best }
        for (q in others) {
            alt = q
            gray(q, altPyr)
            alignLevels()
            if (!noiseDone) {
                estimateNoise(s)
                noiseDone = true
            }
            merge()
        }
        // Later frames reuse one held texture; any other one stays as a spare for the finish.
        alt = others.firstOrNull() ?: Tex(ref!!.width, ref!!.height, GLES31.GL_RGBA16F)
        spare = others.getOrNull(1)
        held = ArrayList()
        refChosen = true
        this.noiseEstimated = noiseDone
    }

    private var noiseEstimated = false

    /** Mean squared Laplacian of the grey image (higher is sharper). */
    private fun measureSharpness(q: Tex): Double {
        gray(q, altPyr, levels = 1)
        if (sharpBuffer == 0) {
            val ids = IntArray(1)
            GLES31.glGenBuffers(1, ids, 0)
            sharpBuffer = ids[0]
        }
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, sharpBuffer)
        val zeros = ByteBuffer.allocateDirect(SHARP_BINS * 4).order(ByteOrder.nativeOrder())
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, SHARP_BINS * 4, zeros, GLES31.GL_DYNAMIC_READ)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, sharpBuffer)
        val p = program("sharpness", Shaders.SHARPNESS)
        p.use()
        altPyr[0].bindImage(0, GLES31.GL_READ_ONLY)
        val g = altPyr[0]
        p.dispatch((g.width + 1) / 2, (g.height + 1) / 2)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, sharpBuffer)
        val mapped = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, SHARP_BINS * 4, GLES31.GL_MAP_READ_BIT) as ByteBuffer
        val ints = IntArray(SHARP_BINS)
        mapped.order(ByteOrder.nativeOrder()).asIntBuffer().get(ints)
        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        var sum = 0.0
        for (v in ints) sum += (v.toLong() and 0xffffffffL).toDouble()
        return sum
    }

    private var sharpBuffer = 0

    /** Finishes the merged image; returns an upright ARGB bitmap. */
    /**
     * The merged frame as a RAW16 mosaic (little endian, width × 2 bytes per row), for a DNG.
     * Call after the last frame and before [finish].
     */
    fun mergedRaw(): ByteBuffer? {
        val s = setup ?: return null
        if (!refChosen) chooseReference(s)
        val acc = accs?.get(accCur) ?: return null
        val qw = s.width / 2
        val qh = s.height / 2
        val out = Tex(qw, qh, GLES31.GL_RGBA16UI)
        val p = program("mosaic", Shaders.MOSAIC)
        p.use()
        acc.bindImage(0, GLES31.GL_READ_ONLY)
        weight!!.bindImage(1, GLES31.GL_READ_ONLY)
        out.bindImage(2, GLES31.GL_WRITE_ONLY)
        val b = FloatArray(4)
        for (i in 0 until 4) b[s.chanAt[i]] = s.black[i]
        p.set("uBlack", b[0], b[1], b[2], b[3])
        p.set("uWhite", s.white)
        p.dispatch(qw, qh)
        val fbo = IntArray(1)
        GLES31.glGenFramebuffers(1, fbo, 0)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fbo[0])
        GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0, GLES31.GL_TEXTURE_2D, out.id, 0)
        val quads = ByteBuffer.allocateDirect(qw * qh * 8).order(ByteOrder.nativeOrder())
        GLES31.glReadPixels(0, 0, qw, qh, GLES31.GL_RGBA_INTEGER, GLES31.GL_UNSIGNED_SHORT, quads)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glDeleteFramebuffers(1, fbo, 0)
        out.release()
        Gl.check("merged raw")
        // Quad channels back to their places in the 2×2 pattern.
        val src = quads.asShortBuffer()
        val mosaic = ByteBuffer.allocateDirect(s.width * s.height * 2).order(ByteOrder.LITTLE_ENDIAN)
        val dst = mosaic.asShortBuffer()
        val pos = IntArray(4) // raw index within the 2×2 block of each quad channel
        for (i in 0 until 4) pos[s.chanAt[i]] = i
        // Row by row with bulk copies (element access on NIO buffers is far too slow here).
        val quadRow = ShortArray(qw * 4)
        val row0 = ShortArray(s.width)
        val row1 = ShortArray(s.width)
        val off0 = IntArray(4) { c -> if (pos[c] < 2) pos[c] % 2 else -1 }
        val off1 = IntArray(4) { c -> if (pos[c] >= 2) pos[c] % 2 else -1 }
        for (y in 0 until qh) {
            src.position(y * qw * 4)
            src.get(quadRow)
            for (c in 0 until 4) {
                val o0 = off0[c]
                val o1 = off1[c]
                if (o0 >= 0) {
                    var x = 0
                    while (x < qw) { row0[x * 2 + o0] = quadRow[x * 4 + c]; x++ }
                } else {
                    var x = 0
                    while (x < qw) { row1[x * 2 + o1] = quadRow[x * 4 + c]; x++ }
                }
            }
            dst.position(y * 2 * s.width); dst.put(row0)
            dst.position((y * 2 + 1) * s.width); dst.put(row1)
        }
        mosaic.position(0)
        return mosaic
    }

    fun finish(
        gains: RggbChannelVector,
        ccm: ColorSpaceTransform,
        shading: LensShadingMap?,
    ): Bitmap {
        val s = setup ?: error("no burst")
        if (!refChosen) chooseReference(s)
        val t0 = SystemClock.elapsedRealtime()
        val qw = s.width / 2
        val qh = s.height / 2
        val o = s.options

        // Lens shading map as a small texture (R, G even, G odd, B).
        val lsc = lensShadingTexture(shading)
        // Merged quad with shading and white balance → reuse alt; half RGB → reuse ref.
        val mq = alt!!
        val half = ref!!
        run {
            val p = program("prep", Shaders.PREP)
            p.use()
            accs!![accCur].bindImage(0, GLES31.GL_READ_ONLY)
            weight!!.bindImage(1, GLES31.GL_READ_ONLY)
            mq.bindImage(2, GLES31.GL_WRITE_ONLY)
            half.bindImage(3, GLES31.GL_WRITE_ONLY)
            lsc.bindSampler(0)
            p.set("uLsc", 0)
            p.set("uLscSize", lsc.width.toFloat(), lsc.height.toFloat())
            p.set("uGains", gains.red, gains.greenEven, gains.greenOdd, gains.blue)
            p.dispatch(qw, qh)
        }
        // Spatial noise reduction into one of the (now free) accumulators.
        var mqFinal = mq
        if (o.denoise) {
            val out = if (accCur == 0) accs!![1] else accs!![0]
            val p = program("denoise", Shaders.DENOISE)
            p.use()
            mq.bindImage(0, GLES31.GL_READ_ONLY)
            weight!!.bindImage(1, GLES31.GL_READ_ONLY)
            out.bindImage(2, GLES31.GL_WRITE_ONLY)
            lsc.bindSampler(0)
            p.set("uLsc", 0)
            p.set("uLscSize", lsc.width.toFloat(), lsc.height.toFloat())
            p.set("uGains", gains.red, gains.greenEven, gains.greenOdd, gains.blue)
            p.set("uNoise", noise[0], noise[1])
            p.set("uStrength", 2.2f)
            p.dispatch(qw, qh)
            mqFinal = out
        }
        // The burst buffers are no longer needed: free them before the full-size images.
        lsc.release()
        accs?.forEach { if (it !== mqFinal) it.release() }
        val keep = mqFinal.takeIf { it !== mq }
        accs = null
        decision?.release(); decision = null
        weight?.release(); weight = null
        raw?.release(); raw = null
        (refPyr + altPyr + align).forEach { it.release() }
        refPyr = emptyList(); altPyr = emptyList(); align = emptyList()

        val ccmRows = FloatArray(9).also { m ->
            for (r in 0 until 3) for (c in 0 until 3) m[r * 3 + c] = ccm.getElement(c, r).toFloat()
        }

        // Blurred half image for colour noise reduction.
        val tmp = Tex(qw, qh, GLES31.GL_RGBA16F)
        val halfBlur = Tex(qw, qh, GLES31.GL_RGBA16F, linear = true)
        run {
            val p = program("blur", Shaders.BLUR)
            p.use()
            half.bindImage(0, GLES31.GL_READ_ONLY); tmp.bindImage(1, GLES31.GL_WRITE_ONLY)
            p.setI("uDir", 1, 0); p.dispatch(qw, qh)
            tmp.bindImage(0, GLES31.GL_READ_ONLY); halfBlur.bindImage(1, GLES31.GL_WRITE_ONLY)
            p.setI("uDir", 0, 1); p.dispatch(qw, qh)
        }

        // Base layer for local tone mapping: block means of log luminance, then blurred.
        val f = 16
        val bw = (qw + f - 1) / f
        val bh = (qh + f - 1) / f
        val baseRaw = Tex(bw, bh, GLES31.GL_RGBA16F)
        val baseTmp = Tex(bw, bh, GLES31.GL_RGBA16F)
        val base = Tex(bw, bh, GLES31.GL_RGBA16F, linear = true)
        run {
            val p = program("base", Shaders.BASE)
            p.use()
            half.bindImage(0, GLES31.GL_READ_ONLY); baseRaw.bindImage(1, GLES31.GL_WRITE_ONLY)
            p.set("uF", f)
            p.setMat3("uCcm", ccmRows)
            p.dispatch(bw, bh)
            val b = program("blur", Shaders.BLUR)
            b.use()
            baseRaw.bindImage(0, GLES31.GL_READ_ONLY); baseTmp.bindImage(1, GLES31.GL_WRITE_ONLY)
            b.setI("uDir", 1, 0); b.dispatch(bw, bh)
            baseTmp.bindImage(0, GLES31.GL_READ_ONLY); base.bindImage(1, GLES31.GL_WRITE_ONLY)
            b.setI("uDir", 0, 1); b.dispatch(bw, bh)
        }
        tmp.release()
        baseTmp.release()

        // Exposure: keep the planned gain, but pull it down if the brightest areas would clip hard.
        val stats = readBase(baseRaw)
        baseRaw.release()
        // Slightly below the auto exposure brightness: deeper shadows, like the camera's own JPEG.
        var gainUse = s.gain * 0.85f
        val highlight = stats.second // log2 of a high percentile of block peaks
        // Local tone mapping and the shoulder take care of bright areas; only pull the global
        // gain down a little when they would clip very hard.
        val maxGain = 2f.pow(2.5f - highlight)
        if (gainUse > maxGain) gainUse = max(maxGain, gainUse * 0.75f)

        // Full-resolution finish into an RGBA8 image, then sharpen and rotate.
        val tone = Tex(s.width, s.height, GLES31.GL_RGBA8)
        run {
            val p = program("finish", Shaders.FINISH)
            p.use()
            mqFinal.bindImage(0, GLES31.GL_READ_ONLY)
            tone.bindImage(1, GLES31.GL_WRITE_ONLY)
            halfBlur.bindSampler(0); p.set("uHalfBlur", 0)
            base.bindSampler(1); p.set("uBase", 1)
            p.setI4("uChanAt", s.chanAt[0], s.chanAt[1], s.chanAt[2], s.chanAt[3])
            p.setMat3("uCcm", ccmRows)
            p.set("uGain", gainUse)
            p.set("uChroma", if (o.chromaDenoise) chromaStrength(s.iso, s.gain) else 0f)
            val hdr = ((s.underEv - 0.7f) / 0.9f).coerceIn(0f, 1f)
            p.set("uCompress", (if (o.vivid) 0.66f else 0.72f) - 0.1f * hdr)
            p.set("uLift", (if (o.vivid) 0.9f else 0.94f) - 0.07f * hdr)
            p.set("uAnchor", -2.5f)
            p.set("uBlack", if (o.vivid) 0.016f else 0.012f)
            p.set("uContrast", if (o.vivid) 0.45f else 0.35f)
            p.set("uSat", if (o.vivid) 1.3f else 1.15f)
            p.set("uLocal", if (o.localTone) 1f else 0f)
            p.dispatch(s.width, s.height)
        }
        halfBlur.release()
        base.release()
        keep?.release()
        alt?.release(); alt = null
        ref?.release(); ref = null
        spare?.release(); spare = null

        val rotated = s.rotation == 90 || s.rotation == 270
        val cw = s.width - 2 * s.crop
        val chh = s.height - 2 * s.crop
        val ow = if (rotated) chh else cw
        val oh = if (rotated) cw else chh
        val out = Tex(ow, oh, GLES31.GL_RGBA8)
        run {
            val p = program("sharpen", Shaders.SHARPEN)
            p.use()
            tone.bindImage(0, GLES31.GL_READ_ONLY)
            out.bindImage(1, GLES31.GL_WRITE_ONLY)
            p.set("uRot", s.rotation)
            p.set("uAmount", if (o.sharpen) 0.55f else 0f)
            p.set("uCrop", s.crop)
            p.dispatch(cw, chh)
        }
        tone.release()

        GLES31.glFinish()
        val tGpu = SystemClock.elapsedRealtime()
        val bitmap = readBitmap(out)
        Log.i(TAG, "finish: gpu ${tGpu - t0} ms, readback ${SystemClock.elapsedRealtime() - tGpu} ms")
        out.release()
        Gl.check("finish")
        Log.i(TAG, "processed $frames frames in ${SystemClock.elapsedRealtime() - startedAt} ms, gain $gainUse (planned ${s.gain}), noise ${noise.toList()}")
        setup = null
        return bitmap
    }

    /** Drops a burst that will not be finished. */
    fun abort() {
        releaseFrames()
        setup = null
    }

    fun release() {
        releaseFrames()
        programs.values.forEach { it.release() }
        programs.clear()
        if (histBuffer != 0) GLES31.glDeleteBuffers(1, intArrayOf(histBuffer), 0)
        histBuffer = 0
        if (sharpBuffer != 0) GLES31.glDeleteBuffers(1, intArrayOf(sharpBuffer), 0)
        sharpBuffer = 0
        gl?.release()
        gl = null
    }

    // ---- stages -------------------------------------------------------------------------

    private fun pack(r: Tex, target: Tex, s: BurstSetup) {
        val p = program("pack", Shaders.PACK)
        p.use()
        r.bindSampler(0)
        p.set("uRaw", 0)
        target.bindImage(0, GLES31.GL_WRITE_ONLY)
        // Raw position of each quad channel.
        val pos = IntArray(8)
        for (i in 0 until 4) {
            val ch = s.chanAt[i]
            pos[ch * 2] = i % 2
            pos[ch * 2 + 1] = i / 2
        }
        GLES31.glUniform2iv(p.loc("uOff"), 4, pos, 0)
        // Black levels in quad channel order.
        val b = FloatArray(4)
        for (i in 0 until 4) b[s.chanAt[i]] = s.black[i]
        p.set("uBlack", b[0], b[1], b[2], b[3])
        p.set("uWhite", s.white)
        p.dispatch(target.width, target.height)
    }

    private fun pyramid(w: Int, h: Int): List<Tex> {
        val out = ArrayList<Tex>()
        var cw = w
        var ch = h
        for (l in LEVELS) {
            cw = max(1, cw / l.factor)
            ch = max(1, ch / l.factor)
            out += Tex(cw, ch, GLES31.GL_R32F)
        }
        return out
    }

    private fun gray(quad: Tex, pyr: List<Tex>, levels: Int = pyr.size) {
        val g = program("gray", Shaders.GRAY)
        g.use()
        quad.bindImage(0, GLES31.GL_READ_ONLY)
        pyr[0].bindImage(1, GLES31.GL_WRITE_ONLY)
        g.dispatch(pyr[0].width, pyr[0].height)
        val d = program("down", Shaders.DOWN)
        d.use()
        for (i in 1 until levels) {
            pyr[i - 1].bindImage(0, GLES31.GL_READ_ONLY)
            pyr[i].bindImage(1, GLES31.GL_WRITE_ONLY)
            d.set("uF", LEVELS[i].factor)
            d.dispatch(pyr[i].width, pyr[i].height)
        }
    }

    private fun alignLevels() {
        val p = program("align", Shaders.ALIGN)
        p.use()
        for (i in LEVELS.indices.reversed()) {
            val lvl = LEVELS[i]
            refPyr[i].bindImage(0, GLES31.GL_READ_ONLY)
            altPyr[i].bindImage(1, GLES31.GL_READ_ONLY)
            val coarser = i + 1 < LEVELS.size
            // Without a coarser level, bind this level's own output as a dummy (uFactor = 0).
            (if (coarser) align[i + 1] else align[i]).bindImage(2, GLES31.GL_READ_ONLY)
            align[i].bindImage(3, GLES31.GL_WRITE_ONLY)
            p.set("uTile", lvl.tile)
            p.set("uRadius", lvl.radius)
            p.set("uFactor", if (coarser) LEVELS[i + 1].factor else 0)
            p.set("uPrevTile", if (coarser) LEVELS[i + 1].tile else 1)
            p.set("uL2", if (i == 0) 0 else 1)
            // The finest level scores every other pixel: a quarter of the reads, same accuracy.
            p.set("uStep", if (i == 0) 2 else 1)
            p.set("uSub", if (i == 0) 1 else 0)
            p.dispatch(align[i].width, align[i].height)
        }
    }

    private fun estimateNoise(s: BurstSetup) {
        if (histBuffer == 0) {
            val ids = IntArray(1)
            GLES31.glGenBuffers(1, ids, 0)
            histBuffer = ids[0]
        }
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histBuffer)
        val zeros = ByteBuffer.allocateDirect(HIST * 4).order(ByteOrder.nativeOrder())
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, HIST * 4, zeros, GLES31.GL_DYNAMIC_READ)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, histBuffer)
        val p = program("noise", Shaders.NOISE)
        p.use()
        ref!!.bindImage(0, GLES31.GL_READ_ONLY)
        alt!!.bindImage(1, GLES31.GL_READ_ONLY)
        align[0].bindImage(2, GLES31.GL_READ_ONLY)
        p.set("uTile", LEVELS[0].tile)
        p.dispatch((ref!!.width + 3) / 4, (ref!!.height + 3) / 4)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histBuffer)
        val mapped = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, HIST * 4, GLES31.GL_MAP_READ_BIT) as ByteBuffer
        val counts = IntArray(HIST)
        mapped.order(ByteOrder.nativeOrder()).asIntBuffer().get(counts)
        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        noise = fitNoise(counts, s.iso)
    }

    private fun merge() {
        val d = program("mergeDecide", Shaders.MERGE_DECIDE)
        d.use()
        refPyr[0].bindImage(0, GLES31.GL_READ_ONLY)
        altPyr[0].bindImage(1, GLES31.GL_READ_ONLY)
        align[0].bindImage(2, GLES31.GL_READ_ONLY)
        decision!!.bindImage(3, GLES31.GL_WRITE_ONLY)
        d.set("uTile", LEVELS[0].tile)
        d.set("uNoise", noise[0], noise[1])
        d.set("uStrength", 0.8f)
        d.dispatch(decision!!.width, decision!!.height)

        val a = program("mergeAdd", Shaders.MERGE_ADD)
        a.use()
        val acc = accs!!
        alt!!.bindImage(0, GLES31.GL_READ_ONLY)
        decision!!.bindImage(1, GLES31.GL_READ_ONLY)
        acc[accCur].bindImage(2, GLES31.GL_READ_ONLY)
        acc[1 - accCur].bindImage(3, GLES31.GL_WRITE_ONLY)
        weight!!.bindImage(4, GLES31.GL_READ_WRITE)
        a.dispatch(alt!!.width, alt!!.height)
        accCur = 1 - accCur
    }


    private fun lensShadingTexture(map: LensShadingMap?): Tex {
        val cols = map?.columnCount ?: 1
        val rows = map?.rowCount ?: 1
        val data = ByteBuffer.allocateDirect(cols * rows * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (r in 0 until rows) for (c in 0 until cols) for (ch in 0 until 4) {
            data.put(map?.getGainFactor(ch, c, r) ?: 1f)
        }
        data.position(0)
        val t = Tex(cols, rows, GLES31.GL_RGBA16F, linear = true)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, t.id)
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 4)
        GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, cols, rows, GLES31.GL_RGBA, GLES31.GL_FLOAT, data)
        Gl.check("lsc")
        return t
    }

    /** Mean log2 luminance and the 99th percentile of block peaks (log2), from the base layer. */
    private fun readBase(t: Tex): Pair<Float, Float> {
        val fbo = IntArray(1)
        GLES31.glGenFramebuffers(1, fbo, 0)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fbo[0])
        GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0, GLES31.GL_TEXTURE_2D, t.id, 0)
        val buf = ByteBuffer.allocateDirect(t.width * t.height * 4 * 4).order(ByteOrder.nativeOrder())
        GLES31.glReadPixels(0, 0, t.width, t.height, GLES31.GL_RGBA, GLES31.GL_FLOAT, buf)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glDeleteFramebuffers(1, fbo, 0)
        if (GLES31.glGetError() != GLES31.GL_NO_ERROR) return -2.5f to 0f
        val f = buf.asFloatBuffer()
        val n = t.width * t.height
        var sum = 0.0
        val peaks = FloatArray(n)
        for (i in 0 until n) {
            sum += f.get(i * 4)
            peaks[i] = f.get(i * 4 + 1)
        }
        peaks.sort()
        return (sum / n).toFloat() to peaks[(n * 0.99f).toInt().coerceIn(0, n - 1)]
    }

    private fun readBitmap(t: Tex): Bitmap {
        val fbo = IntArray(1)
        GLES31.glGenFramebuffers(1, fbo, 0)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fbo[0])
        GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0, GLES31.GL_TEXTURE_2D, t.id, 0)
        GLES31.glPixelStorei(GLES31.GL_PACK_ALIGNMENT, 4)
        val buf = ByteBuffer.allocateDirect(t.width * t.height * 4)
        GLES31.glReadPixels(0, 0, t.width, t.height, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, buf)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glDeleteFramebuffers(1, fbo, 0)
        Gl.check("readPixels")
        buf.position(0)
        val bmp = Bitmap.createBitmap(t.width, t.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    private fun releaseFrames() {
        listOfNotNull(raw, ref, alt, spare, decision, weight).forEach { it.release() }
        held.forEach { it.release() }
        held = ArrayList()
        spare = null
        accs?.forEach { it.release() }
        accs = null
        decision = null
        (refPyr + altPyr + align).forEach { it.release() }
        raw = null; ref = null; alt = null; weight = null
        refPyr = emptyList(); altPyr = emptyList(); align = emptyList()
    }

    private data class Level(val factor: Int, val tile: Int, val radius: Int)

    companion object {
        private const val TAG = "ArcoMerge"
        private const val HIST = 16 * 64
        private const val SHARP_BINS = 256
        /** Frames held to choose the reference from. */
        private const val HOLD = 3
        private const val PROFILE = false

        /** Pyramid: quad resolution, then /2, /4, /4 (as in HDR+). Tile 16 at the finest level. */
        // The coarse levels search ±4 and already cover large motion, so level 1 refines with ±2.
        private val LEVELS = listOf(Level(1, 16, 1), Level(2, 8, 2), Level(4, 8, 4), Level(4, 8, 4))

        private fun Float.pow(e: Float) = Math.pow(this.toDouble(), e.toDouble()).toFloat()

        /** Stronger colour smoothing at high gain, where chroma noise dominates. */
        fun chromaStrength(iso: Int, gain: Float): Float =
            (0.35f + 0.5f * ln(max(1f, iso * gain / 100f)) / ln(16f)).coerceIn(0.35f, 0.9f)

        /**
         * Fits variance = a·x + b from the |difference| histograms: per brightness bin the median
         * |d| of two frames gives sigma = median / (0.6745·√2).
         */
        fun fitNoise(counts: IntArray, iso: Int): FloatArray {
            val xs = ArrayList<Double>()
            val vs = ArrayList<Double>()
            val ws = ArrayList<Double>()
            for (bb in 0 until 16) {
                var total = 0L
                for (db in 0 until 64) total += counts[bb * 64 + db]
                if (total < 400) continue
                var run = 0L
                var median = 0.0
                for (db in 0 until 64) {
                    run += counts[bb * 64 + db]
                    if (run * 2 >= total) {
                        val t = (db + 0.5) / 64.0
                        median = t * t * 0.12
                        break
                    }
                }
                val sigma = median / (0.6745 * sqrt(2.0))
                val x = ((bb + 0.5) / 16.0).let { it * it }
                xs += x; vs += sigma * sigma; ws += total.toDouble()
            }
            val fallbackA = 1.2e-4 * iso / 100.0
            val fallbackB = 4e-7 * (iso / 100.0) * (iso / 100.0)
            if (xs.size < 3) return floatArrayOf(fallbackA.toFloat(), fallbackB.toFloat())
            // Weighted least squares for v = a x + b.
            var sw = 0.0; var sx = 0.0; var sv = 0.0; var sxx = 0.0; var sxv = 0.0
            for (i in xs.indices) {
                val w = ws[i]
                sw += w; sx += w * xs[i]; sv += w * vs[i]; sxx += w * xs[i] * xs[i]; sxv += w * xs[i] * vs[i]
            }
            val den = sw * sxx - sx * sx
            var a = if (den != 0.0) (sw * sxv - sx * sv) / den else fallbackA
            var b = (sv - a * sx) / sw
            if (a <= 0.0) a = fallbackA
            b = max(b, 1e-7)
            return floatArrayOf(min(a, 0.05).toFloat(), min(b, 0.01).toFloat())
        }
    }
}
