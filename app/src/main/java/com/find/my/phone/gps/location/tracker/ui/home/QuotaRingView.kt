package com.find.my.phone.gps.location.tracker.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.find.my.phone.gps.location.tracker.R

/** The limit sheet's usage ring (design 9b): 8dp track with the used share in amber, from 12 o'clock. */
class QuotaRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val stroke = 8 * resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = ContextCompat.getColor(context, R.color.ds_border)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.ds_warn)
    }
    private val oval = RectF()

    /** 0..1 of the allowance used. */
    var fraction: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val inset = stroke / 2
        oval.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(oval, track)
        canvas.drawArc(oval, -90f, 360f * fraction, false, fill)
    }
}
