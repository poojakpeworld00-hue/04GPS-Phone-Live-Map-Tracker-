package com.find.my.phone.gps.location.tracker.util

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.find.my.phone.gps.location.tracker.adcast.domain.AdConfigIngest
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.util.AppVault.THEME_DARK
import com.find.my.phone.gps.location.tracker.util.AppVault.THEME_LIGHT
import com.find.my.phone.gps.location.tracker.util.AppVault.THEME_SYSTEM
import org.json.JSONObject

private const val TAG = "NativeTheme"

/**
 * Copies the light/dark native-ad palette for the active theme into the ad preferences, where
 * the native renderers read it from.
 *
 * The palette keys (`NativebtnColor`, `NativeBgColor`, …) are **global and last-write-wins** —
 * nothing re-derives them at render time — so whichever screen wrote them last decides how every
 * native ad afterwards looks. That makes this a per-screen responsibility, not a one-off:
 * a screen that renders natives without calling this shows them in whatever mode some earlier
 * screen left behind, or unset entirely on a cold boot (dark-on-dark, effectively invisible).
 *
 * Called by [com.find.my.phone.gps.location.tracker.base.CanvasActivity] for every normal screen, and
 * separately by the launcher home — which does not extend it, yet is the device HOME and so is
 * often the first screen after a reboot.
 */
fun Context.applyNativeAdTheme(
    theme: String = AppVault.selectedTheme(this).ifEmpty { THEME_SYSTEM }
) {
    val adsPref = AdsVault.getInstance(this)
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
        // The audience block's own palette (AdConfigIngest.applyNativeTheme), light or dark.
        val palette = JSONObject(adsPref.getString(AdConfigIngest.NATIVE_THEME_ACTIVE, "{}") ?: "{}")
        val themeJson = palette.optJSONObject(modeKey)

        if (themeJson == null) {
            // No palette to copy — Remote Config has not landed yet (the launcher is the device
            // HOME, so it can run before any fetch has ever happened) or the key is absent. Say
            // so rather than logging a write that did not occur.
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
