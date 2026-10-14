package com.find.my.phone.gps.location.tracker.util

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

/**
 * Closes a screen when the user presses Recents (`ACTION_CLOSE_SYSTEM_DIALOGS`, reason
 * `recentapps`) — for the event screens (install / uninstall, charge / discharge) that must not sit
 * in Recents or be returned to later. With [closeOnHome] the Home key closes it too (the reminder
 * alert, which must not be left running behind the lock screen).
 *
 * Add it as a lifecycle observer: `lifecycle.addObserver(SystemDialogHelper(this) { finishAndRemoveTask() })`.
 * Registered while the screen is up; teardown after a stop is deferred briefly so a quick
 * stop/restart does not thrash the receiver.
 */
class SystemDialogHelper(
    private val activity: Activity,
    private val closeOnHome: Boolean = false,
    private val onCloseRequested: () -> Unit,
) : DefaultLifecycleObserver {

    private var receiver: BroadcastReceiver? = null
    private var isRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTeardown: Runnable? = null

    override fun onResume(owner: LifecycleOwner) {
        cancelPendingTeardown()
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
        cancelPendingTeardown()
        unregister()
        receiver = null
    }

    private fun register() {
        if (isRegistered) return
        val r = receiver ?: createReceiver().also { receiver = it }
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
            // Not registered — nothing to undo.
        } finally {
            isRegistered = false
        }
    }

    private fun cancelPendingTeardown() {
        pendingTeardown?.let { mainHandler.removeCallbacks(it) }
        pendingTeardown = null
    }

    private fun createReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
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
