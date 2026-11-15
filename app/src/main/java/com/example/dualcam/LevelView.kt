package com.example.dualcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Nivel de burbuja sobre cada ojo: una marca fija (la horizontal de la pantalla) y una línea que muestra
 * dónde está el horizonte real según el sensor de gravedad. Cuando el celular está derecho (±0,5°)
 * las dos coinciden y se ponen verdes. Solo se ve en pantalla: no sale en fotos ni videos.
 */
class LevelView(ctx: Context, private val eyes: (Int, Int) -> Pair<RectF, RectF>) : View(ctx), SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private var tilt = 0f          // grados que está girado el celular respecto del piso
    private var have = false
    private var gx = 0f; private var gy = 0f

    private val refP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); strokeWidth = 3f; strokeCap = Paint.Cap.ROUND }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; strokeCap = Paint.Cap.ROUND }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }

    init { isClickable = false; isFocusable = false }

    fun start() {
        val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        // filtro suave para que la línea no tiemble
        val k = if (have) 0.2f else 1f
        gx += (e.values[0] - gx) * k; gy += (e.values[1] - gy) * k; have = true
        val rot = when (display?.rotation ?: Surface.ROTATION_90) {
            Surface.ROTATION_90 -> 90f; Surface.ROTATION_180 -> 180f; Surface.ROTATION_270 -> 270f; else -> 0f
        }
        var t = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat() - rot
        while (t > 180f) t -= 360f
        while (t <= -180f) t += 360f
        tilt = t
        invalidate()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDraw(c: Canvas) {
        if (!have || width == 0) return
        val (l, r) = eyes(width, height)
        val ok = abs(tilt) <= 0.5f
        val col = when { ok -> Color.rgb(60, 230, 110); abs(tilt) <= 2f -> Color.rgb(255, 200, 40); else -> Color.rgb(255, 90, 70) }
        lineP.color = col; txtP.color = col
        for (eye in listOf(l, r)) {
            val cx = eye.centerX(); val cy = eye.centerY()
            val half = eye.width() * 0.32f
            // marca fija: dos trazos a los costados (la horizontal de la pantalla)
            c.drawLine(cx - half - 30f, cy, cx - half + 10f, cy, refP)
            c.drawLine(cx + half - 10f, cy, cx + half + 30f, cy, refP)
            // horizonte real
            c.save(); c.rotate(tilt, cx, cy)
            c.drawLine(cx - half, cy, cx + half, cy, lineP)
            c.drawCircle(cx, cy, 6f, lineP)
            c.restore()
            // grados
            txtP.textSize = eye.height() * 0.06f
            val s = if (ok) "0,0° ✓" else String.format(Locale("es"), "%+.1f°", -tilt)
            val ty = cy + eye.height() * 0.14f
            val tw = txtP.measureText(s) / 2 + 12f
            c.drawRoundRect(RectF(cx - tw, ty - txtP.textSize, cx + tw, ty + txtP.textSize * 0.35f), 12f, 12f, bgP)
            c.drawText(s, cx, ty, txtP)
        }
    }
}
