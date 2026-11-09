package com.hk.mobile2tvgame.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import java.util.concurrent.ConcurrentHashMap

/** Telefondaki dokunuslari TV'de yuvarlak imlec olarak gosterir. Koordinatlar 0..1. */
class CursorView(ctx: Context) : View(ctx) {

    private class Dot(var x: Float, var y: Float, var down: Boolean, var hover: Boolean, var t: Long)

    private val dots = ConcurrentHashMap<Int, Dot>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** action: 0=bas, 1=birak, 2=surukle, 3=imlec gezdir (hover) */
    fun update(id: Int, action: Int, nx: Float, ny: Float) {
        val d = dots.getOrPut(id) { Dot(nx, ny, false, false, 0L) }
        d.x = nx
        d.y = ny
        d.down = (action == 0 || action == 2)
        d.hover = (action == 3)
        d.t = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        var again = false
        for ((_, d) in dots) {
            val age = now - d.t
            val fadeMs = if (d.hover) 2500L else 700L
            val alpha: Int
            if (d.down) {
                alpha = 230
                again = true
            } else {
                if (age > fadeMs) continue
                alpha = (210 * (1f - age.toFloat() / fadeMs)).toInt()
                again = true
            }
            val px = d.x * width
            val py = d.y * height
            val r = minOf(width, height) * 0.025f
            paint.style = Paint.Style.FILL
            paint.color = if (d.down) Color.argb((alpha * 2) / 3, 77, 163, 255) else Color.argb(alpha / 3, 77, 163, 255)
            c.drawCircle(px, py, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r * 0.20f
            paint.color = Color.argb(alpha, 255, 255, 255)
            c.drawCircle(px, py, r, paint)
            paint.style = Paint.Style.FILL
            c.drawCircle(px, py, r * 0.22f, paint)
        }
        if (again) postInvalidateOnAnimation()
    }
}
