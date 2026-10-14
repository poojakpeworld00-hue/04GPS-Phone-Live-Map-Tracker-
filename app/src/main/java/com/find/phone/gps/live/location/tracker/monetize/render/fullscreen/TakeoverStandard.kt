package com.find.phone.gps.live.location.tracker.monetize.render.fullscreen

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.ContextCompat
import com.facebook.ads.Ad
import com.facebook.ads.InterstitialAdListener
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.find.phone.gps.live.location.tracker.monetize.model.SpotSort
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotTallyBook.interCounter
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotIncomeGauge
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSeatSpots
import com.find.phone.gps.live.location.tracker.monetize.render.StraightHrefOpener
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordAdEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordAdLoadError
import com.find.phone.gps.live.location.tracker.foundation.NavPressTracker
import com.find.phone.gps.live.location.tracker.monetize.render.isNetworkConnected
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.R
class TakeoverStandard {

    companion object {

        /**
         * The app-wide interstitial's own placement name, so its link-first / `ad_flow` /
         * `inter_fallback` keys live under `app_…` like every other placement's.
         */
        private const val APP_PLACEMENT = "app"

        private var _isInterShow: Boolean = false
        var isInterShow: Boolean
            get() = _isInterShow
            set(value) {
                _isInterShow = value
            }
        private var googleInterAd: InterstitialAd? = null

        /**
         * Whether a show right now would play an interstitial that is already loaded. In Google
         * preload mode that means the preloaded ad is in hand; any other setup (on-demand,
         * Facebook, custom) is left to [presentInterAds] to decide. For moments that must not sit
         * behind a loader or fall back to another format — they show a ready ad or nothing.
         */
        fun hasReadyInter(context: Context): Boolean {
            val pref = SpotsLocker.getInstance(context)
            val googlePreload = SpotSort.fromString(pref.getString("IsAdType")) == SpotSort.GOOGLE &&
                pref.getBoolean("is_preload_ads")
            return !googlePreload || googleInterAd != null
        }
        private var preloadedFbAd: com.facebook.ads.InterstitialAd? = null
        private var isFbPreloading = false
        // Forwarding callbacks — set just before ad.show(), fired by the preload listener
        private var fbOnDismissed: (() -> Unit)? = null
        private var fbOnFail: (() -> Unit)? = null
        var isOpened = false
        var onTabClosed: (() -> Unit)? = null
        fun processTabReturn(context: Context) {
            if (!isOpened) return

            isOpened = false
            onTabClosed?.invoke()
            onTabClosed = null

            freeSession(context) // 🔥 ADD THIS
        }
        private var customTabsClient: CustomTabsClient? = null
        private var customTabsSession: CustomTabsSession? = null
        private var serviceConnection: CustomTabsServiceConnection? = null

        fun freeSession(context: Context) {
            serviceConnection?.let {
                try {
                    context.unbindService(it)
                } catch (_: Exception) {}
            }
            serviceConnection = null
            customTabsClient = null
            customTabsSession = null
        }

        fun prefetchFbAd(context: Context) {
            val pref = SpotsLocker.getInstance(context)
            if (!pref.getBoolean("IsAdsON")) return
            if (isFbPreloading || preloadedFbAd != null) return
            val fbId = pref.getString("faceB_InterAds") ?: return
            isFbPreloading = true
            val inter = com.facebook.ads.InterstitialAd(context, fbId)
            inter.loadAd(
                inter.buildLoadAdConfig()
                    .withAdListener(object : com.facebook.ads.InterstitialAdListener {
                        override fun onAdLoaded(ad: com.facebook.ads.Ad?) {
                            preloadedFbAd = inter
                            isFbPreloading = false
                        }
                        override fun onError(ad: com.facebook.ads.Ad?, e: com.facebook.ads.AdError?) {
                            isFbPreloading = false
                            fbOnFail?.invoke()
                            fbOnFail = null
                            fbOnDismissed = null
                        }
                        override fun onInterstitialDismissed(ad: com.facebook.ads.Ad?) {
                            prefetchFbAd(context)   // replenish
                            fbOnDismissed?.invoke()
                            fbOnDismissed = null
                            fbOnFail = null
                        }
                        override fun onInterstitialDisplayed(ad: com.facebook.ads.Ad?) {}
                        override fun onAdClicked(ad: com.facebook.ads.Ad?) {}
                        override fun onLoggingImpression(ad: com.facebook.ads.Ad?) {}
                    }).build()
            )
        }

        fun enterDirectLink(context: Activity, onClosed: () -> Unit) {
            val url = SpotsLocker.getInstance(context).getString("DirectLink")

            if (url.isNullOrEmpty()) {
                onClosed()
                return
            }

            // `DirectLinkType` webview / browser: StraightHrefOpener owns those. Only the custom tab
            // path below needs the close tracking, so it alone stays here.
            if (StraightHrefOpener.mode(context) != StraightHrefOpener.Mode.CUSTOM_TAB) {
                StraightHrefOpener.open(context, url)
                onClosed()
                return
            }

            val uri = Uri.parse(url)
            isOpened = true
            onTabClosed = onClosed

            getSession(context) { session ->

                val customTab = CustomTabsIntent.Builder(session)
                    .setShowTitle(true)
                    .setToolbarColor(ContextCompat.getColor(context, R.color.black))
                    .build()

                try {
                    customTab.launchUrl(context, uri)
                } catch (e: Exception) {
                    enterInBrowser(context, uri, onClosed)
                }
            }
        }

        // Use CustomTabsSession to track tab close
        private fun getSession(
            context: Context,
            onReady: (CustomTabsSession?) -> Unit
        ) {
            if (customTabsSession != null) {
                onReady(customTabsSession)
                return
            }

            serviceConnection = object : CustomTabsServiceConnection() {

                override fun onCustomTabsServiceConnected(
                    name: ComponentName,
                    client: CustomTabsClient
                ) {
                    customTabsClient = client
                    customTabsSession = client.newSession(null)
                    onReady(customTabsSession)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    customTabsClient = null
                    customTabsSession = null
                    onReady(null)
                }
            }

            CustomTabsClient.bindCustomTabsService(
                context,
                "com.android.chrome",
                serviceConnection as CustomTabsServiceConnection
            )
        }

        private fun enterInBrowser(
            context: Activity,
            uri: Uri,
            onClosed: () -> Unit
        ) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                context.startActivity(intent)
//                safeLog("browser_opened")
            } catch (e: Exception) {
//                context.safeLog("browser_open_failed")
            } finally {
                // Ensure callback is always called
                isOpened = false
                onTabClosed?.invoke()
                onTabClosed = null
                onClosed()
            }
        }


    }


    // ----------------------------------------------------------------------
    // LOAD INTER AD (Google)
    // ----------------------------------------------------------------------
    fun fetchInterAds(activity: Activity) {
        val pref = SpotsLocker.getInstance(activity)
        if (!pref.getBoolean("IsAdsON")) return
        // Firebase "InterAds" master switch — disable interstitial loading entirely
        if (!pref.getBoolean("InterAds")) return
        // Only preload when is_preload_ads = true; on-demand path loads at show time
        if (!pref.getBoolean("is_preload_ads")) return

        val adType = SpotSort.fromString(pref.getString("IsAdType"))

        if (adType == SpotSort.GOOGLE) {
            activity.safeLog("google_inter_load_start")
            val id = pref.getString("googleInter") ?: return
            activity.recordAdEvent("interstitial", "req")
            InterstitialAd.load(
                activity, id, AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        googleInterAd = null
                        activity.safeLog("google_inter_load_failed_${error.code}")
                        activity.recordAdLoadError("interstitial", error.code)
                        Log.e("TakeoverStandard", "Load Failed: ${error.message}")
                    }
                    override fun onAdLoaded(ad: InterstitialAd) {
                        googleInterAd = ad
                        activity.safeLog("google_inter_loaded")
                        activity.recordAdEvent("interstitial", "loaded")
                        Log.d("TakeoverStandard", "Google inter loaded")
                    }
                })
        }

        if (adType == SpotSort.FACEBOOK || pref.getBoolean("IsFail_FB")) {
            prefetchFbAd(activity)
        }
    }

    // ----------------------------------------------------------------------
    // PUBLIC SHOW METHOD
    // ----------------------------------------------------------------------
    fun presentInterAds(activity: Activity?, adsClose: () -> Unit) {
        presentAdInternal(activity, adsClose)
    }

    // ----------------------------------------------------------------------
    // MAIN INTER AD SHOW
    // ----------------------------------------------------------------------
    private fun presentAdInternal(activity: Activity?, adsClose: () -> Unit) {
        val act = activity ?: return adsClose()
        act.safeLog("inter_request_start")

        val pref = SpotsLocker.getInstance(act)
        var hasClosed = false

        fun safeClose(reason: String) {
            if (hasClosed) return
            hasClosed = true
            act.safeLog("inter_closed_$reason")
            adsClose()   // OPEN NEXT ACTIVITY INSTANTLY
        }

        // Network check
        if (!isNetworkConnected(act)) return safeClose("no_network")
        if (!pref.getBoolean("IsAdsON")) return safeClose("ads_off")
        // Firebase "InterAds" master switch — skip showing interstitials entirely
        if (!pref.getBoolean("InterAds")) return safeClose("inter_ads_disabled")

        // Counter logic
        val target = pref.getInt("InterCounter")
        if (interCounter != target) {
            interCounter++
            act.safeLog("inter_counter_skip")
            return safeClose("counter_skip")
        }
        interCounter = 0
        act.safeLog("inter_counter_triggered")

        // `app_link_first_then` + `app_DirectLink`: the links first, then the listed follow-ups.
        if (HomeSeatSpots.presentLinkFirst(act, APP_PLACEMENT) { safeClose("link_first") }) {
            act.safeLog("inter_link_first")
            return
        }
        // `app_ad_flow`: the whole chain, in the configured order, instead of the interstitial.
        if (HomeSeatSpots.presentAdFlow(act, APP_PLACEMENT) { safeClose("ad_flow") }) {
            act.safeLog("inter_ad_flow")
            return
        }

        val isPreload = pref.getBoolean("is_preload_ads")

        when (SpotSort.fromString(pref.getString("IsAdType"))) {

            SpotSort.GOOGLE -> {
                act.safeLog("inter_type_google")
                if (isPreload) {
                    // A preloaded ad has no load time of its own; `Inter_Loader_Ms` can still put a
                    // short loader in front of it. Nothing to put it in front of when none is ready.
                    if (googleInterAd != null) {
                        WholeDisplayWheel.beforeShow(act) { presentGoogleInterstitial(act, pref, ::safeClose) }
                    } else {
                        presentGoogleInterstitial(act, pref, ::safeClose)
                    }
                } else {
                    fetchAndShowGoogleOnDemand(act, pref, ::safeClose)
                }
            }

            SpotSort.FACEBOOK -> {
                act.safeLog("inter_type_facebook")
                if (isPreload && preloadedFbAd != null) {
                    presentPreloadedFbAd(
                        act,
                        onDismissed = { safeClose("fb_dismiss") },
                        onFail = {
                            act.safeLog("fb_preload_fail_fallback")
                            fetchAndShowFacebookInter(
                                act,
                                onDismissed = { safeClose("fb_dismiss") },
                                onFail = { presentCustomAfterFacebookFail(act, pref) { safeClose("fb_fail_custom") } }
                            )
                        }
                    )
                } else {
                    fetchAndShowFacebookInter(
                        act,
                        onDismissed = { safeClose("fb_dismiss") },
                        onFail = {
                            act.safeLog("fb_load_fail")
                            presentCustomAfterFacebookFail(act, pref) { safeClose("fb_fail_custom") }
                        }
                    )
                }
            }

            SpotSort.CUSTOM, SpotSort.UNKNOWN -> {
                act.safeLog("inter_type_custom")
                if (pref.getBoolean("IsCustomADS"))
                    enterDirectLink(act) { safeClose("custom_opened") }
                else safeClose("custom_disabled")
            }
        }
    }

    // ----------------------------------------------------------------------
    // GOOGLE INTERSTITIAL
    // ----------------------------------------------------------------------
    private fun presentGoogleInterstitial(
        activity: Activity,
        pref: SpotsLocker,
        safeClose: (String) -> Unit
    ) {

        val inter = googleInterAd
        if (inter == null) {
            activity.safeLog("google_inter_null")
            return processGoogleFail(activity, pref, safeClose)
        }

        // Log load
        activity.recordKeyEvent("google_inter_show_attempt")

        if (BuildConfig.DEBUG) SpotIncomeGauge.simulateDebugRevenue(activity)

        inter.setOnPaidEventListener {
            SpotIncomeGauge.recordPaidEvent(activity, it)
        }

        inter.fullScreenContentCallback = object : FullScreenContentCallback() {

            override fun onAdShowedFullScreenContent() {
                super.onAdShowedFullScreenContent()
                isInterShow = true
                runCatching { activity.recordAdEvent("interstitial", "show") }
            }

            override fun onAdDismissedFullScreenContent() {
                isInterShow = false
                googleInterAd = null
                activity.safeLog("google_inter_dismiss")
                runCatching { activity.recordAdEvent("interstitial", "close") }
                safeClose("google_dismiss")
                if (pref.getBoolean("is_preload_ads")) fetchInterAds(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                googleInterAd = null
                activity.safeLog("google_inter_failed_show_${error.code}")
                processGoogleFail(activity, pref, safeClose)
                if (pref.getBoolean("is_preload_ads")) fetchInterAds(activity)
            }
        }

        try {
            inter.show(activity)
        } catch (e: Exception) {
            googleInterAd = null
            activity.safeLog("google_inter_exception")
            processGoogleFail(activity, pref, safeClose)
            fetchInterAds(activity)
        }
    }

    // ----------------------------------------------------------------------
    // GOOGLE ON-DEMAND (is_preload_ads = false)
    // ----------------------------------------------------------------------
    private fun fetchAndShowGoogleOnDemand(
        activity: Activity,
        pref: SpotsLocker,
        safeClose: (String) -> Unit
    ) {
        val id = pref.getString("googleInter") ?: return processGoogleFail(activity, pref, safeClose)
        val isLoader = WholeDisplayWheel.isEnabled(activity)

        activity.safeLog("google_inter_ondemand_load_start")
        activity.recordAdEvent("interstitial", "req")
        WholeDisplayWheel.show(activity, isLoader)

        InterstitialAd.load(
            activity, id, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    WholeDisplayWheel.hide()
                    googleInterAd = ad
                    activity.safeLog("google_inter_ondemand_loaded")
                    activity.recordAdEvent("interstitial", "loaded")
                    presentGoogleInterstitial(activity, pref, safeClose)
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    WholeDisplayWheel.hide()
                    googleInterAd = null
                    activity.safeLog("google_inter_ondemand_fail_${error.code}")
                    activity.recordAdLoadError("interstitial", error.code)
                    processGoogleFail(activity, pref, safeClose)
                }
            }
        )
    }

    // ----------------------------------------------------------------------
    // FACEBOOK PRELOADED SHOW (is_preload_ads = true)
    // ----------------------------------------------------------------------
    private fun presentPreloadedFbAd(
        activity: Activity,
        onDismissed: () -> Unit,
        onFail: () -> Unit
    ) {
        val ad = preloadedFbAd
        if (ad == null || !ad.isAdLoaded) {
            preloadedFbAd = null
            onFail()
            return
        }
        preloadedFbAd = null
        // Wire forwarding callbacks (listener was set at load time in prefetchFbAd)
        fbOnDismissed = onDismissed
        fbOnFail = onFail
        try {
            ad.show()
            activity.safeLog("fb_preloaded_show")
        } catch (e: Exception) {
            activity.safeLog("fb_preloaded_show_exception")
            fbOnDismissed = null
            fbOnFail = null
            onFail()
        }
    }

    private fun processGoogleFail(
        activity: Activity,
        pref: SpotsLocker,
        safeClose: (String) -> Unit
    ) {
        activity.safeLog("google_fail_start")

        val fbEnabled = pref.getBoolean("IsFail_FB")
        val customEnabled = pref.getBoolean("IsCustomADS")

        if (fbEnabled) {

            activity.safeLog("google_fail_try_facebook")

            fetchAndShowFacebookInter(
                activity,
                onDismissed = { safeClose("fb_dismiss") },
                onFail = {
                    activity.safeLog("fb_fail_after_google_fail")
                    presentCustomAfterFacebookFail(activity, pref, safeClose)
                }
            )
        } else if (HomeSeatSpots.hasFallback(activity, APP_PLACEMENT)) {
            // `inter_fallback`: rewarded / full-screen native / … before giving up on the slot.
            activity.safeLog("google_fail_fallback_chain")
            HomeSeatSpots.execFallback(activity, APP_PLACEMENT) { safeClose("google_fail_fallback") }
        } else {
            if (customEnabled) {
                activity.safeLog("google_fail_open_custom")
                enterDirectLink(activity) { safeClose("google_fail_custom") }
            } else safeClose("google_fail_no_fb_no_custom")
        }
    }

    // ----------------------------------------------------------------------
    // FACEBOOK INTERSTITIAL
    // ----------------------------------------------------------------------
    fun fetchAndShowFacebookInter(
        context: Context,
        onDismissed: () -> Unit,
        onFail: () -> Unit
    ) {
        val pref = SpotsLocker.getInstance(context)
        val isLoader = WholeDisplayWheel.isEnabled(context)
        val fbId = pref.getString("faceB_InterAds") ?: return onFail()

        context.safeLog("facebook_inter_load_start")
        context.recordAdEvent("interstitial", "req", network = "facebook")

        val inter = com.facebook.ads.InterstitialAd(context, fbId)

        // ⬅ FULLSCREEN LOADER (only if Activity)
        // Show loader only if isLoader == true
        if (context is Activity) WholeDisplayWheel.show(context, isLoader)

        inter.loadAd(
            inter.buildLoadAdConfig()
                .withAdListener(object : InterstitialAdListener {

                    override fun onAdLoaded(ad: Ad?) {
                        context.safeLog("facebook_inter_loaded")
                        context.recordAdEvent("interstitial", "loaded", network = "facebook")
                        if (context is Activity) WholeDisplayWheel.hide()
                        try {
                            inter.show()
                            context.safeLog("facebook_inter_show")
                        } catch (e: Exception) {
                            context.safeLog("facebook_inter_show_exception")
                            onFail()
                        }
                    }

                    override fun onError(ad: Ad?, error: com.facebook.ads.AdError?) {
                        context.safeLog("facebook_inter_error_${error?.errorCode}")
                        context.recordAdLoadError("interstitial", error?.errorCode ?: -1, network = "facebook")
                        if (context is Activity) WholeDisplayWheel.hide()
                        onFail()
                    }

                    override fun onInterstitialDismissed(ad: Ad?) {
                        context.safeLog("facebook_inter_dismiss")
                        NavPressTracker.fullScreenAdShowing = false
                        context.recordAdEvent("interstitial", "close", network = "facebook")
                        if (context is Activity) WholeDisplayWheel.hide()
                        onDismissed()
                    }

                    override fun onInterstitialDisplayed(ad: Ad?) {
                        context.safeLog("facebook_inter_displayed")
                        NavPressTracker.fullScreenAdShowing = true
                        context.recordAdEvent("interstitial", "show", network = "facebook")

                        if (context is Activity) WholeDisplayWheel.hide()
                    }

                    override fun onAdClicked(ad: Ad?) {
                        context.safeLog("facebook_inter_clicked")
                    }

                    override fun onLoggingImpression(ad: Ad?) {
                        context.safeLog("facebook_inter_impression")
                        context.recordAdEvent("interstitial", "impression", network = "facebook")
                    }

                }).build()
        )
    }

    private fun presentCustomAfterFacebookFail(
        activity: Activity,
        pref: SpotsLocker,
        safeClose: (String) -> Unit
    ) {
        activity.safeLog("custom_after_fb_fail")

        if (pref.getBoolean("IsCustomADS"))
            enterDirectLink(activity) { safeClose("fb_fail_custom") }
        else safeClose("fb_fail_no_custom")
    }

    // ----------------------------------------------------------------------
    // SAFE LOG WRAPPER
    // ----------------------------------------------------------------------
    private fun Context.safeLog(event: String) {
        try {
            recordKeyEvent(event)
            Log.d("InterADsLog", event)
        } catch (_: Exception) {
        }
    }
}
