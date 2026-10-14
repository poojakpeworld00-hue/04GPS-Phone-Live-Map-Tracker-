package com.livemap.homedeck.contract

import android.app.Activity
import android.widget.FrameLayout

interface HomeSpots {
    fun isReady(): Boolean = false

    fun bindNative(activity: Activity?, key: String, container: FrameLayout?) {}

    fun wireNativeOnOpen(activity: Activity?, key: String, container: FrameLayout?) =
        bindNative(activity, key, container)

    fun showInterstitial(activity: Activity, tag: String, onDone: () -> Unit) = onDone()

    fun showFullNative(activity: Activity, tag: String, onDone: () -> Unit) =
        showInterstitial(activity, tag, onDone)

    fun openSponsored(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            )
        }
    }

    object NoOp : HomeSpots

    companion object {
        const val SLOT_PANEL_BOTTOM = "apps_panel"

        const val SLOT_PANEL_MID = "suggested_recent_panel"

        const val SLOT_DRAWER_TOP = "app_drawer"
    }
}
