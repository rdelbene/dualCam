package com.example.dualcam

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foto 3D desde una sola foto: la IA (MiDaS v2.1 small, en el celular) estima qué está cerca y qué lejos,
 * y con eso se arman dos vistas (ojo izquierdo y derecho) lado a lado, para ver con los lentes de VR.
 * La foto original no se toca: el resultado se guarda aparte en Imágenes/DualCam3D.
 */
class Photo3DActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val REQ_PICK = 21

    private var photo: Bitmap? = null      // foto (ya derecha), a lo sumo 2400 px de lado
    private var depth: FloatArray? = null  // profundidad 0..1 (1 = cerca) del tamaño de la foto
    private var result: Bitmap? = null
    private var srcName = "foto"
    private var strength = 3               // separación máxima en % del ancho
    private var gen = 0

    private lateinit var img: ImageView
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var strengthLabel: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else null
        if (shared != null) load(shared) else pick()
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun pill(t: String, fill: Int, fg: Int, act: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(fg)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(fill) }
        setOnClickListener { act() }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // tocar la imagen esconde o muestra los controles (para mirarla con los lentes)
            setOnClickListener { controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        root.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6)); setBackgroundColor(Color.argb(170, 0, 0, 0))
        }
        controls.addView(pill("✕", Color.argb(255, 50, 50, 50), Color.WHITE) { finish() })
        controls.addView(pill("Otra foto", Color.argb(255, 50, 50, 50), Color.WHITE) { pick() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        strengthLabel = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(14), 0, dp(4), 0) }
        controls.addView(strengthLabel)
        val bar = SeekBar(this).apply {
            max = 5; progress = strength - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { strength = p + 1; showStrength() }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { stereo() }
            })
        }
        controls.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(pill("Guardar", Color.rgb(0, 200, 130), Color.BLACK) { save() })
        status = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(12), 0, 0, 0) }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(controls)
        wrap.addView(status)
        root.addView(wrap, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        showStrength()
    }

    private fun showStrength() { strengthLabel.text = "Profundidad: $strength" }

    private fun pick() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK) {
            val u = data?.data
            if (resultCode == RESULT_OK && u != null) load(u) else if (photo == null) finish()
        }
    }

    // ------------------------------------------------------------------ cargar y estimar profundidad

    private fun load(u: Uri) {
        val my = ++gen
        status.text = "Leyendo la foto…"
        img.setImageBitmap(null); result = null
        srcName = try {
            contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
            }
        } catch (_: Exception) { null } ?: "foto"
        io.execute {
            try {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, u)) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    if (android.os.Build.VERSION.SDK_INT >= 34) dec.isDecodeGainmap = false
                    val big = max(info.size.width, info.size.height)
                    if (big > 2400) { val k = 2400.0 / big; dec.setTargetSize((info.size.width * k).roundToInt(), (info.size.height * k).roundToInt()) }
                }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
                if (my != gen) return@execute
                main.post { status.text = "Calculando la profundidad con IA…" }
                val d = estimateDepth(bmp)
                if (my != gen) return@execute
                photo = bmp; depth = d
                main.post { stereo() }
            } catch (e: Throwable) {
                main.post { if (my == gen) status.text = "No pude procesar la foto: ${e.message}" }
            }
        }
    }

    private var interpreter: Interpreter? = null

    private fun model(): Interpreter {
        interpreter?.let { return it }
        val fd = assets.openFd("midas.tflite")
        val buf = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        return Interpreter(buf, Interpreter.Options().setNumThreads(4)).also { interpreter = it }
    }

    /** MiDaS v2.1 small: entrada 256×256 RGB normalizada (ImageNet), salida profundidad inversa (más = más cerca). */
    private fun estimateDepth(bmp: Bitmap): FloatArray {
        val n = 256
        val small = Bitmap.createScaledBitmap(bmp, n, n, true)
        val px = IntArray(n * n); small.getPixels(px, 0, n, 0, 0, n, n)
        if (small !== bmp) small.recycle()
        val input = ByteBuffer.allocateDirect(n * n * 3 * 4).order(ByteOrder.nativeOrder())
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (p in px) {
            input.putFloat((((p shr 16) and 255) / 255f - mean[0]) / std[0])
            input.putFloat((((p shr 8) and 255) / 255f - mean[1]) / std[1])
            input.putFloat(((p and 255) / 255f - mean[2]) / std[2])
        }
        input.rewind()
        val out = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        model().run(input, out)
        out.rewind()
        val raw = FloatArray(n * n) { out.float }
        // normalizar con percentiles (ignora puntos sueltos) a 0..1
        val sorted = raw.copyOf().also { it.sort() }
        val lo = sorted[(sorted.size * 0.02).toInt()]; val hi = sorted[(sorted.size * 0.98).toInt()]
        val norm = FloatArray(n * n) { ((raw[it] - lo) / max(hi - lo, 1e-6f)).coerceIn(0f, 1f) }
        // agrandar al tamaño de la foto (bilineal)
        val w = bmp.width; val h = bmp.height
        val d = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = (y + 0.5f) * n / h - 0.5f
            val y0 = fy.toInt().coerceIn(0, n - 1); val y1 = min(y0 + 1, n - 1); val ty = (fy - y0).coerceIn(0f, 1f)
            for (x in 0 until w) {
                val fx = (x + 0.5f) * n / w - 0.5f
                val x0 = fx.toInt().coerceIn(0, n - 1); val x1 = min(x0 + 1, n - 1); val tx = (fx - x0).coerceIn(0f, 1f)
                val a = norm[y0 * n + x0] * (1 - tx) + norm[y0 * n + x1] * tx
                val b = norm[y1 * n + x0] * (1 - tx) + norm[y1 * n + x1] * tx
                d[y * w + x] = a * (1 - ty) + b * ty
            }
        }
        return d
    }

    // ------------------------------------------------------------------ armar las dos vistas

    /** Corre cada pixel según su profundidad: lo cercano se mueve más. Los huecos se rellenan con el fondo. */
    private fun stereo() {
        val bmp = photo ?: return
        val d = depth ?: return
        val my = ++gen
        val st = strength
        status.text = "Armando la vista 3D…"
        io.execute {
            val w = bmp.width; val h = bmp.height
            val src = IntArray(w * h); bmp.getPixels(src, 0, w, 0, 0, w, h)
            val maxDisp = w * st / 100f
            // punto sin separación: la profundidad mediana (lo de adelante sale, lo de atrás se mete)
            val conv = d.copyOf().also { it.sort() }[d.size / 2]
            val left = IntArray(w * h); val right = IntArray(w * h)
            val rowL = IntArray(w); val rowR = IntArray(w)
            val zL = FloatArray(w); val zR = FloatArray(w)
            for (y in 0 until h) {
                warpRow(src, d, y, w, +0.5f * maxDisp, conv, rowL, zL)
                warpRow(src, d, y, w, -0.5f * maxDisp, conv, rowR, zR)
                System.arraycopy(rowL, 0, left, y * w, w)
                System.arraycopy(rowR, 0, right, y * w, w)
                if (my != gen) return@execute
            }
            val out = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888)
            out.setPixels(left, 0, w, 0, 0, w, h)
            out.setPixels(right, 0, w, w, 0, w, h)
            main.post {
                if (my != gen) { out.recycle(); return@post }
                result = out
                img.setImageBitmap(out)
                status.text = "Listo. Tocá la imagen para esconder los controles y mirala con los lentes."
            }
        }
    }

    private fun warpRow(src: IntArray, d: FloatArray, y: Int, w: Int, k: Float, conv: Float, row: IntArray, z: FloatArray) {
        java.util.Arrays.fill(z, -1f)
        val base = y * w
        var prevT = Float.NaN
        for (x in 0 until w) {
            val dz = d[base + x]
            val t = x + k * (dz - conv)
            // rango que cubre este pixel (tapa grietas chicas al estirar)
            val a: Int; val b: Int
            if (!prevT.isNaN() && t - prevT in 0f..2f) { a = (prevT + 0.5f).toInt() + 1; b = (t + 0.5f).toInt() }
            else { a = (t + 0.5f).toInt(); b = a }
            for (tx in max(a, 0)..min(b, w - 1)) {
                if (dz > z[tx]) { z[tx] = dz; row[tx] = src[base + x] }
            }
            prevT = t
        }
        // huecos (lo que tapaba lo de adelante): se completan con el vecino más lejano (el fondo)
        var x = 0
        while (x < w) {
            if (z[x] >= 0f) { x++; continue }
            var e = x
            while (e < w && z[e] < 0f) e++
            val l = x - 1; val r = e
            val fill = when {
                l < 0 && r >= w -> 0xff000000.toInt()
                l < 0 -> row[r]
                r >= w -> row[l]
                z[l] <= z[r] -> row[l]
                else -> row[r]
            }
            for (i in x until e) row[i] = fill
            x = e
        }
    }

    // ------------------------------------------------------------------ guardar

    private fun save() {
        val out = result ?: run { status.text = "Todavía no hay foto 3D."; return }
        status.text = "Guardando…"
        io.execute {
            try {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "3D_${srcName}_SBS.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DualCam3D")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("no se pudo crear el archivo")
                contentResolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                main.post { status.text = "Guardada en Imágenes/DualCam3D (3D_${srcName}_SBS.jpg)." }
            } catch (e: Exception) {
                main.post { status.text = "No pude guardar: ${e.message}" }
            }
        }
    }
}
