package com.find.my.phone.gps.location.tracker.onboard

import android.Manifest
import android.animation.Animator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.find.my.phone.gps.location.tracker.adcast.domain.LauncherAdsConfig
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.databinding.ViewReminderIntroBinding
import com.find.my.phone.gps.location.tracker.reminder.ReminderConfig
import com.find.my.phone.gps.location.tracker.reminder.access.ReminderAccess
import com.find.my.phone.gps.location.tracker.reminder.access.ReminderAccessWatcher
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.intro.IntroRevealConfig
import com.find.my.phone.gps.location.tracker.ui.intro.IntroRevealPolicy
import com.find.my.phone.gps.location.tracker.util.followAdContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.viewBinding

/**
 * Reminders intro (reminders design 5). Two ways in:
 * - first run: the `reminders` step of `launcher_ads.onboarding.order` (default after Welcome);
 * - later launches: the Splash → Language → Terms → Intro chain, right after Intro and before
 *   Premium / Home, gated by `intro_display.reminders_intro` ([wrap]); the screen that would have
 *   come next travels along as [EXTRA_NEXT].
 * "Turn on reminders" asks for notifications (13+), then opens the reminders
 * setting with auto-return; either way the flow then moves on. Skip moves on straight away.
 * Only reached while [LauncherFlow] finds the step applicable (`is_intro_screen`, something to
 * allow, country not excluded).
 */
class ReminderIntroActivity : ShellDeckActivity() {

    private val binding by viewBinding(ViewReminderIntroBinding::inflate)
    private val animators = mutableListOf<Animator>()
    private var awaitingAccess = false
    private var accessPollJob: Job? = null

    /** Whatever the answer, continue to the reminders setting (deferred past the resume). */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        window.decorView.post { openAccessOrNext() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        excludeAppFromRecents()

        val cfg = ReminderConfig.get(this)
        cfg.introTitle.takeIf { it.isNotBlank() }?.let { binding.introTitle.text = it }
        cfg.introDesc.takeIf { it.isNotBlank() }?.let { binding.introDesc.text = it }

        DesignMotion.pressScale(binding.introAllow)
        binding.introAllow.setOnClickListener { onAllow() }
        binding.introSkip.setOnClickListener {
            logKeyEvent("Reminder_Intro_Skip")
            goToNextStep()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (LauncherFlow.backMovesForward(this@ReminderIntroActivity)) goToNextStep()
                else LauncherFlow.passBackThrough(this@ReminderIntroActivity, this)
            }
        })

        // The "Step n of m" header belongs to the first-run onboarding only.
        if (LauncherFlow.isOnboardingActive(this)) {
            LauncherFlow.bindStepHeader(this, LauncherAdsConfig.OnboardScreen.REMINDERS, binding.root)
        } else {
            binding.onboardingStepHeader.root.visibility = android.view.View.GONE
        }
        IntroRevealPolicy.markShown(this, IntroRevealConfig.REMINDERS_INTRO)

        // Bottom mid native, `launcher_ads.onboarding.reminders.slot` (off there → frame hidden).
        LauncherAdsConfig.showSlot(
            activity = this,
            slot = LauncherAdsConfig.onboardingSlot(this, LauncherAdsConfig.OnboardScreen.REMINDERS),
            container = binding.adNativeFrame,
            shimmer = binding.adShimmer,
        )
        binding.adNativeDivider.followAdContainer(binding.adNativeFrame)
        logKeyEvent("Reminder_Intro_Show")
        playMotion()
    }

    private fun onAllow() {
        logKeyEvent("Reminder_Intro_Allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAccessOrNext()
        }
    }

    private fun openAccessOrNext() {
        if (!ReminderAccess.shouldOfferManage(this)) { goToNextStep(); return }
        awaitingAccess = true
        ReminderAccess.openSettings(this)
        ReminderAccessWatcher.start(this, ReminderIntroActivity::class.java)
        startAccessPoll()
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings: brought forward once granted, or the user pressed Back.
        if (awaitingAccess) {
            awaitingAccess = false
            accessPollJob?.cancel()
            ReminderAccessWatcher.stop(this)
            goToNextStep()
        }
    }

    /** The primary return path: Settings sits in this task, so this Activity may bring itself back. */
    private fun startAccessPoll() {
        accessPollJob?.cancel()
        accessPollJob = lifecycleScope.launch {
            while (isActive) {
                if (ReminderAccess.isGranted(this@ReminderIntroActivity)) {
                    runCatching { startActivity(ReminderAccess.returnIntent(this@ReminderIntroActivity, ReminderIntroActivity::class.java)) }
                    break
                }
                delay(500L)
            }
        }
    }

    /** The phone floats, the chips drift out of phase (the onboarding's `floaty`). */
    private fun playMotion() {
        if (DesignMotion.animationsDisabled(this)) return
        animators += DesignMotion.floaty(binding.introPhone, amplitudeDp = 6f)
        animators += DesignMotion.floaty(binding.introChipTonight, delayMs = 600L)
        animators += DesignMotion.floaty(binding.introChipDone, delayMs = 1400L)
    }

    private var leaving = false

    private fun goToNextStep() {
        if (leaving) return
        leaving = true
        accessPollJob?.cancel()
        // The step's exit interstitial, if `launcher_ads.onboarding.reminders` asks for one.
        LauncherAdsConfig.runOnboardingInter(this, LauncherAdsConfig.OnboardScreen.REMINDERS) {
            val next = nextIntent()
            when {
                next != null -> { startActivity(next); finish() }
                LauncherFlow.isOnboardingActive(this) -> LauncherFlow.advance(this)
                else -> LauncherFlow.goHome(this)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun nextIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_NEXT, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_NEXT)

    override fun onDestroy() {
        accessPollJob?.cancel()
        DesignMotion.cancelAll(animators)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_NEXT = "reminders_intro_next"

        /**
         * The later-launches chain: [next] itself, or this intro first (carrying [next] on) when
         * [IntroRevealPolicy.shouldShowRemindersIntro] says so. Never during the first-run
         * onboarding, which has its own `reminders` step.
         */
        fun wrap(activity: android.app.Activity, next: Intent): Intent =
            if (!LauncherFlow.isOnboardingActive(activity) && IntroRevealPolicy.shouldShowRemindersIntro(activity)) {
                Intent(activity, ReminderIntroActivity::class.java).putExtra(EXTRA_NEXT, next)
            } else {
                next
            }
    }
}
