package com.kyle.kitchenhub.core.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.SystemClock
import android.util.AttributeSet
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.concurrent.thread

/**
 * Lightweight OpenGL ES 2.0 TextureView with transparent compositing and 15fps frame cap,
 * enabling real GLSL fragment shaders on Android 11 (API 30) where AGSL RuntimeShader is unavailable.
 */
open class GlShaderTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    @Volatile
    private var running = false
    @Volatile
    protected var continuousRender = true
    @Volatile
    protected var dirty = true

    private val wakeLock = Object()
    private var renderThread: Thread? = null
    private var fragmentShaderSrc: String = DEFAULT_FRAG

    protected open val bufferScale: Float get() = 0.5f

    protected var startMs = SystemClock.uptimeMillis()

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setFragmentShader(src: String) {
        fragmentShaderSrc = src
    }

    fun requestRenderFrame() {
        dirty = true
        synchronized(wakeLock) { wakeLock.notifyAll() }
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) requestRenderFrame()
    }

    protected open fun onBindUniforms(program: Int, width: Int, height: Int, timeSec: Float) {}

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startThread(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        dirty = true
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopThread()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun startThread(surface: SurfaceTexture, initW: Int, initH: Int) {
        stopThread()
        running = true
        dirty = true
        renderThread = thread(isDaemon = true, name = "GlShader-${javaClass.simpleName}") {
            var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
            var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
            try {
                val initBw = (initW * bufferScale).toInt().coerceAtLeast(64)
                val initBh = (initH * bufferScale).toInt().coerceAtLeast(64)
                surface.setDefaultBufferSize(initBw, initBh)

                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val ver = IntArray(2)
                EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)

                val cfgAttrs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numCfg = IntArray(1)
                EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, configs, 0, 1, numCfg, 0)
                val cfg = configs[0] ?: return@thread

                eglContext = EGL14.eglCreateContext(
                    eglDisplay, cfg, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
                )
                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

                val prog = buildProgram(VERTEX_SRC, fragmentShaderSrc)
                val posLoc = GLES20.glGetAttribLocation(prog, "aPos")
                val uResLoc = GLES20.glGetUniformLocation(prog, "uResolution")
                val uTimeLoc = GLES20.glGetUniformLocation(prog, "uTime")
                val quad: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                    position(0)
                }

                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

                var lastBw = initBw
                var lastBh = initBh
                while (running) {
                    if (!isShown || (!continuousRender && !dirty)) {
                        synchronized(wakeLock) {
                            if (running && (!isShown || (!continuousRender && !dirty))) {
                                wakeLock.wait(500L)
                            }
                        }
                        continue
                    }
                    val frameStart = SystemClock.uptimeMillis()
                    dirty = false
                    val vw = (width * bufferScale).toInt().coerceAtLeast(64)
                    val vh = (height * bufferScale).toInt().coerceAtLeast(64)
                    if (vw != lastBw || vh != lastBh) {
                        surface.setDefaultBufferSize(vw, vh)
                        lastBw = vw
                        lastBh = vh
                    }
                    GLES20.glViewport(0, 0, vw, vh)
                    GLES20.glClearColor(0f, 0f, 0f, 0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                    GLES20.glUseProgram(prog)
                    if (uResLoc >= 0) GLES20.glUniform2f(uResLoc, vw.toFloat(), vh.toFloat())
                    val tSec = (frameStart - startMs) / 1000f
                    if (uTimeLoc >= 0) GLES20.glUniform1f(uTimeLoc, tSec)
                    onBindUniforms(prog, vw, vh, tSec)

                    GLES20.glEnableVertexAttribArray(posLoc)
                    GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quad)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glDisableVertexAttribArray(posLoc)
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)

                    val elapsed = SystemClock.uptimeMillis() - frameStart
                    val sleepMs = (66L - elapsed).coerceAtLeast(16L) // 15 fps cap
                    Thread.sleep(sleepMs)
                }
                GLES20.glDeleteProgram(prog)
            } catch (_: Throwable) {
            } finally {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            }
        }
    }

    private fun stopThread() {
        running = false
        synchronized(wakeLock) { wakeLock.notifyAll() }
        runCatching { renderThread?.join(200L) }
        renderThread = null
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
        }
    }

    private fun compile(type: Int, src: String): Int = GLES20.glCreateShader(type).also { s ->
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
    }

    companion object {
        private const val VERTEX_SRC = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """
        private const val DEFAULT_FRAG = """
            precision mediump float;
            void main() { gl_FragColor = vec4(0.0); }
        """
    }
}
