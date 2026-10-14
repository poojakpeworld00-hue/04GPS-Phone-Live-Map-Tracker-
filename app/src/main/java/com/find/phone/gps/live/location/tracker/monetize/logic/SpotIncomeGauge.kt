package com.find.phone.gps.live.location.tracker.monetize.logic

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import com.google.android.gms.ads.AdValue
import com.google.firebase.analytics.FirebaseAnalytics
import  com.find.phone.gps.live.location.tracker.BuildConfig

const val TAG_EVENT = "AdEvents"

/**
 * Universal Ad Revenue Tracker
 * Works for: Interstitial, AppOpen, Banner, Native, Rewarded, Rewarded-Interstitial
 */
object SpotIncomeGauge {

    private const val TAG = "SpotIncomeGauge"

    /** Real revenue handler */
    fun recordPaidEvent(context: Context, adValue: AdValue?) {
        if (adValue == null) {
            Log.e(TAG, "🔥 REAL PAID EVENT → $adValue ")
            return
        }
        val revenue = adValue.valueMicros / 1_000_000.0
        val currency = adValue.currencyCode ?: "USD"

        context.recordAdRevenue(revenue, currency)

        Log.e(TAG, "🔥 REAL PAID EVENT → $revenue $currency (${adValue.valueMicros})")
    }

    /** Debug/Test mode revenue simulation */
    fun simulateDebugRevenue(context: Context) {
        if (!BuildConfig.DEBUG) return   // 🚫 safety

        val revenue = 1.00
        val currency = "USD"

        context.recordAdRevenue(revenue, currency)

        Log.e(TAG, "🧪 DEBUG SIM → $revenue $currency")
    }

}

/* -------------------------------------------------------------
   EXTENSION FUNCTIONS (Correct placement)
--------------------------------------------------------------*/

/**
 * First-session tracking: on the very first run every event is sent as `first_<event>` until the
 * user reaches the home screen ([endFirstSession]).
 */
object EventSession {
    private const val PREFS = "analytics_prefs"
    private const val KEY_DONE = "analytics_first_session_done"
    @Volatile private var first: Boolean? = null

    fun isFirst(context: Context): Boolean = first ?: context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_DONE, false).not().also { first = it }

    fun endFirstSession(context: Context) {
        if (!isFirst(context)) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
        first = false
    }
}

private val INVALID_EVENT_CHARS = Regex("[^A-Za-z0-9_]")

/**
 * Firebase drops any event whose name has characters other than letters, digits and `_`, does not
 * start with a letter, or is longer than 40 characters. Normalise instead of losing the event.
 */
internal fun safeEventName(raw: String): String {
    var name = raw.replace(INVALID_EVENT_CHARS, "_").replace(Regex("_+"), "_").trim('_')
    if (name.isEmpty() || !name[0].isLetter()) name = "e_$name"
    return name.take(40).trimEnd('_')
}

private fun Context.sendEvent(raw: String, params: Bundle?) {
    val name = safeEventName(if (EventSession.isFirst(this)) "first_$raw" else raw)
    if (isDebuggable()) {
        Log.w(TAG_EVENT, "📌 KeyEvent (Debug): $name ${params ?: ""}")
    } else {
        FirebaseAnalytics.getInstance(this).logEvent(name, params)
    }
}

/** Log general key events */
fun Context.recordKeyEvent(key: String) {
    val base = safeEventName(key)
    sendEvent(key, Bundle().apply { putString(base, base) })
}

/**
 * Ad lifecycle event `ad_<type>_<phase>` (type: banner, native, interstitial, back_interstitial,
 * app_open, rewarded; phase: req, loaded, failed, no_fill, impression, show, close, clicked) with
 * the screen and network as params so one bounded event set can be split per placement.
 */
fun Context.recordAdEvent(
    type: String,
    phase: String,
    screen: String? = null,
    network: String = "google",
    errorCode: Int? = null,
) {
    val params = Bundle().apply {
        putString("screen", (screen ?: (this@recordAdEvent as? android.app.Activity)
            ?.javaClass?.simpleName ?: "unknown").take(100))
        putString("network", network)
        errorCode?.let { putInt("error_code", it) }
    }
    sendEvent("ad_${type}_$phase", params)
}

/** Load failure split: AdMob code 3 / Facebook 1001 mean "no fill", anything else is a failure. */
fun Context.recordAdLoadError(type: String, code: Int, screen: String? = null, network: String = "google") {
    val noFill = (network == "google" && code == 3) || (network == "facebook" && code == 1001)
    recordAdEvent(type, if (noFill) "no_fill" else "failed", screen, network, code)
}

/** Screen token used by navigation events: simple class name without the Activity/Fragment suffix. */
fun screenToken(owner: Any): String =
    owner.javaClass.simpleName.lowercase(java.util.Locale.ROOT)
        .removeSuffix("activity").removeSuffix("fragment").ifEmpty { "screen" }

/**
 * Logs a runtime-permission outcome as `Permission_<NAME>_Allow` / `_Deny`,
 * e.g. `Permission_READ_CALL_LOG_Allow`. [permission] is a full
 * `android.permission.*` string; only the short name is used in the event.
 */
fun Context.recordPermissionResult(permission: String, granted: Boolean) {
    val shortName = permission.substringAfterLast('.')
    recordKeyEvent("Permission_${shortName}_${if (granted) "Allow" else "Deny"}")
}

/** Check debug mode */
fun Context.isDebuggable(): Boolean {
    return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

/** Main Firebase revenue logger */
fun Context.recordAdRevenue(revenue: Double, currency: String) {
    val params = Bundle().apply {
        putString(FirebaseAnalytics.Param.AD_PLATFORM, "admob") // correct for AdMob
        putString(FirebaseAnalytics.Param.CURRENCY, currency)
        putDouble(FirebaseAnalytics.Param.VALUE, revenue)
    }

    if (BuildConfig.DEBUG) {
        Log.d("AdPaid", "🧪 DEBUG AD_IMPRESSION → $params")
    } else {
        FirebaseAnalytics.getInstance(this)
            .logEvent(FirebaseAnalytics.Event.AD_IMPRESSION, params)
    }


}
