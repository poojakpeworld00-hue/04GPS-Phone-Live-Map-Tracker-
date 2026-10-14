package com.find.my.phone.gps.location.tracker.ui.design

import android.animation.Animator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.find.my.phone.gps.location.tracker.R

/**
 * One radar ring running the design's `ping` keyframes (scale .25 → 1, opacity .9 → 0,
 * ease-out, infinite). Stagger several rings with [pingDelay]; the ring is drawn as a
 * stroke of [pingStrokeWidth] (and an optional fill), so the whole view scales together.
 * Starts on attach, stops on detach, and shows nothing when animations are off.
 */
class PingRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val durationMs: Long
    private val delayMs: Long
    private var animator: Animator? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PingRingView)
        stroke.color = a.getColor(R.styleable.PingRingView_pingColor, 0x733D63FF)
        stroke.strokeWidth = a.getDimension(R.styleable.PingRingView_pingStrokeWidth, 2f * resources.displayMetrics.density)
        fill.color = a.getColor(R.styleable.PingRingView_pingFillColor, 0)
        durationMs = a.getInt(R.styleable.PingRingView_pingDuration, 2800).toLong()
        delayMs = a.getInt(R.styleable.PingRingView_pingDelay, 0).toLong()
        a.recycle()
        // The first frame of `ping` is scale .25 / opacity .9; hidden until the loop starts.
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
        if (DesignMotion.animationsDisabled(context)) {
            alpha = 0f
            return
        }
        animator?.cancel()
        animator = DesignMotion.ping(this, durationMs, delayMs)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
