package com.find.phone.gps.live.location.tracker.homeshell

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.fragment.app.Fragment
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.render.CoreOpenSpotBook
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.NavTracerCore
import com.find.phone.gps.live.location.tracker.store.LingoBook
import com.find.phone.gps.live.location.tracker.induction.HomeStream
import com.find.phone.gps.live.location.tracker.induction.HomeClueCue
import com.find.phone.gps.live.location.tracker.induction.PartClueActivity
import com.find.phone.gps.live.location.tracker.screens.CoreHubActivity
import com.find.phone.gps.live.location.tracker.helpers.enactNativeAdTheme
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.livemap.homedeck.screens.HomeDeck
import com.livemap.homedeck.contract.HomeGateway
import com.livemap.homedeck.contract.HomeTokens
import org.json.JSONObject

class RingHomeGateway : HomeGateway {
    override fun configString(key: String, fallback: String): String {
        if (key == HomeTokens.ADS_CONFIG) {
            val row = HomeSpotsProfile.appDrawerSlot(NavTracerCore.appContext).position
            return """{"screenWiseAds":{"app_drawer":{"ad_row_position":$row}}}"""
        }
        if (key == HomeTokens.LAUNCHER_CONFIG) return launcherConfig(fallback)
        return topLevel(key).ifBlank { fallback }
    }

    private fun topLevel(key: String): String =
        runCatching { FirebaseRemoteConfig.getInstance().getString(key) }.getOrNull().orEmpty()

    private fun launcherConfig(fallback: String): String {
        val base = runCatching { JSONObject(topLevel(HomeTokens.LAUNCHER_CONFIG)) }.getOrNull()
            ?.let { root -> root.optJSONObject(if (isOrganicAudience()) "organic" else "marketing") ?: root }
        val overlay = SpotsLocker.getInstance(NavTracerCore.appContext)
            .getString(HomeTokens.LAUNCHER_CONFIG, "")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val merged = base ?: JSONObject()
        overlay?.let { o -> o.keys().forEach { merged.put(it, o.get(it)) } }
        enactLauncherAds(merged)
        return merged.toString()
    }

    private fun enactLauncherAds(config: JSONObject) {
        val context = NavTracerCore.appContext
        config.put(
            "panels",
            JSONObject()
                .put("apps", HomeSpotsProfile.panelEnabled(context, left = true))
                .put("host", HomeSpotsProfile.panelEnabled(context, left = false)),
        )
        fun gate(surface: HomeSpotsProfile.HomePlane) = JSONObject()
            .put("inter_enabled", HomeSpotsProfile.surfaceOn(context, surface))
            .put("inter_counter", 0)
            .put("url_enabled", false)
            .put("url", "")
        val gestures = config.optJSONObject("gestures") ?: JSONObject()
        gestures.put("left_swipe", gate(HomeSpotsProfile.HomePlane.LEFT_PANEL))
        gestures.put("right_swipe", gate(HomeSpotsProfile.HomePlane.RIGHT_PANEL))
        gestures.put("app_launch", gate(HomeSpotsProfile.HomePlane.APP_CLICK))
        gestures.put(
            "home_launch",
            gate(HomeSpotsProfile.clickSurface(context, HomeSpotsProfile.HomePlane.HOME_CLICK)),
        )
        gestures.put(
            "drawer_launch",
            gate(HomeSpotsProfile.clickSurface(context, HomeSpotsProfile.HomePlane.DRAWER_CLICK)),
        )
        gestures.put("home_back", gate(HomeSpotsProfile.HomePlane.HOME_BACK))
        gestures.put("home_press", gate(HomeSpotsProfile.HomePlane.HOME_PRESS))
        gestures.put("app_exit", gate(HomeSpotsProfile.HomePlane.APP_CLOSE))
        gestures.put("drawer_open", JSONObject().put("inter_enabled", false))
        config.put("gestures", gestures)

        if (!SpotsLocker.getInstance(context).getBoolean("IsAdsON")) {
            config.put("drawerAppAdEnabled", false)
        }
    }

    override fun dockSlotPackage(context: Context): String? {
        val showHost = runCatching { JSONObject(launcherConfig("")).optBoolean("dock_host_app", true) }
            .getOrDefault(true)
        return if (showHost) context.applicationContext.packageName else Telephony.Sms.getDefaultSmsPackage(context)
    }

    override fun hostLaunchComponent(context: Context): ComponentName =
        ComponentName(context, CoreHubActivity::class.java)

    override fun defaultHomeHintIntent(context: Context): Intent = PartClueActivity.intent(context)

    override fun onEvent(name: String, params: Map<String, String>) {
        NavTracerCore.appContext.recordKeyEvent(name)
    }

    override fun localizeContext(base: Context): Context = LingoBook.localized(base)

    override fun isOrganicAudience(): Boolean =
        !SpotsLocker.getInstance(NavTracerCore.appContext).getBoolean("OnMaketing")

    override fun panelFragment(): Fragment = HomeCasingFragment()

    override fun onLauncherStart(activity: Activity): Boolean {
        if (HomeStream.resumeIfUnfinished(activity)) {
            activity.finish()
            return false
        }

        LingoBook.enactSaved(activity)
        activity.enactNativeAdTheme()
        (activity as? HomeDeck)?.let {
            HomeCasingHolder.attach(it)
            SwitcherSentinel.attach(it)
        }

        HomeSpotsProfile.prefetchLauncherAds(activity)
        return true
    }

    override fun onAppLaunched(packageName: String) {
        CoreOpenSpotBook.expectReturnAd()
        HomeSpotsProfile.prefetchLauncherAds(NavTracerCore.appContext)
    }

    override fun onLauncherResume(activity: Activity) {
        HomeClueCue.dismiss()
        LingoBook.enactSaved(activity)
        HomeCasingHolder.of(activity)?.homeShellController?.onHostResume()

        HomeSpotsProfile.prefetchLauncherAds(activity)

        SwitcherSentinel.onLauncherResume(activity)
    }

    override fun onNonFatal(error: Throwable) {
        runCatching { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(error) }
    }

    override fun isDebug(): Boolean = BuildConfig.DEBUG
}
