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
 * Foto 3D desde una sola foto: la IA (Depth Anything V2 small, en el celular) estima qué está cerca y qué lejos,
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
    private var eyeL: Bitmap? = null
    private var eyeR: Bitmap? = null
    private var srcName = "foto"
    private var strength = 3               // separación máxima en % del ancho (se guarda: la usa también el botón IA)
    private var gen = 0

    private lateinit var img: SbsView
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var strengthLabel: TextView
    private lateinit var saveBtn: TextView
    @Volatile private var batching = false
    @Volatile private var stopBatch = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        strength = Depth3D.strength(this)
        buildUi()
        when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { load(it) } ?: pick()
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                when { list.isEmpty() -> pick(); list.size == 1 -> load(list[0]); else -> processMany(list) }
            }
            else -> pick()
        }
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
        img = SbsView(this).apply {
            // tocar la imagen esconde o muestra los controles (para mirarla con los lentes)
            setOnClickListener { controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        root.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6)); setBackgroundColor(Color.argb(170, 0, 0, 0))
        }
        controls.addView(pill("✕", Color.argb(255, 50, 50, 50), Color.WHITE) { finish() })
        controls.addView(pill("Otras fotos", Color.argb(255, 50, 50, 50), Color.WHITE) { if (!batching) pick() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        strengthLabel = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(14), 0, dp(4), 0) }
        controls.addView(strengthLabel)
        val bar = SeekBar(this).apply {
            max = 5; progress = strength - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { strength = p + 1; showStrength() }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { Depth3D.setStrength(this@Photo3DActivity, strength); stereo() }
            })
        }
        controls.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        saveBtn = pill("Guardar", Color.rgb(0, 200, 130), Color.BLACK) { if (batching) stopBatch = true else save() }
        controls.addView(saveBtn)
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
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) // se pueden elegir varias (toque largo en el selector)
        }, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK) {
            val list = mutableListOf<Uri>()
            data?.clipData?.let { c -> for (i in 0 until c.itemCount) list += c.getItemAt(i).uri }
            if (list.isEmpty()) data?.data?.let { list += it }
            when {
                resultCode != RESULT_OK || list.isEmpty() -> if (photo == null && !batching) finish()
                list.size == 1 -> load(list[0])
                else -> processMany(list)
            }
        }
    }

    // ------------------------------------------------------------------ cargar y estimar profundidad

    private fun nameOf(u: Uri): String = try {
        contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    } catch (_: Exception) { null } ?: "foto"

    private fun decodeSized(u: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, u)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val big = max(info.size.width, info.size.height)
            if (big > 2400) { val k = 2400.0 / big; dec.setTargetSize((info.size.width * k).roundToInt(), (info.size.height * k).roundToInt()) }
        }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }

    /** Varias fotos: una por una, cada una se convierte a 3D y se guarda sola. Se ve cada resultado mientras avanza. */
    private fun processMany(list: List<Uri>) {
        val my = ++gen
        batching = true; stopBatch = false
        saveBtn.text = "Detener"
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val st = strength
        io.execute {
            var ok = 0; var fail = 0
            for ((k, u) in list.withIndex()) {
                if (stopBatch || my != gen) break
                val name = nameOf(u)
                main.post { status.text = "Foto ${k + 1} de ${list.size}: $name · calculando la profundidad…" }
                try {
                    val bmp = decodeSized(u)
                    val d = estimateDepth(bmp)
                    val eyes = buildEyes(bmp, d, st) { stopBatch } ?: break
                    saveFiles(name, eyes.first, eyes.second)
                    ok++
                    main.post {
                        photo = bmp; depth = d; srcName = name
                        eyeL = eyes.first; eyeR = eyes.second; result = null
                        img.set(eyes.first, eyes.second)
                    }
                } catch (e: Throwable) { fail++ }
            }
            val stopped = stopBatch
            main.post {
                batching = false; saveBtn.text = "Guardar"
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                status.text = (if (stopped) "Detenido: " else "Listo: ") + "$ok foto" + (if (ok == 1) "" else "s") + " 3D guardada" + (if (ok == 1) "" else "s") +
                    " en Imágenes/DualCam3D (_VR para los lentes, _SBS lado a lado)" + (if (fail > 0) ". $fail no se pudieron procesar." else ".") +
                    " Se ve la última; con la barra podés cambiarle la profundidad y volver a guardarla."
            }
        }
    }

    private fun load(u: Uri) {
        val my = ++gen
        status.text = "Leyendo la foto…"
        img.set(null, null); result = null; eyeL = null; eyeR = null
        io.execute {
            try {
                srcName = nameOf(u)
                val bmp = decodeSized(u)
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

    private val d3 by lazy { Depth3D.get(this) }
    private fun estimateDepth(bmp: Bitmap) = d3.estimateDepth(bmp)
    private fun buildEyes(bmp: Bitmap, d: FloatArray, st: Int, cancelled: () -> Boolean) = d3.buildEyes(bmp, d, st, cancelled)
    private fun saveFiles(name: String, l: Bitmap, r: Bitmap) = d3.saveFiles(name, l, r)

    // ------------------------------------------------------------------ armar las dos vistas

    /** Corre cada pixel según su profundidad: lo cercano se mueve más. Los huecos se rellenan con el fondo. */
    private fun stereo() {
        val bmp = photo ?: return
        val d = depth ?: return
        val my = ++gen
        val st = strength
        status.text = "Armando la vista 3D…"
        io.execute {
            val eyes = buildEyes(bmp, d, st) { my != gen } ?: return@execute
            main.post {
                if (my != gen) return@post
                eyeL = eyes.first; eyeR = eyes.second; result = null
                img.set(eyes.first, eyes.second)
                status.text = "Listo. Tocá la imagen para esconder los controles y mirala con los lentes."
            }
        }
    }

    private fun save() {
        val l = eyeL; val r = eyeR
        if (l == null || r == null) { status.text = "Todavía no hay foto 3D."; return }
        val name = srcName
        status.text = "Guardando…"
        io.execute {
            try {
                saveFiles(name, l, r)
                main.post { status.text = "Guardadas en Imágenes/DualCam3D: 3D_${name}_VR.jpg (para ver con los lentes en este celular) y _SBS.jpg (lado a lado común)." }
            } catch (e: Exception) {
                main.post { status.text = "No pude guardar: ${e.message}" }
            }
        }
    }
}
