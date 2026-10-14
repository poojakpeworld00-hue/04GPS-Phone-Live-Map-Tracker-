package com.find.phone.gps.live.location.tracker.homeshell

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
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile

object SwitcherSentinel {
    private const val TAG = "SwitcherSentinel"
    private const val REASON_RECENT_APPS = "recentapps"
    private const val TEARDOWN_DELAY_MS = 100L

    private var pressedAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(activity: Activity) {
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(Observer(activity))
    }

    fun onLauncherResume(activity: Activity) {
        val at = pressedAt
        if (at == 0L) return
        pressedAt = 0L
        val age = System.currentTimeMillis() - at
        if (age > HomeSpotsProfile.recentReturnWindowMs(activity)) {
            log("recents return after ${age}ms — outside the window, nothing shown")
            return
        }

        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            HomeSpotsProfile.execSurface(activity, HomeSpotsProfile.HomePlane.RECENT_CLICK) {}
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
            abortTeardown()
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

        override fun onStop(owner: LifecycleOwner) {
            val teardown = Runnable { unregister() }
            pendingTeardown = teardown
            mainHandler.postDelayed(teardown, TEARDOWN_DELAY_MS)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            abortTeardown()
            unregister()
        }

        private fun abortTeardown() {
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
