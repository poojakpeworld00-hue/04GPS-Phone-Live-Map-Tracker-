package com.find.phone.gps.live.location.tracker.helpers

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.find.phone.gps.live.location.tracker.screens.boot.BootActivity

class FailureReviveActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var waitedMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitUntilMainProcessIsGone()
    }

    private fun waitUntilMainProcessIsGone() {
        val alive = mainProcessAlive()
        if (alive && waitedMs < MAX_WAIT_MS) {
            waitedMs += POLL_MS
            handler.postDelayed({ waitUntilMainProcessIsGone() }, POLL_MS)
            return
        }

        if (!alive) runCatching { startActivity(targetIntent()) }
        finish()

        handler.postDelayed({ Process.killProcess(Process.myPid()) }, 500L)
    }

    private fun targetIntent(): Intent =
        if (intent.getBooleanExtra(EXTRA_AS_HOME, false)) {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(this, BootActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

    private fun mainProcessAlive(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.runningAppProcesses.orEmpty().any { it.processName == packageName }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_AS_HOME = "as_home"
        private const val POLL_MS = 150L

        private const val MAX_WAIT_MS = 5_000L

        private const val PROCESS_SUFFIX = ":crash_restart"

        fun intent(context: Context, asHome: Boolean): Intent =
            Intent(context, FailureReviveActivity::class.java)
                .putExtra(EXTRA_AS_HOME, asHome)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        fun isRestartProcess(app: Application): Boolean = processName(app)?.endsWith(PROCESS_SUFFIX) == true

        private fun processName(app: Application): String? {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                return Application.getProcessName()
            }
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val pid = Process.myPid()
            return am.runningAppProcesses.orEmpty().firstOrNull { it.pid == pid }?.processName
        }
    }
}
