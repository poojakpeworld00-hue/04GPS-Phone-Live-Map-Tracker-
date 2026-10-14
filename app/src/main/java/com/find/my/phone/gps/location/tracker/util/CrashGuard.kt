package com.find.my.phone.gps.location.tracker.util

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import io.launcher.home.extensions.isDefaultLauncher
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Process-wide crash handling: records the crash with context, then puts the user back in the app
 * instead of leaving them on the system's "app keeps stopping" dialog.
 *
 * Crashlytics installs its own `UncaughtExceptionHandler` when Firebase initialises, and that is
 * what actually writes the report. This **wraps** it rather than replacing it: the captured
 * handler is always invoked at the end, so failing to chain would lose every crash report. Which
 * is also why [install] has to run *after* Firebase is initialised.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"

    /**
     * Set false to let a crash kill the process with the system dialog instead of relaunching.
     *
     * Relaunching is the friendlier default here because this app can *be* the device home
     * screen, and a home screen that vanishes leaves the user with nowhere to go. The cost is
     * that crashes get less visible in the wild, which [LOOP_WINDOW_MS] bounds.
     */
    private const val RESTART_AFTER_CRASH = true

    /**
     * Two crashes closer together than this count as a loop, and the second one is allowed to
     * kill the app for good rather than restarting into the same crash forever.
     *
     * 60s matches the system's own window: Android treats a second crash within a minute as
     * repeating and shows its "keeps stopping" dialog, which holds the crashed process alive until
     * the user answers. Restarting on top of that attaches the new screen to the frozen process
     * (seen on device), so inside this window the system dialog is the end state instead.
     *
     * Persisted, not in-memory: a crash loop is a *chain of processes*, each crashing and
     * starting the next, so an in-memory timestamp would never see more than the first one.
     */
    private const val LOOP_WINDOW_MS = 60_000L

    private const val PREFS_NAME = "crash_guard"
    private const val KEY_LAST_CRASH_AT = "last_crash_at"

    /**
     * @param isForeground whether the app is actually on screen. A crash on a background thread
     * while the user is in another app must not yank them into this one.
     *
     * Deliberately not "is there a non-null current Activity": the Application tracks that for
     * the app-open ad and clears it on *destroy*, not on pause, so it stays set the whole time
     * the app sits in the background with a live Activity. Process lifecycle is the honest
     * signal here.
     */
    fun install(app: Application, isForeground: () -> Boolean) {
        val chained = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Every step is wrapped: this runs while the process is already failing, and a second
            // throw from inside the handler would lose the original report.
            val restarting = runCatching {
                RESTART_AFTER_CRASH && isForeground() && !isCrashLoop(app)
            }.getOrDefault(false)

            // Context only, attached to the fatal report the chained handler writes below. Not
            // GuardRail.error: in release that records the throwable as a non-fatal too, so every
            // crash showed up twice in Crashlytics.
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
                // Started from here, while this is still the foreground process: an Activity start
                // from a dead process — or from an AlarmManager PendingIntent afterwards — counts
                // as a background activity start and is blocked on Android 10+.
                runCatching { relaunch(app) }
            }
            runCatching { Log.w(TAG, "crash handled on '${thread.name}', restarting=$restarting") }

            // Always hand over: the captured handler is Crashlytics', the report is written from
            // there, and it ends the process — which we want either way.
            if (chained != null) {
                chained.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
            }
        }

        GuardRail.log(TAG, "installed (chained=${chained != null})")
    }

    /**
     * True when the previous crash was recent enough that restarting would just loop.
     *
     * Records this crash's time as a side effect, so the *next* process sees it. Uses wall clock
     * because it has to survive the process; the window is short enough that only a clock change
     * landing inside it could misjudge, and the cost of that is one extra restart.
     */
    private fun isCrashLoop(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previous = prefs.getLong(KEY_LAST_CRASH_AT, 0L)

        // commit(), not apply(): the process is about to be killed and an async write would lose
        // the timestamp the next process needs to detect the loop.
        prefs.edit().putLong(KEY_LAST_CRASH_AT, now).commit()

        return previous != 0L && now - previous in 0..LOOP_WINDOW_MS
    }

    /**
     * Restarts the app at the right entry point for what it currently is.
     *
     * When this app holds the HOME role it *is* the device home screen, so it comes back through
     * the home screen rather than the splash — and it gets there by firing the real HOME intent
     * rather than starting the Activity directly. Two reasons:
     *
     *  - The system re-establishes the home task itself, with the right activity type. Starting a
     *    default-affinity Activity into that task by hand is the trap that stopped the post-call
     *    screen from ever appearing (see `taskAffinity=""` in the manifest).
     *  - `StartupActivity` also carries the package's default affinity, so `CLEAR_TASK` on it would
     *    clear the *home* task and leave the splash rooted in it.
     *
     * A generic HOME intent normally risks handing the user to whichever launcher is default —
     * the reason [com.find.my.phone.gps.location.tracker.ui.AppCoreActivity] deliberately avoids it — but in
     * this branch we are that launcher, so it can only land here.
     */
    private fun relaunch(app: Application) {
        // Not started directly: this process is still alive, so the system would attach the new
        // screen to it and it would die with the crash (seen on device: the user lands on the
        // system home). CrashRestartActivity runs in its own process and starts the entry point
        // once this one is gone.
        val asHome = runCatching { app.isDefaultLauncher() }.getOrDefault(false)
        app.startActivity(CrashRestartActivity.intent(app, asHome))
    }
}
