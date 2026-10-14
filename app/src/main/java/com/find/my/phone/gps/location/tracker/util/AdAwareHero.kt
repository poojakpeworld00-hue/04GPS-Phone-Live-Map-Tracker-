package com.find.my.phone.gps.location.tracker.util

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.isVisible

/**
 * Hides this hero illustration whenever [adContainer] is on screen, and brings it back when the
 * slot collapses (ads off, no fill, slot disabled in Remote Config).
 *
 * The first-run steps carry a native ad under the CTA; on small phones the ad and the hero card
 * do not both fit, and the ad is the one that has to stay. The renderers hide the container
 * outright when there is nothing to show, so its visibility is the whole signal.
 *
 * [compact] is the small icon that stands in for the hero while the ad is up (the reference
 * app's 58dp tile), so the screen keeps an identity at the top; it goes away with the ad.
 */
fun View.hideWhileAdVisible(adContainer: ViewGroup, compact: View? = null) {
    fun sync() {
        val adShowing = adContainer.isVisible && adContainer.childCount > 0
        if (isVisible == adShowing) isVisible = !adShowing
        if (compact != null && compact.isVisible != adShowing) compact.isVisible = adShowing
    }
    sync()
    adContainer.viewTreeObserver.addOnGlobalLayoutListener(
        object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!adContainer.isAttachedToWindow) {
                    adContainer.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
                    return
                }
                sync()
            }
        }
    )
}
