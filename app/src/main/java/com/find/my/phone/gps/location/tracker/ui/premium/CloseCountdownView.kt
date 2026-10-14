package com.find.my.phone.gps.location.tracker.ui.premium

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.res.ResourcesCompat
import com.find.my.phone.gps.location.tracker.R
import kotlin.math.ceil

/**
 * The paywall's close control (design 10a, top-right on the light map): a dark 72% ink disc with
 * a white ring that drains over [start]'s seconds and the remaining count in the middle, then a
 * white X on the same disc, tappable. Fixed colours: the map under it is light in both themes.
 */
class CloseCountdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = WHITE_20
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = WHITE
    }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.figtree_bold)
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        color = WHITE
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK_72
    }
    private val oval = RectF()

    private var total = 0L
    private var left = 0f
    private var animator: ValueAnimator? = null

    /** True once the countdown has run out and the X is showing. */
    var closable = false
        private set

    init {
        isClickable = false
        contentDescription = context.getString(R.string.ds_cd_close)
    }

    /** How opaque the control is, countdown and X alike (`premium.close_button_opacity`). */
    private var targetAlpha = 1f

    /**
     * Runs the countdown; [onClosable] fires when the X appears (straight away for 0 s).
     * [opacity] is the control's alpha throughout, 1 = fully visible.
     */
    fun start(seconds: Int, opacity: Float = 1f, onClosable: () -> Unit) {
        animator?.cancel()
        targetAlpha = opacity
        alpha = opacity
        if (seconds <= 0) return finish(onClosable)
        total = seconds * 1000L
        left = total.toFloat()
        animator = ValueAnimator.ofFloat(total.toFloat(), 0f).apply {
            duration = total
            interpolator = LinearInterpolator()
            addUpdateListener {
                left = it.animatedValue as Float
                invalidate()
            }
            doOnEnd { finish(onClosable) }
            start()
        }
    }

    private fun finish(onClosable: () -> Unit) {
        closable = true
        isClickable = true
        alpha = 0f
        scaleX = 0.6f
        scaleY = 0.6f
        invalidate()
        animate().alpha(targetAlpha).scaleX(1f).scaleY(1f).setDuration(250).start()
        onClosable()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, minOf(cx, cy), disc)
        if (closable) {
            val arm = 5.5f * density
            canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, cross)
            canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, cross)
            return
        }
        val radius = 13 * density
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawCircle(cx, cy, radius, track)
        val fraction = if (total > 0) left / total else 0f
        canvas.drawArc(oval, -90f, -360f * fraction, false, ring)
        val count = ceil(left / 1000f).toInt().coerceAtLeast(1).toString()
        canvas.drawText(count, cx, cy - (digits.descent() + digits.ascent()) / 2, digits)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val INK_72 = 0xB80B1B2B.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val WHITE_20 = 0x33FFFFFF
    }

    private inline fun ValueAnimator.doOnEnd(crossinline block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            private var canceled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { canceled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) { if (!canceled) block() }
        })
    }
}
