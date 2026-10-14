package com.find.phone.gps.live.location.tracker.helpers

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import com.livemap.homedeck.ext.isDefaultLauncher
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics

object FailureWarden {
    private const val TAG = "FailureWarden"

    private const val RESTART_AFTER_CRASH = true

    private const val LOOP_WINDOW_MS = 60_000L

    private const val PREFS_NAME = "crash_guard"
    private const val KEY_LAST_CRASH_AT = "last_crash_at"

    fun install(app: Application, isForeground: () -> Boolean) {
        val chained = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->

            val restarting = runCatching {
                RESTART_AFTER_CRASH && isForeground() && !isCrashLoop(app)
            }.getOrDefault(false)

            runCatching {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Uncaught exception on thread '${thread.name}'", error)
                } else {
                    FirebaseCrashlytics.getInstance().apply {
                        log("$TAG: uncaught on thread '${thread.name}'")
                        setCustomKey("crash_guard_restarting", restarting)
                    }
                }
            }

            if (restarting) {
                runCatching { relaunch(app) }
            }
            runCatching { Log.w(TAG, "crash handled on '${thread.name}', restarting=$restarting") }

            if (chained != null) {
                chained.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
            }
        }

        WardenRail.log(TAG, "installed (chained=${chained != null})")
    }

    private fun isCrashLoop(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previous = prefs.getLong(KEY_LAST_CRASH_AT, 0L)

        prefs.edit().putLong(KEY_LAST_CRASH_AT, now).commit()

        return previous != 0L && now - previous in 0..LOOP_WINDOW_MS
    }

    private fun relaunch(app: Application) {
        val asHome = runCatching { app.isDefaultLauncher() }.getOrDefault(false)
        app.startActivity(FailureReviveActivity.intent(app, asHome))
    }
}
