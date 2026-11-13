package com.example.dualcam

import android.graphics.Bitmap
import android.graphics.Matrix
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
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Recibe los cuadros de los dos lentes (texturas OES) y los dibuja lado a lado:
 * en pantalla, en el codificador de video y en fotos.
 */
class StereoRenderer(onReady: (SurfaceTexture, SurfaceTexture) -> Unit) {

    private val thread = HandlerThread("gl").apply { start() }
    private val handler = Handler(thread.looper)

    private lateinit var egl: EglCore
    private lateinit var pbuffer: EGLSurface
    private var display: EGLSurface? = null
    private var displayW = 0
    private var displayH = 0

    private var encoder: VideoEncoder? = null
    private var encoderSurface: EGLSurface? = null

    private val tex = IntArray(2)
    private lateinit var stA: SurfaceTexture
    private lateinit var stB: SurfaceTexture
    private val mtxA = FloatArray(16)
    private val mtxB = FloatArray(16)
    private var gotA = false
    private var gotB = false

    @Volatile private var cropA = 1f
    @Volatile private var cropB = 1f
    @Volatile private var bufW = 1280
    @Volatile private var bufH = 960
    @Volatile var swapEyes = false
    /** Cuartos de vuelta extra aplicados a la imagen (1 = apaisado normal). */
    @Volatile var quarterTurns = 1

    /** Ajuste fino de cada lente: desplazamiento (fracción del ancho/alto), rotación (grados) y zoom. */
    data class EyeAdjust(val dx: Float = 0f, val dy: Float = 0f, val rotDeg: Float = 0f, val zoom: Float = 1f)

    @Volatile var adjA = EyeAdjust()
    @Volatile var adjB = EyeAdjust()
    /** Separación horizontal entre las dos imágenes (fracción del ancho de cada ojo). */
    @Volatile var separation = 0f
    /** Líneas guía para alinear (solo en pantalla, no se guardan en fotos ni videos). */
    @Volatile var showGuides = false

    private var program = 0
    private var aPos = 0
    private var aTc = 0
    private var uSt = 0
    private var uTex = 0
    private val posBuf = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val tcBuf = floatBuffer(FloatArray(8))
    private val tcArr = FloatArray(8)

    init {
        handler.post {
            egl = EglCore()
            pbuffer = egl.createPbuffer()
            egl.makeCurrent(pbuffer)
            buildProgram()
            GLES20.glGenTextures(2, tex, 0)
            for (t in tex) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            stA = SurfaceTexture(tex[0]).apply {
                setDefaultBufferSize(bufW, bufH)
                setOnFrameAvailableListener({ drawFrame(fromA = true) }, handler)
            }
            stB = SurfaceTexture(tex[1]).apply {
                setDefaultBufferSize(bufW, bufH)
                setOnFrameAvailableListener({ drawFrame(fromA = false) }, handler)
            }
            onReady(stA, stB)
        }
    }

    // ---------- API pública ----------

    fun setBufferSize(w: Int, h: Int) {
        bufW = w
        bufH = h
        runBlocking {
            stA.setDefaultBufferSize(w, h)
            stB.setDefaultBufferSize(w, h)
        }
    }

    fun setCrop(a: Float, b: Float) {
        cropA = a
        cropB = b
    }

    fun setDisplay(surface: Surface, w: Int, h: Int) = runBlocking {
        display?.let {
            egl.makeCurrent(pbuffer)
            egl.destroySurface(it)
        }
        display = egl.createWindowSurface(surface)
        displayW = w
        displayH = h
    }

    fun clearDisplay() = runBlocking {
        display?.let {
            egl.makeCurrent(pbuffer)
            egl.destroySurface(it)
        }
        display = null
    }

    fun startRecording(pfd: ParcelFileDescriptor, eyeW: Int, eyeH: Int, onError: (Exception) -> Unit) {
        handler.post {
            try {
                val portrait = quarterTurns % 2 == 0
                val ew = if (portrait) eyeH else eyeW
                val eh = if (portrait) eyeW else eyeH
                val enc = VideoEncoder(ew * 2, eh, pfd)
                encoderSurface = egl.createWindowSurface(enc.inputSurface)
                encoder = enc
            } catch (e: Exception) {
                try { pfd.close() } catch (_: Exception) {}
                onError(e)
            }
        }
    }

    fun stopRecording(done: () -> Unit) {
        handler.post {
            val enc = encoder
            encoder = null
            egl.makeCurrent(pbuffer)
            encoderSurface?.let { egl.destroySurface(it) }
            encoderSurface = null
            if (enc != null) {
                try { enc.drain(true) } catch (_: Exception) {}
                enc.release()
            }
            done()
        }
    }

    /** Foto SBS a la resolución completa de los lentes. */
    fun capture(cb: (Bitmap) -> Unit) {
        handler.post {
            egl.makeCurrent(pbuffer)
            val portrait = quarterTurns % 2 == 0
            var eyeW = if (portrait) bufH else bufW
            var eyeH = if (portrait) bufW else bufH
            val maxTex = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
            if (eyeW * 2 > maxTex[0]) {
                val s = maxTex[0].toFloat() / (eyeW * 2)
                eyeW = (eyeW * s).toInt()
                eyeH = (eyeH * s).toInt()
            }
            val w = eyeW * 2
            val h = eyeH

            val t = IntArray(1)
            val fbo = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glGenFramebuffers(1, fbo, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, t[0], 0)

            renderSbs(w, h, letterbox = false)

            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            GLES20.glDeleteTextures(1, t, 0)

            Thread {
                val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                buf.rewind()
                raw.copyPixelsFromBuffer(buf)
                // OpenGL lee de abajo hacia arriba: invertir verticalmente.
                val flipped = Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { preScale(1f, -1f) }, false)
                raw.recycle()
                cb(flipped)
            }.start()
        }
    }

    fun release() {
        handler.post {
            encoder?.let {
                try { it.drain(true) } catch (_: Exception) {}
                it.release()
            }
            encoder = null
            stA.release()
            stB.release()
            egl.release()
            thread.quitSafely()
        }
    }

    // ---------- Dibujo ----------

    private fun drawFrame(fromA: Boolean) {
        egl.makeCurrent(display ?: pbuffer)
        if (fromA) {
            stA.updateTexImage()
            stA.getTransformMatrix(mtxA)
            gotA = true
        } else {
            stB.updateTexImage()
            stB.getTransformMatrix(mtxB)
            gotB = true
        }
        if (!gotA || !gotB) return

        display?.let {
            renderSbs(displayW, displayH, letterbox = true)
            egl.swap(it)
        }

        // El video avanza al ritmo del lente A (30 fps estables).
        val enc = encoder
        val es = encoderSurface
        if (fromA && enc != null && es != null) {
            try {
                enc.drain(false)
                egl.makeCurrent(es)
                renderSbs(enc.width, enc.height, letterbox = false)
                egl.setPresentationTime(es, stA.timestamp)
                egl.swap(es)
            } catch (_: Exception) {
            }
        }
    }

    private fun renderSbs(w: Int, h: Int, letterbox: Boolean) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val portrait = quarterTurns % 2 == 0
        val eyeAspect = if (portrait) bufH.toFloat() / bufW else bufW.toFloat() / bufH
        var cw = w
        var ch = h
        var x0 = 0
        var y0 = 0
        if (letterbox) {
            val target = 2f * eyeAspect
            if (w.toFloat() / h > target) {
                cw = (h * target).toInt()
                x0 = (w - cw) / 2
            } else {
                ch = (w / target).toInt()
                y0 = (h - ch) / 2
            }
        }
        val eyeW = cw / 2
        val left = if (swapEyes) 1 else 0
        drawEye(x0, y0, eyeW, ch, left, side = 0)
        drawEye(x0 + eyeW, y0, eyeW, ch, 1 - left, side = 1)
        if (letterbox && showGuides) drawGuides(x0, y0, cw, ch)
    }

    /** Dibuja líneas finas usando recortes (scissor) + clear: horizontales y el centro de cada ojo. */
    private fun drawGuides(x0: Int, y0: Int, cw: Int, ch: Int) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0.2f, 1f, 0.4f, 1f)
        for (k in 1..7) {
            val y = y0 + ch * k / 8
            GLES20.glScissor(x0, y - 1, cw, 2)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        val eyeW = cw / 2
        for (e in 0..1) {
            val x = x0 + e * eyeW + eyeW / 2
            GLES20.glScissor(x - 1, y0, 2, ch)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    private fun drawEye(x: Int, y: Int, w: Int, h: Int, cam: Int, side: Int) {
        GLES20.glViewport(x, y, w, h)
        val crop = if (cam == 0) cropA else cropB
        val mtx = if (cam == 0) mtxA else mtxB
        val adj = if (cam == 0) adjA else adjB
        val shift = if (side == 0) -separation / 2f else separation / 2f
        val aspect = w.toFloat() / h
        val th = Math.toRadians(-adj.rotDeg.toDouble())
        val cs = kotlin.math.cos(th).toFloat()
        val sn = kotlin.math.sin(th).toFloat()
        val zoom = if (adj.zoom < 0.1f) 0.1f else adj.zoom
        val q = quarterTurns and 3
        for (i in 0 until 4) {
            val sx0 = if (i == 1 || i == 3) 1f else 0f
            val sy0 = if (i >= 2) 1f else 0f
            // Desplazar, rotar (corrigiendo el aspecto) y hacer zoom alrededor del centro.
            val px = sx0 - adj.dx - shift - 0.5f
            val py = sy0 - adj.dy - 0.5f
            val rx = (cs * px * aspect - sn * py) / aspect
            val ry = sn * px * aspect + cs * py
            val sx = rx / zoom + 0.5f
            val sy = ry / zoom + 0.5f
            val u: Float
            val v: Float
            when (q) {
                0 -> { u = sx; v = sy }
                1 -> { u = sy; v = 1f - sx }
                2 -> { u = 1f - sx; v = 1f - sy }
                else -> { u = 1f - sy; v = sx }
            }
            tcArr[2 * i] = 0.5f + (u - 0.5f) * crop
            tcArr[2 * i + 1] = 0.5f + (v - 0.5f) * crop
        }
        tcBuf.position(0)
        tcBuf.put(tcArr)
        tcBuf.position(0)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[cam])
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uSt, 1, false, mtx, 0)
        posBuf.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aTc, 2, GLES20.GL_FLOAT, false, 0, tcBuf)
        GLES20.glEnableVertexAttribArray(aTc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram() {
        val vs = """
            attribute vec2 aPos;
            attribute vec2 aTc;
            uniform mat4 uSt;
            varying vec2 vTc;
            varying vec2 vRaw;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vRaw = aTc;
                vTc = (uSt * vec4(aTc, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uTex;
            varying vec2 vTc;
            varying vec2 vRaw;
            void main() {
                if (vRaw.x < 0.0 || vRaw.x > 1.0 || vRaw.y < 0.0 || vRaw.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTex, vTc);
                }
            }
        """.trimIndent()
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link: " + GLES20.glGetProgramInfoLog(program))
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTc = GLES20.glGetAttribLocation(program, "aTc")
        uSt = GLES20.glGetUniformLocation(program, "uSt")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun runBlocking(block: () -> Unit) {
        if (Thread.currentThread() == thread) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try { block() } finally { latch.countDown() }
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data)
            position(0)
        }
}

/** Manejo mínimo de EGL para dibujar en pantalla, en el codificador y fuera de pantalla. */
class EglCore {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext

    init {
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        config = configs[0] ?: throw RuntimeException("No hay configuración EGL compatible")
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
    }

    fun createWindowSurface(surface: Any): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)

    fun createPbuffer(): EGLSurface =
        EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )

    fun makeCurrent(s: EGLSurface) {
        EGL14.eglMakeCurrent(display, s, s, context)
    }

    fun swap(s: EGLSurface) {
        EGL14.eglSwapBuffers(display, s)
    }

    fun setPresentationTime(s: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, s, nanos)
    }

    fun destroySurface(s: EGLSurface) {
        EGL14.eglDestroySurface(display, s)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
