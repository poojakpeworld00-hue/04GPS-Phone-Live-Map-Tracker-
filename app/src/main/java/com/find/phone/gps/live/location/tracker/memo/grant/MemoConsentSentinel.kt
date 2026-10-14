package com.find.phone.gps.live.location.tracker.memo.grant

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class MemoConsentSentinel : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var returnToClass: String? = null

    private val poll = object : Runnable {
        override fun run() {
            if (MemoConsent.isGranted(this@MemoConsentSentinel)) {
                bringAppToFront(); stopSelf()
            } else {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        returnToClass = intent?.getStringExtra(EXTRA_RETURN_TO)
        handler.removeCallbacks(poll)
        handler.post(poll)

        handler.postDelayed({ stopSelf() }, MAX_WATCH_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bringAppToFront() {
        val intent = returnToClass
            ?.let { runCatching { Class.forName(it) }.getOrNull() }
            ?.let { Intent(this, it) }
            ?: packageManager.getLaunchIntentForPackage(packageName)
            ?: return
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        runCatching { startActivity(intent) }
    }

    companion object {
        private const val EXTRA_RETURN_TO = "return_to"
        private const val POLL_MS = 500L
        private const val MAX_WATCH_MS = 3 * 60_000L

        fun start(context: Context, returnTo: Class<out Activity>) {
            runCatching {
                context.startService(
                    Intent(context, MemoConsentSentinel::class.java).putExtra(EXTRA_RETURN_TO, returnTo.name)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MemoConsentSentinel::class.java)) }
        }
    }
}
