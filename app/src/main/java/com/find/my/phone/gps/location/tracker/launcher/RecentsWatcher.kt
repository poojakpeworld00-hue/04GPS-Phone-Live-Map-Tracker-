package com.find.my.phone.gps.location.tracker.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.adcast.domain.LauncherAdsConfig

/**
 * `launcher_ads.recent_click`: the Recents (overview) button pressed while the launcher is on screen.
 *
 * Nothing can be shown over the Recents list itself, so the press is only recorded here, and the
 * ad runs when the user comes back to the launcher — within `return_window_sec` (default 60s), so
 * a return much later is not treated as the same moment. Picking another app from Recents and
 * coming back later therefore shows nothing.
 *
 * The press is heard through `ACTION_CLOSE_SYSTEM_DIALOGS` (reason `recentapps`), the same signal
 * [SystemDialogHelper][com.find.my.phone.gps.location.tracker.util.SystemDialogHelper] uses. The
 * platform keeps narrowing that broadcast, so on some devices and Android versions it never
 * arrives, and then this surface simply never fires.
 */
object RecentsWatcher {

    private const val TAG = "RecentsWatcher"
    private const val REASON_RECENT_APPS = "recentapps"
    private const val TEARDOWN_DELAY_MS = 100L

    /** When Recents was last pressed on the launcher; 0 = nothing pending. */
    private var pressedAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Listens for Recents while [activity] (the launcher) is on screen. Call once per instance. */
    fun attach(activity: Activity) {
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(Observer(activity))
    }

    /** The launcher is back in front: runs `recent_click` if Recents was pressed recently enough. */
    fun onLauncherResume(activity: Activity) {
        val at = pressedAt
        if (at == 0L) return
        pressedAt = 0L
        val age = System.currentTimeMillis() - at
        if (age > LauncherAdsConfig.recentReturnWindowMs(activity)) {
            log("recents return after ${age}ms — outside the window, nothing shown")
            return
        }
        // After the resume settles: a full-screen ad shown from inside onResume can be refused.
        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            LauncherAdsConfig.runSurface(activity, LauncherAdsConfig.LauncherSurface.RECENT_CLICK) {}
        }
    }

    private class Observer(private val activity: Activity) : DefaultLifecycleObserver {
        private var registered = false
        private var pendingTeardown: Runnable? = null

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
                if (intent.getStringExtra("reason") != REASON_RECENT_APPS) return
                pressedAt = System.currentTimeMillis()
                log("recents pressed on the launcher")
            }
        }

        override fun onResume(owner: LifecycleOwner) {
            cancelTeardown()
            if (registered) return
            runCatching {
                ContextCompat.registerReceiver(
                    activity, receiver,
                    IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
            }
        }

        // The broadcast arrives as the launcher is left, so the receiver stays up a beat past onStop.
        override fun onStop(owner: LifecycleOwner) {
            val teardown = Runnable { unregister() }
            pendingTeardown = teardown
            mainHandler.postDelayed(teardown, TEARDOWN_DELAY_MS)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            cancelTeardown()
            unregister()
        }

        private fun cancelTeardown() {
            pendingTeardown?.let { mainHandler.removeCallbacks(it) }
            pendingTeardown = null
        }

        private fun unregister() {
            pendingTeardown = null
            if (!registered) return
            registered = false
            runCatching { activity.unregisterReceiver(receiver) }
        }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
