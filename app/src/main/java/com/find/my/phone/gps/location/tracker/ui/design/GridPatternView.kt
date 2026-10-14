package com.find.my.phone.gps.location.tracker.ui.design

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.find.my.phone.gps.location.tracker.R

/**
 * The design's map grid: `background-image: linear-gradient(<color> 1px, transparent 1px),
 * linear-gradient(90deg, …); background-size: N N` — one hairline every [cell] dp.
 */
class GridPatternView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var cell: Float

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.GridPatternView)
        paint.color = a.getColor(R.styleable.GridPatternView_gridColor, 0x0F002EFE)
        cell = a.getDimension(R.styleable.GridPatternView_gridSize, 30f * resources.displayMetrics.density)
        a.recycle()
        paint.strokeWidth = resources.displayMetrics.density // 1px in the design ≈ 1dp hairline
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
