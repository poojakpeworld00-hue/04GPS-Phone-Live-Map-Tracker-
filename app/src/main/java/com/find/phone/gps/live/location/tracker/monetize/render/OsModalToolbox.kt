package com.find.phone.gps.live.location.tracker.monetize.render

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

class OsModalToolbox(
    private val activity: Activity,
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
            ContextCompat.RECEIVER_NOT_EXPORTED,
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
            try {
                if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
                when (intent.getStringExtra(EXTRA_REASON)) {
                    REASON_HOME_KEY, REASON_RECENT_APPS -> {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            onCloseRequested()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val TEARDOWN_DELAY_MS = 100L
        const val EXTRA_REASON = "reason"
        const val REASON_HOME_KEY = "homekey"
        const val REASON_RECENT_APPS = "recentapps"
    }
}
