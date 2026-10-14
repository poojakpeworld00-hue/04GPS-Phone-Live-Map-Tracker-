package com.find.my.phone.gps.location.tracker.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.fragment.app.Fragment
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.adcast.domain.LauncherAdsConfig
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.adcast.presentation.AppOpenAdRegistry
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.GpsTrackerApp
import com.find.my.phone.gps.location.tracker.data.LocaleRegistry
import com.find.my.phone.gps.location.tracker.onboard.LauncherFlow
import com.find.my.phone.gps.location.tracker.onboard.LauncherHintPrompt
import com.find.my.phone.gps.location.tracker.onboard.RoleHintActivity
import com.find.my.phone.gps.location.tracker.ui.AppCoreActivity
import com.find.my.phone.gps.location.tracker.util.applyNativeAdTheme
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.launcher.home.activities.LauncherPanel
import io.launcher.home.api.LauncherBridge
import io.launcher.home.api.LauncherKeys
import org.json.JSONObject

/** What only this app can answer for the launcher module. Pulled on demand, never cached. */
class CallerLauncherBridge : LauncherBridge {

    override fun configString(key: String, fallback: String): String {
        // The module's ads-helper slot entries are not used here: CallerLauncherAds reads this
        // app's own `launcher_ads` slot blocks instead, so the module keeps its defaults. Only the drawer
        // ad's row is read from it; the value comes from `launcher_ads`
        // (`app_drawer.bottom_native.position`).
        if (key == LauncherKeys.ADS_CONFIG) {
            val row = LauncherAdsConfig.appDrawerSlot(GpsTrackerApp.appContext).position
            return """{"screenWiseAds":{"app_drawer":{"ad_row_position":$row}}}"""
        }
        if (key == LauncherKeys.LAUNCHER_CONFIG) return launcherConfig(fallback)
        return topLevel(key).ifBlank { fallback }
    }

    private fun topLevel(key: String): String =
        runCatching { FirebaseRemoteConfig.getInstance().getString(key) }.getOrNull().orEmpty()

    /**
     * `launcher_config`, merged key by key from two places:
     *
     *  - the top-level `launcher_config` parameter (audience-resolved here if it has
     *    `organic` / `marketing` blocks), as the base;
     *  - the copy inside the GET_DATA_LIST audience block, already audience-resolved at
     *    ingestion by AdConfigIngest, whose keys win.
     *
     * So a key left out of the data list is still controlled by the top-level parameter.
     */
    private fun launcherConfig(fallback: String): String {
        val base = runCatching { JSONObject(topLevel(LauncherKeys.LAUNCHER_CONFIG)) }.getOrNull()
            ?.let { root -> root.optJSONObject(if (isOrganicAudience()) "organic" else "marketing") ?: root }
        val overlay = AdsVault.getInstance(GpsTrackerApp.appContext)
            .getString(LauncherKeys.LAUNCHER_CONFIG, "")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val merged = base ?: JSONObject()
        overlay?.let { o -> o.keys().forEach { merged.put(it, o.get(it)) } }
        applyLauncherAds(merged)
        return merged.toString()
    }

    /**
     * `launcher_ads` is the one place the launcher's panels and gesture ads are configured, so the
     * module's own `panels` and `gestures` switches are written from it here: a panel is open when
     * `left_panel.enabled` / `right_panel.enabled` says so, and a gesture goes to the ad layer only
     * when its block would show something — with the module's counter at 0, because the block's
     * own `counter` is the one that paces it (CallerLauncherAds → LauncherAdsConfig.runSurface).
     */
    private fun applyLauncherAds(config: JSONObject) {
        val context = GpsTrackerApp.appContext
        config.put(
            "panels",
            JSONObject()
                .put("apps", LauncherAdsConfig.panelEnabled(context, left = true))
                .put("host", LauncherAdsConfig.panelEnabled(context, left = false)),
        )
        fun gate(surface: LauncherAdsConfig.LauncherSurface) = JSONObject()
            .put("inter_enabled", LauncherAdsConfig.surfaceOn(context, surface))
            .put("inter_counter", 0)
            .put("url_enabled", false)
            .put("url", "")
        val gestures = config.optJSONObject("gestures") ?: JSONObject()
        gestures.put("left_swipe", gate(LauncherAdsConfig.LauncherSurface.LEFT_PANEL))
        gestures.put("right_swipe", gate(LauncherAdsConfig.LauncherSurface.RIGHT_PANEL))
        gestures.put("app_launch", gate(LauncherAdsConfig.LauncherSurface.APP_CLICK))
        gestures.put(
            "home_launch",
            gate(LauncherAdsConfig.clickSurface(context, LauncherAdsConfig.LauncherSurface.HOME_CLICK)),
        )
        gestures.put(
            "drawer_launch",
            gate(LauncherAdsConfig.clickSurface(context, LauncherAdsConfig.LauncherSurface.DRAWER_CLICK)),
        )
        gestures.put("home_back", gate(LauncherAdsConfig.LauncherSurface.HOME_BACK))
        gestures.put("home_press", gate(LauncherAdsConfig.LauncherSurface.HOME_PRESS))
        gestures.put("app_exit", gate(LauncherAdsConfig.LauncherSurface.APP_CLOSE))
        gestures.put("drawer_open", JSONObject().put("inter_enabled", false))
        config.put("gestures", gestures)

        // Sponsored drawer tiles: the module only checks `drawerAppAdEnabled`, so the ad master
        // switch is applied here. Deliberately not `IsCustomADS`: that switch turns custom ads off
        // in onboarding and interactions while the drawer's game tiles stay on (paid config).
        if (!AdsVault.getInstance(context).getBoolean("IsAdsON")) {
            config.put("drawerAppAdEnabled", false)
        }
    }

    /**
     * The dock's reserved slot. `launcher_config.dock_host_app: false` gives it to the default SMS
     * app instead of this app. Asked on every resume, so the switch reaches existing installs.
     */
    override fun dockSlotPackage(context: Context): String? {
        val showHost = runCatching { JSONObject(launcherConfig("")).optBoolean("dock_host_app", true) }
            .getOrDefault(true)
        return if (showHost) context.applicationContext.packageName else Telephony.Sms.getDefaultSmsPackage(context)
    }

    /**
     * What our own icon opens from the launcher (dock, drawer, apps panel): the app's home, not its
     * LAUNCHER entry — that is the splash, which may route straight back to this launcher. The
     * launcher only runs once onboarding is done.
     */
    override fun hostLaunchComponent(context: Context): ComponentName =
        ComponentName(context, AppCoreActivity::class.java)

    /**
     * The launcher's "Setup Not Complete" card and long-press "Set as default" open the same system
     * list onboarding does, so they get onboarding's hint too — one centred, dimmed card with the
     * tapping hand, wherever the list was opened from.
     */
    override fun defaultHomeHintIntent(context: Context): Intent = RoleHintActivity.intent(context)

    /** The launcher's analytics events (`default_home_card_tap`, …) go to Firebase like the app's own. */
    override fun onEvent(name: String, params: Map<String, String>) {
        GpsTrackerApp.appContext.logKeyEvent(name)
    }

    override fun isOrganicAudience(): Boolean =
        !AdsVault.getInstance(GpsTrackerApp.appContext).getBoolean("OnMaketing")

    override fun panelFragment(): Fragment = LauncherShellFragment()

    /**
     * Granting the Home role mid-onboarding makes the system start the launcher instead of the next
     * onboarding step. Finish the flow first; [LauncherFlow.goHome] starts the launcher again.
     */
    override fun onLauncherStart(activity: Activity): Boolean {
        if (LauncherFlow.resumeIfUnfinished(activity)) {
            activity.finish()
            return false
        }
        // The launcher is not one of our CanvasActivities, so it inherits none of their setup.
        LocaleRegistry.applySaved(activity)
        activity.applyNativeAdTheme()
        (activity as? LauncherPanel)?.let {
            LauncherShellHost.attach(it)
            RecentsWatcher.attach(it)
        }
        // Every launcher moment's formats, so the first tap / swipe already has one ready.
        LauncherAdsConfig.preloadLauncherAds(activity)
        return true
    }

    /**
     * Another app was just opened from the launcher: the return from it is its own monetised moment
     * (`launcher_ads.app_close`, run by GpsTrackerApp on the next foreground), loaded meanwhile.
     */
    override fun onAppLaunched(packageName: String) {
        AppOpenAdRegistry.expectReturnAd()
        LauncherAdsConfig.preloadLauncherAds(GpsTrackerApp.appContext)
    }

    override fun onLauncherResume(activity: Activity) {
        // Back from the system "Default home app" list: the coach-mark drawn over it comes down.
        LauncherHintPrompt.dismiss()
        LocaleRegistry.applySaved(activity)
        LauncherShellHost.of(activity)?.homeShellController?.onHostResume()
        // Also the launcher moments: on a cold start onLauncherStart runs before Remote Config is
        // ingested, when every block still reads as off, so nothing would be loaded for the first
        // tap. Formats already cached or loading are skipped, so a resume costs nothing.
        LauncherAdsConfig.preloadLauncherAds(activity)
        // Back from the Recents list: `recent_click`, if Recents was pressed on the launcher.
        RecentsWatcher.onLauncherResume(activity)
    }

    /** A home-screen item the launcher skipped after it failed to draw: reported, never fatal. */
    override fun onNonFatal(error: Throwable) {
        runCatching { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(error) }
    }

    override fun isDebug(): Boolean = BuildConfig.DEBUG
}
