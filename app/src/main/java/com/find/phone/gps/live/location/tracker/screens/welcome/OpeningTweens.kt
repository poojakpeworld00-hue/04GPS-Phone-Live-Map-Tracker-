package com.find.phone.gps.live.location.tracker.screens.welcome

import android.animation.Animator
import android.animation.ValueAnimator
import android.view.View
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement

object OpeningTweens {
    fun attach(root: View): List<Animator> {
        if (CraftMovement.animationsDisabled(root.context)) return emptyList()
        val out = ArrayList<Animator>(8)

        root.findViewById<View?>(R.id.indPhoneMock)?.let { out += CraftMovement.floaty(it) }
        root.findViewById<View?>(R.id.indChipDial)?.let { out += CraftMovement.floaty(it, delayMs = 800L) }
        root.findViewById<View?>(R.id.indChipGps)?.let { out += CraftMovement.floaty(it, delayMs = 1600L) }
        root.findViewById<View?>(R.id.indSearchTile)?.let { out += CraftMovement.floaty(it, delayMs = 2400L) }

        root.findViewById<View?>(R.id.indDetailCard)?.let { out += CraftMovement.fadeIn(it, durationMs = 800L) }

        root.findViewById<View?>(R.id.indCursor)?.let { out += CraftMovement.blink(it) }
        root.findViewById<View?>(R.id.indGlow)?.let { out += CraftMovement.glow(it, durationMs = 2000L) }

        val srLoad = root.findViewById<View?>(R.id.indSrLoad)
        val srRes = root.findViewById<View?>(R.id.indSrRes)
        if (srLoad != null && srRes != null) out += crossFade(srLoad, srRes)

        root.findViewById<View?>(R.id.indOrbit)?.let { out += CraftMovement.spin(it, ORBIT_MS) }
        listOf(R.id.indTile1, R.id.indTile2, R.id.indTile3, R.id.indTile4).forEach { id ->
            root.findViewById<View?>(id)?.let { out += CraftMovement.spin(it, ORBIT_MS, reverse = true) }
        }
        return out
    }

    private const val ORBIT_MS = 18_000L

    private fun crossFade(load: View, res: View): Animator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = CraftMovement.LINEAR
            addUpdateListener {
                val t = it.animatedValue as Float

                load.alpha = when {
                    t < 0.52f -> 1f
                    t < 0.58f -> 1f - (t - 0.52f) / 0.06f
                    t < 0.94f -> 0f
                    else -> (t - 0.94f) / 0.06f
                }

                res.alpha = when {
                    t < 0.52f -> 0f
                    t < 0.60f -> (t - 0.52f) / 0.08f
                    t < 0.92f -> 1f
                    t < 0.98f -> 1f - (t - 0.92f) / 0.06f
                    else -> 0f
                }
            }
            start()
        }
}
