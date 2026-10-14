package com.find.my.phone.gps.location.tracker.adcast.presentation

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
import com.find.my.phone.gps.location.tracker.adcast.data.AdKind
import com.find.my.phone.gps.location.tracker.adcast.data.OnDataReady
import com.find.my.phone.gps.location.tracker.adcast.data.getLocationFromIP
import com.find.my.phone.gps.location.tracker.adcast.domain.AdRevenueMeter
import com.find.my.phone.gps.location.tracker.adcast.domain.AdConfigIngest
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.adcast.domain.LauncherPlacementAds
import com.find.my.phone.gps.location.tracker.adcast.domain.RemoteConfigPolicy
import com.find.my.phone.gps.location.tracker.adcast.domain.GoogleMobileAdsConsentRegistry
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.adcast.presentation.oninterAds.InterstitialBack
import com.find.my.phone.gps.location.tracker.adcast.presentation.oninterAds.InterstitialNormal
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.data.VaultRegistry
import com.find.my.phone.gps.location.tracker.permission.AccessEngine
import com.find.my.phone.gps.location.tracker.permission.AccessSource
import com.find.my.phone.gps.location.tracker.permission.ScreenMatcher
import com.find.my.phone.gps.location.tracker.util.AppVault
import com.find.my.phone.gps.location.tracker.util.applyNativeAdTheme
import com.find.my.phone.gps.location.tracker.util.AppVault.THEME_DARK
import com.find.my.phone.gps.location.tracker.util.AppVault.THEME_LIGHT
import com.find.my.phone.gps.location.tracker.util.AppVault.THEME_SYSTEM
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

open class AdBeaconActivity : AppCompatActivity() {
    private var customTabsSession: CustomTabsSession? = null
    private var customTabsClient: CustomTabsClient? = null
    var isCustomTabOpened = false
    var isCloseHandled = false
    var onCustomTabClosed: (() -> Unit)? = null
    private var activity: Activity? = null
    private var onGetData: OnDataReady? = null
    private var isSplash: Boolean? = false
    private val isMobileAdsInitialized = AtomicBoolean(false)
    private val isMobileAdsInitializeCalled = AtomicBoolean(false)
    private val referrerHandoffDone = AtomicBoolean(false)
    private var isGoogleAdsEnabled = true
    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor()

    // Not private: GpsTrackerApp mirrors DEBUG_AUDIENCE_MARKETING into the LightHouse SDK
    // and reuses ATTRIBUTION_WAIT_MS, so the SDK's audience read, the disclosure gate and
    // the config half under test all agree on a debug build.
    companion object {
        /** Sentinel in CountryList_Counter_NShow meaning "every location". */
        const val COUNTRY_LIST_ALL = "all"

        /** One grep-able tag for the whole splash AppOpen/interstitial load+show path. */
        const val APPOPEN_TAG = "AppOpenAd"

        /** One grep-able tag for the getData Remote Config → prefs ingestion path. */
        const val CONFIG_TAG = "GetDataConfig"

        /**
         * Which audience a DEBUG build runs as — flip this one line to test the other side.
         * `true` = marketing, `false` = organic.
         *
         * A build installed from Studio or adb has no install referrer and no LightHouse
         * attribution, so it always resolves to organic on its own and the `marketing` half
         * of the config could never be exercised on a test device. Release builds ignore
         * this entirely and keep using the real attribution.
         */
        const val DEBUG_AUDIENCE_MARKETING = true

        /**
         * Applies [DEBUG_AUDIENCE_MARKETING] to **release** builds too, instead of letting
         * real attribution decide.
         *
         * A signed APK is the only way to exercise the release config — R8, the real ad
         * units, the shrunk resources — but a release APK installed by adb or a direct
         * download has no Play install referrer, so LightHouse settles it organic and the
         * `marketing` half of the config can never be reached on a test device. This forces
         * both halves of the decision ([resolveMarketingAudience] here and the SDK's own
         * install source in `GpsTrackerApp`) onto the chosen side so they agree.
         *
         * **Must be `false` in anything that reaches Play.** Left `true`, every real install
         * is pinned to one audience and genuine attribution is never consulted. The splash
         * logs a warning on every launch while it is on, so a build that ships by accident
         * says so in logcat.
         */
        const val FORCE_AUDIENCE_IN_RELEASE = false

        /**
         * True when the audience is being forced rather than resolved — either because this
         * is a debug build, or because [FORCE_AUDIENCE_IN_RELEASE] is still on.
         */
        val isAudienceForced: Boolean
            get() =BuildConfig.DEBUG || FORCE_AUDIENCE_IN_RELEASE

        /**
         * How long the audience gate waits for LightHouse's install-referrer verdict before
         * settling for what it has. First launch only — the SDK caches it afterwards.
         */
        const val ATTRIBUTION_WAIT_MS = 5_000L
    }

    open fun getData(
        act: Activity, isSplsh: Boolean? = false, onData: OnDataReady
    ) {
        activity = act
        onGetData = onData
        isSplash = isSplsh

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // We defer MobileAds.initialize() until after consent is obtained.

                if (!isInternetConnected(this@AdBeaconActivity)) {
                    withContext(Dispatchers.Main) {
                        onGetData?.onError()
                    }
                    return@launch
                }

                // Consent and SDK initialization
                withContext(Dispatchers.Main) {
                    try {
                        val googleMobileAdsConsentManager =
                            GoogleMobileAdsConsentRegistry.getInstance(
                                applicationContext
                            )

                        googleMobileAdsConsentManager.gatherConsent(this@AdBeaconActivity) { consentError: FormError? ->
                            // Always drive the flow forward once consent gathering
                            // completes. initializeMobileAdsSdk() is what kicks off
                            // remote config → prefs → permissions → navigation, and
                            // it's idempotent. If we only called it when canRequestAds
                            // is true, a consent failure (e.g. "Error making request"
                            // on a slow network) would silently dead-end the splash —
                            // no permission prompt, no navigation, hangs forever.
                            if (consentError != null) {
                                Log.w(
                                    "AdBeaconActivity",
                                    "Consent error: ${consentError.message} — proceeding without ads consent"
                                )
                            }
                            initializeMobileAdsSdk()
                            if (googleMobileAdsConsentManager.isPrivacyOptionsRequired) {
                                invalidateOptionsMenu()
                            }
                        }

                        // Fast path: if consent is already available, start the SDK +
                        // downstream flow immediately instead of waiting on the callback.
                        if (googleMobileAdsConsentManager.canRequestAds()) {
                            initializeMobileAdsSdk()
                        }
                    } catch (e: Exception) {
                        Log.e("AdBeaconActivity", "Consent manager error", e)
                        initializeMobileAdsSdk() // Fallback
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

        // For Android 10 (API level 29) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            // For older Android versions
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }
    }

    private fun initializeMobileAdsSdk() {
        if (isMobileAdsInitializeCalled.getAndSet(true)) {
            return
        }

        // Initialize MobileAds on a background thread after consent
        if (!isMobileAdsInitialized.getAndSet(true)) {
            backgroundExecutor.execute {
                try {
                    activity?.let {
                        isGoogleAdsEnabled = true
                        MobileAds.initialize(it) {}
                        if (BuildConfig.DEBUG) Log.d("AdBeaconActivity", "MobileAds initialized")
                    }
                } catch (e: Exception) {
                    isGoogleAdsEnabled = false
                    Log.e("AdBeaconActivity", "Failed to initialize MobileAds", e)
                    isMobileAdsInitialized.set(false)
                }
            }
        }

        val remoteConfig = FirebaseRemoteConfig.getInstance()
        activity?.let { host ->
            // Through withSettings, never straight to fetchAndActivate: the settings call is
            // async, and a fetch that starts before it lands runs on the SDK's default
            // 12-hour interval — it answers from cache and still reports success.
            RemoteConfigPolicy.withSettings(remoteConfig) {
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
        // Run everything in a background thread to prevent cold-start stutters and ANR
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (blobKey, configString) = AdConfigIngest.readBlob(remoteConfig)

                // An empty or missing blob (a parameter not yet published, a fetch that brought
                // nothing) must not strand the splash: nothing is ingested, and the chain carries
                // on with whatever an earlier run cached — or the code defaults on a first run.
                if (configString.isEmpty()) {
                    Log.w(
                        CONFIG_TAG,
                        "$blobKey is empty → nothing ingested (using cached/default prefs)"
                    )
                    checkInstallerRefere()
                    return@launch
                }

                val response = JSONObject(configString)
                val adsPref = AdsVault.getInstance(this@AdBeaconActivity)

                val isSplit = response.has("marketing") || response.has("organic")
                val onMarketing = adsPref.getBoolean("OnMaketing")
                if (BuildConfig.DEBUG) Log.d(
                    CONFIG_TAG,
                    "fetched $blobKey (${configString.length} chars) → audienceSplit=$isSplit, " +
                            "OnMaketing=$onMarketing "
                )

                // Persist the raw blob + whether it uses a top-level audience split,
                // so funOnAdsLoad can re-apply the correct audience once the install
                // referrer has resolved OnMaketing (not known on the first launch).
                adsPref.putString("GET_DATA_RAW", configString)
                adsPref.putBoolean("__cfg_audience_split", isSplit)

                // Top-level audience split: every key is read from
                // response.marketing / response.organic (chosen by OnMaketing). A
                // flat response (no wrapper) is used verbatim → legacy config is
                // unchanged.
                ingestConfig(this@AdBeaconActivity, audienceRoot(response, onMarketing))

                // Resolve the install referrer, then hand off to funOnAdsLoad. Must
                // run AFTER ingestConfig so funOnAdsLoad reads the freshly-persisted
                // config (GET_DATA_RAW / __cfg_audience_split / ad gates), not stale
                // or half-written values.
                checkInstallerRefere()

            } catch (e: Exception) {
                Log.e("AdBeaconActivity", "Failed to update ad preferences: ${e.message}")
                // Same reasoning: a malformed blob falls back to the cached config rather than
                // leaving the splash waiting on a callback that would never come.
                runCatching { checkInstallerRefere() }
            }
        }
    }

    /**
     * Delegates to [AdConfigIngest], then initialises the Facebook SDK, which needs an Activity
     * and so cannot live with the rest of the ingest.
     */
    private fun ingestConfig(context: Context, root: JSONObject) {
        val fb = AdConfigIngest.ingest(context, root)
        if (fb.usable) setApplication(fb.appId, fb.clientToken)
    }

    private fun audienceRoot(response: JSONObject, isMarketing: Boolean): JSONObject =
        AdConfigIngest.audienceRoot(response, isMarketing)



    private fun checkInstallerRefere() {
        if (activity!!.getPreferences(MODE_PRIVATE).getBoolean("isReferrerDone", false)) {
            onReferrerSettled()
            return
        }

        val referrerClient = InstallReferrerClient.newBuilder(activity).build()
        backgroundExecutor.execute(Runnable { getInstallReferrerFromClient(referrerClient) })
    }

    /**
     * The single exit from the install-referrer probe.
     *
     * Every path out of [getInstallReferrerFromClient] — resolved, unsupported, unavailable,
     * developer/permission error, or the service dropping before it ever finished — has to end
     * up here, because [funOnAdsLoad] is what writes the audience flag and re-ingests the right
     * half of the config. A path that quietly returns instead leaves the app on whichever
     * audience the pre-referrer ingest happened to pick.
     *
     * Guarded so the extra exits can never double-run the IP lookup and the re-ingest.
     */
    /**
     * True when this install should be served the `marketing` half of the ad config.
     *
     * The verdict comes from LightHouse, which holds its answer until the Play install
     * referrer resolves and never reports UNKNOWN — an install still unsettled when its own
     * wait elapses is reported organic. Capped again here so a callback that never arrives
     * cannot park [funOnAdsLoad], and with it the audience re-ingest, indefinitely; that
     * timeout also falls back to organic, matching the SDK's own failure rule.
     *
     * First launch only: the referrer is cached from the second launch on, so every later
     * call resolves immediately.
     */
    private suspend fun resolveMarketingAudience(): Boolean {
        if (isAudienceForced) {
            // Loud, and not behind a DEBUG check, precisely because the case worth catching is
            // a release build that still has FORCE_AUDIENCE_IN_RELEASE switched on.
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
            val adsPreference = AdsVault.getInstance(activity)
            lifecycleScope.launch(Dispatchers.IO) {

                // Fetch IP geo once. Use the ISO country code to set the app's
                // country (Home search chip + Lookup country picker) unless the
                // user already picked one, then feed the ads country-counter logic.
                val location = getLocationFromIP()
                location?.countryCode?.takeIf { it.isNotBlank() }?.let { iso ->
                    val prefs = VaultRegistry(activity)
                    if (prefs.homeCountryIso.isBlank()) {
                        prefs.homeCountryIso = iso
                        if (BuildConfig.DEBUG) Log.d(
                            "LocationCheck",
                            "Country code set from IP: $iso"
                        )
                    }
                }

                val isMarketingOn = resolveMarketingAudience()
                if (BuildConfig.DEBUG) {
                    Log.d(CONFIG_TAG, "audience → ${if (isMarketingOn) "MARKETING" else "ORGANIC"}")
                }
                adsPreference.putBoolean("OnMaketing", isMarketingOn)

                // Top-level audience split only: OnMaketing is now final (referrer
                // resolved), so re-apply the correct audience's keys — the first
                // ingest ran before the referrer and may have used the wrong side.
                // Flat config skips this entirely (behaviour unchanged).
                val isSplitConfig = adsPreference.getBoolean("__cfg_audience_split")
                if (isSplitConfig) {
                    val raw = adsPreference.getString("GET_DATA_RAW", "")
                    if (!raw.isNullOrBlank()) runCatching {
                        ingestConfig(activity, audienceRoot(JSONObject(raw), isMarketingOn))
                    }
                }

                // One pair of keys for both audiences: the config is already split into
                // marketing / organic blocks, so each side carries its own values and the
                // *_Marketing_* duplicates only gave the config a way to contradict itself.
                val countryEnableKey = "Iscountry_Counter"
                val countryListKey = "CountryList_Counter_NShow"
                if (BuildConfig.DEBUG) Log.d(
                    "LocationCheck",
                    "install=${if (isMarketingOn) "MARKETING" else "ORGANIC"} → using $countryEnableKey / $countryListKey"
                )

                if (adsPreference.getBoolean(countryEnableKey)) {
                    val blockedLocations = (adsPreference.getString(countryListKey, "") ?: "")
                        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

                    // Checked before the location block on purpose: "all" has to hold even
                    // when the IP lookup fails, which is exactly when a location-keyed test
                    // would quietly fall through.
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

                    // Remembered for every surface that must stay quiet in these regions
                    // (see AdsVault.isNShowLocation).
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
                    // Check off = nothing suppressed; clear any earlier match so a config
                    // change takes effect on this very launch.
                    adsPreference.isNShowLocation = false
                    if (BuildConfig.DEBUG) Log.d(
                        "LocationCheck",
                        "Country check is disabled in preferences"
                    )
                }

                // (marketing state already decided above as `isMarketingOn`)

                // --------------------
                // 1️⃣ Apply marketing counters (ONLY if marketing ON).
                //     Skipped for a top-level split config — its `marketing` block
                //     already carries the final counters/links, so copying the
                //     *Market* keys (absent there) would zero them out.
                // --------------------
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
                // 2️⃣ Native ad colours: the audience block's palette for the current mode.
                activity.applyNativeAdTheme()

                launch {

                    val isAdsOn = adsPreference.getBoolean("IsAdsON")


                    withContext(Dispatchers.Main) {

                        // 🔹 Native ads (never block navigation)
                        if (isAdsOn && isSplash == false) {
                            Log.d(APPOPEN_TAG, "isSplash=false → no splash AppOpen here")
                            onGetData?.onSuccess()
                            return@withContext
                        }

                        // Splash owns its permission priming explicitly (notification
                        // → phone_state) via AccessEngine.request() — the targeted,
                        // activity-independent path — rather than check()'s Activity-name
                        // matching. This guarantees the OS Allow/Deny dialogs fire HERE,
                        // on the splash, BEFORE the splash ad, even when the Remote Config
                        // permission_engine rules don't list StartupActivity. Resolving
                        // notification now also stops LightHouse's later
                        // subscribeAsync()/data-disclosure from re-prompting for it on the
                        // next screen. Order is: permission(s) → ad → dismiss/fail → next.
                        primeSplashPermissions(activity) {
                            if (!isAdsOn) {
                                // Ads OFF → continue
                                Log.w(
                                    APPOPEN_TAG,
                                    "IsAdsON=false → ads disabled, no AppOpen, continuing to app"
                                )
                                onGetData?.onSuccess()
                            } else {
                                if (isGoogleAdsEnabled) {
                                    NativePromo().loadNativeADs(activity)
                                    NativePromoBanner().loadNativeBannerAds(activity)
                                    InterstitialNormal().loadInterAds(activity)
                                    InterstitialBack().loadBackInterAds(activity)
                                }

                                // Splash ads gated by Firebase "is_splash_ads" flag
                                // AND days-since-install check
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
                                    // Splash ads disabled from Firebase → skip entirely
                                    Log.w(
                                        APPOPEN_TAG,
                                        "is_splash_ads=false → skipping splash ad entirely, continuing to app"
                                    )
                                    onGetData?.onSuccess()
                                } else {
                                    // Ads ON + splash enabled + gate passed → preload → show → continue
                                    preloadAds(adsPreference, activity) {
                                        showPreloadedAd(activity, adsPreference) {
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

    /**
     * Sequentially primes the splash's runtime permissions — notification, then
     * phone_state — through [AccessEngine.request], then runs [onDone].
     *
     * Uses request() (targeted by permission key) so the prompts fire HERE, in
     * this exact order, before the splash ad — but only for keys whose Remote
     * Config rule actually lists this screen (see [targetsScreen]). So dropping
     * `StartupActivity` from a permission's `activities` list keeps it off the
     * splash, and it is then asked wherever it *is* listed (e.g. AppCoreActivity).
     * Each request still honours SDK applicability (notification only on API
     * 33+), any pref gate of the spec, already-granted, `show_once`,
     * and the remote enable off-switch. Each callback always fires once on the
     * main thread, so [onDone] runs exactly once after both resolve (or are
     * skipped).
     */
    private fun primeSplashPermissions(activity: Activity, onDone: () -> Unit) {
        // OnMaketing is only final a few lines above (LightHouse attribution), and
        // the engine may have cached its rules earlier — at Application startup,
        // when the audience was still unknown. Re-parse so the marketing/organic
        // split below is read from the right side of the config.
        runCatching { AccessSource.reload() }
        val screen = activity::class.java.simpleName
        val prime: (String, () -> Unit) -> Unit = { key, next ->
            if (targetsScreen(key, screen)) AccessEngine.request(activity, key) { next() }
            else next()
        }
        prime("notification") {
            onDone()
        }
    }

    /**
     * True when the Remote Config rule for [key] targets [screen]. A key with no
     * configured rule returns true — there is nothing to opt out of, so the
     * splash keeps its previous behaviour of asking.
     */
    private fun targetsScreen(key: String, screen: String): Boolean {
        val rule = AccessSource.rules().firstOrNull { it.key == key } ?: return true
        val targets = rule.activities.any { ScreenMatcher.matches(it, screen) }
        if (!targets) Log.d("AccessEngine", "'$key' not configured for $screen — skipped on splash")
        return targets
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // ------------------------
    // AppOpenAd
    // ------------------------
    // Preload all ads in sequence or parallel
    fun preloadAds(adsPreference: AdsVault, activity: Activity, onComplete: () -> Unit) {
        bindCustomTabs(activity)
        val adType = AdKind.fromString(adsPreference.getString("IsAdType"))
        Log.d(APPOPEN_TAG, "preload() → IsAdType=$adType, googleAdsEnabled=$isGoogleAdsEnabled")
        when (adType) {
            AdKind.GOOGLE -> {
                if (isGoogleAdsEnabled) {
                    val showInterstitialOnSplash = adsPreference.getBoolean("is_splash_inter_show")
                    if (showInterstitialOnSplash) {
                        Log.d(
                            APPOPEN_TAG,
                            "preload → is_splash_inter_show=true → loading splash INTERSTITIAL instead of AppOpen"
                        )
                        loadGoogleInterstitialWithFallback(activity, adsPreference, onComplete)
                    } else {
                        Log.d(APPOPEN_TAG, "preload → is_splash_inter_show=false → loading APPOPEN")
                        loadAppOpenAdWithFallback(activity, adsPreference, onComplete)
                    }
                } else {
                    Log.w(
                        APPOPEN_TAG,
                        "preload → Google ads disabled (init failed) → Facebook fallback"
                    )
                    loadFacebookFallback(activity, adsPreference, onComplete)
                }
            }

            AdKind.FACEBOOK -> {
                if (adsPreference.getBoolean("IsFail_FB")) {
                    val fbId = adsPreference.getString("faceB_InterAds")!!
                    loadFacebookInterstitial(
                        activity,
                        fbId,
                        onLoaded = { onComplete() },
                        onFailed = { onComplete() },
                        onDismissed = {
                            onComplete()
                        }// fallback
                    )
                } else {
                    onComplete()
                }
            }

            AdKind.CUSTOM, AdKind.UNKNOWN -> onComplete() // nothing to preload
        }
    }

    fun showPreloadedAd(activity: Activity, adsPreference: AdsVault, onDismissed: () -> Unit) {
        // `splash_ad_flow` / a `splash_` link chain: the dynamic flow instead of the preloaded
        // App Open / interstitial. Only a flow configured for `splash` by name switches it over.
        if (LauncherPlacementAds.hasOwnFlow(activity, "splash")) {
            if (!LauncherPlacementAds.placementEnabled(activity, "splash")) return onDismissed()
            Log.d(APPOPEN_TAG, "showPreloaded() → splash flow (splash_*)")
            LauncherPlacementAds.showInterstitial(activity, "splash") { onDismissed() }
            return
        }
        when (AdKind.fromString(adsPreference.getString("IsAdType"))) {
            AdKind.GOOGLE -> {
                Log.d(
                    APPOPEN_TAG,
                    "showPreloaded() → appOpenReady=${appOpenAd != null}, interstitialReady=${interstitialAd != null}"
                )
                if (isGoogleAdsEnabled && (appOpenAd != null || interstitialAd != null)) {
                    if (appOpenAd != null) {
                        showAppOpenAd(activity) { onDismissed() }
                    } else if (interstitialAd != null) {
                        showGoogleInterstitial(activity) {
                            onDismissed()
                        }
                    }
                } else {
                    if (adsPreference.getBoolean("IsFail_FB")) {
                        val fbId = adsPreference.getString("faceB_InterAds")
                        if (!fbId.isNullOrEmpty() && fbInterstitial != null) {
                            showFacebookInterstitial { onDismissed() }
                        } else if (adsPreference.getBoolean("IsCustomADS")) {
                            // All failed + custom ads ON → fallback to custom link
                            launchCustomAdLink(
                                activity, adsPreference.getString("DirectLink")!!
                            ) {
                                onDismissed()
                            }
                        } else {
                            onDismissed()
                        }
                    } else if (adsPreference.getBoolean("IsCustomADS")) {
                        // All failed + custom ads ON → fallback to custom link
                        launchCustomAdLink(
                            activity, adsPreference.getString("DirectLink")!!
                        ) {
                            onDismissed()
                        }
                    } else {
                        onDismissed()
                    }
                }
            }

            AdKind.FACEBOOK -> {
                if (fbInterstitial != null) showFacebookInterstitial { onDismissed() }
                else onDismissed()
            }

            AdKind.CUSTOM, AdKind.UNKNOWN -> {
                if (adsPreference.getBoolean("IsCustomADS")) {
                    launchCustomAdLink(
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
    private fun loadGoogleInterstitialWithFallback(
        activity: Activity, adsPreference: AdsVault, onComplete: () -> Unit
    ) {
        val googleId = adsPreference.getString("googleS_Inter") ?: run { onComplete(); return }
        loadGoogleInterstitial(activity, googleId, onLoaded = { onComplete() }, onFailed = {
            loadFacebookFallback(activity, adsPreference, onComplete)
        })
    }

    private fun loadAppOpenAdWithFallback(
        activity: Activity, adsPreference: AdsVault, onComplete: () -> Unit
    ) {
        val appOpenId = adsPreference.getString("googleAppopen")
        if (appOpenId.isNullOrBlank()) {
            Log.w(
                APPOPEN_TAG,
                "no/blank 'googleAppopen' unit id in Remote Config → skipping AppOpen, continuing"
            )
            onComplete(); return
        }
        loadAppOpenAd(activity, appOpenId, onLoaded = { onComplete() }, onFailed = {
            Log.w(APPOPEN_TAG, "AppOpen load failed → falling back to Google interstitial")
            loadGoogleInterstitialWithFallback(activity, adsPreference, onComplete)
        })
    }

    private fun loadFacebookFallback(
        activity: Activity, adsPreference: AdsVault, onComplete: () -> Unit
    ) {
        if (adsPreference.getBoolean("IsFail_FB")) {
            val fbId = adsPreference.getString("faceB_InterAds")
            if (!fbId.isNullOrEmpty()) {
                loadFacebookInterstitial(
                    activity,
                    fbId,
                    onLoaded = { onComplete() },
                    onFailed = { onComplete() },
                    onDismissed = { onComplete() })
            } else onComplete()
        } else onComplete()
    }

    fun loadAppOpenAd(
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
                    // The single most useful line for "why didn't it load".
                    Log.e(
                        APPOPEN_TAG,
                        "load FAILED → code=${loadAdError.code}, domain=${loadAdError.domain}, " +
                                "message=${loadAdError.message}"
                    )
                    appOpenAd = null
                    AppOpenAdRegistry.isShowingAd = false
                    onFailed?.invoke()
                }

                override fun onAdLoaded(ad: AppOpenAd) {
                    Log.d(APPOPEN_TAG, "load SUCCESS → AppOpen ad is ready to show")
                    appOpenAd = ad
                    onLoaded?.invoke()
                }
            })
    }

    fun showAppOpenAd(activity: Activity, onDismissed: (() -> Unit)? = null) {
        appOpenAd?.let { ad ->
            Log.d(APPOPEN_TAG, "show() → displaying AppOpen ad")
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    onDismissed?.invoke()
                    AppOpenAdRegistry.isShowingAd = false
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    onDismissed?.invoke()
                }

                override fun onAdShowedFullScreenContent() {
                    AppOpenAdRegistry.isShowingAd = true
                }
            }
            // Log load
            activity.logKeyEvent("AppOpen_Loaded")

            if (BuildConfig.DEBUG) AdRevenueMeter.simulateDebugRevenue(activity)

            appOpenAd?.setOnPaidEventListener {
                AdRevenueMeter.logPaidEvent(activity, it)
            }
            ad.show(activity)
        } ?: run {
            // Ad not loaded yet
            Log.w(APPOPEN_TAG, "show() → AppOpen ad is null (not loaded in time) → skipping show")
            onDismissed?.invoke()
        }
    }

    // ------------------------
// Google Interstitial
// ------------------------
    private var interstitialAd: InterstitialAd? = null

    fun loadGoogleInterstitial(
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

    fun showGoogleInterstitial(activity: Activity, onDismissed: (() -> Unit)? = null) {
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

            // Log load
            activity.logKeyEvent("Interstitial_Splash_Loaded")

            if (BuildConfig.DEBUG) AdRevenueMeter.simulateDebugRevenue(activity)

            interstitialAd?.setOnPaidEventListener {
                AdRevenueMeter.logPaidEvent(activity, it)
            }
            ad.show(activity)
        } ?: run { onDismissed?.invoke() }
    }

    // ------------------------
// Facebook Interstitial
// ------------------------
    private var fbInterstitial: com.facebook.ads.InterstitialAd? = null

    fun loadFacebookInterstitial(
        activity: Activity,
        adUnitId: String,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
        onDismissed: (() -> Unit)? = null // Added parameter
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

    fun showFacebookInterstitial(onDismissed: (() -> Unit)? = null) {
        val ad = fbInterstitial

        if (ad != null && ad.isAdLoaded) {
            ad.show()
        } else {
            onDismissed?.invoke()
        }
    }

    // 1️⃣ Bind Custom Tabs
    private fun bindCustomTabs(activity: Activity) {
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
                                handleCustomTabClose()
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

    // 2️⃣ Handle close in one function
    private fun handleCustomTabClose() {
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

    // 3️⃣ Launch Custom Tab
    private fun launchCustomAdLink(activity: Activity, url: String, onClosed: () -> Unit) {
        bindCustomTabs(activity)

        isCustomTabOpened = true
        isCloseHandled = false
        onCustomTabClosed = { onClosed() }

        val customTabsIntent = CustomTabsIntent.Builder(customTabsSession).setShowTitle(true)
            .setToolbarColor(ContextCompat.getColor(activity, R.color.black)).build()

        try {
            customTabsIntent.launchUrl(activity, Uri.parse(url))
        } catch (e: Exception) {
            // fallback to browser
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            handleCustomTabClose()
        }
    }

    override fun onResume() {
        super.onResume()
        handleCustomTabClose()
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
                                AdsVault.getInstance(it).putString("FinalString", ref)
                            }
                            // Audience (OnMaketing) is no longer derived from the
                            // referrer string — funOnAdsLoad() now resolves it from
                            // LightHouse.isOrganicUser(). We still store the raw
                            // referrer (FinalString) and drive the flow forward.
                            onReferrerSettled()

                            activity?.getPreferences(MODE_PRIVATE)?.edit()?.apply {
                                putBoolean("isReferrerDone", true)
                                apply() // Use apply() for efficiency
                            }
                        } catch (e: RemoteException) {
                            onReferrerSettled()
                        } finally {
                            referrerClient.endConnection()
                        }

                    }

                    // Everything else — unsupported, unavailable, and the DEVELOPER_ERROR /
                    // PERMISSION_ERROR codes that used to fall off the end of this when —
                    // still has to hand off, or the audience flag is never written.
                    else -> onReferrerSettled()
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                // The service can drop before setup ever finishes; without this the probe
                // would end here and the flow would stall on the pre-referrer audience.
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
