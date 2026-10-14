package com.find.phone.gps.live.location.tracker.helpers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class OsModalAide(
    private val activity: Activity,
    private val closeOnHome: Boolean = false,
    private val onCloseRequested: () -> Unit,
) : DefaultLifecycleObserver {
    private var receiver: BroadcastReceiver? = null
    private var isRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTeardown: Runnable? = null

    override fun onResume(owner: LifecycleOwner) {
        abortPendingTeardown()
        register()
    }

    override fun onStop(owner: LifecycleOwner) {
        val teardown = Runnable {
            unregister()
            pendingTeardown = null
        }
        pendingTeardown = teardown
        mainHandler.postDelayed(teardown, TEARDOWN_DELAY_MS)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        abortPendingTeardown()
        unregister()
        receiver = null
    }

    private fun register() {
        if (isRegistered) return
        val r = receiver ?: spawnReceiver().also { receiver = it }
        ContextCompat.registerReceiver(
            activity,
            r,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_EXPORTED,
        )
        isRegistered = true
    }

    private fun unregister() {
        if (!isRegistered) return
        val r = receiver ?: return
        try {
            activity.unregisterReceiver(r)
        } catch (_: IllegalArgumentException) {
        } finally {
            isRegistered = false
        }
    }

    private fun abortPendingTeardown() {
        pendingTeardown?.let { mainHandler.removeCallbacks(it) }
        pendingTeardown = null
    }

    private fun spawnReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching {
                if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
                val reason = intent.getStringExtra(EXTRA_REASON)
                val close = reason == REASON_RECENT_APPS || reason == REASON_RECENT_APPS_ALT ||
                    (closeOnHome && reason == REASON_HOME_KEY)
                if (close && !activity.isFinishing && !activity.isDestroyed) onCloseRequested()
            }
        }
    }

    private companion object {
        const val TEARDOWN_DELAY_MS = 100L
        const val EXTRA_REASON = "reason"
        const val REASON_RECENT_APPS = "recentapps"
        const val REASON_RECENT_APPS_ALT = "recent_apps"
        const val REASON_HOME_KEY = "homekey"
    }
}
