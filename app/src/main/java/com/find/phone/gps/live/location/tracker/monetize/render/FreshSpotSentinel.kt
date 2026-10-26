package com.find.phone.gps.live.location.tracker.monetize.render

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import java.lang.ref.WeakReference
import androidx.core.content.ContextCompat
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent

object FreshSpotSentinel : Application.ActivityLifecycleCallbacks {
    private const val TAG = "FreshSpotSentinel"
    private const val REASON_RECENT_APPS = "recentapps"
    private const val PLAY_STORE_PACKAGE = "com.android.vending"

    private const val LEAVE_GRACE_MS = 1_000L

    private const val DEBOUNCE_MS = 3_000L

    private const val OPENED_KEY = "recent_playstore_opened"
    private const val DEFAULT_MAX_OPENS = 3
    private const val DEFAULT_WINDOW_SEC = 180

    private var startedCount = 0
    private var lastStoppedAt = 0L
    private var lastOpenedAt = 0L
    private var visible: WeakReference<Activity>? = null

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
        if (!ours) return log("skip: app not in front (started=$startedCount, leftAgo=${now - lastStoppedAt}ms)")

        val vault = SpotsLocker.getInstance(context)
        // RC "recent_playstore" is the master switch (false or absent in the audience block → off); only before the
        // first config arrives (nothing stored yet) is it assumed on, so the very first Recents press isn't lost
        val configIn = !vault.getString("GET_DATA_RAW", "").isNullOrBlank()
        if (configIn && !vault.getBoolean("recent_playstore")) return log("skip: recent_playstore off")
        // RC "recent_playstore_count" = how many Recents presses open the Play Store (missing/0 → default)
        val opened = vault.getInt(OPENED_KEY, 0)
        if (opened >= vault.getInt("recent_playstore_count").takeIf { it > 0 } ?: DEFAULT_MAX_OPENS) return log("skip: limit reached ($opened opened)")
        val windowSec = vault.getInt("recent_playstore_window_sec").takeIf { it > 0 } ?: DEFAULT_WINDOW_SEC
        if (windowSec > 0) {
            val age = now - firstInstallTime(context)
            if (age > windowSec * 1000L) return log("recents after ${age / 1000}s — outside the ${windowSec}s window")
        }
        if (now - lastOpenedAt < DEBOUNCE_MS) return log("skip: debounce")
        lastOpenedAt = now

        val playStore = context.packageManager.getLaunchIntentForPackage(PLAY_STORE_PACKAGE)
            ?: return log("Play Store not installed — nothing opened")
        runCatching {
            // CLEAR_TASK: a Play Store task already in Recents would otherwise resume on its last page, not its home
            // started from the visible Activity when we have one: a foreground launch skips the OEM "pop up a window?" prompt
            val starter: Context = visible?.get()?.takeIf { !it.isFinishing } ?: context
            starter.startActivity(playStore.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            vault.putInt(OPENED_KEY, opened + 1)
            context.recordKeyEvent("recent_playstore_open")
            log("recents pressed → Play Store home")
        }.onFailure { log("Play Store not opened: ${it.message}") }
    }

    private fun firstInstallTime(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }
            .getOrDefault(0L)

    override fun onActivityStarted(activity: Activity) {
        startedCount++
        visible = WeakReference(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        lastStoppedAt = System.currentTimeMillis()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
