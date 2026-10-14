package com.find.my.phone.gps.location.tracker.ui.design

import android.animation.Animator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import com.find.my.phone.gps.location.tracker.R

/**
 * The radar sweep: `conic-gradient(from 0deg, <color>, transparent <angle>deg, transparent)`
 * spinning a full turn every [sweepDuration] ms (linear, infinite). The wedge fades from
 * [sweepColor] at 0° to transparent at [sweepAngle]°.
 */
class RadarSweepView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val color: Int
    private val wedgeDeg: Float
    private val durationMs: Long
    private var animator: Animator? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.RadarSweepView)
        color = a.getColor(R.styleable.RadarSweepView_sweepColor, 0x59FFFFFF)
        wedgeDeg = a.getFloat(R.styleable.RadarSweepView_sweepAngle, 70f)
        durationMs = a.getInt(R.styleable.RadarSweepView_sweepDuration, 3000).toLong()
        a.recycle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val transparent = color and 0x00FFFFFF
        // CSS conic gradients start at 12 o'clock; SweepGradient starts at 3 o'clock, so the
        // view is pre-rotated by -90° in onDraw.
        paint.shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(color, transparent, transparent),
            floatArrayOf(0f, wedgeDeg / 360f, 1f),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.save()
        canvas.rotate(-90f, cx, cy)
        canvas.drawCircle(cx, cy, minOf(width, height) / 2f, paint)
        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (DesignMotion.animationsDisabled(context)) return
        animator?.cancel()
        animator = DesignMotion.spin(this, durationMs)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
