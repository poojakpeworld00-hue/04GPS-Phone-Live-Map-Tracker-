package com.find.phone.gps.live.location.tracker.induction

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.find.phone.gps.live.location.tracker.helpers.WardenRail

object HomeClueCue {
    private const val SHOW_DELAY_MS = 600L

    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun presentAfterSettings(context: Context) {
        abortPending()
        val app = context.applicationContext
        val task = Runnable {
            pending = null
            runCatching {
                app.startActivity(PartClueActivity.intent(app))
            }.onFailure { WardenRail.error("DefaultHomeHint", "hint could not be started", it) }
        }
        pending = task
        main.postDelayed(task, SHOW_DELAY_MS)
    }

    fun dismiss() {
        abortPending()
        PartClueActivity.dismiss()
    }

    private fun abortPending() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
    }
}
