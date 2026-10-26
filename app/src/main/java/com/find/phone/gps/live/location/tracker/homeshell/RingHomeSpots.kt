package com.find.phone.gps.live.location.tracker.homeshell

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import com.find.phone.gps.live.location.tracker.monetize.logic.DisplayTeaserProfile
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.monetize.render.StraightHrefOpener
import com.find.phone.gps.live.location.tracker.monetize.render.TeaserSiteActivity
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.render.EmbeddedTeaser
import com.find.phone.gps.live.location.tracker.NavTracerCore
import com.google.android.gms.ads.nativead.NativeAdView
import com.livemap.homedeck.contract.HomeSpots
import com.livemap.homedeck.contract.HomeBadges

class RingHomeSpots : HomeSpots {
    override fun isReady(): Boolean =
        SpotsLocker.getInstance(NavTracerCore.appContext).getBoolean("IsAdsON")

    override fun bindNative(activity: Activity?, key: String, container: FrameLayout?) {
        if (activity == null || container == null) return
        if (activity.isFinishing || activity.isDestroyed) return

        val slot = when (key) {
            HomeSpots.SLOT_PANEL_BOTTOM -> HomeSpotsProfile.leftPanelBottomSlot(activity)
            HomeSpots.SLOT_PANEL_MID -> HomeSpotsProfile.leftPanelMidSlot(activity)
            HomeSpots.SLOT_DRAWER_TOP -> HomeSpotsProfile.appDrawerSlot(activity)
            else -> return
        }

        if (!DisplayTeaserProfile.resolve(activity, screenAdsKey(key)).show) {
            container.removeAllViews()
            container.visibility = View.GONE
            return
        }

        val emptyFrame = container.childCount == 0
        if (slot.needsNativePreload && emptyFrame && !EmbeddedTeaser.hasPreloadedNative()) {
            EmbeddedTeaser().fetchNativeADs(activity, object : EmbeddedTeaser.NativeAdObserver {
                override fun onNativeAdLoaded() {
                    if (activity.isFinishing || activity.isDestroyed) return
                    paint(activity, slot, container, key)
                }

                override fun onNativeAdFailed() = Unit
            })
            return
        }

        paint(activity, slot, container, key)
    }

    private fun paint(activity: Activity, slot: HomeSpotsProfile.Slot, container: FrameLayout, key: String) {
        HomeSpotsProfile.renewSlot(activity, slot, container)

        ViewCompat.requestApplyInsets(container)
        if (key == HomeSpots.SLOT_DRAWER_TOP) shapeAdIcon(container)
    }

    private fun shapeAdIcon(container: FrameLayout) {
        val apply = {
            (container.locateNativeAdView()?.iconView as? ImageView)?.let(HomeBadges::enactTo)
        }
        runCatching(apply)
        container.post { runCatching(apply) }
    }

    private fun View.locateNativeAdView(): NativeAdView? {
        if (this is NativeAdView) return this
        if (this !is ViewGroup) return null
        for (i in 0 until childCount) getChildAt(i).locateNativeAdView()?.let { return it }
        return null
    }

    override fun showInterstitial(activity: Activity, tag: String, onDone: () -> Unit) {
        val surface = when (tag) {
            LEFT_SWIPE_TAG -> HomeSpotsProfile.HomePlane.LEFT_PANEL
            RIGHT_SWIPE_TAG -> HomeSpotsProfile.HomePlane.RIGHT_PANEL
            HOME_BACK_TAG -> HomeSpotsProfile.HomePlane.HOME_BACK
            HOME_PRESS_TAG -> HomeSpotsProfile.HomePlane.HOME_PRESS
            else -> return onDone()
        }
        HomeSpotsProfile.execSurface(activity, surface, onDone)
    }

    override fun showFullNative(activity: Activity, tag: String, onDone: () -> Unit) {
        val surface = when (tag) {
            APP_LAUNCH_TAG -> HomeSpotsProfile.HomePlane.APP_CLICK
            HOME_LAUNCH_TAG -> HomeSpotsProfile.clickSurface(activity, HomeSpotsProfile.HomePlane.HOME_CLICK)
            DRAWER_LAUNCH_TAG -> HomeSpotsProfile.clickSurface(activity, HomeSpotsProfile.HomePlane.DRAWER_CLICK)
            else -> return onDone()
        }
        HomeSpotsProfile.execSurface(activity, surface, onDone)
    }

    override fun openSponsored(activity: Activity, url: String) {
        val mode = StraightHrefOpener.modeOf(SpotsLocker.getInstance(activity).getString("link_open_in"))
            ?: StraightHrefOpener.Mode.WEBVIEW
        activity.recordKeyEvent("game_icon_click")
        HomeSpotsProfile.execSurface(activity, HomeSpotsProfile.HomePlane.GAME_CLICK) {
            if (mode == StraightHrefOpener.Mode.WEBVIEW && !StraightHrefOpener.isStoreLink(url)) {
                TeaserSiteActivity.openGame(activity, url)
            } else {
                StraightHrefOpener.open(activity, url, mode)
            }
        }
    }

    private companion object {
        const val APP_LAUNCH_TAG = "appLaunch"
        const val HOME_LAUNCH_TAG = "homeLaunch"
        const val DRAWER_LAUNCH_TAG = "drawerLaunch"
        const val HOME_BACK_TAG = "homeBack"
        const val HOME_PRESS_TAG = "homePress"
        const val LEFT_SWIPE_TAG = "leftSwipe"
        const val RIGHT_SWIPE_TAG = "rightSwipe"
    }

    private fun screenAdsKey(key: String): String = when (key) {
        HomeSpots.SLOT_PANEL_BOTTOM -> "LauncherAppsPanel"
        HomeSpots.SLOT_PANEL_MID -> "LauncherAppsPanelMid"
        HomeSpots.SLOT_DRAWER_TOP -> "LauncherAppDrawer"
        else -> key
    }
}
