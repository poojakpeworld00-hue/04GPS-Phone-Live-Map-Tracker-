package com.find.my.phone.gps.location.tracker.util

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.find.my.phone.gps.location.tracker.ui.splash.StartupActivity

/**
 * Brings the app back after a crash, from its own process (`:crash_restart` in the manifest).
 *
 * [CrashGuard] cannot start the app again itself: the crashing process is still alive while its
 * handler runs, so the system attaches the new screen to that process and it dies with it a moment
 * later, leaving the user on the device home screen. This screen lives in a separate process, so
 * it survives the crash. It waits for the crashed process to be gone, then starts the entry point
 * in a fresh one. It is invisible (translucent, no UI) and removes itself straight after.
 */
class CrashRestartActivity : Activity() {

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
        // Still alive after the wait means the system is holding it (its "keeps stopping" dialog
        // waits for the user). Starting now would attach the app to that frozen process, so leave
        // it to the dialog.
        if (!alive) runCatching { startActivity(targetIntent()) }
        finish()
        // Nothing else runs here; this process has done its one job.
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, 500L)
    }

    private fun targetIntent(): Intent =
        if (intent.getBooleanExtra(EXTRA_AS_HOME, false)) {
            // We are the device home screen: the real HOME intent can only land here, and the
            // system rebuilds the home task itself (see CrashGuard.relaunch).
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(this, StartupActivity::class.java)
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

        /** Crashlytics writes its report before the process ends; give it room, but not forever. */
        private const val MAX_WAIT_MS = 5_000L

        private const val PROCESS_SUFFIX = ":crash_restart"

        fun intent(context: Context, asHome: Boolean): Intent =
            Intent(context, CrashRestartActivity::class.java)
                .putExtra(EXTRA_AS_HOME, asHome)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        /** True in the restart process, where the Application must skip the app's own setup. */
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
