package com.find.phone.gps.live.location.tracker.foundation

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.screenToken
import com.find.phone.gps.live.location.tracker.monetize.render.CoreOpenSpotBook
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverRear
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverStandard
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/**
 * App-wide navigation analytics:
 *  - `screen_<name>` for every own Activity that does not extend [StageActivity] (which logs it itself);
 *  - `<screen>_back_press` for every own Activity, via a pass-through back callback;
 *  - `<screen>_home_press` when the user really leaves the app with Home / Recents.
 */
object NavPressTracker : Application.ActivityLifecycleCallbacks {

    private const val OWN_PREFIX = "com.find.phone.gps.live.location.tracker"
    private const val LAUNCHER_PREFIX = "com.livemap.homedeck"
    private const val WINDOW_MS = 2_000L

    private val hooked = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var started = 0
    private var leaveHintAt = 0L
    private var leaveHintFrom: String? = null
    private var appLaunchAt = 0L

    @Volatile var fullScreenAdShowing = false

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    /** The app itself is starting an Activity (internal screen, settings, share sheet, browser…). */
    fun markAppLaunch() {
        appLaunchAt = SystemClock.elapsedRealtime()
    }

    /** Called from `onUserLeaveHint()` of the base activities. */
    fun onLeaveHint(activity: Activity) {
        val now = SystemClock.elapsedRealtime()
        if (now - appLaunchAt < WINDOW_MS) return
        leaveHintAt = now
        leaveHintFrom = screenToken(activity)
    }

    private fun isOwn(activity: Activity) = activity.javaClass.name.let {
        it.startsWith(OWN_PREFIX) || it.startsWith(LAUNCHER_PREFIX)
    }

    private fun adShowing() = fullScreenAdShowing || CoreOpenSpotBook.isShowingAd ||
        TakeoverStandard.isInterShow || TakeoverRear.isInterBAckShow

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!isOwn(activity) || activity is StageActivity<*>) return
        activity.recordKeyEvent("screen_${activity.javaClass.simpleName.lowercase(Locale.ROOT)}")
    }

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityResumed(activity: Activity) {
        if (!isOwn(activity) || activity !is ComponentActivity || !hooked.add(activity)) return
        val dispatcher = activity.onBackPressedDispatcher
        dispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                activity.recordKeyEvent("${screenToken(activity)}_back_press")
                isEnabled = false
                try {
                    dispatcher.onBackPressed()
                } finally {
                    isEnabled = true
                }
            }
        })
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started > 0 || activity.isFinishing || activity.isChangingConfigurations) return
        val hintAge = SystemClock.elapsedRealtime() - leaveHintAt
        val from = leaveHintFrom
        leaveHintFrom = null
        if (from == null || hintAge > WINDOW_MS || adShowing() || !isInteractive(activity)) return
        activity.recordKeyEvent("${from}_home_press")
    }

    private fun isInteractive(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        hooked.remove(activity)
    }
}
