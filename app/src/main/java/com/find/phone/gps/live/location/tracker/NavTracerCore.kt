package com.find.phone.gps.live.location.tracker

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
import com.find.phone.gps.live.location.tracker.screens.memo.MemoSirenActivity
import com.google.firebase.FirebaseApp
import com.find.phone.gps.live.location.tracker.monetize.model.SpotSort
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSeatSpots
import com.find.phone.gps.live.location.tracker.monetize.logic.InstantProfileSentinel
import com.find.phone.gps.live.location.tracker.monetize.render.SpotBeaconActivity
import com.find.phone.gps.live.location.tracker.monetize.render.CoreOpenSpotBook
import com.find.phone.gps.live.location.tracker.monetize.render.CoreOpenSpotBook.isAdAvailable
import com.find.phone.gps.live.location.tracker.monetize.render.FreshSpotSentinel
import com.find.phone.gps.live.location.tracker.induction.InductionMainOnward
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverStandard
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverRear
import com.find.phone.gps.live.location.tracker.monetize.render.TeaserSiteActivity
import com.find.phone.gps.live.location.tracker.store.peoplesync.BuddyRelay
import com.find.phone.gps.live.location.tracker.store.plus.PlusCheckout
import com.find.phone.gps.live.location.tracker.homeshell.RingHomeSpots
import com.find.phone.gps.live.location.tracker.homeshell.RingHomeGateway
import com.find.phone.gps.live.location.tracker.homeshell.HomeFlip
import com.find.phone.gps.live.location.tracker.homeshell.UnlatchSpotSentinel
import com.find.phone.gps.live.location.tracker.consent.ConsentDriver
import com.find.phone.gps.live.location.tracker.screens.CoreHubActivity
import com.find.phone.gps.live.location.tracker.screens.power.JuiceHappeningSentinel
import com.find.phone.gps.live.location.tracker.screens.parcel.ParcelHappeningSentinel
import com.find.phone.gps.live.location.tracker.screens.boot.BootActivity
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker
import com.find.phone.gps.live.location.tracker.helpers.FailureWarden
import com.find.phone.gps.live.location.tracker.helpers.FailureReviveActivity
import com.find.phone.gps.live.location.tracker.helpers.WardenRail
import com.find.phone.gps.live.location.tracker.helpers.PlaceDiary
import com.find.phone.gps.live.location.tracker.helpers.ChipNumDiary
import com.livemap.homedeck.screens.HomeDeck as LauncherHomeActivity
import com.livemap.homedeck.contract.HomeBook
import io.lighthouse.push.LightHouse
import io.lighthouse.push.LightHouseConfig
import io.lighthouse.push.extended.LightHouseRichPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fossify.commons.helpers.BaseConfig
import org.fossify.commons.helpers.SIDELOADING_FALSE

class NavTracerCore : Application(), Application.ActivityLifecycleCallbacks, LifecycleObserver {
    private var currentActivity: Activity? = null

    companion object {
        lateinit var appContext: Context
            private set

        fun currentScreen(): String =
            if (::appContext.isInitialized) (appContext as? NavTracerCore)?.currentActivity?.javaClass?.simpleName ?: "unknown"
            else "unknown"
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        if (FailureReviveActivity.isRestartProcess(this)) return
        SpotsLocker.getInstance(this)

        AppCompatDelegate.setDefaultNightMode(
            when (CoreLocker.effectiveTheme(this)) {
                CoreLocker.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                CoreLocker.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        runCatching { WebSettings.getDefaultUserAgent(this) }

        PlusCheckout.sync(this)

        PlusCheckout.prefetchDetails(this)

        HomeFlip.apply(this)

        CoroutineScope(Dispatchers.IO).launch { BuddyRelay.pushOnceIfDue(this@NavTracerCore) }

        ChipNumDiary.log(this)

        PlaceDiary.log(this)

        ParcelHappeningSentinel.register(this)

        JuiceHappeningSentinel.register(this)

        UnlatchSpotSentinel.register(this)

        BaseConfig.newInstance(this).appSideloadingStatus = SIDELOADING_FALSE

        HomeBook.install(
            context = this,
            bridge = RingHomeGateway(),
            ads = RingHomeSpots(),
        )

        LightHouseRichPush.setActivities(
            splashActivity = BootActivity::class.java,
            richPushActivity = CoreHubActivity::class.java,
        )
        LightHouse.initialize(
            context = this,
            config = LightHouseConfig(
                apiKey = Muddle.s(BuildConfig.LH_API_KEY),
                baseUrl = Muddle.s(BuildConfig.LH_BASE_URL),
                attributionWaitMs = SpotBeaconActivity.ATTRIBUTION_WAIT_MS,
                richPushActivity = CoreHubActivity::class.java,
                onRemoteConfigSync = { version -> InstantProfileSentinel.applyPushed(this, version) },
            ),
        )

        if (SpotBeaconActivity.isAudienceForced) {
            if (SpotBeaconActivity.DEBUG_AUDIENCE_MARKETING) LightHouse.debugForceFlagged(false)
            LightHouse.debugForceInstallSource(
                if (SpotBeaconActivity.DEBUG_AUDIENCE_MARKETING) "paid" else "organic"
            )
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                FirebaseApp.initializeApp(this@NavTracerCore)

                ConsentDriver.init(this@NavTracerCore)
            } catch (e: Exception) {
                WardenRail.log("NavTracerCore", "init failed: ${e.message}")
            }
        }

        registerActivityLifecycleCallbacks(this)
        com.find.phone.gps.live.location.tracker.foundation.NavPressTracker.install(this)

        FreshSpotSentinel.install(this)

        InductionMainOnward.install(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    processAppForeground()

                    InstantProfileSentinel.start(this@NavTracerCore)

                    InstantProfileSentinel.renewIfStale(this@NavTracerCore)
                }

                override fun onStop(owner: LifecycleOwner) {
                    InstantProfileSentinel.stop()
                }
            }
        )

        FailureWarden.install(this) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
    }

    private fun processAppForeground() {
        WardenRail.log("AppOpen", "handleAppForeground() called")

        val activity = currentActivity
        if (activity == null) {
            WardenRail.log("AppOpen", "❌ No RESUMED activity")
            return
        }
        WardenRail.log("AppOpen", "Activity = ${activity::class.java.simpleName}")
        if (activity.isFinishing || activity.isDestroyed) {
            WardenRail.log("AppOpen", "❌ Activity invalid")
            return
        }

        if (TakeoverStandard.isInterShow || TakeoverRear.isInterBAckShow) {
            WardenRail.log("AppOpen", "⛔ Interstitial showing — skip app-open")
            return
        }
        val foregroundClass = activity.javaClass.name
        if (foregroundClass == "com.google.android.gms.ads.AdActivity" ||
            foregroundClass == "com.facebook.ads.AudienceNetworkActivity"
        ) {
            WardenRail.log("AppOpen", "⛔ Ad activity in foreground — skip app-open")
            return
        }

        if (activity is BootActivity) {
            WardenRail.log("AppOpen", "⛔ Excluded screen (splash)")
            return
        }

        if (activity is MemoSirenActivity) {
            WardenRail.log("AppOpen", "⛔ Excluded screen (reminder alert)")
            return
        }

        if (CoreOpenSpotBook.skipNextAppOpenAd) {
            CoreOpenSpotBook.skipNextAppOpenAd = false
            CoreOpenSpotBook.absorbExpectReturnAd()
            WardenRail.log("AppOpen", "⛔ Skipped (app-initiated settings return)")
            return
        }

        if (CoreOpenSpotBook.absorbExpectReturnAd()) {
            WardenRail.log("AppOpen", "↩ other_app_return")
            activity.execWhenWindowFocused {
                if (activity.isFinishing || activity.isDestroyed) return@execWhenWindowFocused
                HomeSpotsProfile.execSurface(activity, HomeSpotsProfile.HomePlane.APP_CLOSE) {}
            }
            return
        }

        if (activity is LauncherHomeActivity) {
            WardenRail.log("AppOpen", "⛔ Excluded screen")
            return
        }

        if (HomeSeatSpots.hasOwnFlow(activity, "appOpen")) {
            if (!SpotsLocker.getInstance(activity).getBoolean("IsAdsON") ||
                !HomeSeatSpots.placementEnabled(activity, "appOpen") ||
                CoreOpenSpotBook.isShowingAd || TakeoverStandard.isInterShow
            ) return
            WardenRail.log("AppOpen", "🚀 appOpen flow (appOpen_*)")
            activity.execWhenWindowFocused {
                if (activity.isFinishing || activity.isDestroyed) return@execWhenWindowFocused
                HomeSeatSpots.showInterstitial(activity, "appOpen") {}
            }
            return
        }

        val adType = SpotSort.fromString(SpotsLocker.getInstance(activity).getString("IsAdType"))
        WardenRail.log(
            "AppOpen",
            "AdType=$adType | available=${isAdAvailable} | showing=${CoreOpenSpotBook.isShowingAd}"
        )

        if (adType == SpotSort.GOOGLE && isAdAvailable && !CoreOpenSpotBook.isShowingAd) {
            activity.execWhenWindowFocused {
                if (activity.isFinishing || activity.isDestroyed) return@execWhenWindowFocused
                WardenRail.log("AppOpen", "🚀 Showing App Open Ad")
                CoreOpenSpotBook.presentAdIfAvailable(
                    activity,
                    object : CoreOpenSpotBook.OnShowAdCompleteListener {
                        override fun onShowAdComplete() {
                            WardenRail.log("AppOpen", "✅ App Open Ad closed safely")
                        }
                    }
                )
            }
        } else {
            WardenRail.log("AppOpen", "❌ Ad NOT shown (conditions failed)")
        }
    }

    override fun onActivityCreated(p0: Activity, p1: Bundle?) {}

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        if (CoreOpenSpotBook.pendingGameCloseAd && activity !is TeaserSiteActivity) {
            CoreOpenSpotBook.pendingGameCloseAd = false
            HomeSpotsProfile.execSurface(activity, HomeSpotsProfile.HomePlane.GAME_CLOSE) {}
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }

    override fun onActivityPaused(p0: Activity) {}
    override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {}
    override fun onActivityStarted(p0: Activity) {}
    override fun onActivityStopped(p0: Activity) {}

    private fun Activity.execWhenWindowFocused(action: () -> Unit) {
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
