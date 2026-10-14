package com.find.phone.gps.live.location.tracker.screens.boot

import android.animation.Animator
import android.animation.ValueAnimator
import android.view.View
import com.find.phone.gps.live.location.tracker.databinding.ScreenSplashBinding
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.dp
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.loop
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.pvh

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

    private fun presentFinalFrame() {
        listOf(binding.lvStageSignal, binding.lvStagePin, binding.condSignal, binding.condPin)
            .forEach { it.alpha = 0f }
        binding.lvStageLogo.alpha = 1f
        binding.condLogo.alpha = 1f
        binding.progTrack.post { setFillFraction(1f) }
    }

    private fun st1e(v: View) = loop(
        v, CYCLE_MS, 0L,
        pvh(View.ALPHA, CraftMovement.EASE, 0f to 0f, .04f to 1f, .68f to 1f, .74f to 0f, 1f to 0f),
        pvh(View.SCALE_X, CraftMovement.EASE, 0f to .9f, .04f to 1f, .68f to 1f, .74f to 1.12f, 1f to 1.12f),
        pvh(View.SCALE_Y, CraftMovement.EASE, 0f to .9f, .04f to 1f, .68f to 1f, .74f to 1.12f, 1f to 1.12f),
    )

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
        const val CYCLE_MS = 6400L
    }
}
