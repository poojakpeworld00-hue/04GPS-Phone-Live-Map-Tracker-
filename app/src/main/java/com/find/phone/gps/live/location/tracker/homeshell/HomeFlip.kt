package com.find.phone.gps.live.location.tracker.homeshell

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.livemap.homedeck.ext.isDefaultLauncher

object HomeFlip {
    private const val TAG = "HomeFlip"

    const val KEY = "launcher_enabled"

    private const val HOME_ALIAS = "com.find.phone.gps.live.location.tracker.HomeEntry"

    fun configEnabled(context: Context): Boolean =
        SpotsLocker.getInstance(context).getString(KEY)?.trim()?.lowercase() != "false"

    fun isEnabled(context: Context): Boolean =
        configEnabled(context) || runCatching { context.isDefaultLauncher() }.getOrDefault(false)

    fun apply(context: Context) {
        runCatching {
            val app = context.applicationContext
            val pm = app.packageManager
            val component = ComponentName(app.packageName, HOME_ALIAS)
            val wanted = if (isEnabled(app)) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (pm.getComponentEnabledSetting(component) == wanted) return
            pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            if (BuildConfig.DEBUG) Log.d(TAG, "home alias → ${if (wanted == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) "disabled" else "enabled"}")
        }.onFailure { Log.w(TAG, "apply failed", it) }
    }
}
