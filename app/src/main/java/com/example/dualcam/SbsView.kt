package com.example.dualcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Muestra las dos vistas: la izquierda centrada en la mitad izquierda de la pantalla y la derecha en la derecha (bajo cada lente). */
class SbsView(ctx: Context) : View(ctx) {
    private var l: Bitmap? = null
    private var r: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun set(left: Bitmap?, right: Bitmap?) { l = left; r = right; invalidate() }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val a = l ?: return; val b = r ?: return
        drawEyes(c, a, b, width, height, paint)
    }

    companion object {
        fun drawEyes(c: Canvas, a: Bitmap, b: Bitmap, w: Int, h: Int, paint: Paint) {
            val half = w / 2f
            val k = min(half / a.width, h.toFloat() / a.height)
            val dw = a.width * k; val dh = a.height * k
            val top = (h - dh) / 2
            c.drawBitmap(a, null, RectF((half - dw) / 2, top, (half - dw) / 2 + dw, top + dh), paint)
            c.drawBitmap(b, null, RectF(half + (half - dw) / 2, top, half + (half - dw) / 2 + dw, top + dh), paint)
        }
    }
}
