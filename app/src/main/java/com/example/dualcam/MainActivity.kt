package com.example.dualcam

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cámara estéreo: usa dos lentes traseros de una cámara lógica multi-lente
 * (Camera2, Android 10+) y genera fotos y videos 3D lado a lado (SBS) para
 * ver con lentes de realidad virtual.
 */
class MainActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var info: TextView
    private lateinit var panel: LinearLayout
    private lateinit var btnPhoto: Button
    private lateinit var btnRecord: Button
    private lateinit var btnPair: Button
    private lateinit var btnSwap: Button
    private lateinit var btnRotate: Button
    private lateinit var btnSettings: Button
    private lateinit var settings: View
    private lateinit var settingsBox: LinearLayout

    // Calibración: se guarda por par de lentes.
    private val prefs by lazy { getSharedPreferences("calibracion", MODE_PRIVATE) }
    @Volatile private var pairKey = ""
    private var editLeft = true
    private val refreshers = mutableListOf<() -> Unit>()

    private val manager by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }
    private val camThread = HandlerThread("camera").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private val camExecutor = Executor { camHandler.post(it) }

    private lateinit var renderer: StereoRenderer
    private var surfaceA: Surface? = null
    private var surfaceB: Surface? = null

    private var logicalId: String? = null
    private var pairs: List<Pair<String, String>> = emptyList()
    private var pairIndex = 0

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    @Volatile private var streamSize = Size(1280, 960)
    private var labelA = ""
    private var labelB = ""

    private var recording = false
    private var videoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemBars()

        surfaceView = findViewById(R.id.surface)
        info = findViewById(R.id.info)
        panel = findViewById(R.id.panel)
        btnPhoto = findViewById(R.id.btnPhoto)
        btnRecord = findViewById(R.id.btnRecord)
        btnPair = findViewById(R.id.btnPair)
        btnSwap = findViewById(R.id.btnSwap)
        btnRotate = findViewById(R.id.btnRotate)
        btnSettings = findViewById(R.id.btnSettings)
        settings = findViewById(R.id.settings)
        settingsBox = findViewById(R.id.settingsBox)

        findCameras()

        renderer = StereoRenderer { stA, stB ->
            surfaceA = Surface(stA)
            surfaceB = Surface(stB)
            runOnUiThread { maybeOpenCamera() }
        }
        renderer.quarterTurns = prefs.getInt("giro", 1)
        buildSettings()
        btnSettings.setOnClickListener {
            settings.visibility = if (settings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            refreshRows()
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                renderer.setDisplay(holder.surface, w, h)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                renderer.clearDisplay()
            }
        })

        // Tocar la imagen oculta/muestra los controles (modo "visor VR en vivo").
        surfaceView.setOnClickListener {
            val v = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            panel.visibility = v
            info.visibility = v
        }

        btnPhoto.setOnClickListener { takePhoto() }
        btnRecord.setOnClickListener { toggleRecording() }
        btnPair.setOnClickListener {
            if (pairs.size > 1 && !recording) {
                pairIndex = (pairIndex + 1) % pairs.size
                camHandler.post { configureSession() }
            }
        }
        btnSwap.setOnClickListener {
            renderer.swapEyes = !renderer.swapEyes
            saveCalibration()
            updateInfo()
            refreshRows()
        }
        btnRotate.setOnClickListener {
            if (!recording) {
                renderer.quarterTurns = (renderer.quarterTurns + 1) % 4
                prefs.edit().putInt("giro", renderer.quarterTurns).apply()
            }
        }
    }

    // ---------- Panel de ajustes / calibración ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    /** Lente (0 = A, 1 = B) que se está mostrando del lado que se edita. */
    private fun editedCam(): Int {
        val leftCam = if (renderer.swapEyes) 1 else 0
        return if (editLeft) leftCam else 1 - leftCam
    }

    private fun currentAdj(): StereoRenderer.EyeAdjust =
        if (editedCam() == 0) renderer.adjA else renderer.adjB

    private fun setAdj(a: StereoRenderer.EyeAdjust) {
        if (editedCam() == 0) renderer.adjA = a else renderer.adjB = a
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = dp(44)
        minimumWidth = dp(44)
        setOnClickListener { onClick() }
    }

    private fun buildSettings() {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var bEye: Button
        bEye = smallButton("Editando: ojo IZQ") {
            editLeft = !editLeft
            bEye.text = if (editLeft) "Editando: ojo IZQ" else "Editando: ojo DER"
            refreshRows()
        }
        top.addView(bEye)
        top.addView(smallButton("Líneas guía") { renderer.showGuides = !renderer.showGuides })
        top.addView(smallButton("Restablecer") {
            renderer.adjA = StereoRenderer.EyeAdjust()
            renderer.adjB = StereoRenderer.EyeAdjust()
            renderer.separation = 0f
            saveCalibration()
            refreshRows()
        })
        top.addView(smallButton("Cerrar") { settings.visibility = View.GONE })
        settingsBox.addView(top)

        val pct = { v: Int -> String.format(Locale.US, "%+.1f%%", v / 10f) }
        addRow("Separación (ambos)", -300, 300, pct,
            { (renderer.separation * 1000).roundToInt() },
            { renderer.separation = it / 1000f })
        addRow("Mover ← →", -300, 300, pct,
            { (currentAdj().dx * 1000).roundToInt() },
            { setAdj(currentAdj().copy(dx = it / 1000f)) })
        addRow("Mover ↓ ↑", -300, 300, pct,
            { (currentAdj().dy * 1000).roundToInt() },
            { setAdj(currentAdj().copy(dy = it / 1000f)) })
        addRow("Rotar", -200, 200, { v -> String.format(Locale.US, "%+.1f°", v / 10f) },
            { (currentAdj().rotDeg * 10).roundToInt() },
            { setAdj(currentAdj().copy(rotDeg = it / 10f)) })
        addRow("Zoom", 700, 1400, { v -> String.format(Locale.US, "%.1f%%", v / 10f) },
            { (currentAdj().zoom * 1000).roundToInt() },
            { setAdj(currentAdj().copy(zoom = it / 1000f)) })
    }

    private fun addRow(name: String, min: Int, max: Int, fmt: (Int) -> String, get: () -> Int, set: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            width = dp(150)
        }
        val seek = android.widget.SeekBar(this).apply { this.max = max - min }
        fun show(v: Int) { label.text = "$name: ${fmt(v)}" }
        fun applyValue(v: Int) {
            val c = v.coerceIn(min, max)
            set(c)
            show(c)
            saveCalibration()
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) applyValue(p + min)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
        })
        val minus = smallButton("−") {
            val v = (get() - 1).coerceIn(min, max)
            applyValue(v)
            seek.progress = v - min
        }
        val plus = smallButton("+") {
            val v = (get() + 1).coerceIn(min, max)
            applyValue(v)
            seek.progress = v - min
        }
        row.addView(label)
        row.addView(minus)
        row.addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(plus)
        settingsBox.addView(row)
        refreshers += {
            val v = get().coerceIn(min, max)
            seek.progress = v - min
            show(v)
        }
    }

    private fun refreshRows() = runOnUiThread { refreshers.forEach { it() } }

    private fun loadCalibration() {
        val k = pairKey
        fun adj(c: String) = StereoRenderer.EyeAdjust(
            prefs.getFloat("${k}_${c}_dx", 0f),
            prefs.getFloat("${k}_${c}_dy", 0f),
            prefs.getFloat("${k}_${c}_rot", 0f),
            prefs.getFloat("${k}_${c}_zoom", 1f)
        )
        renderer.adjA = adj("A")
        renderer.adjB = adj("B")
        renderer.separation = prefs.getFloat("${k}_sep", 0f)
        renderer.swapEyes = prefs.getBoolean("${k}_swap", false)
        refreshRows()
    }

    private fun saveCalibration() {
        val k = pairKey
        if (k.isEmpty()) return
        val e = prefs.edit()
        for ((c, a) in listOf("A" to renderer.adjA, "B" to renderer.adjB)) {
            e.putFloat("${k}_${c}_dx", a.dx)
            e.putFloat("${k}_${c}_dy", a.dy)
            e.putFloat("${k}_${c}_rot", a.rotDeg)
            e.putFloat("${k}_${c}_zoom", a.zoom)
        }
        e.putFloat("${k}_sep", renderer.separation)
        e.putBoolean("${k}_swap", renderer.swapEyes)
        e.apply()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        maybeOpenCamera()
    }

    override fun onPause() {
        if (recording) toggleRecording()
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        renderer.release()
        camThread.quitSafely()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ---------- Búsqueda de lentes ----------

    private fun findCameras() {
        var best: String? = null
        var bestPhys = emptyList<String>()
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) continue
            val phys = c.physicalCameraIds.toList()
            if (phys.size > bestPhys.size) {
                best = id
                bestPhys = phys
            }
        }
        logicalId = best
        val all = mutableListOf<Pair<String, String>>()
        for (i in bestPhys.indices) for (j in i + 1 until bestPhys.size) all.add(bestPhys[i] to bestPhys[j])
        // Primero los pares con campos de visión más parecidos (menos recorte).
        pairs = all.sortedByDescending { (a, b) ->
            val ra = fovFactor(a)
            val rb = fovFactor(b)
            min(ra, rb) / max(ra, rb)
        }
        if (best == null || pairs.isEmpty()) {
            info.text = "Este celular no expone una cámara trasera multi-lente para apps."
        }
    }

    /** Proporcional a tan(FOV/2): ancho del sensor / distancia focal. */
    private fun fovFactor(id: String): Double {
        val c = manager.getCameraCharacteristics(id)
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 1.0
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 1.0
        return (s.width / f).toDouble()
    }

    private fun lensLabel(id: String): String {
        val c = manager.getCameraCharacteristics(id)
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (f == null || s == null) return "lente $id"
        val diag = sqrt(s.width * s.width + s.height * s.height)
        val equiv = (f * 43.27f / diag).roundToInt()
        return "lente $id (~$equiv mm)"
    }

    private fun supportsSize(physicalId: String, size: Size): Boolean {
        val map = manager.getCameraCharacteristics(physicalId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return true
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return true
        return sizes.contains(size)
    }

    // ---------- Cámara ----------

    private fun maybeOpenCamera() {
        val id = logicalId ?: return
        if (surfaceA == null || device != null || opening) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        opening = true
        try {
            manager.openCamera(id, camExecutor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    opening = false
                    device = d
                    configureSession()
                }
                override fun onDisconnected(d: CameraDevice) {
                    opening = false
                    d.close()
                    device = null
                }
                override fun onError(d: CameraDevice, error: Int) {
                    opening = false
                    d.close()
                    device = null
                    toast("Error de cámara ($error)")
                }
            })
        } catch (e: Exception) {
            opening = false
            toast("No se pudo abrir la cámara: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            maybeOpenCamera()
        } else {
            toast("La app necesita permiso de cámara")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
    }

    /** Corre en el hilo de cámara. */
    private fun configureSession() {
        val d = device ?: return
        if (pairs.isEmpty()) return
        val sa = surfaceA ?: return
        val sb = surfaceB ?: return
        val (a, b) = pairs[pairIndex]

        try { session?.close() } catch (_: Exception) {}
        session = null

        // Recortar el lente más gran angular para igualar el campo de visión del otro.
        val ra = fovFactor(a)
        val rb = fovFactor(b)
        val m = min(ra, rb)
        renderer.setCrop((m / ra).toFloat(), (m / rb).toFloat())
        labelA = lensLabel(a)
        labelB = lensLabel(b)
        pairKey = "${a}_${b}"
        loadCalibration()

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(sa)
                        addTarget(sb)
                    }
                    s.setRepeatingRequest(req.build(), null, camHandler)
                } catch (e: Exception) {
                    toast("Error al iniciar la vista previa: ${e.message}")
                }
                updateInfo()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                toast("Este par de lentes no se puede usar junto. Probá otro con \"Lentes\".")
            }
        }

        val candidates = listOf(
            Size(1920, 1440), Size(1600, 1200), Size(1440, 1080),
            Size(1280, 960), Size(1024, 768), Size(640, 480)
        )
        var chosen: SessionConfiguration? = null
        for (size in candidates) {
            if (!supportsSize(a, size) || !supportsSize(b, size)) continue
            renderer.setBufferSize(size.width, size.height)
            val cfg = makeConfig(a, b, sa, sb, callback)
            val ok = try {
                d.isSessionConfigurationSupported(cfg)
            } catch (e: Exception) {
                size.width <= 1280 // si el equipo no responde la consulta, ir a lo seguro
            }
            if (ok) {
                streamSize = size
                chosen = cfg
                break
            }
        }
        if (chosen == null) {
            streamSize = Size(1280, 960)
            renderer.setBufferSize(1280, 960)
            chosen = makeConfig(a, b, sa, sb, callback)
        }
        try {
            d.createCaptureSession(chosen)
        } catch (e: Exception) {
            toast("No se pudo crear la sesión: ${e.message}")
        }
    }

    private fun makeConfig(
        a: String, b: String, sa: Surface, sb: Surface,
        cb: CameraCaptureSession.StateCallback
    ): SessionConfiguration {
        val oa = OutputConfiguration(sa).apply { setPhysicalCameraId(a) }
        val ob = OutputConfiguration(sb).apply { setPhysicalCameraId(b) }
        return SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(oa, ob), camExecutor, cb)
    }

    private fun updateInfo() {
        val left = if (renderer.swapEyes) labelB else labelA
        val right = if (renderer.swapEyes) labelA else labelB
        val s = streamSize
        runOnUiThread {
            info.text = "Ojo izq: $left · Ojo der: $right · ${s.width}x${s.height} por lente" +
                    "\nTocá la imagen para ocultar los controles"
        }
    }

    // ---------- Foto ----------

    private fun takePhoto() {
        if (session == null) return
        renderer.capture { bmp -> saveBitmap(bmp) }
    }

    private fun saveBitmap(bmp: Bitmap) {
        val name = "DualCam_${stamp()}_SBS.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DualCam3D")
        }
        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore no devolvió URI")
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            toast("Foto 3D guardada: Pictures/DualCam3D/$name")
        } catch (e: Exception) {
            toast("No se pudo guardar la foto: ${e.message}")
        } finally {
            bmp.recycle()
        }
    }

    // ---------- Video ----------

    private fun toggleRecording() {
        if (!recording) {
            if (session == null) return
            val name = "DualCam_${stamp()}_SBS.mp4"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/DualCam3D")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: run {
                toast("No se pudo crear el archivo de video")
                return
            }
            val pfd = contentResolver.openFileDescriptor(uri, "rw") ?: run {
                toast("No se pudo abrir el archivo de video")
                return
            }
            videoUri = uri
            val eye = VideoEncoder.pickEyeSize()
            renderer.startRecording(pfd, eye.width, eye.height) { e ->
                toast("No se pudo grabar: ${e.message}")
                runOnUiThread { setRecordingUi(false) }
            }
            setRecordingUi(true)
        } else {
            val uri = videoUri
            setRecordingUi(false)
            renderer.stopRecording {
                if (uri != null) {
                    val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    contentResolver.update(uri, v, null, null)
                }
                toast("Video 3D guardado en Movies/DualCam3D")
            }
        }
    }

    private fun setRecordingUi(on: Boolean) {
        recording = on
        btnRecord.text = if (on) "■ Detener" else "● Grabar"
        btnPair.isEnabled = !on
        btnRotate.isEnabled = !on
        btnPhoto.isEnabled = !on
        if (on) settings.visibility = View.GONE
        btnSettings.isEnabled = !on
    }

    // ---------- Utilidades ----------

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
