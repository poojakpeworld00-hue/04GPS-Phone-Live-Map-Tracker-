package com.find.my.phone.gps.location.tracker.ui.design

import android.animation.Animator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.util.Property
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * The GPS Tracker design's CSS keyframes, as Android animators.
 *
 * Every helper is a literal port of one `@keyframes` block in `GPS Tracker Design.dc.html`
 * (`ping`, `spin`, `floaty`, `fadein`, `blink`, `drop`, `st1..st4`, …) with the same durations,
 * delays and easing curves. Per-segment easing is applied the way CSS does it: each [Keyframe]
 * carries the interpolator of the segment that ends on it, and the animator itself runs linear.
 *
 * All motion is decorative. When the user has animations off ([animationsDisabled]) callers
 * show the final frame instead; the helpers themselves always leave the view in a sane state.
 */
object DesignMotion {

    /** CSS `ease`. */
    val EASE: TimeInterpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    /** CSS `ease-out`. */
    val EASE_OUT: TimeInterpolator = PathInterpolator(0f, 0f, 0.58f, 1f)
    /** CSS `ease-in-out`. */
    val EASE_IN_OUT: TimeInterpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)
    /** CSS `linear`. */
    val LINEAR: TimeInterpolator = LinearInterpolator()
    /** The pin drop's `cubic-bezier(.3,1.4,.5,1)` overshoot. */
    val DROP: TimeInterpolator = PathInterpolator(0.3f, 1.4f, 0.5f, 1f)

    /** True when the user has turned system animations off ("Remove animations"). */
    fun animationsDisabled(context: Context): Boolean = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f

    fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

    /** A keyframed property with one easing per segment (CSS `animation-timing-function`). */
    fun pvh(
        prop: Property<View, Float>,
        easing: TimeInterpolator,
        vararg stops: Pair<Float, Float>,
    ): PropertyValuesHolder {
        val frames = Array(stops.size) { i ->
            Keyframe.ofFloat(stops[i].first, stops[i].second).apply {
                if (i > 0) interpolator = easing
            }
        }
        return PropertyValuesHolder.ofKeyframe(prop, *frames)
    }

    /** An infinite, linear-driven keyframe loop (the keyframes carry the easing). */
    fun loop(view: View, durationMs: Long, delayMs: Long, vararg holders: PropertyValuesHolder): ObjectAnimator =
        ObjectAnimator.ofPropertyValuesHolder(view, *holders).apply {
            duration = durationMs
            startDelay = delayMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LINEAR
            start()
        }

    /** `floaty`: 0 → -8px → 0 over 4s, ease-in-out, infinite. */
    fun floaty(view: View, delayMs: Long = 0L, amplitudeDp: Float = 8f, durationMs: Long = 4000L): Animator {
        val amp = view.context.dp(amplitudeDp)
        return loop(
            view, durationMs, delayMs,
            pvh(View.TRANSLATION_Y, EASE_IN_OUT, 0f to 0f, 0.5f to -amp, 1f to 0f),
        )
    }

    /** `ping`: scale .25 → 1 with opacity .9 → 0, ease-out, infinite. */
    fun ping(view: View, durationMs: Long = 2800L, delayMs: Long = 0L): Animator = loop(
        view, durationMs, delayMs,
        pvh(View.SCALE_X, EASE_OUT, 0f to 0.25f, 1f to 1f),
        pvh(View.SCALE_Y, EASE_OUT, 0f to 0.25f, 1f to 1f),
        pvh(View.ALPHA, EASE_OUT, 0f to 0.9f, 1f to 0f),
    )

    /** `spin` / `spinr`: one full turn per [durationMs], linear, infinite. */
    fun spin(view: View, durationMs: Long, reverse: Boolean = false): Animator =
        ObjectAnimator.ofFloat(view, View.ROTATION, 0f, if (reverse) -360f else 360f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LINEAR
            start()
        }

    /** `fadein`: opacity 0 → 1 while rising 10px, CSS `ease`, played once (`both` fill). */
    fun fadeIn(view: View, durationMs: Long = 600L, delayMs: Long = 0L): Animator {
        val rise = view.context.dp(10f)
        view.alpha = 0f
        view.translationY = rise
        return ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, rise, 0f),
        ).apply {
            duration = durationMs
            startDelay = delayMs
            interpolator = EASE
            start()
        }
    }

    /** `blink 1s step-end`: visible for the first half of the period, hidden for the second. */
    fun blink(view: View, periodMs: Long = 1000L): Animator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LINEAR
            addUpdateListener { view.alpha = if ((it.animatedValue as Float) < 0.5f) 1f else 0f }
            start()
        }

    /**
     * `glow`: the design pulses the Search button's shadow (`0 10 24 -10 cyan/70` ↔
     * `0 14 34 -6 signal/95`). A radial glow view behind the button breathes instead.
     */
    fun glow(glowView: View, durationMs: Long = 2000L): Animator = loop(
        glowView, durationMs, 0L,
        pvh(View.ALPHA, EASE_IN_OUT, 0f to 0.55f, 0.5f to 1f, 1f to 0.55f),
        pvh(View.SCALE_X, EASE_IN_OUT, 0f to 1f, 0.5f to 1.08f, 1f to 1f),
        pvh(View.SCALE_Y, EASE_IN_OUT, 0f to 1f, 0.5f to 1.16f, 1f to 1f),
    )

    /** `dots`: the three loading dots bob up 6px out of phase. */
    fun dots(views: List<View>, durationMs: Long = 900L): List<Animator> {
        val amp = views.firstOrNull()?.context?.dp(6f) ?: return emptyList()
        return views.mapIndexed { i, v ->
            loop(
                v, durationMs, i * 150L,
                pvh(View.TRANSLATION_Y, EASE_IN_OUT, 0f to 0f, 0.5f to -amp, 1f to 0f),
                pvh(View.ALPHA, EASE_IN_OUT, 0f to 0.5f, 0.5f to 1f, 1f to 0.5f),
            )
        }
    }

    /** Press feedback from the design system: scale .98 for 90ms, back on release. */
    fun pressScale(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90L).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
            }
            false
        }
    }

    /** Cancels and forgets every animator in [list]. */
    fun cancelAll(list: MutableList<Animator>) {
        list.forEach { it.cancel() }
        list.clear()
    }
}
