package com.find.my.phone.gps.location.tracker.ui.design

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.find.my.phone.gps.location.tracker.R
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The self-typing number in the onboarding search card: the design animates `max-width`
 * with `type 4s steps(12) infinite` (0–8% nothing, 62–100% the whole 14ch). Here the visible
 * prefix of [typingText] follows the same stepped timeline. Width is reserved for the full
 * text so the cursor next to it does not jitter.
 */
class TypingTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    private val fullText: String
    private val durationMs: Long
    private var animator: Animator? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.TypingTextView)
        fullText = a.getString(R.styleable.TypingTextView_typingText) ?: text.toString()
        durationMs = a.getInt(R.styleable.TypingTextView_typingDuration, 4000).toLong()
        a.recycle()
        text = fullText
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (DesignMotion.animationsDisabled(context)) {
            text = fullText
            return
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = DesignMotion.LINEAR
            addUpdateListener {
                val t = it.animatedValue as Float
                val stepped = floor(t * STEPS) / STEPS            // steps(12, end)
                val fraction = ((stepped - 0.08f) / 0.54f).coerceIn(0f, 1f)
                val chars = (fraction * fullText.length).roundToInt()
                val shown = fullText.take(chars)
                if (shown != text.toString()) text = shown
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val STEPS = 12f
    }
}
