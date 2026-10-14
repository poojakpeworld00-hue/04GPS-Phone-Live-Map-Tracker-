package com.find.my.phone.gps.location.tracker.adcast.domain

import android.content.Context
import android.util.Log
import com.find.my.phone.gps.location.tracker.adcast.presentation.CustomAdsRegistry
import com.find.my.phone.gps.location.tracker.adcast.presentation.DirectLinkOpener
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.launcher.LauncherSwitch
import com.find.my.phone.gps.location.tracker.util.applyNativeAdTheme
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import org.json.JSONObject

/**
 * Reads a getData blob into [AdsVault].
 *
 * Lifted out of AdBeaconActivity so it is not tied to the splash: the same ingest has to run
 * when Remote Config pushes a change to a running app (see LiveConfigWatcher), and duplicating
 * it would leave two lists of keys to keep in step.
 *
 * Facebook SDK initialisation stays with the caller — it needs an Activity and only makes
 * sense once per process — so [ingest] hands the credentials back instead of applying them.
 */
object AdConfigIngest {

    private const val CONFIG_TAG = "AdConfig"

    /**
     * The blob parameter this build reads. Debug and release are separate on purpose.
     *
     * `_1`: the placement-based ad flow reads a schema the builds already in users' hands do not
     * understand, so it lives in its own parameters and the old `GET_DATA_LIST` /
     * `DEBUG_GET_DATA_LIST` keep serving those builds untouched. Every reader of the blob goes
     * through [readBlob].
     */
    val blobKey: String get() = if (BuildConfig.DEBUG) "DEBUG_GET_DATA_LIST_1" else "GET_DATA_LIST_1"

    private val legacyBlobKey: String get() = if (BuildConfig.DEBUG) "DEBUG_GET_DATA_LIST" else "GET_DATA_LIST"

    /** Whether a Remote Config key is the blob this build reads (new or legacy). */
    fun isBlobKey(key: String): Boolean = key == blobKey || key == legacyBlobKey

    /**
     * The activated blob and the key it came from. Falls back to the legacy key while the `_1`
     * parameter is not published yet, so a new build is never left on code defaults (every ad
     * off) just because the console has not caught up. Empty when neither has anything — the
     * caller then keeps whatever an earlier run cached.
     */
    fun readBlob(remoteConfig: FirebaseRemoteConfig): Pair<String, String> {
        val raw = runCatching { remoteConfig.getString(blobKey) }.getOrDefault("")
        if (raw.isNotBlank()) return blobKey to raw
        val legacy = runCatching { remoteConfig.getString(legacyBlobKey) }.getOrDefault("")
        if (legacy.isNotBlank()) Log.w(CONFIG_TAG, "$blobKey is empty → falling back to $legacyBlobKey")
        return legacyBlobKey to legacy
    }

    /** The Facebook credentials found in the blob; empty when the blob carries none. */
    data class FacebookKeys(val appId: String, val clientToken: String) {
        val usable: Boolean get() = appId.isNotEmpty() && clientToken.isNotEmpty()
    }

    /**
     * Reads every getData key from [root] into AdsVault (batched). [root] is
     * either the flat response or one of its `marketing` / `organic` sub-objects
     * (see [audienceRoot]). Safe to call again (funOnAdsLoad re-applies the correct
     * audience once the referrer settles OnMaketing).
     */
    fun ingest(context: Context, root: JSONObject): FacebookKeys {
        val adsPref = AdsVault.getInstance(context)
        adsPref.update {
            // --- Booleans ---
            listOf(
                "IsAdsON", "IsFail_FB", "isLoaderForFB", "IsCustomADS", "IsBack",
                "NativeBanner", "BannerAds", "In_App_Update_Show", "In_App_Update_Force_Show",
                "Iscountry_Counter", "Iscountry_Marketing_Counter", "is_preload_ads",
                "is_splash_inter_show", "is_splash_ads", "InterAds", "AppopenAds",
                "NativeAd", "is_rateus", "is_share", "Perm_Sheet_Show",
                "screen_wise_ad", "screen_wise_default",
                // First-session Recents → Play Store home (RecentAdWatcher).
                "recent_playstore",
                // App-wide "display over other apps" prompts on/off (FloatKit). Absent = on.
                "Overlay_Permission_Show",
                // Interstitial loader on/off (FullScreenSpinner). Absent = isLoaderForFB.
                "Inter_Loader_Show"
            ).forEach { key -> if (root.has(key)) putBoolean(key, root.optBoolean(key, false)) }

            // The reference config's names for the same two switches, so its config pastes across
            // unchanged. This app's name wins when both are present.
            mapOf("BannerAdPresenter" to "BannerAds", "NativeBannerPresenter" to "NativeBanner")
                .forEach { (alias, key) ->
                    if (root.has(alias) && !root.has(key)) putBoolean(key, root.optBoolean(alias, false))
                }

            // --- Strings ---
            listOf(
                "IsAdType", "In_App_Update_Link", "CountryList_Counter_NShow",
                "CountryList_Marketing_Counter_NShow", "PrivacyPolicy", "TermLink",
                "DirectLink", "MarketLink",
                // How a DirectLink opens: webview / browser / custom tab (DirectLinkOpener.MODE_KEY).
                DirectLinkOpener.MODE_KEY,
                "googleS_Inter", "googleBackInter", "googleInter", "googleAppopen",
                "googleNative", "googleBanner", "googleRewarded", "faceB_InterAds",
                "faceB_NativeAds", "faceB_NativeBannerAds", "faceB_BannerAds",
                "NativeTheme", "NativeBgColor", "NativebtnColor", "NativebtnColorEnd",
                "NativetxtColor", "NativebtntxtColor", "Perm_Sheet_Mode",
                // API origin — see RetrofitClient, which falls back to its compiled-in default
                // when this is absent or malformed. Renamed from `api_base_url` with the move to
                // contact-saver, so a config still carrying the old key cannot pin the retired host.
                "contacts_base_url",
                // One-time phonebook upload switch (ContactSync): { "enabled": false }.
                "contacts_upload",
                // Where onboarding ends: "app" = this app's own home, else the launcher home.
                "onboarding_home",
                // Nested JSON objects stored as text (read back via JSONObject).
                "intro_display", "ScreenAds", "launcher_ads",
                // Premium paywall: switches, close delay and the Play products (PremiumConfig).
                "premium",
                // Rewarded no-fill handling: cooldown, free / wait mode, Retry limit (RewardedConfig).
                "rewarded",
                // The :launcher module's own config; CallerLauncherBridge overlays it on the
                // top-level Remote Config parameter of the same name.
                "launcher_config",
                // Every placement's own ad settings, nested (LauncherPlacementAds).
                LauncherPlacementAds.PLACEMENTS_KEY
            ).forEach { key -> if (root.has(key)) putString(key, root.optString(key, "")) }

            // The launcher's per-placement ad keys (`leftPanel_googleInter`, `drawer_link_first_then`,
            // …) and the link-first switches. Always stored as strings, whatever their JSON type, so
            // a key that is a boolean in one config and a string in the next never clashes in prefs.
            root.keys().forEach { key ->
                if (LauncherPlacementAds.isPlacementKey(key)) putString(key, root.optString(key, ""))
            }

            // --- Integers ---
            listOf(
                "InterCounter", "InterBackCounter", "MarketInterCounter", "MarketBackCounter",
                "NativeCounter", "MarketNativeCounter", "MidNativeCounter", "BannerCounter",
                "MarketBannerCounter", "MarketAppopenCounter", "AppopenCounter",
                "Perm_Sheet_Interval_Days",
                // Backstop-fetch window for LiveConfigWatcher, in hours. 0 = fetch on every
                // foreground (testing only); absent falls back to its own default.
                "Config_Sync_Hrs",
                "recent_playstore_window_sec",
                // Loader beat before a preloaded interstitial, in ms (FullScreenSpinner). 0 = none.
                "Inter_Loader_Ms"
            ).forEach { key -> if (root.has(key)) putInt(key, root.optInt(key, 0)) }

            // Launcher mode on/off for this audience (LauncherSwitch). Written every time, blank
            // when absent, so dropping the key from Remote Config restores the default (on).
            putString(LauncherSwitch.KEY, if (root.has(LauncherSwitch.KEY)) root.optString(LauncherSwitch.KEY, "") else "")

            applyNativeTheme(context, root) // DEFAULT theme

            // --- Custom Ads ---
            val customAdsArray = root.optJSONArray("custom_ads")
            if (customAdsArray != null) {
                putString("CUSTOM_ADS", customAdsArray.toString())
                CustomAdsRegistry.clearCache()
            }
        }

        // Show or hide the app as a home app to match the audience's launcher mode.
        LauncherSwitch.apply(context)

        // Facebook Ad initialization parameters
        val fbAppId = root.optString("FbAppId", "")
        val fbClientToken = root.optString("FbClientToken", "")

        if (BuildConfig.DEBUG) Log.d(
            CONFIG_TAG,
            "ingested → IsAdsON=${adsPref.getBoolean("IsAdsON")}, IsAdType=${adsPref.getString("IsAdType")}, " +
                "InterAds=${adsPref.getBoolean("InterAds")}, AppopenAds=${adsPref.getBoolean("AppopenAds")}, " +
                "NativeAd=${adsPref.getBoolean("NativeAd")}, BannerAds=${adsPref.getBoolean("BannerPromo")}, " +
                "screen_wise_ad=${adsPref.getBoolean("screen_wise_ad")}, " +
                "customAds=${root.optJSONArray("custom_ads")?.length() ?: 0}, " +
                "fbInit=${fbAppId.isNotEmpty() && fbClientToken.isNotEmpty()}, " +
                "appOpenId=${adsPref.getString("googleAppopen")}"
        )

        return FacebookKeys(fbAppId, fbClientToken)
    }

    /**
     * The audience-specific sub-object of a getData response — `marketing` or
     * `organic` per [isMarketing], falling back to the other audience, then to the
     * flat [response] itself (legacy, un-split config → unchanged behaviour).
     */
    fun audienceRoot(response: JSONObject, isMarketing: Boolean): JSONObject {
        val preferred = if (isMarketing) "marketing" else "organic"
        val fallback = if (isMarketing) "organic" else "marketing"
        response.optJSONObject(preferred)?.let {
            if (BuildConfig.DEBUG) Log.d(CONFIG_TAG, "audienceRoot → using '$preferred' segment")
            return it
        }
        response.optJSONObject(fallback)?.let {
            if (BuildConfig.DEBUG) Log.d(CONFIG_TAG, "audienceRoot → '$preferred' missing, fell back to '$fallback' segment")
            return it
        }
        if (BuildConfig.DEBUG) Log.d(CONFIG_TAG, "audienceRoot → no marketing/organic wrapper, using flat config")
        return response
    }

    /**
     * `NativeTheme` of the audience block this user got (marketing or organic):
     * `{ "NativeLight": {…}, "NativeDark": {…} }`. The block itself already is the audience
     * choice, so there is nothing to pick here — it is stored as is and applied for the current
     * light / dark mode by [applyNativeAdTheme], which every screen runs as it opens.
     *
     * Configs still carrying the old nested `{ "marketing": {…}, "default": {…} }` shape keep
     * working: marketing users read `marketing` (else `default`), everyone else `default`.
     */
    fun applyNativeTheme(context: Context, response: JSONObject) {
        val adsPreference = AdsVault.getInstance(context)
        val root = response.optJSONObject("NativeTheme") ?: return
        val palette = if (root.has("NativeLight") || root.has("NativeDark")) {
            root
        } else {
            val marketing = root.optJSONObject("marketing")
            val default = root.optJSONObject("default")
            if (adsPreference.getBoolean("OnMaketing")) marketing ?: default else default
        } ?: return
        adsPreference.putString(NATIVE_THEME_ACTIVE, palette.toString())
        context.applyNativeAdTheme()
    }

    /** The audience's native palette (NativeLight / NativeDark), as ingested. */
    const val NATIVE_THEME_ACTIVE = "NativeTheme_active"
}
