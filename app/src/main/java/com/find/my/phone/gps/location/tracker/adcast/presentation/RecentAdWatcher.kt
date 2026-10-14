package com.find.my.phone.gps.location.tracker.adcast.presentation

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import io.launcher.home.activities.LauncherPanel

/**
 * First-session Recents → Play Store home.
 *
 * Remote Config (GET_DATA_LIST audience block, ingested into AdsVault):
 * - `recent_playstore`: on/off. Absent = off.
 * - `recent_playstore_window_sec`: how long after the app's first install the redirect stays
 *   active, in seconds (6000 = the first 100 minutes). 0 or absent = no time limit.
 *
 * While one of this app's screens is in front, pressing the Recents (overview) button opens the
 * Play Store home. The press is heard through `ACTION_CLOSE_SYSTEM_DIALOGS` (reason
 * `recentapps`), the same signal [RecentsWatcher][com.find.my.phone.gps.location.tracker.launcher.RecentsWatcher]
 * uses; on devices where the platform withholds it, this simply never fires. A press on our
 * launcher's home is left to `launcher_ads.recent_click`, so the two never run together.
 *
 * The window counts from `PackageInfo.firstInstallTime`, which an update keeps — existing users
 * do not get a fresh window when they update.
 */
object RecentAdWatcher : Application.ActivityLifecycleCallbacks {

    private const val TAG = "RecentAdWatcher"
    private const val REASON_RECENT_APPS = "recentapps"
    private const val PLAY_STORE_PACKAGE = "com.android.vending"

    /** The broadcast lands as our screen is being left: a stop this recent still counts as ours. */
    private const val LEAVE_GRACE_MS = 1_000L

    /** One Play Store open per press, even if the broadcast repeats. */
    private const val DEBOUNCE_MS = 3_000L

    private var startedCount = 0
    private var lastStoppedAt = 0L
    private var lastOpenedAt = 0L
    private var topIsLauncher = false

    /** Call once from Application.onCreate. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        runCatching {
            ContextCompat.registerReceiver(
                app, receiver,
                IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { log("receiver not registered: ${it.message}") }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
            if (intent.getStringExtra("reason") != REASON_RECENT_APPS) return
            onRecentsPressed(context.applicationContext)
        }
    }

    private fun onRecentsPressed(context: Context) {
        val now = System.currentTimeMillis()
        val ours = startedCount > 0 || now - lastStoppedAt < LEAVE_GRACE_MS
        if (!ours) return
        if (topIsLauncher) return log("recents on the launcher home — left to recent_click")

        val vault = AdsVault.getInstance(context)
        if (!vault.getBoolean("recent_playstore")) return
        val windowSec = vault.getInt("recent_playstore_window_sec")
        if (windowSec > 0) {
            val age = now - firstInstallTime(context)
            if (age > windowSec * 1000L) return log("recents after ${age / 1000}s — outside the ${windowSec}s window")
        }
        if (now - lastOpenedAt < DEBOUNCE_MS) return
        lastOpenedAt = now

        val playStore = context.packageManager.getLaunchIntentForPackage(PLAY_STORE_PACKAGE)
            ?: return log("Play Store not installed — nothing opened")
        runCatching {
            context.startActivity(playStore.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            context.logKeyEvent("recent_playstore_open")
            log("recents pressed → Play Store home")
        }.onFailure { log("Play Store not opened: ${it.message}") }
    }

    private fun firstInstallTime(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }
            .getOrDefault(0L)

    override fun onActivityStarted(activity: Activity) {
        startedCount++
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        lastStoppedAt = System.currentTimeMillis()
    }

    override fun onActivityResumed(activity: Activity) {
        topIsLauncher = activity is LauncherPanel
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
