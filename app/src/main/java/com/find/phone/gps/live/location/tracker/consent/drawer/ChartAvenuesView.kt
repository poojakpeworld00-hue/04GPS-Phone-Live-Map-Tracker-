package com.find.phone.gps.live.location.tracker.consent.drawer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.dp

class ChartAvenuesView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ps_map_road)
    }
    private val cell = context.dp(84f)

    override fun onDraw(canvas: Canvas) {
        val from = cell * 0.46f
        val to = cell * 0.54f
        var x = 0f
        while (x < width) {
            canvas.drawRect(x + from, 0f, x + to, height.toFloat(), paint)
            x += cell
        }
        var y = 0f
        while (y < height) {
            canvas.drawRect(0f, y + from, width.toFloat(), y + to, paint)
            y += cell
        }
    }
}
