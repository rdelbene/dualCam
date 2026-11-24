package com.example.dualcam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Convierte una foto común en 3D: profundidad con IA (Depth Anything V2 small, en el celular) y dos vistas
 * (ojo izquierdo y derecho). Lo usan la pantalla "Foto 3D desde la galería" y el botón IA de la cámara.
 */
class Depth3D private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    private val assets = app.assets
    private val resources = app.resources
    private val contentResolver = app.contentResolver

    companion object {
        @Volatile private var inst: Depth3D? = null
        fun get(ctx: Context): Depth3D = inst ?: synchronized(this) { inst ?: Depth3D(ctx).also { inst = it } }

        /** Profundidad (1 a 6) elegida con la barra; la usa también el botón IA de la cámara. */
        fun strength(ctx: Context) = ctx.getSharedPreferences("foto3d", Context.MODE_PRIVATE).getInt("fuerza", 3)
        fun setStrength(ctx: Context, v: Int) = ctx.getSharedPreferences("foto3d", Context.MODE_PRIVATE).edit().putInt("fuerza", v).apply()
    }

    private var interpreter: Interpreter? = null

    private fun model(): Interpreter {
        interpreter?.let { return it }
        val fd = assets.openFd("depth.tflite")
        val buf = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        return Interpreter(buf, Interpreter.Options().setNumThreads(4)).also { interpreter = it }
    }

    /**
     * Depth Anything V2 small (versión TFLite de Qualcomm AI Hub): entrada 518×518 RGB en 0..1 (normaliza adentro),
     * salida 518×518 de profundidad inversa relativa (más = más cerca). Verificado contra el ONNX original.
     */
    @Synchronized fun estimateDepth(bmp: Bitmap): FloatArray {
        val it = model()
        val shp = it.getInputTensor(0).shape()          // [1, 518, 518, 3]
        val n = shp[1]; val m = shp[2]
        val small = Bitmap.createScaledBitmap(bmp, m, n, true)
        val px = IntArray(n * m); small.getPixels(px, 0, m, 0, 0, m, n)
        if (small !== bmp) small.recycle()
        val input = ByteBuffer.allocateDirect(n * m * 3 * 4).order(ByteOrder.nativeOrder())
        for (p in px) {
            input.putFloat(((p shr 16) and 255) / 255f)
            input.putFloat(((p shr 8) and 255) / 255f)
            input.putFloat((p and 255) / 255f)
        }
        input.rewind()
        val out = ByteBuffer.allocateDirect(n * m * 4).order(ByteOrder.nativeOrder())
        it.run(input, out)
        out.rewind()
        val raw = FloatArray(n * m) { out.float }
        // normalizar con percentiles (ignora puntos sueltos) a 0..1
        val sorted = raw.copyOf().also { a -> a.sort() }
        val lo = sorted[(sorted.size * 0.01).toInt()]; val hi = sorted[(sorted.size * 0.99).toInt()]
        val norm = FloatArray(n * m) { ((raw[it] - lo) / max(hi - lo, 1e-6f)).coerceIn(0f, 1f) }
        return upsampleJoint(norm, m, n, px, bmp)
    }

    /**
     * Agranda la profundidad al tamaño de la foto respetando los bordes de la imagen (bilateral conjunto):
     * cada pixel toma la profundidad de los vecinos chicos que tienen un color parecido al suyo, así el borde
     * de la profundidad cae justo en el borde del sujeto y no queda un "halo" estirado alrededor.
     */
    fun upsampleJoint(low: FloatArray, lw: Int, lh: Int, lowPx: IntArray, bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val hi = IntArray(w * h); bmp.getPixels(hi, 0, w, 0, 0, w, h)
        val d = FloatArray(w * h)
        val sc = 1f / (2f * 18f * 18f)   // tolerancia de color
        val ss = 1f / (2f * 0.8f * 0.8f) // distancia en pixeles chicos
        for (y in 0 until h) {
            val fy = (y + 0.5f) * lh / h - 0.5f
            val cy = (fy + 0.5f).toInt().coerceIn(0, lh - 1)
            for (x in 0 until w) {
                val fx = (x + 0.5f) * lw / w - 0.5f
                val cx = (fx + 0.5f).toInt().coerceIn(0, lw - 1)
                val p = hi[y * w + x]
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                var sw = 0f; var sd = 0f
                for (dy in -1..1) {
                    val yy = cy + dy; if (yy < 0 || yy >= lh) continue
                    for (dx in -1..1) {
                        val xx = cx + dx; if (xx < 0 || xx >= lw) continue
                        val q = lowPx[yy * lw + xx]
                        val dr = r - ((q shr 16) and 255); val dg = g - ((q shr 8) and 255); val db = b - (q and 255)
                        val ex = xx - fx; val ey = yy - fy
                        val wgt = kotlin.math.exp(-(dr * dr + dg * dg + db * db) * sc / 3f - (ex * ex + ey * ey) * ss)
                        sw += wgt; sd += wgt * low[yy * lw + xx]
                    }
                }
                d[y * w + x] = if (sw > 1e-6f) sd / sw else low[cy * lw + cx]
            }
        }
        return d
    }


    /** Arma las dos vistas (ojo izquierdo y derecho). Devuelve null si se canceló. */
    fun buildEyes(bmp: Bitmap, d: FloatArray, st: Int, cancelled: () -> Boolean): Pair<Bitmap, Bitmap>? {
        val w = bmp.width; val h = bmp.height
        val src = IntArray(w * h); bmp.getPixels(src, 0, w, 0, 0, w, h)
        val maxDisp = w * st / 100f
        // punto sin separación: la profundidad mediana (lo de adelante sale, lo de atrás se mete)
        val conv = d.copyOf().also { it.sort() }[d.size / 2]
        val left = IntArray(w * h); val right = IntArray(w * h)
        val holeL = BooleanArray(w * h); val holeR = BooleanArray(w * h)
        val rowL = IntArray(w); val rowR = IntArray(w)
        val zL = FloatArray(w); val zR = FloatArray(w)
        for (y in 0 until h) {
            warpRow(src, d, y, w, +0.5f * maxDisp, conv, rowL, zL, holeL)
            warpRow(src, d, y, w, -0.5f * maxDisp, conv, rowR, zR, holeR)
            System.arraycopy(rowL, 0, left, y * w, w)
            System.arraycopy(rowR, 0, right, y * w, w)
            if (cancelled()) return null
        }
        smoothHoles(left, holeL, w, h); smoothHoles(right, holeR, w, h)
        return Bitmap.createBitmap(left, w, h, Bitmap.Config.ARGB_8888) to Bitmap.createBitmap(right, w, h, Bitmap.Config.ARGB_8888)
    }


    fun warpRow(src: IntArray, d: FloatArray, y: Int, w: Int, k: Float, conv: Float, row: IntArray, z: FloatArray, hole: BooleanArray) {
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
        // huecos (lo que tapaba lo de adelante): se completan con la textura del fondo, reflejada desde el lado
        // más lejano, en vez de estirar un solo pixel (eso dejaba rayas)
        var x = 0
        while (x < w) {
            if (z[x] >= 0f) { x++; continue }
            var e = x
            while (e < w && z[e] < 0f) e++
            val l = x - 1; val r = e
            val fromLeft = when { l < 0 && r >= w -> true; l < 0 -> false; r >= w -> true; else -> z[l] <= z[r] }
            for (i in x until e) {
                row[i] = if (fromLeft) {
                    if (l < 0) 0xff000000.toInt() else { var j = l - (i - l - 1); if (j < 0 || z[j] < 0f || z[j] > z[l] + 0.05f) j = l; row[j] }
                } else {
                    var j = r + (r - i - 1); if (j >= w || z[j] < 0f || z[j] > z[r] + 0.05f) j = r; row[j]
                }
                hole[y * w + i] = true
            }
            x = e
        }
    }

    /** Suaviza en vertical solo lo rellenado, para que no se note el corte entre filas. */
    fun smoothHoles(img: IntArray, hole: BooleanArray, w: Int, h: Int) {
        val r = 3
        val copy = img.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!hole[i]) continue
            var sr = 0; var sg = 0; var sb = 0; var c = 0
            for (dy in -r..r) {
                for (dx in -1..1) {
                    val yy = y + dy; val xx = x + dx
                    if (yy < 0 || yy >= h || xx < 0 || xx >= w) continue
                    val p = copy[yy * w + xx]
                    sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255; c++
                }
            }
            img[i] = (0xff shl 24) or ((sr / c) shl 16) or ((sg / c) shl 8) or (sb / c)
        }
    }

    /** Imagen del tamaño de la pantalla (apaisada) con cada ojo centrado en su mitad, como la muestra la app. */
    fun vrFrame(l: Bitmap, r: Bitmap): Bitmap {
        val dm = resources.displayMetrics
        val sw = max(dm.widthPixels, dm.heightPixels); val sh = min(dm.widthPixels, dm.heightPixels)
        // a resolución de la foto: el alto de la imagen manda
        val k = max(1f, min(l.height.toFloat() / sh, l.width * 2f / sw))
        val W = (sw * k).roundToInt(); val H = (sh * k).roundToInt()
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out); c.drawColor(Color.BLACK)
        SbsView.drawEyes(c, l, r, W, H, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun writeJpeg(name: String, bmp: Bitmap) {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DualCam3D")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("no se pudo crear el archivo")
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    /** Guarda las dos versiones: _VR (para los lentes en este celular) y _SBS (lado a lado común). */
    fun saveFiles(name: String, l: Bitmap, r: Bitmap) {
        val w = l.width; val h = l.height
        val sbs = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(sbs).apply { drawBitmap(l, 0f, 0f, null); drawBitmap(r, w.toFloat(), 0f, null) }
        writeJpeg("3D_${name}_SBS.jpg", sbs); sbs.recycle()
        val vr = vrFrame(l, r)
        writeJpeg("3D_${name}_VR.jpg", vr); vr.recycle()
    }

}
