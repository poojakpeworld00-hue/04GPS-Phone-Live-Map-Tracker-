package com.find.phone.gps.live.location.tracker.induction

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile.OnboardScreen
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.NavTracerCore
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.memo.MemoProfile
import com.find.phone.gps.live.location.tracker.memo.grant.MemoConsent
import com.livemap.homedeck.screens.HomeDeck
import com.find.phone.gps.live.location.tracker.screens.CoreHubActivity
import com.find.phone.gps.live.location.tracker.helpers.WardenRail
import org.fossify.commons.extensions.getSharedPrefs
import com.livemap.homedeck.ext.isDefaultLauncher
import com.find.phone.gps.live.location.tracker.screens.tongue.TongueSelectorActivity
import com.find.phone.gps.live.location.tracker.screens.welcome.BriefingActivity
import com.find.phone.gps.live.location.tracker.homeshell.HomeFlip

object HomeStream {
    private const val TAG = "HomeStream"

    private const val WAS_ONBOARDING_COMPLETED = "was_onboarding_completed"
    private const val ONBOARDING_STEP = "onboarding_step"
    private const val ALREADY_DEFAULT_LOGGED = "default_home_already_logged"

    const val EXTRA_LAUNCHER_ONBOARDING = "extra_launcher_onboarding"

    fun isOnboarding(activity: Activity): Boolean =
        activity.intent.getBooleanExtra(EXTRA_LAUNCHER_ONBOARDING, false)

    fun isOnboardingActive(activity: Activity): Boolean =
        isOnboarding(activity) || !wasOnboardingCompleted(activity)

    fun backMovesForward(context: Context): Boolean =
        SpotsLocker.getInstance(context).getBoolean("OnMaketing")

    fun passBackThrough(activity: ComponentActivity, callback: OnBackPressedCallback) {
        callback.isEnabled = false
        activity.onBackPressedDispatcher.onBackPressed()
    }

    fun wasOnboardingCompleted(context: Context): Boolean =
        context.getSharedPrefs().getBoolean(WAS_ONBOARDING_COMPLETED, false)

    fun onboardingIntent(context: Context, target: Class<*>): Intent =
        Intent(context, target).putExtra(EXTRA_LAUNCHER_ONBOARDING, true)

    fun flagOnboardingCompleted(context: Context) {
        context.getSharedPrefs().edit().putBoolean(WAS_ONBOARDING_COMPLETED, true).apply()
    }

    fun goHome(activity: Activity) {
        flagOnboardingCompleted(activity)
        activity.startActivity(
            Intent(activity, homeActivity()).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        activity.finish()
    }

    fun homeActivity(): Class<*> =
        if (!HomeFlip.isEnabled(NavTracerCore.appContext) ||
            SpotsLocker.getInstance(NavTracerCore.appContext).getString(HOME_KEY)
                ?.trim()?.lowercase() == "app"
        ) {
            CoreHubActivity::class.java
        } else {
            HomeDeck::class.java
        }

    private const val HOME_KEY = "onboarding_home"

    private val NUMBERED_STEPS = setOf(
        OnboardScreen.SET_DEFAULT,
        OnboardScreen.LANGUAGE,
        OnboardScreen.WELCOME,
        OnboardScreen.REMINDERS,
    )

    private val LAUNCHER_ONLY_STEPS = setOf(OnboardScreen.SET_DEFAULT, OnboardScreen.WELCOME)

    data class StepPosition(val index: Int, val total: Int)

    fun stepPosition(context: Context, screen: OnboardScreen): StepPosition? {
        val launcher = HomeFlip.isEnabled(context)
        val numbered = HomeSpotsProfile.onboardingOrder(context)
            .filter { it in NUMBERED_STEPS && (launcher || it !in LAUNCHER_ONLY_STEPS) }

            .filter { it != OnboardScreen.REMINDERS || isApplicable(context, it) }
        val pos = numbered.indexOf(screen)
        return if (pos < 0) null else StepPosition(pos + 1, numbered.size)
    }

    fun wireStepHeader(context: Context, screen: OnboardScreen, root: View) {
        val header = root.findViewById<View>(R.id.induct_step_header) ?: return
        val position = stepPosition(context, screen)
        if (position == null) {
            header.visibility = View.GONE
            return
        }
        header.visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.induct_step_pill).text =
            context.getString(R.string.onboarding_step_counter, position.index, position.total)
        root.findViewById<ProgressBar>(R.id.induct_step_progress).progress =
            position.index * 100 / position.total
    }

    fun firstScreen(context: Context): Class<*> = settleFrom(context, 0)

    fun currentStep(context: Context): Int = context.getSharedPrefs().getInt(ONBOARDING_STEP, 0)

    fun resumeScreen(context: Context, step: Int): Class<*> = settleFrom(context, step)

    fun advance(activity: Activity, skipRest: Boolean = false) {
        if (skipRest) {
            log("skipping the rest of the order")
            return goHome(activity)
        }

        val next = nextActivity(activity)
        if (next == homeActivity()) {
            return goHome(activity)
        }

        activity.startActivity(onboardingIntent(activity, next))
        activity.finish()
    }

    fun nextActivity(context: Context): Class<*> =
        settleFrom(context, context.getSharedPrefs().getInt(ONBOARDING_STEP, 0) + 1)

    fun resumeIfUnfinished(activity: Activity): Boolean {
        if (wasOnboardingCompleted(activity)) {
            return false
        }

        val step = activity.getSharedPrefs().getInt(ONBOARDING_STEP, 0)
        if (HomeSpotsProfile.onboardingOrder(activity).getOrNull(step) == OnboardScreen.SET_DEFAULT &&
            activity.isDefaultLauncher()
        ) {
            log("home role granted mid-run → back to set_default to finish it")
            activity.startActivity(
                onboardingIntent(activity, MainPartPhaseActivity::class.java)
                    .putExtra(MainPartPhaseActivity.EXTRA_ROLE_GRANTED, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
            return true
        }

        val next = settleFrom(activity, step)
        if (next == homeActivity()) {
            return false
        }

        log("home reached mid-run → resuming onboarding")
        activity.startActivity(onboardingIntent(activity, next))
        return true
    }

    private fun settleFrom(context: Context, from: Int): Class<*> {
        val order = HomeSpotsProfile.onboardingOrder(context)
        var index = from

        while (index < order.size) {
            val screen = order[index]
            if (isApplicable(context, screen)) {
                context.getSharedPrefs().edit().putInt(ONBOARDING_STEP, index).apply()
                log("→ [$index] ${screen.key}")
                return activityFor(screen)
            }

            if (screen == OnboardScreen.SET_DEFAULT && alreadyGranted(context)) {
                log("[$index] ${screen.key}: role already held → home")
                break
            }

            if (screen == OnboardScreen.SET_DEFAULT) recordAlreadyDefault(context)
            log("skipping [$index] ${screen.key}")
            index++
        }

        log("order finished → home")
        flagOnboardingCompleted(context)
        return homeActivity()
    }

    private fun recordAlreadyDefault(context: Context) {
        if (!context.isDefaultLauncher()) return
        val prefs = context.getSharedPrefs()
        if (prefs.getBoolean(ALREADY_DEFAULT_LOGGED, false)) return
        prefs.edit().putBoolean(ALREADY_DEFAULT_LOGGED, true).apply()
        context.recordKeyEvent("default_home_already")
    }

    private fun isApplicable(context: Context, screen: OnboardScreen): Boolean = when (screen) {
        OnboardScreen.WELCOME -> HomeFlip.isEnabled(context)

        OnboardScreen.REMINDERS -> MemoProfile.get(context).isIntroScreen &&
            MemoConsent.mustOfferManage(context) && MemoProfile.isCountryAllowed(context)

        OnboardScreen.SET_DEFAULT -> {
            val step = HomeSpotsProfile.defaultHomeStep(context)
            HomeFlip.isEnabled(context) && step.enabled && !(step.skipIfDefault && context.isDefaultLauncher())
        }

        else -> true
    }

    private fun alreadyGranted(context: Context): Boolean {
        val step = HomeSpotsProfile.defaultHomeStep(context)
        return step.enabled && step.skipIfDefault && step.skipRestOnGrant &&
                context.isDefaultLauncher()
    }

    private fun activityFor(screen: OnboardScreen): Class<*> = when (screen) {
        OnboardScreen.WELCOME -> HelloPhaseActivity::class.java
        OnboardScreen.SET_DEFAULT -> MainPartPhaseActivity::class.java
        OnboardScreen.INTRO -> BriefingActivity::class.java
        OnboardScreen.LANGUAGE -> TongueSelectorActivity::class.java
        OnboardScreen.REMINDERS -> MemoOpeningActivity::class.java
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}

fun Activity.excludeAppFromRecents() {
    try {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        manager.appTasks
            .filter { task ->
                val info = task.taskInfo
                val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else @Suppress("DEPRECATION") info.id
                id == taskId
            }
            .forEach { task -> task.setExcludeFromRecents(true) }
    } catch (e: Exception) {
        WardenRail.error("Recents", "could not exclude task from recents", e)
    }
}
