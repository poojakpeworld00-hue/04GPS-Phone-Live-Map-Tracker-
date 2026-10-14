package com.find.phone.gps.live.location.tracker.helpers

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.isVisible

fun View.concealWhileAdVisible(adContainer: ViewGroup, compact: View? = null) {
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
