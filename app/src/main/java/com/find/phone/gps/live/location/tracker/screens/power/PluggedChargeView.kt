package com.find.phone.gps.live.location.tracker.screens.power

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.find.phone.gps.live.location.tracker.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class PluggedChargeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()

    private var connected = true
    private var progress = if (isInEditMode) 68f else 0f

    private var sweep = 0f
    private var rotationAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null

    private var arcShader: SweepGradient? = null
    private var orbShader: RadialGradient? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private fun primary() = color(R.color.charging_ring_primary)
    private fun secondary() = color(R.color.charging_ring_secondary)
    private fun activeColor() =
        if (connected) primary() else color(R.color.charging_ring_disconnected)

    fun setProgress(value: Int, animate: Boolean) {
        val target = value.coerceIn(0, 100).toFloat()
        progressAnimator?.cancel()
        if (!animate || !isAttachedToWindow) {
            progress = target
            invalidate()
            return
        }
        progressAnimator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 760L
            interpolator = AnimationUtils.loadInterpolator(
                context, android.R.interpolator.fast_out_slow_in
            )
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        arcShader = null
        if (isAttachedToWindow) {
            rotationAnimator?.cancel()
            rotationAnimator = null
            beginRotation()
        }
        invalidate()
    }

    private fun beginRotation() {
        if (isInEditMode || rotationAnimator != null) return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = if (connected) 2800L else 4400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweep = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        beginRotation()
    }

    override fun onDetachedFromWindow() {
        rotationAnimator?.cancel()
        progressAnimator?.cancel()
        rotationAnimator = null
        progressAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        arcShader = null
        orbShader = null
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    override fun onDraw(views: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val stroke = dp(9f)
        val radius = min(cx, cy) - stroke
        if (radius <= 0f) return
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)

        if (orbShader == null) {
            orbShader = RadialGradient(
                cx, cy, radius,
                intArrayOf(color(R.color.charging_orb_center), color(R.color.charging_orb_edge)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        }
        fillPaint.shader = orbShader
        views.drawCircle(cx, cy, radius, fillPaint)
        fillPaint.shader = null

        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = stroke
        ringPaint.shader = null
        ringPaint.color = color(R.color.charging_ring_track)
        views.drawCircle(cx, cy, radius, ringPaint)

        val sweepAngle = (progress / 100f) * 360f

        if (connected) {
            glowPaint.style = Paint.Style.STROKE
            glowPaint.strokeWidth = stroke * 1.7f
            glowPaint.color = (primary() and 0x00FFFFFF) or 0x55000000
            views.drawArc(arc, sweep - 90f, 46f, false, glowPaint)
        }

        if (sweepAngle > 0f) {
            if (arcShader == null) {
                val a = activeColor()
                val b = if (connected) secondary() else color(R.color.charging_ring_disconnected)
                arcShader = SweepGradient(
                    cx, cy,
                    intArrayOf(a, b, a),
                    floatArrayOf(0f, 0.5f, 1f)
                )
            }
            ringPaint.shader = arcShader
            views.drawArc(arc, -90f, sweepAngle, false, ringPaint)
            ringPaint.shader = null

            val headAngle = Math.toRadians((-90f + sweepAngle).toDouble())
            val hx = cx + radius * cos(headAngle).toFloat()
            val hy = cy + radius * sin(headAngle).toFloat()
            dotPaint.color = Color.WHITE
            views.drawCircle(hx, hy, stroke * 0.62f, dotPaint)
            dotPaint.color = (activeColor() and 0x00FFFFFF) or 0x66000000
            views.drawCircle(hx, hy, stroke * 1.1f, dotPaint)
        }
    }
}
