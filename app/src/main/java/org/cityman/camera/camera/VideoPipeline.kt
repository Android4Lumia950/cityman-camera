package org.cityman.camera.camera

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Video frames go camera → YUV ImageReader (in this process) → GL → the recorder's input
 * surface. On this ROM the gralloc cannot allocate buffers that the camera writes and the
 * encoder reads directly, while GPU-drawn encoder buffers work (screenrecord does the same).
 * Frames keep the sensor orientation; the recorder's orientation hint rotates them.
 */
class VideoPipeline(val size: Size) {
    private val thread = HandlerThread("arco-video").apply { start() }
    private val handler = Handler(thread.looper)
    val reader: ImageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var target: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texY = 0
    private var texUv = 0
    private var quad: ByteBuffer? = null
    private var uvCopy: ByteBuffer? = null
    @Volatile var framesDrawn = 0
        private set

    /** How a recording is drawn: time-lapse factor (1 = real time) and stabilisation. */
    class Options(val every: Int = 1, val stabilise: Boolean = false, val sensorOrientation: Int = 90, val front: Boolean = false)

    private var options = Options()
    private var frameIndex = 0
    private var firstPts = 0L
    private val stabiliser = Stabiliser()

    /** Gyroscope sample (SystemClock.elapsedRealtimeNanos time base), for stabilisation. */
    fun addGyro(timestampNs: Long, wx: Float, wy: Float, wz: Float) {
        if (options.stabilise) handler.post { stabiliser.addGyro(timestampNs, wx, wy, wz) }
    }

    init {
        runAndWait { setupGl() }
        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                stabiliser.noteFrameArrival(image.timestamp, android.os.SystemClock.elapsedRealtimeNanos())
                if (target != EGL14.EGL_NO_SURFACE) {
                    frameIndex++
                    if ((frameIndex - 1) % options.every == 0) draw(image)
                }
            } catch (e: Exception) {
                Log.e(TAG, "video frame", e)
            } finally {
                image.close()
            }
        }, handler)
    }

    /** Starts drawing into [surface] (the recorder's input surface). */
    fun attach(surface: Surface, opts: Options = Options()) = runAndWait {
        options = opts
        frameIndex = 0
        firstPts = 0L
        stabiliser.reset()
        target = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(target != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        framesDrawn = 0
    }

    /** Stops drawing; returns once no more frames go to the recorder. */
    fun detach() = runAndWait {
        if (target != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, target)
            target = EGL14.EGL_NO_SURFACE
        }
    }

    fun release() {
        runAndWait {
            reader.setOnImageAvailableListener(null, null)
            if (target != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, target)
            target = EGL14.EGL_NO_SURFACE
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
            }
            display = EGL14.EGL_NO_DISPLAY
        }
        reader.close()
        thread.quitSafely()
    }

    private fun setupGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            ),
            0, configs, 0, 1, n, 0,
        )
        check(n[0] > 0) { "no recordable EGL config" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)

        program = buildProgram(VERTEX, FRAGMENT)
        val tex = IntArray(2)
        GLES30.glGenTextures(2, tex, 0)
        texY = tex[0]
        texUv = tex[1]
        for ((t, fmt, w, h) in listOf(Quad(texY, GLES30.GL_R8, size.width, size.height), Quad(texUv, GLES30.GL_RG8, size.width / 2, size.height / 2))) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, fmt, w, h)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).apply {
            asFloatBuffer().put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        }
    }

    private data class Quad(val tex: Int, val format: Int, val w: Int, val h: Int)

    private fun draw(image: Image) {
        EGL14.eglMakeCurrent(display, target, target, context)
        val w = size.width
        val h = size.height
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texY)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, y.rowStride)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, y.buffer)

        // Chroma: interleaved (NV21 / NV12, pixel stride 2) uploads directly; planar is packed first.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texUv)
        val cw = w / 2
        val ch = h / 2
        val vFirst: Boolean
        val chroma: ByteBuffer
        if (u.pixelStride == 2 && v.pixelStride == 2 && u.rowStride == v.rowStride) {
            // The plane that starts first holds the interleaved pairs; its buffer may end one byte short.
            vFirst = isBefore(v.buffer, u.buffer)
            val src = if (vFirst) v.buffer else u.buffer
            val needed = (ch - 1) * u.rowStride + cw * 2
            if (src.remaining() >= needed) {
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, u.rowStride / 2)
                chroma = src
            } else {
                chroma = pack(src, needed, u.rowStride, cw * 2, ch)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
            }
        } else {
            vFirst = false
            chroma = packPlanar(u, v, cw, ch)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        }
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, cw, ch, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, chroma)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)

        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uY"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uUv"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uVFirst"), if (vFirst) 1 else 0)
        // Stabilisation: crop 10 % and move/rotate the crop against the shake.
        val t = if (options.stabilise) stabiliser.transform(image.timestamp, w, h, options.sensorOrientation, options.front) else null
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uSize"), w.toFloat(), h.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uScale"), if (t != null) 0.9f else 1f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uRot"), t?.rotation ?: 0f)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uShift"), t?.dx ?: 0f, t?.dy ?: 0f)
        val q = quad!!
        q.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, q)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        // Time-lapse: frames spaced at 1/30 s whatever their real spacing.
        val pts = if (options.every > 1) {
            if (firstPts == 0L) firstPts = image.timestamp
            firstPts + framesDrawn * 33_333_333L
        } else image.timestamp
        EGLExt.eglPresentationTimeANDROID(display, target, pts)
        EGL14.eglSwapBuffers(display, target)
        framesDrawn++
    }

    /** True when the V plane starts first (NV21): then V shifted by one byte is the U plane. */
    private fun isBefore(v: ByteBuffer, u: ByteBuffer): Boolean {
        val n = minOf(64, u.remaining() - 1, v.remaining() - 1)
        var vFirst = 0
        var uFirst = 0
        for (i in 0 until n step 2) {
            if (v.get(i + 1) == u.get(i)) vFirst++
            if (u.get(i + 1) == v.get(i)) uFirst++
        }
        return vFirst >= uFirst
    }

    private fun pack(src: ByteBuffer, needed: Int, stride: Int, rowBytes: Int, rows: Int): ByteBuffer {
        val out = uvCopy?.takeIf { it.capacity() >= rowBytes * rows }
            ?: ByteBuffer.allocateDirect(rowBytes * rows).also { uvCopy = it }
        out.clear()
        val row = ByteArray(rowBytes)
        for (r in 0 until rows) {
            val start = r * stride
            val len = minOf(rowBytes, src.limit() - start)
            src.position(start)
            src.get(row, 0, len)
            out.put(row, 0, rowBytes)
        }
        src.position(0)
        out.flip()
        return out
    }

    private fun packPlanar(u: Image.Plane, v: Image.Plane, cw: Int, ch: Int): ByteBuffer {
        val out = uvCopy?.takeIf { it.capacity() >= cw * ch * 2 }
            ?: ByteBuffer.allocateDirect(cw * ch * 2).also { uvCopy = it }
        out.clear()
        val ub = u.buffer
        val vb = v.buffer
        for (r in 0 until ch) for (c in 0 until cw) {
            out.put(ub.get(r * u.rowStride + c * u.pixelStride))
            out.put(vb.get(r * v.rowStride + c * v.pixelStride))
        }
        out.flip()
        return out
    }

    private fun runAndWait(block: () -> Unit) {
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        error?.let { throw it }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("video shader: ${GLES30.glGetShaderInfoLog(s)}")
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        return p
    }

    /**
     * Electronic stabilisation from the gyroscope: integrates the rotation, follows it with a
     * smooth path, and returns how far the frame content moved away from that path.
     */
    private class Stabiliser {
        private val times = LongArray(512)
        private val ax = FloatArray(512)
        private val ay = FloatArray(512)
        private val az = FloatArray(512)
        private var count = 0
        private var head = 0
        private var lastT = 0L
        private var angX = 0f
        private var angY = 0f
        private var angZ = 0f
        private var offset = Long.MAX_VALUE
        private var smoothX = 0f
        private var smoothY = 0f
        private var smoothZ = 0f
        private var lastFrame = 0L

        class Transform(val dx: Float, val dy: Float, val rotation: Float)

        fun reset() {
            count = 0; head = 0; lastT = 0L; angX = 0f; angY = 0f; angZ = 0f
            offset = Long.MAX_VALUE; lastFrame = 0L
        }

        fun addGyro(t: Long, wx: Float, wy: Float, wz: Float) {
            if (lastT != 0L) {
                val dt = (t - lastT) / 1e9f
                if (dt in 0f..0.1f) { angX += wx * dt; angY += wy * dt; angZ += wz * dt }
            }
            lastT = t
            times[head] = t; ax[head] = angX; ay[head] = angY; az[head] = angZ
            head = (head + 1) % times.size
            if (count < times.size) count++
        }

        /** The camera clock has an unknown base: the smallest arrival delay gives the offset. */
        fun noteFrameArrival(frameTs: Long, nowElapsed: Long) {
            val d = nowElapsed - frameTs
            if (d < offset) offset = d
        }

        private fun anglesAt(t: Long): FloatArray? {
            if (count < 2) return null
            var best = -1
            for (k in 0 until count) {
                val i = (head - 1 - k + times.size) % times.size
                if (times[i] <= t) { best = i; break }
            }
            if (best < 0) return null
            return floatArrayOf(ax[best], ay[best], az[best])
        }

        fun transform(frameTs: Long, w: Int, h: Int, sensorOrientation: Int, front: Boolean): Transform? {
            if (offset == Long.MAX_VALUE) return null
            val a = anglesAt(frameTs + offset) ?: return null
            if (lastFrame == 0L) { smoothX = a[0]; smoothY = a[1]; smoothZ = a[2] }
            val dt = if (lastFrame == 0L) 0f else ((frameTs - lastFrame) / 1e9f).coerceIn(0f, 0.2f)
            lastFrame = frameTs
            val k = 1f - kotlin.math.exp(-dt / 0.6f)
            smoothX += (a[0] - smoothX) * k
            smoothY += (a[1] - smoothY) * k
            smoothZ += (a[2] - smoothZ) * k
            var dX = a[0] - smoothX
            var dY = a[1] - smoothY
            val dZ = a[2] - smoothZ
            // Scene displacement in the phone's screen plane (x right, y up), in pixels.
            val f = w / 1.4f
            val sx = if (front) -f * dY else f * dY
            val sy = if (front) f * dX else -f * dX
            // Screen plane → frame axes for this sensor orientation.
            val th = Math.toRadians(sensorOrientation.toDouble())
            val upX = (-kotlin.math.sin(th)).toFloat()
            val upY = kotlin.math.cos(th).toFloat()
            val rightX = upY
            val rightY = -upX
            var px = sx * rightX + sy * rightY
            var py = -(sx * upX + sy * upY)
            // Stay inside the 10 % crop margin.
            val mx = w * 0.05f
            val my = h * 0.05f
            if (kotlin.math.abs(px) > mx || kotlin.math.abs(py) > my) {
                val sc = minOf(mx / kotlin.math.abs(px).coerceAtLeast(1e-3f), my / kotlin.math.abs(py).coerceAtLeast(1e-3f))
                px *= sc; py *= sc
            }
            val rot = if (front) -dZ else dZ
            return Transform(px, py, rot.coerceIn(-0.05f, 0.05f))
        }
    }

    companion object {
        private const val TAG = "ArcoVideo"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vUv;
uniform vec2 uSize;
uniform float uScale;
uniform float uRot;
uniform vec2 uShift;
void main() {
    // Image row 0 at the top of the encoded frame; source position = crop, rotation and shift
    // (in pixels, rows downwards) of the output position.
    vec2 p = vec2(aPos.x, -aPos.y) * 0.5 * uSize * uScale;
    float c = cos(uRot), s = sin(uRot);
    p = vec2(c * p.x - s * p.y, s * p.x + c * p.y) + uShift;
    vUv = p / uSize + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private const val FRAGMENT = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uY;
uniform sampler2D uUv;
uniform int uVFirst;
out vec4 color;
void main() {
    float y = texture(uY, vUv).r;
    vec2 c = texture(uUv, vUv).rg - 0.5;
    float cb = uVFirst == 1 ? c.y : c.x;
    float cr = uVFirst == 1 ? c.x : c.y;
    // Full-range BT.601 (camera YUV) to RGB.
    color = vec4(y + 1.402 * cr, y - 0.344136 * cb - 0.714136 * cr, y + 1.772 * cb, 1.0);
}
"""
    }
}
