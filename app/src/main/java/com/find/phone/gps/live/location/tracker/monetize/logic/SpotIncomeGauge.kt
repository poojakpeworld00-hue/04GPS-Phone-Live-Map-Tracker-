package com.find.phone.gps.live.location.tracker.monetize.logic

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.ResponseInfo
import com.google.firebase.analytics.FirebaseAnalytics
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.NavTracerCore

const val TAG_EVENT = "AdEvents"

object SpotIncomeGauge {
    private const val TAG = "SpotIncomeGauge"

    fun recordPaidEvent(
        context: Context,
        adValue: AdValue?,
        format: String,
        unitId: String?,
        info: ResponseInfo?,
        placement: String = NavTracerCore.currentScreen(),
    ) {
        if (adValue == null) return
        val source = info?.loadedAdapterResponseInfo?.adSourceName?.takeIf { it.isNotBlank() } ?: "AdMob"
        val segment = if (SpotsLocker.getInstance(context).getBoolean("OnMaketing")) "paid" else "organic"
        val params = Bundle().apply {
            putString(FirebaseAnalytics.Param.AD_PLATFORM, "admob")
            putString(FirebaseAnalytics.Param.AD_SOURCE, source)
            putString(FirebaseAnalytics.Param.AD_FORMAT, format)
            putString(FirebaseAnalytics.Param.AD_UNIT_NAME, unitId.orEmpty())
            putDouble(FirebaseAnalytics.Param.VALUE, adValue.valueMicros / 1_000_000.0)
            putString(FirebaseAnalytics.Param.CURRENCY, adValue.currencyCode.ifBlank { "USD" })
            putInt("precision", adValue.precisionType)
            putString("placement", placement)
            putString("segment", segment)
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ad_impression → $params")
        } else {
            FirebaseAnalytics.getInstance(context).logEvent(FirebaseAnalytics.Event.AD_IMPRESSION, params)
        }
    }

}

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

fun Context.recordKeyEvent(key: String) {
    val base = safeEventName(key)
    sendEvent(key, Bundle().apply { putString(base, base) })
}

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

fun Context.recordAdLoadError(type: String, code: Int, screen: String? = null, network: String = "google") {
    val noFill = (network == "google" && code == 3) || (network == "facebook" && code == 1001)
    recordAdEvent(type, if (noFill) "no_fill" else "failed", screen, network, code)
}

fun screenToken(owner: Any): String =
    owner.javaClass.simpleName.lowercase(java.util.Locale.ROOT)
        .removeSuffix("activity").removeSuffix("fragment").ifEmpty { "screen" }

fun Context.recordPermissionResult(permission: String, granted: Boolean) {
    val shortName = permission.substringAfterLast('.')
    recordKeyEvent("Permission_${shortName}_${if (granted) "Allow" else "Deny"}")
}

fun Context.isDebuggable(): Boolean {
    return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
