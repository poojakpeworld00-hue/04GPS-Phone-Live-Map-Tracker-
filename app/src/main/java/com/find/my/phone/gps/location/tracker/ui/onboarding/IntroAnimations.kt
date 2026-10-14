package com.find.my.phone.gps.location.tracker.ui.onboarding

import android.animation.Animator
import android.animation.ValueAnimator
import android.view.View
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion

/**
 * Wires an inflated onboarding scene to the design's element loops by view id and returns
 * the started animators so the adapter can cancel them on recycle:
 *
 *  - A1: phone mock `floaty 4s`, chips at +.8s / +1.6s, search tile at +2.4s
 *  - A2: details card `fadein .8s ease both`
 *  - A3: cursor `blink 1s step-end`, search tile `glow 2s`
 *  - A4: orbit `spin 18s linear`, each tile `spinr 18s` so its icon stays upright
 *
 * Ping rings, the route draw and the typing number animate themselves on attach.
 */
object IntroAnimations {

    fun attach(root: View): List<Animator> {
        if (DesignMotion.animationsDisabled(root.context)) return emptyList()
        val out = ArrayList<Animator>(8)

        root.findViewById<View?>(R.id.obPhoneMock)?.let { out += DesignMotion.floaty(it) }
        root.findViewById<View?>(R.id.obChipDial)?.let { out += DesignMotion.floaty(it, delayMs = 800L) }
        root.findViewById<View?>(R.id.obChipGps)?.let { out += DesignMotion.floaty(it, delayMs = 1600L) }
        root.findViewById<View?>(R.id.obSearchTile)?.let { out += DesignMotion.floaty(it, delayMs = 2400L) }

        root.findViewById<View?>(R.id.obDetailCard)?.let { out += DesignMotion.fadeIn(it, durationMs = 800L) }

        root.findViewById<View?>(R.id.obCursor)?.let { out += DesignMotion.blink(it) }
        root.findViewById<View?>(R.id.obGlow)?.let { out += DesignMotion.glow(it, durationMs = 2000L) }

        // A3: the result area cross-fades from the "Searching…" shimmer to the result card
        // (the design's `srload` / `srres` on one 4 s loop).
        val srLoad = root.findViewById<View?>(R.id.obSrLoad)
        val srRes = root.findViewById<View?>(R.id.obSrRes)
        if (srLoad != null && srRes != null) out += crossFade(srLoad, srRes)

        root.findViewById<View?>(R.id.obOrbit)?.let { out += DesignMotion.spin(it, ORBIT_MS) }
        listOf(R.id.obTile1, R.id.obTile2, R.id.obTile3, R.id.obTile4).forEach { id ->
            root.findViewById<View?>(id)?.let { out += DesignMotion.spin(it, ORBIT_MS, reverse = true) }
        }
        return out
    }

    private const val ORBIT_MS = 18_000L

    /**
     * `srload` / `srres`: on a 4 s loop the shimmer holds, fades out around 55%, the result card
     * fades in and holds, then both reset. Drives alpha on both views from one animator.
     */
    private fun crossFade(load: View, res: View): Animator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = DesignMotion.LINEAR
            addUpdateListener {
                val t = it.animatedValue as Float
                // shimmer: 1 until .52, →0 by .58, 0 until .94, →1 by 1.0
                load.alpha = when {
                    t < 0.52f -> 1f
                    t < 0.58f -> 1f - (t - 0.52f) / 0.06f
                    t < 0.94f -> 0f
                    else -> (t - 0.94f) / 0.06f
                }
                // result: 0 until .52, →1 by .60, 1 until .92, →0 by .98
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
