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
        popIn(binding.bootLogo, 150L)
        popIn(binding.orbitAtoms, 500L)
        rise(binding.txtAppName, 600L)
        rise(binding.txtTagline, 750L)
        animators += status(binding.condSignal, 0f, .33f)
        animators += status(binding.condPin, .33f, .66f)
        animators += status(binding.condLogo, .66f, 1f)
        bar()
    }

    fun cancel() = CraftMovement.abortAll(animators)

    private fun presentFinalFrame() {
        listOf(binding.condSignal, binding.condPin).forEach { it.alpha = 0f }
        binding.condLogo.alpha = 1f
        binding.progTrack.post { setFillFraction(1f) }
    }

    private fun popIn(v: View, delayMs: Long) {
        v.alpha = 0f
        v.scaleX = .4f
        v.scaleY = .4f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delayMs).setDuration(700L)
            .setInterpolator(CraftMovement.DROP).start()
    }

    private fun rise(v: View, delayMs: Long) {
        v.alpha = 0f
        v.translationY = context.dp(14f)
        v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(500L)
            .setInterpolator(CraftMovement.EASE_OUT).start()
    }

    // one status line visible per third of the cycle
    private fun status(v: View, from: Float, to: Float) = loop(
        v, CYCLE_MS, 0L,
        pvh(
            View.ALPHA, CraftMovement.EASE,
            0f to 0f, from to 0f, (from + .04f) to 1f, (to - .04f).coerceAtLeast(from + .05f) to 1f, to to 0f, 1f to 0f,
        ),
    )

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
