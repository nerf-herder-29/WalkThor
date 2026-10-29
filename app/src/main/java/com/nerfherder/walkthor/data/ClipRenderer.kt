package com.nerfherder.walkthor.data

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Takes the top screen's picture (from Mirror's display, which can only be made once) and draws
 * each frame into the video encoder, and also into the live preview when one is showing, so
 * Mirror, Shots and Stats keep working while a clip is kept. Runs on a thread of its own.
 */
internal class ClipRenderer(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val previewSize: () -> Pair<Int, Int>,
    private val preview: () -> Surface?,
) {
    private val thread = HandlerThread("WalkThor clips picture").apply { start() }
    private val handler = Handler(thread.looper)
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewFor: Surface? = null
    private var program = 0
    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val matrix = FloatArray(16)
    private var lastPreviewNs = 0L

    /** The surface the display draws into. Null if the picture couldn't be set up. */
    var input: Surface? = null
        private set

    fun start(): Boolean {
        val done = CountDownLatch(1)
        var ok = false
        handler.post {
            ok = runCatching { setUp() }.isSuccess
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        if (!ok) release()
        return ok && input != null
    }

    private fun setUp() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1))
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0)
        config = configs[0]
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        encoderEgl = EGL14.eglCreateWindowSurface(eglDisplay, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(encoderEgl != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(eglDisplay, encoderEgl, encoderEgl, eglContext))

        program = buildProgram()
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({ drawFrame() }, handler)
        surfaceTexture = st
        input = Surface(st)
    }

    private fun drawFrame() {
        val st = surfaceTexture ?: return
        runCatching {
            EGL14.eglMakeCurrent(eglDisplay, encoderEgl, encoderEgl, eglContext)
            st.updateTexImage()
            st.getTransformMatrix(matrix)
            val ts = st.timestamp
            draw(width, height)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEgl, ts)
            EGL14.eglSwapBuffers(eglDisplay, encoderEgl)
            // The live preview, at most about 30 times a second.
            val target = preview()
            if (target == null) {
                dropPreview()
            } else if (ts - lastPreviewNs >= 30_000_000L) {
                if (target !== previewFor) {
                    dropPreview()
                    previewEgl = EGL14.eglCreateWindowSurface(eglDisplay, config, target, intArrayOf(EGL14.EGL_NONE), 0)
                    previewFor = target
                }
                if (previewEgl != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, previewEgl, previewEgl, eglContext)
                    // Never wait on the preview: a full queue drops a frame instead.
                    EGL14.eglSwapInterval(eglDisplay, 0)
                    val (pw, ph) = previewSize()
                    draw(pw, ph)
                    EGL14.eglSwapBuffers(eglDisplay, previewEgl)
                    lastPreviewNs = ts
                }
            }
        }
    }

    private fun dropPreview() {
        if (previewEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, encoderEgl, encoderEgl, eglContext)
            EGL14.eglDestroySurface(eglDisplay, previewEgl)
        }
        previewEgl = EGL14.EGL_NO_SURFACE
        previewFor = null
    }

    private val quad: java.nio.FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }

    private fun draw(w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val uv = GLES20.glGetAttribLocation(program, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, matrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }
        val vs = shader(
            GLES20.GL_VERTEX_SHADER,
            """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }
            """.trimIndent(),
        )
        val fs = shader(
            GLES20.GL_FRAGMENT_SHADER,
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
            """.trimIndent(),
        )
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { "clip shader didn't link" }
        }
    }

    /** Frees it all (on its own thread, after anything already drawing). */
    fun release() {
        handler.post {
            runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
            runCatching { dropPreview() }
            runCatching {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (encoderEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encoderEgl)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglReleaseThread()
                }
            }
            runCatching { input?.release() }
            runCatching { surfaceTexture?.release() }
            surfaceTexture = null
            input = null
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        thread.quitSafely()
    }

    /** Waits (briefly) until [release] has finished. */
    fun awaitReleased() { runCatching { thread.join(1_000) } }
}
