package com.find.my.phone.gps.location.tracker.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import io.launcher.home.extensions.isDefaultLauncher

/**
 * Launcher mode on or off, per audience, from `launcher_enabled` in the GET_DATA_LIST audience
 * block (`marketing` / `organic`).
 *
 *  - `true`, or absent: launcher mode — the app is a home app, onboarding may ask for the Home
 *    role, and onboarding can end on the launcher home (`onboarding_home`).
 *  - `false`: plain app — the app is not offered as a home app at all, onboarding never asks for
 *    the role, every set-default prompt stays hidden, and Home is always [AppCoreActivity].
 *
 * The HOME intent-filter lives on the [HOME_ALIAS] activity-alias (the app manifest removes it
 * from the module's LauncherPanel), and [apply] enables or disables that alias to match.
 *
 * A user who already holds the Home role keeps the launcher even when the audience is switched
 * off, so nobody's home screen changes under them; once they pick another home app, the next
 * [apply] takes this app out of the list.
 */
object LauncherSwitch {

    private const val TAG = "LauncherSwitch"

    /** Audience-block key, stored as text by AdConfigIngest (blank when absent). */
    const val KEY = "launcher_enabled"

    /** The manifest alias that carries MAIN/HOME/DEFAULT for LauncherPanel. */
    private const val HOME_ALIAS = "com.find.my.phone.gps.location.tracker.HomeEntry"

    /** What Remote Config asks for this audience; absent or unreadable means on. */
    fun configEnabled(context: Context): Boolean =
        AdsVault.getInstance(context).getString(KEY)?.trim()?.lowercase() != "false"

    /**
     * Whether the app runs in launcher mode: the config says so, or the user already made it
     * their home app (kept, never taken away).
     */
    fun isEnabled(context: Context): Boolean =
        configEnabled(context) || runCatching { context.isDefaultLauncher() }.getOrDefault(false)

    /** Enables or disables the home alias to match [isEnabled]. Cheap; a no-op when unchanged. */
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
