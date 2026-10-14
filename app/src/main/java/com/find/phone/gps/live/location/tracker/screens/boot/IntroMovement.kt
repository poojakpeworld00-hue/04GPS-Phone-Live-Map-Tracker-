package com.find.phone.gps.live.location.tracker.screens.boot

import android.animation.Animator
import android.animation.ValueAnimator
import android.view.View
import com.find.phone.gps.live.location.tracker.databinding.ScreenSplashBinding
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.dp
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.loop
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.pvh

/**
 * The redesign's three-stage sequence, a literal port of the design's `st1e`, `st1c`, `st3`,
 * `st4`, `drop` and `bar` keyframes on one 6.4 s cycle (CSS `animation: stN 6.4s ease infinite`):
 *
 * | stage | layer (visible)              | status line (visible)         |
 * |-------|------------------------------|-------------------------------|
 * | 1     | earth globe — `st1e` 4%–68%  | "Triangulating" — `st1c` 4%–41% |
 * | 2     | red pin — `st3` 53%–70%      | "Dropping pin" — `st3` 53%–70%  |
 * | 3     | logo — `st4` 79%–97%         | tagline — `st4` 79%–97%         |
 *
 * The composition holds the globe longer than its caption (`st1e` vs `st1c`), the pin runs
 * `drop` (−80px → +6 → −5 → 0 between 50% and 65% with the (.3,1.4,.5,1) overshoot) and the
 * progress fill grows linearly to full width at 97%. The globe's own spin, the orbiting
 * satellites and the pin ping are owned by their views; navigation is owned by BootActivity,
 * which only waits for one full cycle.
 */
class IntroMovement(private val binding: ScreenSplashBinding) {

    private val animators = mutableListOf<Animator>()
    private val context get() = binding.root.context

    fun start() {
        cancel()
        if (CraftMovement.animationsDisabled(context)) {
            presentFinalFrame()
            return
        }
        animators += st1e(binding.lvStageSignal)
        animators += st1c(binding.condSignal)
        animators += st3(binding.lvStagePin)
        animators += st3(binding.condPin)
        animators += st4(binding.lvStageLogo)
        animators += st4(binding.condLogo)
        drop(binding.bootPin)
        bar()
    }

    fun cancel() = CraftMovement.abortAll(animators)

    /** "Remove animations": the final frame — the logo held, the bar full. */
    private fun presentFinalFrame() {
        listOf(binding.lvStageSignal, binding.lvStagePin, binding.condSignal, binding.condPin)
            .forEach { it.alpha = 0f }
        binding.lvStageLogo.alpha = 1f
        binding.condLogo.alpha = 1f
        binding.progTrack.post { setFillFraction(1f) }
    }

    // ── the three stage timelines (fractions of the 6.4 s cycle) ──

    /** `st1e`: the earth composition, held 4% → 68%. */
    private fun st1e(v: View) = loop(
        v, CYCLE_MS, 0L,
        pvh(View.ALPHA, CraftMovement.EASE, 0f to 0f, .04f to 1f, .68f to 1f, .74f to 0f, 1f to 0f),
        pvh(View.SCALE_X, CraftMovement.EASE, 0f to .9f, .04f to 1f, .68f to 1f, .74f to 1.12f, 1f to 1.12f),
        pvh(View.SCALE_Y, CraftMovement.EASE, 0f to .9f, .04f to 1f, .68f to 1f, .74f to 1.12f, 1f to 1.12f),
    )

    /** `st1c`: the earth caption, held 4% → 41% (shorter than the composition). */
    private fun st1c(v: View) = loop(
        v, CYCLE_MS, 0L,
        pvh(View.ALPHA, CraftMovement.EASE, 0f to 0f, .04f to 1f, .41f to 1f, .46f to 0f, 1f to 0f),
        pvh(View.SCALE_X, CraftMovement.EASE, 0f to .9f, .04f to 1f, .41f to 1f, .46f to 1.12f, 1f to 1.12f),
        pvh(View.SCALE_Y, CraftMovement.EASE, 0f to .9f, .04f to 1f, .41f to 1f, .46f to 1.12f, 1f to 1.12f),
    )

    private fun st3(v: View) = loop(
        v, CYCLE_MS, 0L,
        pvh(View.ALPHA, CraftMovement.EASE, 0f to 0f, .46f to 0f, .53f to 1f, .70f to 1f, .75f to 0f, 1f to 0f),
        pvh(View.SCALE_X, CraftMovement.EASE, 0f to .8f, .46f to .8f, .53f to 1f, .70f to 1f, .75f to 1.15f, 1f to 1.15f),
        pvh(View.SCALE_Y, CraftMovement.EASE, 0f to .8f, .46f to .8f, .53f to 1f, .70f to 1f, .75f to 1.15f, 1f to 1.15f),
    )

    private fun st4(v: View) = loop(
        v, CYCLE_MS, 0L,
        pvh(View.ALPHA, CraftMovement.EASE, 0f to 0f, .71f to 0f, .79f to 1f, .97f to 1f, 1f to 0f),
        pvh(View.SCALE_X, CraftMovement.EASE, 0f to .82f, .71f to .82f, .79f to 1f, 1f to 1f),
        pvh(View.SCALE_Y, CraftMovement.EASE, 0f to .82f, .71f to .82f, .79f to 1f, 1f to 1f),
    )

    /** `drop 6.4s cubic-bezier(.3,1.4,.5,1) infinite` on the pin. */
    private fun drop(pin: View) {
        val from = context.dp(-80f)
        val bounce = context.dp(6f)
        val settle = context.dp(-5f)
        animators += loop(
            pin, CYCLE_MS, 0L,
            pvh(View.ALPHA, CraftMovement.DROP, 0f to 0f, .50f to 0f, .57f to 1f, 1f to 1f),
            pvh(View.TRANSLATION_Y, CraftMovement.DROP, 0f to from, .50f to from, .57f to bounce, .61f to settle, .65f to 0f, 1f to 0f),
        )
    }

    /** `bar 6.4s linear infinite`: width 0 → 100% by 97%. */
    private fun bar() {
        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = CraftMovement.LINEAR
            addUpdateListener {
                val t = it.animatedValue as Float
                setFillFraction((t / 0.97f).coerceAtMost(1f))
            }
            start()
        }
    }

    private fun setFillFraction(fraction: Float) {
        val track = binding.progTrack.width
        if (track <= 0) return
        val lp = binding.progFill.layoutParams
        lp.width = (track * fraction).toInt()
        binding.progFill.layoutParams = lp
    }

    companion object {
        /** One full pass through the three stages. */
        const val CYCLE_MS = 6400L
    }
}
