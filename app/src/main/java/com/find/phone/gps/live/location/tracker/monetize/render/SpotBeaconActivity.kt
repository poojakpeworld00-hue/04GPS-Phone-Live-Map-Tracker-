package com.find.phone.gps.live.location.tracker.monetize.render

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import com.facebook.FacebookSdk
import com.facebook.LoggingBehavior
import com.facebook.ads.Ad
import com.facebook.ads.InterstitialAdListener
import com.facebook.appevents.AppEventsLogger
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.FormError
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.lighthouse.push.Attribution
import io.lighthouse.push.LightHouse
import com.find.phone.gps.live.location.tracker.monetize.model.SpotSort
import com.find.phone.gps.live.location.tracker.monetize.model.UponPayloadPrepared
import com.find.phone.gps.live.location.tracker.monetize.model.getLocationFromIP
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotIncomeGauge
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotProfileAbsorb
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSeatSpots
import com.find.phone.gps.live.location.tracker.monetize.logic.CloudProfileCharter
import com.find.phone.gps.live.location.tracker.monetize.logic.GmsCellSpotsAssentBook
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverRear
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverStandard
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.consent.ConsentDriver
import com.find.phone.gps.live.location.tracker.consent.ConsentFeed
import com.find.phone.gps.live.location.tracker.consent.DisplayMatcher
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker
import com.find.phone.gps.live.location.tracker.helpers.enactNativeAdTheme
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker.THEME_DARK
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker.THEME_LIGHT
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker.THEME_SYSTEM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

open class SpotBeaconActivity : AppCompatActivity() {
    private var customTabsSession: CustomTabsSession? = null
    private var customTabsClient: CustomTabsClient? = null
    var isCustomTabOpened = false
    var isCloseHandled = false
    var onCustomTabClosed: (() -> Unit)? = null
    private var activity: Activity? = null
    private var onGetData: UponPayloadPrepared? = null
    private var isSplash: Boolean? = false
    private val isMobileAdsInitialized = AtomicBoolean(false)
    private val isMobileAdsInitializeCalled = AtomicBoolean(false)
    private val referrerHandoffDone = AtomicBoolean(false)
    private var isGoogleAdsEnabled = true
    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor()

    companion object {
        const val COUNTRY_LIST_ALL = "all"

        const val APPOPEN_TAG = "AppOpenAd"

        const val CONFIG_TAG = "GetDataConfig"

        const val DEBUG_AUDIENCE_MARKETING = true

        const val FORCE_AUDIENCE_IN_RELEASE = false

        val isAudienceForced: Boolean
            get() =BuildConfig.DEBUG || FORCE_AUDIENCE_IN_RELEASE

        const val ATTRIBUTION_WAIT_MS = 5_000L
    }

    open fun getData(
        act: Activity, isSplsh: Boolean? = false, onData: UponPayloadPrepared
    ) {
        activity = act
        onGetData = onData
        isSplash = isSplsh

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!isInternetConnected(this@SpotBeaconActivity)) {
                    withContext(Dispatchers.Main) {
                        onGetData?.onError()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    try {
                        val googleMobileAdsConsentManager =
                            GmsCellSpotsAssentBook.getInstance(
                                applicationContext
                            )

                        googleMobileAdsConsentManager.gatherConsent(this@SpotBeaconActivity) { consentError: FormError? ->

                            if (consentError != null) {
                                Log.w(
                                    "SpotBeaconActivity",
                                    "Consent error: ${consentError.message} — proceeding without ads consent"
                                )
                            }
                            initializeMobileAdsSdk()
                            if (googleMobileAdsConsentManager.isPrivacyOptionsRequired) {
                                invalidateOptionsMenu()
                            }
                        }

                        if (googleMobileAdsConsentManager.canRequestAds()) {
                            initializeMobileAdsSdk()
                        }
                    } catch (e: Exception) {
                        Log.e("SpotBeaconActivity", "Consent manager error", e)
                        initializeMobileAdsSdk()
                        withContext(Dispatchers.Main) {
                            onGetData?.onError()
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onGetData?.onError()
                }
            }
        }
    }

    fun isInternetConnected(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }
    }

    private fun initializeMobileAdsSdk() {
        if (isMobileAdsInitializeCalled.getAndSet(true)) {
            return
        }

        if (!isMobileAdsInitialized.getAndSet(true)) {
            backgroundExecutor.execute {
                try {
                    activity?.let {
                        isGoogleAdsEnabled = true
                        MobileAds.initialize(it) {}
                        if (BuildConfig.DEBUG) Log.d("SpotBeaconActivity", "MobileAds initialized")
                    }
                } catch (e: Exception) {
                    isGoogleAdsEnabled = false
                    Log.e("SpotBeaconActivity", "Failed to initialize MobileAds", e)
                    isMobileAdsInitialized.set(false)
                }
            }
        }

        val remoteConfig = FirebaseRemoteConfig.getInstance()
        activity?.let { host ->

            CloudProfileCharter.withSettings(remoteConfig) {
                remoteConfig.fetchAndActivate().addOnCompleteListener(host) { task ->
                    if (task.isSuccessful) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            setResponceInPref(remoteConfig)
                        }
                    } else {
                        onGetData?.onError()
                    }
                }
            }
        }
    }

    private fun setResponceInPref(remoteConfig: FirebaseRemoteConfig) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (blobKey, configString) = SpotProfileAbsorb.pullBlob(remoteConfig)

                if (configString.isEmpty()) {
                    Log.w(
                        CONFIG_TAG,
                        "$blobKey is empty → nothing ingested (using cached/default prefs)"
                    )
                    verifyInstallerRefere()
                    return@launch
                }

                val response = JSONObject(configString)
                val adsPref = SpotsLocker.getInstance(this@SpotBeaconActivity)

                val isSplit = response.has("marketing") || response.has("organic")
                val onMarketing = adsPref.getBoolean("OnMaketing")
                if (BuildConfig.DEBUG) Log.d(
                    CONFIG_TAG,
                    "fetched $blobKey (${configString.length} chars) → audienceSplit=$isSplit, " +
                            "OnMaketing=$onMarketing "
                )

                adsPref.putString("GET_DATA_RAW", configString)
                adsPref.putBoolean("__cfg_audience_split", isSplit)

                ingestConfig(this@SpotBeaconActivity, audienceRoot(response, onMarketing))

                verifyInstallerRefere()

            } catch (e: Exception) {
                Log.e("SpotBeaconActivity", "Failed to update ad preferences: ${e.message}")

                runCatching { verifyInstallerRefere() }
            }
        }
    }

    private fun ingestConfig(context: Context, root: JSONObject) {
        val fb = SpotProfileAbsorb.ingest(context, root)
        if (fb.usable) setApplication(fb.appId, fb.clientToken)
    }

    private fun audienceRoot(response: JSONObject, isMarketing: Boolean): JSONObject =
        SpotProfileAbsorb.audienceRoot(response, isMarketing)

    private fun verifyInstallerRefere() {
        if (activity!!.getPreferences(MODE_PRIVATE).getBoolean("isReferrerDone", false)) {
            onReferrerSettled()
            return
        }

        val referrerClient = InstallReferrerClient.newBuilder(activity).build()
        backgroundExecutor.execute(Runnable { getInstallReferrerFromClient(referrerClient) })
    }

    private suspend fun settleMarketingAudience(): Boolean {
        if (isAudienceForced) {
            if (!BuildConfig.DEBUG) {
                Log.w(
                    CONFIG_TAG,
                    "FORCE_AUDIENCE_IN_RELEASE is ON — audience pinned to " +
                            "${if (DEBUG_AUDIENCE_MARKETING) "MARKETING" else "ORGANIC"}, " +
                            "real attribution ignored. Turn it off before shipping."
                )
            }
            return DEBUG_AUDIENCE_MARKETING
        }
        return withTimeoutOrNull(ATTRIBUTION_WAIT_MS) {
            suspendCancellableCoroutine { cont ->
                LightHouse.resolveAttribution { attribution ->
                    if (cont.isActive) cont.resume(attribution == Attribution.PAID)
                }
            }
        } ?: false
    }

    private fun onReferrerSettled() {
        if (referrerHandoffDone.compareAndSet(false, true)) {
            funOnAdsLoad()
        }
    }

    private fun funOnAdsLoad() {
        activity?.let { activity ->
            val adsPreference = SpotsLocker.getInstance(activity)
            lifecycleScope.launch(Dispatchers.IO) {
                val location = getLocationFromIP()
                location?.countryCode?.takeIf { it.isNotBlank() }?.let { iso ->
                    val prefs = LockerBook(activity)
                    if (prefs.homeCountryIso.isBlank()) {
                        prefs.homeCountryIso = iso
                        if (BuildConfig.DEBUG) Log.d(
                            "LocationCheck",
                            "Country code set from IP: $iso"
                        )
                    }
                }

                val isMarketingOn = settleMarketingAudience()
                if (BuildConfig.DEBUG) {
                    Log.d(CONFIG_TAG, "audience → ${if (isMarketingOn) "MARKETING" else "ORGANIC"}")
                }
                adsPreference.putBoolean("OnMaketing", isMarketingOn)

                val isSplitConfig = adsPreference.getBoolean("__cfg_audience_split")
                if (isSplitConfig) {
                    val raw = adsPreference.getString("GET_DATA_RAW", "")
                    if (!raw.isNullOrBlank()) runCatching {
                        ingestConfig(activity, audienceRoot(JSONObject(raw), isMarketingOn))
                    }
                }

                val countryEnableKey = "Iscountry_Counter"
                val countryListKey = "CountryList_Counter_NShow"
                if (BuildConfig.DEBUG) Log.d(
                    "LocationCheck",
                    "install=${if (isMarketingOn) "MARKETING" else "ORGANIC"} → using $countryEnableKey / $countryListKey"
                )

                if (adsPreference.getBoolean(countryEnableKey)) {
                    val blockedLocations = (adsPreference.getString(countryListKey, "") ?: "")
                        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

                    val blocksEveryone =
                        blockedLocations.any { it.equals(COUNTRY_LIST_ALL, ignoreCase = true) }

                    location?.let { loc ->
                        if (BuildConfig.DEBUG) {
                            Log.d("LocationCheck", "=== Location Info ===")
                            Log.d("LocationCheck", "Country: ${loc.country}")
                            Log.d("LocationCheck", "Region: ${loc.regionName}")
                            Log.d("LocationCheck", "City: ${loc.city}")
                        }
                        adsPreference.userCountry = loc.country.orEmpty()
                        adsPreference.userRegion = loc.regionName.orEmpty()
                        adsPreference.userCity = loc.city.orEmpty()
                    }

                    val isAllowed = location?.let { loc ->
                        blockedLocations.any { blocked ->
                            blocked.equals(loc.country, ignoreCase = true) ||
                                    blocked.equals(loc.regionName, ignoreCase = true) ||
                                    blocked.equals(loc.city, ignoreCase = true)
                        }
                    } ?: false

                    adsPreference.isNShowLocation = blocksEveryone || isAllowed

                    when {
                        blocksEveryone -> {
                            if (BuildConfig.DEBUG) Log.d(
                                "LocationCheck",
                                "\uD83C\uDF0D $countryListKey=\"$COUNTRY_LIST_ALL\" → every location blocked"
                            )
                        }

                        location == null ->
                            if (BuildConfig.DEBUG) Log.w(
                                "LocationCheck",
                                "⚠️ Location not available"
                            )

                        isAllowed -> {
                            if (BuildConfig.DEBUG) Log.d(
                                "LocationCheck",
                                "✅ Location IN list ($countryListKey)"
                            )
                        }

                        else -> if (BuildConfig.DEBUG) Log.d(
                            "LocationCheck",
                            "❌ Location NOT in list ($countryListKey)"
                        )
                    }
                } else {
                    adsPreference.isNShowLocation = false
                    if (BuildConfig.DEBUG) Log.d(
                        "LocationCheck",
                        "Country check is disabled in preferences"
                    )
                }

                if (isMarketingOn && !isSplitConfig) {
                    with(adsPreference) {
                        putInt("InterCounter", getInt("MarketInterCounter"))
                        putInt("InterBackCounter", getInt("MarketBackCounter"))
                        putInt("MidNativeCounter", getInt("MarketNativeCounter"))
                        putInt("NativeCounter", getInt("MarketNativeCounter"))
                        putInt("BannerCounter", getInt("MarketBannerCounter"))
                        putInt("AppopenCounter", getInt("MarketAppopenCounter"))
                        putBoolean("IsBack", true)
                        putString("DirectLink", getString("MarketLink"))
                        putBoolean("is_intro", true)
                    }
                }

                activity.enactNativeAdTheme()

                launch {
                    val isAdsOn = adsPreference.getBoolean("IsAdsON")

                    withContext(Dispatchers.Main) {
                        if (isAdsOn && isSplash == false) {
                            Log.d(APPOPEN_TAG, "isSplash=false → no splash AppOpen here")
                            onGetData?.onSuccess()
                            return@withContext
                        }

                        primeSplashPermissions(activity) {
                            if (!isAdsOn) {
                                Log.w(
                                    APPOPEN_TAG,
                                    "IsAdsON=false → ads disabled, no AppOpen, continuing to app"
                                )
                                onGetData?.onSuccess()
                            } else {
                                if (isGoogleAdsEnabled) {
                                    EmbeddedTeaser().fetchNativeADs(activity)
                                    EmbeddedTeaserRibbon().fetchNativeBannerAds(activity)
                                    TakeoverStandard().fetchInterAds(activity)
                                    TakeoverRear().fetchBackInterAds(activity)
                                }

                                val isSplashAdsEnabled = adsPreference.getBoolean("is_splash_ads")
                                Log.d(
                                    APPOPEN_TAG,
                                    "gate → IsAdsON=$isAdsOn, isSplash=$isSplash, is_splash_ads=$isSplashAdsEnabled, " +
                                            "IsAdType=${adsPreference.getString("IsAdType")}, appopenId=${
                                                adsPreference.getString(
                                                    "googleAppopen"
                                                )
                                            }"
                                )
                                if (!isSplashAdsEnabled) {
                                    Log.w(
                                        APPOPEN_TAG,
                                        "is_splash_ads=false → skipping splash ad entirely, continuing to app"
                                    )
                                    onGetData?.onSuccess()
                                } else {
                                    prefetchAds(adsPreference, activity) {
                                        presentPreloadedAd(activity, adsPreference) {
                                            onGetData?.onSuccess()
                                        }
                                    }
                                }
                            }
                        }

                    }
                }

            }
        }
    }

    private fun primeSplashPermissions(activity: Activity, onDone: () -> Unit) {
        runCatching { ConsentFeed.reload() }
        val screen = activity::class.java.simpleName
        val prime: (String, () -> Unit) -> Unit = { key, next ->
            if (targetsScreen(key, screen)) ConsentDriver.request(activity, key) { next() }
            else next()
        }
        prime("notification") {
            onDone()
        }
    }

    private fun targetsScreen(key: String, screen: String): Boolean {
        val rule = ConsentFeed.rules().firstOrNull { it.key == key } ?: return true
        val targets = rule.activities.any { DisplayMatcher.matches(it, screen) }
        if (!targets) Log.d("ConsentDriver", "'$key' not configured for $screen — skipped on splash")
        return targets
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    fun prefetchAds(adsPreference: SpotsLocker, activity: Activity, onComplete: () -> Unit) {
        wireCustomTabs(activity)
        val adType = SpotSort.fromString(adsPreference.getString("IsAdType"))
        Log.d(APPOPEN_TAG, "preload() → IsAdType=$adType, googleAdsEnabled=$isGoogleAdsEnabled")
        when (adType) {
            SpotSort.GOOGLE -> {
                if (isGoogleAdsEnabled) {
                    val showInterstitialOnSplash = adsPreference.getBoolean("is_splash_inter_show")
                    if (showInterstitialOnSplash) {
                        Log.d(
                            APPOPEN_TAG,
                            "preload → is_splash_inter_show=true → loading splash INTERSTITIAL instead of AppOpen"
                        )
                        fetchGoogleInterstitialWithFallback(activity, adsPreference, onComplete)
                    } else {
                        Log.d(APPOPEN_TAG, "preload → is_splash_inter_show=false → loading APPOPEN")
                        fetchAppOpenAdWithFallback(activity, adsPreference, onComplete)
                    }
                } else {
                    Log.w(
                        APPOPEN_TAG,
                        "preload → Google ads disabled (init failed) → Facebook fallback"
                    )
                    fetchFacebookFallback(activity, adsPreference, onComplete)
                }
            }

            SpotSort.FACEBOOK -> {
                if (adsPreference.getBoolean("IsFail_FB")) {
                    val fbId = adsPreference.getString("faceB_InterAds")!!
                    fetchFacebookInterstitial(
                        activity,
                        fbId,
                        onLoaded = { onComplete() },
                        onFailed = { onComplete() },
                        onDismissed = {
                            onComplete()
                        }
                    )
                } else {
                    onComplete()
                }
            }

            SpotSort.CUSTOM, SpotSort.UNKNOWN -> onComplete()
        }
    }

    fun presentPreloadedAd(activity: Activity, adsPreference: SpotsLocker, onDismissed: () -> Unit) {
        if (HomeSeatSpots.hasOwnFlow(activity, "splash")) {
            if (!HomeSeatSpots.placementEnabled(activity, "splash")) return onDismissed()
            Log.d(APPOPEN_TAG, "showPreloaded() → splash flow (splash_*)")
            HomeSeatSpots.showInterstitial(activity, "splash") { onDismissed() }
            return
        }
        when (SpotSort.fromString(adsPreference.getString("IsAdType"))) {
            SpotSort.GOOGLE -> {
                Log.d(
                    APPOPEN_TAG,
                    "showPreloaded() → appOpenReady=${appOpenAd != null}, interstitialReady=${interstitialAd != null}"
                )
                if (isGoogleAdsEnabled && (appOpenAd != null || interstitialAd != null)) {
                    if (appOpenAd != null) {
                        presentAppOpenAd(activity) { onDismissed() }
                    } else if (interstitialAd != null) {
                        presentGoogleInterstitial(activity) {
                            onDismissed()
                        }
                    }
                } else {
                    if (adsPreference.getBoolean("IsFail_FB")) {
                        val fbId = adsPreference.getString("faceB_InterAds")
                        if (!fbId.isNullOrEmpty() && fbInterstitial != null) {
                            presentFacebookInterstitial { onDismissed() }
                        } else if (adsPreference.getBoolean("IsCustomADS")) {
                            fireCustomAdLink(
                                activity, adsPreference.getString("DirectLink")!!
                            ) {
                                onDismissed()
                            }
                        } else {
                            onDismissed()
                        }
                    } else if (adsPreference.getBoolean("IsCustomADS")) {
                        fireCustomAdLink(
                            activity, adsPreference.getString("DirectLink")!!
                        ) {
                            onDismissed()
                        }
                    } else {
                        onDismissed()
                    }
                }
            }

            SpotSort.FACEBOOK -> {
                if (fbInterstitial != null) presentFacebookInterstitial { onDismissed() }
                else onDismissed()
            }

            SpotSort.CUSTOM, SpotSort.UNKNOWN -> {
                if (adsPreference.getBoolean("IsCustomADS")) {
                    fireCustomAdLink(
                        activity, adsPreference.getString("DirectLink")!!
                    ) {
                        onDismissed()
                    }
                } else {
                    onDismissed()
                }
            }
        }
    }

    private var appOpenAd: AppOpenAd? = null
    private fun fetchGoogleInterstitialWithFallback(
        activity: Activity, adsPreference: SpotsLocker, onComplete: () -> Unit
    ) {
        val googleId = adsPreference.getString("googleS_Inter") ?: run { onComplete(); return }
        fetchGoogleInterstitial(activity, googleId, onLoaded = { onComplete() }, onFailed = {
            fetchFacebookFallback(activity, adsPreference, onComplete)
        })
    }

    private fun fetchAppOpenAdWithFallback(
        activity: Activity, adsPreference: SpotsLocker, onComplete: () -> Unit
    ) {
        val appOpenId = adsPreference.getString("googleAppopen")
        if (appOpenId.isNullOrBlank()) {
            Log.w(
                APPOPEN_TAG,
                "no/blank 'googleAppopen' unit id in Remote Config → skipping AppOpen, continuing"
            )
            onComplete(); return
        }
        fetchAppOpenAd(activity, appOpenId, onLoaded = { onComplete() }, onFailed = {
            Log.w(APPOPEN_TAG, "AppOpen load failed → falling back to Google interstitial")
            fetchGoogleInterstitialWithFallback(activity, adsPreference, onComplete)
        })
    }

    private fun fetchFacebookFallback(
        activity: Activity, adsPreference: SpotsLocker, onComplete: () -> Unit
    ) {
        if (adsPreference.getBoolean("IsFail_FB")) {
            val fbId = adsPreference.getString("faceB_InterAds")
            if (!fbId.isNullOrEmpty()) {
                fetchFacebookInterstitial(
                    activity,
                    fbId,
                    onLoaded = { onComplete() },
                    onFailed = { onComplete() },
                    onDismissed = { onComplete() })
            } else onComplete()
        } else onComplete()
    }

    fun fetchAppOpenAd(
        activity: Activity,
        adUnitId: String,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null
    ) {
        Log.d(APPOPEN_TAG, "load() → requesting AppOpen, id=$adUnitId")
        AppOpenAd.load(
            activity,
            adUnitId,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    Log.e(
                        APPOPEN_TAG,
                        "load FAILED → code=${loadAdError.code}, domain=${loadAdError.domain}, " +
                                "message=${loadAdError.message}"
                    )
                    appOpenAd = null
                    CoreOpenSpotBook.isShowingAd = false
                    onFailed?.invoke()
                }

                override fun onAdLoaded(ad: AppOpenAd) {
                    Log.d(APPOPEN_TAG, "load SUCCESS → AppOpen ad is ready to show")
                    appOpenAd = ad
                    onLoaded?.invoke()
                }
            })
    }

    fun presentAppOpenAd(activity: Activity, onDismissed: (() -> Unit)? = null) {
        appOpenAd?.let { ad ->
            Log.d(APPOPEN_TAG, "show() → displaying AppOpen ad")
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    onDismissed?.invoke()
                    CoreOpenSpotBook.isShowingAd = false
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    onDismissed?.invoke()
                }

                override fun onAdShowedFullScreenContent() {
                    CoreOpenSpotBook.isShowingAd = true
                }
            }

            activity.recordKeyEvent("AppOpen_Loaded")


            appOpenAd?.setOnPaidEventListener {
                SpotIncomeGauge.recordPaidEvent(activity, it, "app_open", ad.adUnitId, ad.responseInfo, "splash")
            }
            ad.show(activity)
        } ?: run {
            Log.w(APPOPEN_TAG, "show() → AppOpen ad is null (not loaded in time) → skipping show")
            onDismissed?.invoke()
        }
    }

    private var interstitialAd: InterstitialAd? = null

    fun fetchGoogleInterstitial(
        activity: Activity,
        adUnitId: String,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null
    ) {
        InterstitialAd.load(
            activity, adUnitId, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    onLoaded?.invoke()
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    interstitialAd = null
                    onFailed?.invoke()
                }
            })
    }

    fun presentGoogleInterstitial(activity: Activity, onDismissed: (() -> Unit)? = null) {
        interstitialAd?.let { ad ->
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    onDismissed?.invoke()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    onDismissed?.invoke()
                }

                override fun onAdShowedFullScreenContent() {
                }
            }

            activity.recordKeyEvent("Interstitial_Splash_Loaded")


            interstitialAd?.setOnPaidEventListener {
                SpotIncomeGauge.recordPaidEvent(activity, it, "interstitial", ad.adUnitId, ad.responseInfo, "splash")
            }
            ad.show(activity)
        } ?: run { onDismissed?.invoke() }
    }

    private var fbInterstitial: com.facebook.ads.InterstitialAd? = null

    fun fetchFacebookInterstitial(
        activity: Activity,
        adUnitId: String,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
        onDismissed: (() -> Unit)? = null
    ) {
        fbInterstitial = com.facebook.ads.InterstitialAd(activity, adUnitId)
        fbInterstitial?.loadAd(
            fbInterstitial!!.buildLoadAdConfig().withAdListener(object : InterstitialAdListener {
                override fun onInterstitialDisplayed(ad: Ad?) {}
                override fun onInterstitialDismissed(ad: Ad?) {
                    onDismissed?.invoke()
                }

                override fun onError(ad: Ad?, adError: com.facebook.ads.AdError?) {
                    onFailed?.invoke()
                }

                override fun onAdLoaded(ad: Ad?) {
                    onLoaded?.invoke()
                }

                override fun onAdClicked(ad: Ad?) {}
                override fun onLoggingImpression(ad: Ad?) {}
            }).build()
        )
    }

    fun presentFacebookInterstitial(onDismissed: (() -> Unit)? = null) {
        val ad = fbInterstitial

        if (ad != null && ad.isAdLoaded) {
            ad.show()
        } else {
            onDismissed?.invoke()
        }
    }

    private fun wireCustomTabs(activity: Activity) {
        if (customTabsClient != null) return

        CustomTabsClient.bindCustomTabsService(
            activity, "com.android.chrome", object : CustomTabsServiceConnection() {
                override fun onCustomTabsServiceConnected(
                    name: ComponentName, client: CustomTabsClient
                ) {
                    customTabsClient = client
                    customTabsSession = client.newSession(object : CustomTabsCallback() {
                        override fun onNavigationEvent(event: Int, extras: Bundle?) {
                            if ((event == CustomTabsCallback.TAB_HIDDEN || event == CustomTabsCallback.NAVIGATION_ABORTED) && isCustomTabOpened) {
                                processCustomTabClose()
                            }
                        }
                    })
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    customTabsClient = null
                    customTabsSession = null
                }
            })
    }

    private fun processCustomTabClose() {
        if (isFinishing || isDestroyed) return
        if (!isCloseHandled) {
            isCloseHandled = true
            isCustomTabOpened = false
            onCustomTabClosed?.invoke()
        }
    }

    override fun onDestroy() {
        try {
            customTabsClient = null
            customTabsSession = null
            onCustomTabClosed = null
            activity = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    private fun fireCustomAdLink(activity: Activity, url: String, onClosed: () -> Unit) {
        wireCustomTabs(activity)

        isCustomTabOpened = true
        isCloseHandled = false
        onCustomTabClosed = { onClosed() }

        val customTabsIntent = CustomTabsIntent.Builder(customTabsSession).setShowTitle(true)
            .setToolbarColor(ContextCompat.getColor(activity, R.color.black)).build()

        try {
            customTabsIntent.launchUrl(activity, Uri.parse(url))
        } catch (e: Exception) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            processCustomTabClose()
        }
    }

    override fun onResume() {
        super.onResume()
        processCustomTabClose()
    }

    fun getInstallReferrerFromClient(referrerClient: InstallReferrerClient) {
        referrerClient.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                when (responseCode) {
                    InstallReferrerClient.InstallReferrerResponse.OK -> {
                        var response: ReferrerDetails? = null
                        try {
                            response = referrerClient.installReferrer

                            val ref = response.installReferrer
                            activity?.let {
                                SpotsLocker.getInstance(it).putString("FinalString", ref)
                            }

                            onReferrerSettled()

                            activity?.getPreferences(MODE_PRIVATE)?.edit()?.apply {
                                putBoolean("isReferrerDone", true)
                                apply()
                            }
                        } catch (e: RemoteException) {
                            onReferrerSettled()
                        } finally {
                            referrerClient.endConnection()
                        }

                    }

                    else -> onReferrerSettled()
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                onReferrerSettled()
            }
        })
    }

    private fun setApplication(fbAppId: String, fbClientToken: String) {
        FacebookSdk.setApplicationId(fbAppId)
        FacebookSdk.setClientToken(fbClientToken)
        activity?.let { FacebookSdk.sdkInitialize(it) }

        FacebookSdk.setAutoInitEnabled(true)
        FacebookSdk.fullyInitialize()
        FacebookSdk.setAutoLogAppEventsEnabled(true)
        FacebookSdk.addLoggingBehavior(LoggingBehavior.APP_EVENTS)
        val logger = activity?.let { AppEventsLogger.newLogger(it) }
        logger?.applicationId
    }
}
