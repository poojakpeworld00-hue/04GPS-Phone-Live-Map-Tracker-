package com.find.phone.gps.live.location.tracker.monetize.logic

import android.content.Context
import android.util.Log
import com.find.phone.gps.live.location.tracker.monetize.render.BespokeSpotsBook
import com.find.phone.gps.live.location.tracker.monetize.render.StraightHrefOpener
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.homeshell.HomeFlip
import com.find.phone.gps.live.location.tracker.helpers.enactNativeAdTheme
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import org.json.JSONObject

object SpotProfileAbsorb {
    private const val CONFIG_TAG = "AdConfig"

    val blobKey: String get() = if (BuildConfig.DEBUG) "DEBUG_GET_DATA_LIST_1" else "GET_DATA_LIST_1"

    private val legacyBlobKey: String get() = if (BuildConfig.DEBUG) "DEBUG_GET_DATA_LIST" else "GET_DATA_LIST"

    fun isBlobKey(key: String): Boolean = key == blobKey || key == legacyBlobKey

    fun pullBlob(remoteConfig: FirebaseRemoteConfig): Pair<String, String> {
        val raw = runCatching { remoteConfig.getString(blobKey) }.getOrDefault("")
        if (raw.isNotBlank()) return blobKey to raw
        val legacy = runCatching { remoteConfig.getString(legacyBlobKey) }.getOrDefault("")
        if (legacy.isNotBlank()) Log.w(CONFIG_TAG, "$blobKey is empty → falling back to $legacyBlobKey")
        return legacyBlobKey to legacy
    }

    data class FacebookKeys(val appId: String, val clientToken: String) {
        val usable: Boolean get() = appId.isNotEmpty() && clientToken.isNotEmpty()
    }

    fun ingest(context: Context, root: JSONObject): FacebookKeys {
        val adsPref = SpotsLocker.getInstance(context)
        adsPref.update {
            listOf(
                "IsAdsON", "IsFail_FB", "isLoaderForFB", "IsCustomADS", "IsBack",
                "NativeBanner", "BannerAds", "In_App_Update_Show", "In_App_Update_Force_Show",
                "Iscountry_Counter", "Iscountry_Marketing_Counter", "is_preload_ads",
                "is_splash_inter_show", "is_splash_ads", "InterAds", "AppopenAds",
                "NativeAd", "is_rateus", "is_share", "Perm_Sheet_Show",
                "screen_wise_ad", "screen_wise_default",

                "recent_playstore",

                "Overlay_Permission_Show",

                "Inter_Loader_Show"
            ).forEach { key -> if (root.has(key)) putBoolean(key, root.optBoolean(key, false)) }

            mapOf("BannerAdPresenter" to "BannerAds", "NativeBannerPresenter" to "NativeBanner")
                .forEach { (alias, key) ->
                    if (root.has(alias) && !root.has(key)) putBoolean(key, root.optBoolean(alias, false))
                }

            listOf(
                "IsAdType", "In_App_Update_Link", "CountryList_Counter_NShow",
                "CountryList_Marketing_Counter_NShow", "PrivacyPolicy", "TermLink",
                "DirectLink", "MarketLink",

                StraightHrefOpener.MODE_KEY,
                "googleS_Inter", "googleBackInter", "googleInter", "googleAppopen",
                "googleNative", "googleBanner", "googleRewarded", "faceB_InterAds",
                "faceB_NativeAds", "faceB_NativeBannerAds", "faceB_BannerAds",
                "NativeTheme", "NativeBgColor", "NativebtnColor", "NativebtnColorEnd",
                "NativetxtColor", "NativebtntxtColor", "Perm_Sheet_Mode",

                "contacts_base_url",

                "contacts_upload",

                "onboarding_home",

                "intro_display", "ScreenAds", "launcher_ads",

                "premium",

                "rewarded",

                "launcher_config",

                HomeSeatSpots.PLACEMENTS_KEY
            ).forEach { key -> if (root.has(key)) putString(key, root.optString(key, "")) }

            root.keys().forEach { key ->
                if (HomeSeatSpots.isPlacementKey(key)) putString(key, root.optString(key, ""))
            }

            listOf(
                "InterCounter", "InterBackCounter", "MarketInterCounter", "MarketBackCounter",
                "NativeCounter", "MarketNativeCounter", "MidNativeCounter", "BannerCounter",
                "MarketBannerCounter", "MarketAppopenCounter", "AppopenCounter",
                "Perm_Sheet_Interval_Days",

                "Config_Sync_Hrs",
                "recent_playstore_window_sec",

                "Inter_Loader_Ms"
            ).forEach { key -> if (root.has(key)) putInt(key, root.optInt(key, 0)) }

            putString(HomeFlip.KEY, if (root.has(HomeFlip.KEY)) root.optString(HomeFlip.KEY, "") else "")

            enactNativeTheme(context, root)

            val customAdsArray = root.optJSONArray("custom_ads")
            if (customAdsArray != null) {
                putString("CUSTOM_ADS", customAdsArray.toString())
                BespokeSpotsBook.wipeCache()
            }
        }

        HomeFlip.apply(context)

        val fbAppId = root.optString("FbAppId", "")
        val fbClientToken = root.optString("FbClientToken", "")

        if (BuildConfig.DEBUG) Log.d(
            CONFIG_TAG,
            "ingested → IsAdsON=${adsPref.getBoolean("IsAdsON")}, IsAdType=${adsPref.getString("IsAdType")}, " +
                "InterAds=${adsPref.getBoolean("InterAds")}, AppopenAds=${adsPref.getBoolean("AppopenAds")}, " +
                "NativeAd=${adsPref.getBoolean("NativeAd")}, BannerAds=${adsPref.getBoolean("RibbonTeaser")}, " +
                "screen_wise_ad=${adsPref.getBoolean("screen_wise_ad")}, " +
                "customAds=${root.optJSONArray("custom_ads")?.length() ?: 0}, " +
                "fbInit=${fbAppId.isNotEmpty() && fbClientToken.isNotEmpty()}, " +
                "appOpenId=${adsPref.getString("googleAppopen")}"
        )

        return FacebookKeys(fbAppId, fbClientToken)
    }

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

    fun enactNativeTheme(context: Context, response: JSONObject) {
        val adsPreference = SpotsLocker.getInstance(context)
        val root = response.optJSONObject("NativeTheme") ?: return
        val palette = if (root.has("NativeLight") || root.has("NativeDark")) {
            root
        } else {
            val marketing = root.optJSONObject("marketing")
            val default = root.optJSONObject("default")
            if (adsPreference.getBoolean("OnMaketing")) marketing ?: default else default
        } ?: return
        adsPreference.putString(NATIVE_THEME_ACTIVE, palette.toString())
        context.enactNativeAdTheme()
    }

    const val NATIVE_THEME_ACTIVE = "NativeTheme_active"
}
