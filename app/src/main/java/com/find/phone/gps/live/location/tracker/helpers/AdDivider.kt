package com.find.phone.gps.live.location.tracker.helpers

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.isVisible

fun View.followAdContainer(container: ViewGroup) {
    isVisible = false

    container.viewTreeObserver.addOnGlobalLayoutListener(
        object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!container.isAttachedToWindow) {
                    container.viewTreeObserver
                        .takeIf { it.isAlive }
                        ?.removeOnGlobalLayoutListener(this)
                    return
                }
                isVisible = container.isVisible &&
                        container.childCount > 0 &&
                        container.height > 0
            }
        }
    )
}
