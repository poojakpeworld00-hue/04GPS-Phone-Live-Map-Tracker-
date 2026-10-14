package com.find.phone.gps.live.location.tracker.screens.craft

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.find.phone.gps.live.location.tracker.R

class LatticeMotifView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var cell: Float

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.LatticeMotifView)
        paint.color = a.getColor(R.styleable.LatticeMotifView_gridColor, 0x0F315BFF)
        cell = a.getDimension(R.styleable.LatticeMotifView_gridSize, 30f * resources.displayMetrics.density)
        a.recycle()
        paint.strokeWidth = resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        if (cell <= 0f) return
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            x += cell
        }
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
            y += cell
        }
    }
}
