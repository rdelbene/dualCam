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


    /**
     * Arma las dos vistas (ojo izquierdo y derecho). Devuelve null si se canceló.
     *
     * Cada pixel de cada ojo va a buscar su color a la foto original (muestreo inverso), corrido según la profundidad.
     * Así no quedan huecos ni copias: en los bordes (pelo, ramas) la imagen se estira apenas en vez de partirse en
     * dos capas. La profundidad se suaviza un poco para que el borde del sujeto se mueva todo junto.
     */
    fun buildEyes(bmp: Bitmap, d0: FloatArray, st: Int, cancelled: () -> Boolean): Pair<Bitmap, Bitmap>? {
        val w = bmp.width; val h = bmp.height
        val src = IntArray(w * h); bmp.getPixels(src, 0, w, 0, 0, w, h)
        val maxDisp = w * st / 100f
        // suavizado de la profundidad (dos pasadas de caja ≈ gaussiana), radio ~ la mitad del corrimiento máximo
        val rad = max(2, (maxDisp * 0.5f).roundToInt())
        val d = blur(blur(d0, w, h, rad), w, h, rad)
        if (cancelled()) return null
        // punto sin separación: la profundidad mediana (lo de adelante sale, lo de atrás se mete)
        val conv = d.copyOf().also { it.sort() }[d.size / 2]
        val left = IntArray(w * h); val right = IntArray(w * h)
        for (y in 0 until h) {
            sampleRow(src, d, y, w, +0.5f * maxDisp, conv, left)
            sampleRow(src, d, y, w, -0.5f * maxDisp, conv, right)
            if (cancelled()) return null
        }
        return Bitmap.createBitmap(left, w, h, Bitmap.Config.ARGB_8888) to Bitmap.createBitmap(right, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Fila de un ojo: el pixel x toma el color de la foto en xs, con xs = x − k·(profundidad(xs) − conv). */
    private fun sampleRow(src: IntArray, d: FloatArray, y: Int, w: Int, k: Float, conv: Float, out: IntArray) {
        val base = y * w
        val last = (w - 1).toFloat()
        for (x in 0 until w) {
            var xs = x.toFloat()
            for (it in 0 until 4) {
                val i0 = xs.toInt().coerceIn(0, w - 1); val i1 = min(i0 + 1, w - 1); val t = (xs - i0).coerceIn(0f, 1f)
                val dz = d[base + i0] * (1 - t) + d[base + i1] * t
                xs = (x - k * (dz - conv)).coerceIn(0f, last)
            }
            val x0 = xs.toInt(); val x1 = min(x0 + 1, w - 1); val t = xs - x0
            val a = src[base + x0]; val b = src[base + x1]
            val r = (((a shr 16) and 255) * (1 - t) + ((b shr 16) and 255) * t + 0.5f).toInt()
            val g = (((a shr 8) and 255) * (1 - t) + ((b shr 8) and 255) * t + 0.5f).toInt()
            val bl = ((a and 255) * (1 - t) + (b and 255) * t + 0.5f).toInt()
            out[base + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }

    private fun blur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(a.size); val o = FloatArray(a.size)
        val n = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            var s = 0f; val b = y * w
            for (x in -r..r) s += a[b + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                t[b + x] = s / n
                s += a[b + (x + r + 1).coerceIn(0, w - 1)] - a[b + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var s = 0f
            for (y in -r..r) s += t[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                o[y * w + x] = s / n
                s += t[(y + r + 1).coerceIn(0, h - 1) * w + x] - t[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return o
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
        // una sola foto: preparada para el visor (cada ojo centrado bajo su lente)
        val vr = vrFrame(l, r)
        writeJpeg("3D_${name}.jpg", vr); vr.recycle()
    }

}
