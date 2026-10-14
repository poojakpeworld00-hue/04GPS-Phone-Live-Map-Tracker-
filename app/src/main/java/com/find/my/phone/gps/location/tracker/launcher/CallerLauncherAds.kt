package com.find.my.phone.gps.location.tracker.launcher

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import com.find.my.phone.gps.location.tracker.adcast.domain.ScreenPromoConfig
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.adcast.domain.LauncherAdsConfig
import com.find.my.phone.gps.location.tracker.adcast.presentation.DirectLinkOpener
import com.find.my.phone.gps.location.tracker.adcast.presentation.NativePromo
import com.find.my.phone.gps.location.tracker.GpsTrackerApp
import com.google.android.gms.ads.nativead.NativeAdView
import io.launcher.home.api.LauncherAds
import io.launcher.home.api.LauncherIcons

/**
 * The launcher module's ad surface, backed by this app's own ad layer (admesh).
 *
 * Everything comes from this app's `launcher_ads`, one block per moment (see
 * [LauncherAdsConfig.LauncherSurface]): the gesture ads run `app_click` / `left_panel.open_ad` /
 * `right_panel.open_ad` with their own sequence and counter, and the inline slots are:
 *
 *  - [LauncherAds.SLOT_PANEL_BOTTOM] ← `left_panel.native_bottom`
 *  - [LauncherAds.SLOT_PANEL_MID]    ← `left_panel.native_mid`
 *  - [LauncherAds.SLOT_DRAWER_TOP]   ← `app_drawer.bottom_native`
 *
 * On top of that, each slot answers to its `ScreenAds.<name>.show` switch like every app screen.
 */
class CallerLauncherAds : LauncherAds {

    override fun isReady(): Boolean =
        AdsVault.getInstance(GpsTrackerApp.appContext).getBoolean("IsAdsON")

    override fun bindNative(activity: Activity?, key: String, container: FrameLayout?) {
        if (activity == null || container == null) return
        if (activity.isFinishing || activity.isDestroyed) return

        val slot = when (key) {
            LauncherAds.SLOT_PANEL_BOTTOM -> LauncherAdsConfig.leftPanelBottomSlot(activity)
            LauncherAds.SLOT_PANEL_MID -> LauncherAdsConfig.leftPanelMidSlot(activity)
            LauncherAds.SLOT_DRAWER_TOP -> LauncherAdsConfig.appDrawerSlot(activity)
            else -> return
        }

        if (!ScreenPromoConfig.resolve(activity, screenAdsKey(key)).show) {
            container.removeAllViews()
            container.visibility = View.GONE
            return
        }

        // The launcher never goes through this app's Activities, so the native preload those do
        // has not run — on a device booted straight into the home screen the cache is empty, and
        // an empty frame asked to render with nothing cached stays blank for good. Request one and
        // paint when it lands; the second pass has a cached ad and cannot come back here.
        val emptyFrame = container.childCount == 0
        if (slot.needsNativePreload && emptyFrame && !NativePromo.hasPreloadedNative()) {
            NativePromo().loadNativeADs(activity, object : NativePromo.NativeAdObserver {
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

    private fun paint(activity: Activity, slot: LauncherAdsConfig.Slot, container: FrameLayout, key: String) {
        // refreshSlot keeps an ad already on screen rather than swapping it for a blank when the
        // shared native cache is momentarily empty.
        LauncherAdsConfig.refreshSlot(activity, slot, container)
        // A slot that was GONE at the last inset dispatch has no nav-bar padding yet.
        ViewCompat.requestApplyInsets(container)
        if (key == LauncherAds.SLOT_DRAWER_TOP) shapeAdIcon(container)
    }

    /**
     * The drawer slot sits among app icons, and a native ad's icon arrives square: give it the same
     * device shape as the tiles around it. Once now and once after layout, since the ad view may be
     * bound a frame later.
     */
    private fun shapeAdIcon(container: FrameLayout) {
        val apply = {
            (container.findNativeAdView()?.iconView as? ImageView)?.let(LauncherIcons::applyTo)
        }
        runCatching(apply)
        container.post { runCatching(apply) }
    }

    private fun View.findNativeAdView(): NativeAdView? {
        if (this is NativeAdView) return this
        if (this !is ViewGroup) return null
        for (i in 0 until childCount) getChildAt(i).findNativeAdView()?.let { return it }
        return null
    }

    /**
     * A panel is opening (`left_panel.open_ad` / `right_panel.open_ad`) or Back was pressed on the
     * home screen (`home_back`): that block's own sequence and counter. [onDone] (the panel opening,
     * or nothing for Back) runs exactly once whatever happens.
     */
    override fun showInterstitial(activity: Activity, tag: String, onDone: () -> Unit) {
        val surface = when (tag) {
            LEFT_SWIPE_TAG -> LauncherAdsConfig.LauncherSurface.LEFT_PANEL
            RIGHT_SWIPE_TAG -> LauncherAdsConfig.LauncherSurface.RIGHT_PANEL
            HOME_BACK_TAG -> LauncherAdsConfig.LauncherSurface.HOME_BACK
            HOME_PRESS_TAG -> LauncherAdsConfig.LauncherSurface.HOME_PRESS
            else -> return onDone()
        }
        LauncherAdsConfig.runSurface(activity, surface, onDone)
    }

    /**
     * An app is being opened: `home_click` from the home screen, `drawer_click` from the drawer
     * (each falling back to `app_click` when Remote Config has no block for it), and `app_click`
     * from anywhere else (the apps panel and its search). Then the app.
     */
    override fun showFullNative(activity: Activity, tag: String, onDone: () -> Unit) {
        val surface = when (tag) {
            APP_LAUNCH_TAG -> LauncherAdsConfig.LauncherSurface.APP_CLICK
            HOME_LAUNCH_TAG -> LauncherAdsConfig.clickSurface(activity, LauncherAdsConfig.LauncherSurface.HOME_CLICK)
            DRAWER_LAUNCH_TAG -> LauncherAdsConfig.clickSurface(activity, LauncherAdsConfig.LauncherSurface.DRAWER_CLICK)
            else -> return onDone()
        }
        LauncherAdsConfig.runSurface(activity, surface, onDone)
    }

    /** Sponsored drawer tiles open through the same opener as every direct link (`link_open_in`, default WebView). */
    override fun openSponsored(activity: Activity, url: String) {
        val mode = DirectLinkOpener.modeOf(AdsVault.getInstance(activity).getString("link_open_in"))
            ?: DirectLinkOpener.Mode.WEBVIEW
        DirectLinkOpener.open(activity, url, mode)
    }

    /**
     * The `ScreenAds` entry that switches [key], named for where the slot actually is. A key the
     * launcher adds later keeps its own name, so it is switchable the moment it exists.
     */
    private companion object {
        /** The module's gesture tags (`io.launcher.home.promo.LauncherAdsConfig`). */
        const val APP_LAUNCH_TAG = "appLaunch"
        const val HOME_LAUNCH_TAG = "homeLaunch"
        const val DRAWER_LAUNCH_TAG = "drawerLaunch"
        const val HOME_BACK_TAG = "homeBack"
        const val HOME_PRESS_TAG = "homePress"
        const val LEFT_SWIPE_TAG = "leftSwipe"
        const val RIGHT_SWIPE_TAG = "rightSwipe"
    }

    private fun screenAdsKey(key: String): String = when (key) {
        LauncherAds.SLOT_PANEL_BOTTOM -> "LauncherAppsPanel"
        LauncherAds.SLOT_PANEL_MID -> "LauncherAppsPanelMid"
        LauncherAds.SLOT_DRAWER_TOP -> "LauncherAppDrawer"
        else -> key
    }
}
