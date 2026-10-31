package org.cityman.camera.processing

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES31

/** Off-screen OpenGL ES 3.1 context for compute work, current on the thread that creates it. */
class GlContext {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}

/** An immutable 2D texture usable both as an image and a sampler. */
class Tex(val width: Int, val height: Int, val format: Int, linear: Boolean = false) {
    val id: Int

    init {
        val ids = IntArray(1)
        GLES31.glGenTextures(1, ids, 0)
        id = ids[0]
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, id)
        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, format, width, height)
        val filter = if (linear) GLES31.GL_LINEAR else GLES31.GL_NEAREST
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, filter)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, filter)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
        Gl.check("texture ${width}x$height")
    }

    fun bindImage(unit: Int, access: Int) = GLES31.glBindImageTexture(unit, id, 0, false, 0, access, format)

    fun bindSampler(unit: Int) {
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + unit)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, id)
    }

    fun release() = GLES31.glDeleteTextures(1, intArrayOf(id), 0)
}

/** A compute program with uniform lookup by name. */
class Program(name: String, source: String) {
    val id: Int = GLES31.glCreateProgram()
    private val locations = HashMap<String, Int>()

    init {
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES31.glShaderSource(shader, source)
        GLES31.glCompileShader(shader)
        val ok = IntArray(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) error("shader $name: ${GLES31.glGetShaderInfoLog(shader)}")
        GLES31.glAttachShader(id, shader)
        GLES31.glLinkProgram(id)
        GLES31.glGetProgramiv(id, GLES31.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("program $name: ${GLES31.glGetProgramInfoLog(id)}")
        GLES31.glDeleteShader(shader)
    }

    fun use() = GLES31.glUseProgram(id)

    fun loc(name: String): Int = locations.getOrPut(name) { GLES31.glGetUniformLocation(id, name) }

    fun set(name: String, v: Int) = GLES31.glUniform1i(loc(name), v)
    fun set(name: String, v: Float) = GLES31.glUniform1f(loc(name), v)
    fun set(name: String, x: Float, y: Float) = GLES31.glUniform2f(loc(name), x, y)
    fun set(name: String, x: Float, y: Float, z: Float, w: Float) = GLES31.glUniform4f(loc(name), x, y, z, w)
    fun setI(name: String, x: Int, y: Int) = GLES31.glUniform2i(loc(name), x, y)
    fun setI4(name: String, x: Int, y: Int, z: Int, w: Int) = GLES31.glUniform4i(loc(name), x, y, z, w)
    fun setMat3(name: String, rowMajor: FloatArray) = GLES31.glUniformMatrix3fv(loc(name), 1, true, rowMajor, 0)

    /** Runs the program over [w]×[h] invocations (8×8 work groups) and waits for its writes. */
    fun dispatch(w: Int, h: Int) {
        GLES31.glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1)
        GLES31.glMemoryBarrier(
            GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or
                GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_FRAMEBUFFER_BARRIER_BIT,
        )
    }

    /** Runs [x]×[y] work groups (for shaders that size their own groups). */
    fun dispatchGroups(x: Int, y: Int) {
        GLES31.glDispatchCompute(x, y, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
    }

    fun release() = GLES31.glDeleteProgram(id)
}

object Gl {
    fun check(what: String) {
        val e = GLES31.glGetError()
        if (e != GLES31.GL_NO_ERROR) error("GL error 0x${Integer.toHexString(e)} at $what")
    }
}
