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
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordPermissionResult
import com.find.phone.gps.live.location.tracker.databinding.ScreenReminderIntroBinding
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

class MemoOpeningActivity : CasingDeckActivity() {
    private val binding by viewBinding(ScreenReminderIntroBinding::inflate)
    private val animators = mutableListOf<Animator>()
    private var awaitingAccess = false
    private var accessPollJob: Job? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        recordPermissionResult(Manifest.permission.POST_NOTIFICATIONS, granted)
        window.decorView.post { enterAccessOrNext() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        excludeAppFromRecents()

        val cfg = MemoProfile.get(this)
        cfg.introTitle.takeIf { it.isNotBlank() }?.let { binding.openTitle.text = it }
        cfg.introDesc.takeIf { it.isNotBlank() }?.let { binding.openDesc.text = it }

        CraftMovement.pressScale(binding.openAllow)
        binding.openAllow.setOnClickListener { onAllow() }
        binding.openSkip.setOnClickListener {
            recordKeyEvent("Reminder_Intro_Skip")
            headToNextStep()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (HomeStream.backMovesForward(this@MemoOpeningActivity)) headToNextStep()
                else HomeStream.passBackThrough(this@MemoOpeningActivity, this)
            }
        })

        if (HomeStream.isOnboardingActive(this)) {
            HomeStream.wireStepHeader(this, HomeSpotsProfile.OnboardScreen.REMINDERS, binding.root)
        } else {
            binding.inductStepHeader.root.visibility = android.view.View.GONE
        }
        OpeningUnveilCharter.flagShown(this, OpeningUnveilProfile.REMINDERS_INTRO)

        HomeSpotsProfile.presentSlot(
            activity = this,
            slot = HomeSpotsProfile.onboardingSlot(this, HomeSpotsProfile.OnboardScreen.REMINDERS),
            container = binding.spotNativeFrame,
            shimmer = binding.spotShimmer,
        )
        binding.spotNativeDivider.followAdContainer(binding.spotNativeFrame)
        recordKeyEvent("Reminder_Intro_Show")
        playMotion()
    }

    private fun onAllow() {
        recordKeyEvent("Reminder_Intro_Allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            recordKeyEvent("Permission_POST_NOTIFICATIONS_Show")
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            recordKeyEvent("Permission_POST_NOTIFICATIONS_Bypass")
            enterAccessOrNext()
        }
    }

    private fun enterAccessOrNext() {
        if (!MemoConsent.mustOfferManage(this)) { headToNextStep(); return }
        awaitingAccess = true
        MemoConsent.enterSettings(this)
        MemoConsentSentinel.start(this, MemoOpeningActivity::class.java)
        beginAccessPoll()
    }

    override fun onResume() {
        super.onResume()

        if (awaitingAccess) {
            awaitingAccess = false
            accessPollJob?.cancel()
            MemoConsentSentinel.stop(this)
            headToNextStep()
        }
    }

    private fun beginAccessPoll() {
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

    private fun playMotion() {
        if (CraftMovement.animationsDisabled(this)) return
        animators += CraftMovement.floaty(binding.openPhone, amplitudeDp = 6f)
        animators += CraftMovement.floaty(binding.openChipTonight, delayMs = 600L)
        animators += CraftMovement.floaty(binding.openChipDone, delayMs = 1400L)
    }

    private var leaving = false

    private fun headToNextStep() {
        if (leaving) return
        leaving = true
        accessPollJob?.cancel()

        HomeSpotsProfile.execOnboardingInter(this, HomeSpotsProfile.OnboardScreen.REMINDERS) {
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
        CraftMovement.abortAll(animators)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_NEXT = "reminders_intro_next"

        fun wrap(activity: android.app.Activity, next: Intent): Intent =
            if (!HomeStream.isOnboardingActive(activity) && OpeningUnveilCharter.mustShowRemindersIntro(activity)) {
                Intent(activity, MemoOpeningActivity::class.java).putExtra(EXTRA_NEXT, next)
            } else {
                next
            }
    }
}
