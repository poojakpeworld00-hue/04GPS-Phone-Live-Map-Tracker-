package com.find.phone.gps.live.location.tracker.screens.craft

import android.animation.Animator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.find.phone.gps.live.location.tracker.R

class BlipLoopView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val durationMs: Long
    private val delayMs: Long
    private var animator: Animator? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.BlipLoopView)
        stroke.color = a.getColor(R.styleable.BlipLoopView_pingColor, 0x733D63FF)
        stroke.strokeWidth = a.getDimension(R.styleable.BlipLoopView_pingStrokeWidth, 2f * resources.displayMetrics.density)
        fill.color = a.getColor(R.styleable.BlipLoopView_pingFillColor, 0)
        durationMs = a.getInt(R.styleable.BlipLoopView_pingDuration, 2800).toLong()
        delayMs = a.getInt(R.styleable.BlipLoopView_pingDelay, 0).toLong()
        a.recycle()

        alpha = 0f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - stroke.strokeWidth / 2f
        if (fill.color != 0) canvas.drawCircle(cx, cy, r, fill)
        if (stroke.strokeWidth > 0f) canvas.drawCircle(cx, cy, r, stroke)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (CraftMovement.animationsDisabled(context)) {
            alpha = 0f
            return
        }
        animator?.cancel()
        animator = CraftMovement.ping(this, durationMs, delayMs)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
