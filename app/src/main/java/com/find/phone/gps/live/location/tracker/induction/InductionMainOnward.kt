package com.find.phone.gps.live.location.tracker.induction

import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.screens.tongue.TongueSelectorActivity
import com.find.phone.gps.live.location.tracker.screens.welcome.BriefingActivity
import com.find.phone.gps.live.location.tracker.screens.plus.PlusActivity
import com.find.phone.gps.live.location.tracker.screens.policy.PolicyPortalActivity
import com.livemap.homedeck.ext.isDefaultLauncher
import java.lang.ref.WeakReference

object InductionMainOnward : Application.ActivityLifecycleCallbacks {
    interface Step {
        fun onHomeForward()
    }

    private const val TAG = "InductionMainOnward"
    private const val REASON_HOME_KEY = "homekey"
    private const val PREFS = "onboarding_home_forward"
    private const val KEY_SCREEN = "screen"
    private const val KEY_STEP = "step"

    private const val LEAVE_GRACE_MS = 1_000L

    private val SCREENS: Set<Class<*>> = setOf(
        MainPartPhaseActivity::class.java,
        TongueSelectorActivity::class.java,
        HelloPhaseActivity::class.java,
        MemoOpeningActivity::class.java,
        BriefingActivity::class.java,
        PlusActivity::class.java,
        PolicyPortalActivity::class.java,
    )

    private var current: WeakReference<Activity>? = null
    private var lastLeft: WeakReference<Activity>? = null
    private var lastLeftAt = 0L

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        runCatching {
            ContextCompat.registerReceiver(
                app, receiver,
                IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { log("receiver not registered: ${it.message}") }
    }

    fun resumeScreen(context: Context): Class<*>? {
        val p = prefs(context)
        val screen = p.getString(KEY_SCREEN, null) ?: return null
        val step = p.getInt(KEY_STEP, -1)
        if (step < 0) {
            clear(context)
            return null
        }
        if (screen == PlusActivity::class.java.name) {
            clear(context)
            // Home pressed on the paywall counts as skipping it, same as its close button
            SpotsLocker.getInstance(context).putBoolean(HomeSpotsProfile.PREMIUM_GATE_SEEN, false)
            log("home on PlusActivity → resuming after step $step")
            return HomeStream.resumeScreen(context, step + 1)
        }
        log("home on ${screen.substringAfterLast('.')} → resuming at step $step")
        return HomeStream.resumeScreen(context, step)
    }

    fun isSavedFor(activity: Activity): Boolean =
        prefs(activity).getString(KEY_SCREEN, null) == activity.javaClass.name

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
            if (intent.getStringExtra("reason") != REASON_HOME_KEY) return
            onHomePressed()
        }
    }

    private fun onHomePressed() {
        val screen = current?.get()
            ?: lastLeft?.get()?.takeIf { System.currentTimeMillis() - lastLeftAt < LEAVE_GRACE_MS }
            ?: return
        if (screen.javaClass !in SCREENS) return
        if (!HomeStream.isOnboardingActive(screen) || !HomeStream.backMovesForward(screen)) return

        if (!screen.isDefaultLauncher()) return
        prefs(screen).edit()
            .putString(KEY_SCREEN, screen.javaClass.name)
            .putInt(KEY_STEP, HomeStream.currentStep(screen))
            .apply()
        log("home on ${screen.javaClass.simpleName} → next step on return")
    }

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
        if (activity.javaClass !in SCREENS) return
        val saved = prefs(activity).getString(KEY_SCREEN, null) ?: return

        clear(activity)
        if (saved != activity.javaClass.name) return
        val screen = activity as? ComponentActivity ?: return

        screen.window.decorView.post {
            if (screen.isFinishing || screen.isDestroyed) return@post
            log("forward on ${screen.javaClass.simpleName} after home")
            if (screen is Step) screen.onHomeForward() else screen.onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (current?.get() === activity) current = null
        lastLeft = WeakReference(activity)
        lastLeftAt = System.currentTimeMillis()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
