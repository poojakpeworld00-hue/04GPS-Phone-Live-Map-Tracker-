package com.find.phone.gps.live.location.tracker.helpers

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotProfileAbsorb
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker.THEME_DARK
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker.THEME_LIGHT
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker.THEME_SYSTEM
import org.json.JSONObject

private const val TAG = "NativeTheme"

fun Context.enactNativeAdTheme(
    theme: String = CoreLocker.selectedTheme(this).ifEmpty { THEME_SYSTEM }
) {
    val adsPref = SpotsLocker.getInstance(this)
    val modeKey = when (theme) {
        THEME_LIGHT -> "NativeLight"
        THEME_DARK -> "NativeDark"
        THEME_SYSTEM -> {
            val isSystemDark =
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            if (isSystemDark) "NativeDark" else "NativeLight"
        }

        else -> "NativeLight"
    }

    try {
        val palette = JSONObject(adsPref.getString(SpotProfileAbsorb.NATIVE_THEME_ACTIVE, "{}") ?: "{}")
        val themeJson = palette.optJSONObject(modeKey)

        if (themeJson == null) {
            Log.d(TAG, "No $modeKey palette in NativeTheme — native colors left unchanged")
            return
        }

        adsPref.putString("NativebtnColor", themeJson.optString("btnColor"))
        adsPref.putString("NativebtnColorEnd", themeJson.optString("btnColorEnd"))
        adsPref.putString("NativebtntxtColor", themeJson.optString("btnText"))
        adsPref.putString("NativeBgColor", themeJson.optString("bgColor"))
        adsPref.putString("NativetxtColor", themeJson.optString("textColor"))

        Log.d(TAG, "Applied $modeKey theme to ads dynamically")
    } catch (e: Exception) {
        Log.e(TAG, "Error applying native theme dynamically", e)
    }
}
