package com.find.my.phone.gps.location.tracker

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.ViewTreeObserver
import android.webkit.WebSettings
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.find.my.phone.gps.location.tracker.ui.reminder.ReminderAlertActivity
import com.google.firebase.FirebaseApp
import com.find.my.phone.gps.location.tracker.adcast.data.AdKind
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.adcast.domain.LauncherAdsConfig
import com.find.my.phone.gps.location.tracker.adcast.domain.LauncherPlacementAds
import com.find.my.phone.gps.location.tracker.adcast.domain.LiveConfigWatcher
import com.find.my.phone.gps.location.tracker.adcast.presentation.AdBeaconActivity
import com.find.my.phone.gps.location.tracker.adcast.presentation.AppOpenAdRegistry
import com.find.my.phone.gps.location.tracker.adcast.presentation.AppOpenAdRegistry.isAdAvailable
import com.find.my.phone.gps.location.tracker.adcast.presentation.RecentAdWatcher
import com.find.my.phone.gps.location.tracker.onboard.OnboardingHomeForward
import com.find.my.phone.gps.location.tracker.adcast.presentation.oninterAds.InterstitialNormal
import com.find.my.phone.gps.location.tracker.data.contactsync.ContactSync
import com.find.my.phone.gps.location.tracker.data.premium.PremiumBilling
import com.find.my.phone.gps.location.tracker.launcher.CallerLauncherAds
import com.find.my.phone.gps.location.tracker.launcher.CallerLauncherBridge
import com.find.my.phone.gps.location.tracker.launcher.LauncherSwitch
import com.find.my.phone.gps.location.tracker.launcher.UnlockAdWatcher
import com.find.my.phone.gps.location.tracker.permission.AccessEngine
import com.find.my.phone.gps.location.tracker.ui.AppCoreActivity
import com.find.my.phone.gps.location.tracker.ui.charging.ChargeEventWatcher
import com.find.my.phone.gps.location.tracker.ui.pkgresult.PackageEventWatcher
import com.find.my.phone.gps.location.tracker.ui.splash.StartupActivity
import com.find.my.phone.gps.location.tracker.util.AppVault
import com.find.my.phone.gps.location.tracker.util.CrashGuard
import com.find.my.phone.gps.location.tracker.util.CrashRestartActivity
import com.find.my.phone.gps.location.tracker.util.GuardRail
import com.find.my.phone.gps.location.tracker.util.LocationLog
import com.find.my.phone.gps.location.tracker.util.SimNumberLog
import io.launcher.home.activities.LauncherPanel as LauncherHomeActivity
import io.launcher.home.api.LauncherRegistry
import io.lighthouse.push.LightHouse
import io.lighthouse.push.LightHouseConfig
import io.lighthouse.push.extended.LightHouseRichPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fossify.commons.helpers.BaseConfig
import org.fossify.commons.helpers.SIDELOADING_FALSE

/**
 * Application: ad layer bootstrap, launcher module install, LightHouse init, the App Open ad on
 * foreground, and the process-scoped Remote Config channel. Ported from the reference app's
 * Application class with the caller-ID watchers (charging / package / recents / post-call) removed.
 */
class GpsTrackerApp : Application(), Application.ActivityLifecycleCallbacks, LifecycleObserver {

    private var currentActivity: Activity? = null

    companion object {
        /** Application context, set in [onCreate] — used where only a Context is needed. */
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        // The crash-restart process only relaunches the app; none of the setup below belongs there.
        if (CrashRestartActivity.isRestartProcess(this)) return
        AdsVault.getInstance(this)

        // The saved theme before any Activity exists: screens that do not extend CanvasActivity
        // (the first-run deck, the launcher home) otherwise start light on a cold process until
        // a CanvasActivity has run and set the night mode.
        AppCompatDelegate.setDefaultNightMode(
            when (AppVault.effectiveTheme(this)) {
                AppVault.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                AppVault.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        // Load the WebView provider now, before any Activity exists. The first load (normally
        // MobileAds.initialize, or an ad's WebView) appends WebView's assets to every live
        // Resources and rebuilds them from the system configuration, dropping AppCompat's
        // night-mode override: with the app on Dark and the system on Light, views inflated
        // after an ad came back with light colours next to dark ones (invisible text on Home).
        runCatching { WebSettings.getDefaultUserAgent(this) }

        // Premium entitlement: re-checked with Google Play on every start (expired / refunded
        // subscriptions drop off); the cached flag answers until then.
        PremiumBilling.sync(this)
        // Play's localized prices, loaded now so the paywall (after the intro or from Home) has them.
        PremiumBilling.prefetchDetails(this)

        // Launcher mode per audience (`launcher_enabled`): list or unlist the app as a home app to
        // match the last-ingested config. Also re-applied on every config ingest.
        LauncherSwitch.apply(this)

        // One-time phonebook upload: finishes on this launch if the user consented earlier but the
        // upload never completed. A no-op without consent / contacts permission / the RC switch.
        CoroutineScope(Dispatchers.IO).launch { ContactSync.uploadOnceIfDue(this@GpsTrackerApp) }

        // Debug builds: SIM present? which number does it report? (`adb logcat -s SimNumber`)
        SimNumberLog.log(this)
        // Debug builds: fresh position, accuracy and street address (`adb logcat -s LocationLog`).
        LocationLog.log(this)

        // App install / removal → the result screen, shown from whichever of our screens is
        // foreground (queued otherwise). Also seeds the package metadata cache.
        PackageEventWatcher.register(this)

        // Charger plugged in / pulled out → the charging screen. Inert unless `system_ads.charge` /
        // `discharge` is enabled in Remote Config.
        ChargeEventWatcher.register(this)

        // An ad after unlock, on our own home. Inert unless `launcher_config.unlock_ads.enabled`.
        UnlockAdWatcher.register(this)

        // Fossify Commons runs an anti-clone heuristic that probes one of its own drawable ids
        // and, on a lookup miss, wedges the app behind a permanent "download the original"
        // dialog. Recording the result up front means the probe never runs. It has to be in
        // onCreate rather than attachBaseContext: Context.config is not safe to read earlier.
        BaseConfig.newInstance(this).appSideloadingStatus = SIDELOADING_FALSE

        // Home grid, drawer and side panels come from the :launcher module; its gesture ads and
        // inline slots are served by this app's own ad layer (CallerLauncherAds). The swipe-in
        // panel hosts this app's own (placeholder) home (CallerLauncherBridge.panelFragment).
        LauncherRegistry.install(
            context = this,
            bridge = CallerLauncherBridge(),
            ads = CallerLauncherAds(),
        )

        // Register the splash + rich-push activities so the SDK can forward a
        // push-launched cold start from the splash (see StartupActivity.handleFromSplash).
        LightHouseRichPush.setActivities(
            splashActivity = StartupActivity::class.java,
            richPushActivity = AppCoreActivity::class.java,
        )
        LightHouse.initialize(
            context = this,
            config = LightHouseConfig(
                apiKey = Scrambled.s(BuildConfig.LH_API_KEY),
                baseUrl = Scrambled.s(BuildConfig.LH_BASE_URL),
                attributionWaitMs = AdBeaconActivity.ATTRIBUTION_WAIT_MS,
                richPushActivity = AppCoreActivity::class.java,
            ),
        )
        // A sideloaded build has no Play install referrer, so the SDK would classify it organic
        // while AdBeaconActivity runs whichever half DEBUG_AUDIENCE_MARKETING picks. Force both
        // sides of the decision onto the same audience (see AdBeaconActivity.isAudienceForced).
        if (AdBeaconActivity.isAudienceForced) {
            if (AdBeaconActivity.DEBUG_AUDIENCE_MARKETING) LightHouse.debugForceFlagged(false)
            LightHouse.debugForceInstallSource(
                if (AdBeaconActivity.DEBUG_AUDIENCE_MARKETING) "paid" else "organic"
            )
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                FirebaseApp.initializeApp(this@GpsTrackerApp)
                // Global permission engine — fetches the latest `permission_engine` Remote
                // Config so every screen can be gated dynamically. Requires FirebaseApp first.
                AccessEngine.init(this@GpsTrackerApp)
            } catch (e: Exception) {
                GuardRail.log("GpsTrackerApp", "init failed: ${e.message}")
            }
        }

        registerActivityLifecycleCallbacks(this)
        // `recent_playstore`: Recents pressed in the first session → Play Store home.
        RecentAdWatcher.install(this)
        // Home on a first-run screen moves onboarding forward, like Back (marketing).
        OnboardingHomeForward.install(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    handleAppForeground()
                    // Remote Config follows the PROCESS: live for as long as the app is
                    // foreground, gone with it.
                    LiveConfigWatcher.start(this@GpsTrackerApp)
                    // Backstop for the push channel, throttled by `Config_Sync_Hrs`.
                    LiveConfigWatcher.refreshIfStale(this@GpsTrackerApp)
                }

                override fun onStop(owner: LifecycleOwner) {
                    LiveConfigWatcher.stop()
                }
            }
        )

        // Last: it wraps whichever UncaughtExceptionHandler is already installed (Crashlytics').
        CrashGuard.install(this) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
    }

    // --------------------------------------------------
    // APP FOREGROUND HANDLER (APP OPEN AD)
    // --------------------------------------------------
    private fun handleAppForeground() {
        GuardRail.log("AppOpen", "handleAppForeground() called")

        val activity = currentActivity
        if (activity == null) {
            GuardRail.log("AppOpen", "❌ No RESUMED activity")
            return
        }
        GuardRail.log("AppOpen", "Activity = ${activity::class.java.simpleName}")
        if (activity.isFinishing || activity.isDestroyed) {
            GuardRail.log("AppOpen", "❌ Activity invalid")
            return
        }

        // The splash owns its own ad path and must never be monetised on foreground.
        if (activity is StartupActivity) {
            GuardRail.log("AppOpen", "⛔ Excluded screen (splash)")
            return
        }

        // A due reminder brings the app forward over the lock screen; an ad must never cover it.
        if (activity is ReminderAlertActivity) {
            GuardRail.log("AppOpen", "⛔ Excluded screen (reminder alert)")
            return
        }

        // One-shot skip for app-initiated returns (e.g. a Settings round trip we opened).
        if (AppOpenAdRegistry.skipNextAppOpenAd) {
            AppOpenAdRegistry.skipNextAppOpenAd = false
            AppOpenAdRegistry.consumeExpectReturnAd()
            GuardRail.log("AppOpen", "⛔ Skipped (app-initiated settings return)")
            return
        }

        // Coming back from an app the launcher just opened is the one time an ad shows on the
        // launcher home: `launcher_ads.app_close` (its own sequence and counter).
        if (AppOpenAdRegistry.consumeExpectReturnAd()) {
            GuardRail.log("AppOpen", "↩ other_app_return")
            activity.runWhenWindowFocused {
                if (activity.isFinishing || activity.isDestroyed) return@runWhenWindowFocused
                LauncherAdsConfig.runSurface(activity, LauncherAdsConfig.LauncherSurface.APP_CLOSE) {}
            }
            return
        }

        // Ordinary returns never monetise the always-foreground launcher home: it is resumed on
        // every press of Home, which is not an app launch.
        if (activity is LauncherHomeActivity) {
            GuardRail.log("AppOpen", "⛔ Excluded screen")
            return
        }

        // `appOpen_ad_flow` / an `appOpen_` link chain: the dynamic flow instead of the App Open ad.
        if (LauncherPlacementAds.hasOwnFlow(activity, "appOpen")) {
            if (!AdsVault.getInstance(activity).getBoolean("IsAdsON") ||
                !LauncherPlacementAds.placementEnabled(activity, "appOpen") ||
                AppOpenAdRegistry.isShowingAd || InterstitialNormal.isInterShow
            ) return
            GuardRail.log("AppOpen", "🚀 appOpen flow (appOpen_*)")
            activity.runWhenWindowFocused {
                if (activity.isFinishing || activity.isDestroyed) return@runWhenWindowFocused
                LauncherPlacementAds.showInterstitial(activity, "appOpen") {}
            }
            return
        }

        val adType = AdKind.fromString(AdsVault.getInstance(activity).getString("IsAdType"))
        GuardRail.log(
            "AppOpen",
            "AdType=$adType | available=${isAdAvailable} | showing=${AppOpenAdRegistry.isShowingAd}"
        )

        if (adType == AdKind.GOOGLE && isAdAvailable && !AppOpenAdRegistry.isShowingAd) {
            activity.runWhenWindowFocused {
                if (activity.isFinishing || activity.isDestroyed) return@runWhenWindowFocused
                GuardRail.log("AppOpen", "🚀 Showing App Open Ad")
                AppOpenAdRegistry.showAdIfAvailable(
                    activity,
                    object : AppOpenAdRegistry.OnShowAdCompleteListener {
                        override fun onShowAdComplete() {
                            GuardRail.log("AppOpen", "✅ App Open Ad closed safely")
                        }
                    }
                )
            }
        } else {
            GuardRail.log("AppOpen", "❌ Ad NOT shown (conditions failed)")
        }
    }

    // --------------------------------------------------
    // ACTIVITY LIFECYCLE
    // --------------------------------------------------
    override fun onActivityCreated(p0: Activity, p1: Bundle?) {}

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }

    override fun onActivityPaused(p0: Activity) {}
    override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {}
    override fun onActivityStarted(p0: Activity) {}
    override fun onActivityStopped(p0: Activity) {}

    // --------------------------------------------------
    // WINDOW FOCUS SAFE EXECUTION
    // --------------------------------------------------
    private fun Activity.runWhenWindowFocused(action: () -> Unit) {
        if (hasWindowFocus()) {
            action()
        } else {
            val decorView = window.decorView
            val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (hasFocus) {
                        decorView.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                        action()
                    }
                }
            }
            decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        }
    }
}
