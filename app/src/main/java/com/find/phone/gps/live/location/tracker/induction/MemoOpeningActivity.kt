package com.find.phone.gps.live.location.tracker.induction

import android.Manifest
import android.animation.Animator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.monetize.logic.logKeyEvent
import com.find.phone.gps.live.location.tracker.databinding.ViewReminderIntroBinding
import com.find.phone.gps.live.location.tracker.memo.MemoProfile
import com.find.phone.gps.live.location.tracker.memo.grant.MemoConsent
import com.find.phone.gps.live.location.tracker.memo.grant.MemoConsentSentinel
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.opening.OpeningUnveilProfile
import com.find.phone.gps.live.location.tracker.screens.opening.OpeningUnveilCharter
import com.find.phone.gps.live.location.tracker.helpers.followAdContainer
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
 * Only reached while [HomeStream] finds the step applicable (`is_intro_screen`, something to
 * allow, country not excluded).
 */
class MemoOpeningActivity : CasingDeckActivity() {

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

        val cfg = MemoProfile.get(this)
        cfg.introTitle.takeIf { it.isNotBlank() }?.let { binding.introTitle.text = it }
        cfg.introDesc.takeIf { it.isNotBlank() }?.let { binding.introDesc.text = it }

        CraftMovement.pressScale(binding.introAllow)
        binding.introAllow.setOnClickListener { onAllow() }
        binding.introSkip.setOnClickListener {
            logKeyEvent("Reminder_Intro_Skip")
            goToNextStep()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (HomeStream.backMovesForward(this@MemoOpeningActivity)) goToNextStep()
                else HomeStream.passBackThrough(this@MemoOpeningActivity, this)
            }
        })

        // The "Step n of m" header belongs to the first-run onboarding only.
        if (HomeStream.isOnboardingActive(this)) {
            HomeStream.bindStepHeader(this, HomeSpotsProfile.OnboardScreen.REMINDERS, binding.root)
        } else {
            binding.onboardingStepHeader.root.visibility = android.view.View.GONE
        }
        OpeningUnveilCharter.markShown(this, OpeningUnveilProfile.REMINDERS_INTRO)

        // Bottom mid native, `launcher_ads.onboarding.reminders.slot` (off there → frame hidden).
        HomeSpotsProfile.showSlot(
            activity = this,
            slot = HomeSpotsProfile.onboardingSlot(this, HomeSpotsProfile.OnboardScreen.REMINDERS),
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
        if (!MemoConsent.shouldOfferManage(this)) { goToNextStep(); return }
        awaitingAccess = true
        MemoConsent.openSettings(this)
        MemoConsentSentinel.start(this, MemoOpeningActivity::class.java)
        startAccessPoll()
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings: brought forward once granted, or the user pressed Back.
        if (awaitingAccess) {
            awaitingAccess = false
            accessPollJob?.cancel()
            MemoConsentSentinel.stop(this)
            goToNextStep()
        }
    }

    /** The primary return path: Settings sits in this task, so this Activity may bring itself back. */
    private fun startAccessPoll() {
        accessPollJob?.cancel()
        accessPollJob = lifecycleScope.launch {
            while (isActive) {
                if (MemoConsent.isGranted(this@MemoOpeningActivity)) {
                    runCatching { startActivity(MemoConsent.returnIntent(this@MemoOpeningActivity, MemoOpeningActivity::class.java)) }
                    break
                }
                delay(500L)
            }
        }
    }

    /** The phone floats, the chips drift out of phase (the onboarding's `floaty`). */
    private fun playMotion() {
        if (CraftMovement.animationsDisabled(this)) return
        animators += CraftMovement.floaty(binding.introPhone, amplitudeDp = 6f)
        animators += CraftMovement.floaty(binding.introChipTonight, delayMs = 600L)
        animators += CraftMovement.floaty(binding.introChipDone, delayMs = 1400L)
    }

    private var leaving = false

    private fun goToNextStep() {
        if (leaving) return
        leaving = true
        accessPollJob?.cancel()
        // The step's exit interstitial, if `launcher_ads.onboarding.reminders` asks for one.
        HomeSpotsProfile.runOnboardingInter(this, HomeSpotsProfile.OnboardScreen.REMINDERS) {
            val next = nextIntent()
            when {
                next != null -> { startActivity(next); finish() }
                HomeStream.isOnboardingActive(this) -> HomeStream.advance(this)
                else -> HomeStream.goHome(this)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun nextIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_NEXT, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_NEXT)

    override fun onDestroy() {
        accessPollJob?.cancel()
        CraftMovement.cancelAll(animators)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_NEXT = "reminders_intro_next"

        /**
         * The later-launches chain: [next] itself, or this intro first (carrying [next] on) when
         * [OpeningUnveilCharter.shouldShowRemindersIntro] says so. Never during the first-run
         * onboarding, which has its own `reminders` step.
         */
        fun wrap(activity: android.app.Activity, next: Intent): Intent =
            if (!HomeStream.isOnboardingActive(activity) && OpeningUnveilCharter.shouldShowRemindersIntro(activity)) {
                Intent(activity, MemoOpeningActivity::class.java).putExtra(EXTRA_NEXT, next)
            } else {
                next
            }
    }
}
