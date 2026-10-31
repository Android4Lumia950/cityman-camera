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

    init {
        runAndWait { setupGl() }
        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                if (target != EGL14.EGL_NO_SURFACE) draw(image)
            } catch (e: Exception) {
                Log.e(TAG, "video frame", e)
            } finally {
                image.close()
            }
        }, handler)
    }

    /** Starts drawing into [surface] (the recorder's input surface). */
    fun attach(surface: Surface) = runAndWait {
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
        val q = quad!!
        q.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, q)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, target, image.timestamp)
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

    companion object {
        private const val TAG = "ArcoVideo"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
    // Image row 0 at the top of the encoded frame.
    vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
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
