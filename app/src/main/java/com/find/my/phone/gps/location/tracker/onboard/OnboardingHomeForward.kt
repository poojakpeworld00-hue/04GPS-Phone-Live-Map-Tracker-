package com.find.my.phone.gps.location.tracker.onboard

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
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.ui.language.LangChooserActivity
import com.find.my.phone.gps.location.tracker.ui.onboarding.PrimerActivity
import com.find.my.phone.gps.location.tracker.ui.premium.PremiumActivity
import com.find.my.phone.gps.location.tracker.ui.terms.TermsGateActivity
import io.launcher.home.extensions.isDefaultLauncher
import java.lang.ref.WeakReference

/**
 * The navigation-bar Home on a first-run screen moves onboarding forward, like Back already does.
 *
 * Only where Back moves forward ([LauncherFlow.backMovesForward] — paid/marketing users), only
 * while onboarding is unfinished, and only while this app is the default home: Home then opens our
 * launcher, which resumes the same step at once. Organic users, and a phone whose home is another
 * launcher, get a plain Home. The press is saved — the screen and its step, in prefs, so it
 * survives the process — and the step is taken when the screen is back:
 *
 * - Through our launcher: it resumes the same step itself, so the move is immediate.
 * - Through the app icon (if the launcher path was missed): the splash restarts the run, so
 *   [resumeScreen] sends it back to the step that was left instead of the first one.
 *
 * That screen, on its resume, then moves forward: a [Step] screen by its own [Step.onHomeForward]
 * (set-default skips, where its Back would only ask for the role again), any other by its own Back
 * handler — the same action, the same ads, the same next screen. The saved press is spent on the
 * first first-run screen that resumes, whichever it is, so it can never fire later on its own.
 *
 * The press is heard through `ACTION_CLOSE_SYSTEM_DIALOGS` (reason `homekey`), as RecentAdWatcher
 * hears Recents; on devices that withhold it, Home simply leaves the screen as it is.
 */
object OnboardingHomeForward : Application.ActivityLifecycleCallbacks {

    /** A first-run screen whose forward step after Home is not its Back action. */
    interface Step {
        fun onHomeForward()
    }

    private const val TAG = "OnboardingHomeForward"
    private const val REASON_HOME_KEY = "homekey"
    private const val PREFS = "onboarding_home_forward"
    private const val KEY_SCREEN = "screen"
    private const val KEY_STEP = "step"

    /** The broadcast lands as the screen is being left: a pause this recent still counts. */
    private const val LEAVE_GRACE_MS = 1_000L

    /** First-run screens whose Back moves forward. Splash has no Back step of its own. */
    private val SCREENS: Set<Class<*>> = setOf(
        HomeRoleStepActivity::class.java,
        LangChooserActivity::class.java,
        GreetingStepActivity::class.java,
        ReminderIntroActivity::class.java,
        PrimerActivity::class.java,
        PremiumActivity::class.java,
        TermsGateActivity::class.java,
    )

    private var current: WeakReference<Activity>? = null
    private var lastLeft: WeakReference<Activity>? = null
    private var lastLeftAt = 0L

    /** Call once from Application.onCreate. */
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

    /**
     * The splash's destination while a Home press is saved: the screen that was left (the paywall
     * that follows the intro is past its step already, so the run goes on from the next one).
     * Null when nothing is saved — the splash then starts the run as usual.
     */
    fun resumeScreen(context: Context): Class<*>? {
        val p = prefs(context)
        val screen = p.getString(KEY_SCREEN, null) ?: return null
        val step = p.getInt(KEY_STEP, -1)
        if (step < 0) {
            clear(context)
            return null
        }
        if (screen == PremiumActivity::class.java.name) {
            // Its Back leaves for the step after the intro; there is no step of its own to land on.
            clear(context)
            log("home on PremiumActivity → resuming after step $step")
            return LauncherFlow.resumeScreen(context, step + 1)
        }
        log("home on ${screen.substringAfterLast('.')} → resuming at step $step")
        return LauncherFlow.resumeScreen(context, step)
    }

    /** Whether [activity] is the screen a saved Home press will move on (it is about to resume). */
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
        if (!LauncherFlow.isOnboardingActive(screen) || !LauncherFlow.backMovesForward(screen)) return
        // Only with the Home role held: Home then opens our launcher, which resumes this step and
        // lets it move on at once. With another launcher as home, Home just leaves the screen.
        if (!screen.isDefaultLauncher()) return
        prefs(screen).edit()
            .putString(KEY_SCREEN, screen.javaClass.name)
            .putInt(KEY_STEP, LauncherFlow.currentStep(screen))
            .apply()
        log("home on ${screen.javaClass.simpleName} → next step on return")
    }

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
        if (activity.javaClass !in SCREENS) return
        val saved = prefs(activity).getString(KEY_SCREEN, null) ?: return
        // Spent here either way: a press can only move the screen it was made on, and only once.
        clear(activity)
        if (saved != activity.javaClass.name) return
        val screen = activity as? ComponentActivity ?: return
        // After the resume settles: the step may start a screen or show an interstitial.
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
