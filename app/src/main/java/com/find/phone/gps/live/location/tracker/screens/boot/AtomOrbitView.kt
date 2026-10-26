package com.find.phone.gps.live.location.tracker.screens.boot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class AtomOrbitView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private class Orbit(val tilt: Float, val periodMs: Long, val phaseMs: Long, val color: Int, val icon: Int)

    private val d = resources.displayMetrics.density
    private val orbits = listOf(
        Orbit(-28f, 7_000L, 0L, color(R.color.ds_primary), R.drawable.ic_orb_phone),
        Orbit(28f, 8_500L, -3_400L, color(R.color.ds_pin_red), R.drawable.ic_orb_pin),
        Orbit(90f, 10_000L, -7_000L, color(R.color.pw_brand), R.drawable.ic_orb_hash),
    )
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = color(R.color.ds_primary) and 0x00FFFFFF or 0x38000000
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val still = CraftMovement.animationsDisabled(context)
    private val t0 = SystemClock.uptimeMillis()

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val a = 130f * d
        val b = 46f * d
        val r = 18f * d
        val now = if (still) 0L else SystemClock.uptimeMillis() - t0
        for (o in orbits) {
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(o.tilt)
            canvas.drawOval(-a, -b, a, b, ring)
            val theta = 2.0 * PI * (((now + o.phaseMs) % o.periodMs + o.periodMs) % o.periodMs) / o.periodMs
            val x = (a * cos(theta)).toFloat()
            val y = (b * sin(theta)).toFloat()
            fill.color = o.color
            canvas.drawCircle(x, y, r, fill)
            fill.color = color(R.color.white)
            canvas.drawCircle(x, y, r - 2.5f * d, fill)
            canvas.restore()
            // icon stays upright: place it at the rotated position without rotating the glyph
            val rad = Math.toRadians(o.tilt.toDouble())
            val px = cx + (x * cos(rad) - y * sin(rad)).toFloat()
            val py = cy + (x * sin(rad) + y * cos(rad)).toFloat()
            ContextCompat.getDrawable(context, o.icon)?.mutate()?.let {
                it.setTint(o.color)
                val h = 8f * d
                it.setBounds((px - h).toInt(), (py - h).toInt(), (px + h).toInt(), (py + h).toInt())
                it.draw(canvas)
            }
        }
        if (!still) postInvalidateOnAnimation()
    }
}
