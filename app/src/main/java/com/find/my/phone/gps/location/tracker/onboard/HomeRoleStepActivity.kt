package com.find.my.phone.gps.location.tracker.onboard

import com.find.my.phone.gps.location.tracker.BuildConfig
import android.animation.Animator
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.find.my.phone.gps.location.tracker.adcast.domain.LauncherAdsConfig
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.adcast.presentation.oninterAds.InterstitialNormal
import com.find.my.phone.gps.location.tracker.databinding.ViewOnboardingDefaultLauncherBinding
import com.find.my.phone.gps.location.tracker.permission.AccessEngine
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.util.hideWhileAdVisible
import io.launcher.home.extensions.isDefaultLauncher
import io.launcher.home.extensions.roleManager
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.isQPlus

/**
 * The "Set as default launcher?" decision point (design 4b).
 *
 * A fresh arrival opens the first stage by itself; the CTA re-runs it. Two chances, in order:
 * the system home-app settings page, then the Q+ role dialog. Grant → home (with
 * `skip_rest_on_grant`), cancel → stay, Skip → whatever `onboarding.order` has next.
 * The two stages are driven separately because the launcher module's helper fires whichever
 * intent resolves first and never reaches the role dialog on a device with a settings page.
 *
 * Opened with [EXTRA_ROLE_GRANTED] (the role was just granted and the OS started the launcher
 * instead of returning here, see [LauncherFlow.resumeIfUnfinished]) it never shows itself: the
 * screen stays blank and the grant path runs at once — interstitial, then the next step.
 */
class HomeRoleStepActivity : ShellDeckActivity(), OnboardingHomeForward.Step {

    companion object {
        private const val REQ_HOME_SETTINGS = 7011
        private const val REQ_ROLE_HOME = 7012
        private const val TAG = "HomeRoleStep"

        /** Finish a grant that happened while this screen was away: no page, straight to the ad. */
        const val EXTRA_ROLE_GRANTED = "role_granted"

        /**
         * The surface last asked for the role, for the grant event — kept for the process, since a
         * grant through the settings list comes back to a new instance (EXTRA_ROLE_GRANTED).
         */
        private var lastRequest = 0
        private var grantLogged = false
    }

    private val binding by viewBinding(ViewOnboardingDefaultLauncherBinding::inflate)
    private val animators = mutableListOf<Animator>()

    /** One-way latch: once a destination is committed, nothing else may pick another. */
    private var leaving = false

    /** True between launching a request and its result. */
    private var requestInFlight = false

    /** Open the home-app page on the first onResume. */
    private var autoOpenPending = false

    /** The decline-time permission ask runs once per visit. */
    private var askedOnDecline = false

    /** Whether Skip is on screen — without it, a cancelled role dialog has to move on. */
    private var skipEnabled = true

    /** Here only to finish a grant ([EXTRA_ROLE_GRANTED]): nothing shown, no delay. */
    private var finishingGrant = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        excludeAppFromRecents()

        if (intent.getBooleanExtra(EXTRA_ROLE_GRANTED, false)) {
            finishingGrant = true
            binding.root.isInvisible = true
            return
        }

        if (savedInstanceState == null) logKeyEvent("default_home_view")

        DesignMotion.pressScale(binding.onboardingSetDefault)
        binding.onboardingSetDefault.setOnClickListener {
            if (isDefaultLauncher()) goHome() else openHomeSettings()
        }
        binding.onboardingSkip.setOnClickListener {
            logKeyEvent("default_home_skip")
            goToNextStep()
        }

        val ui = LauncherAdsConfig.onboardingUi(this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT)
        skipEnabled = ui.skipEnabled
        binding.onboardingSkip.isVisible = ui.skipEnabled

        // Paid users: Back gets one last ask (the role dialog). Organic users: Back is Back.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (LauncherFlow.backMovesForward(this@HomeRoleStepActivity)) promptForRole()
                else LauncherFlow.passBackThrough(this@HomeRoleStepActivity, this)
            }
        })

        // Native big ad card, `launcher_ads.onboarding.set_default.slot`; the frame is hidden
        // outright when the slot is off.
        LauncherAdsConfig.showSlot(
            activity = this,
            slot = LauncherAdsConfig.onboardingSlot(this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT),
            container = binding.adNativeFrame,
            shimmer = binding.adShimmer,
        )

        // Small phones: the hero card gives way to the ad while one is on screen.
        binding.imageHero.hideWhileAdVisible(binding.adNativeFrame, binding.heroCompact)

        LauncherFlow.bindStepHeader(this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT, binding.root)

        // First arrival: open the system default-app page straight away, not on a restore.
        // Debug builds accept `qa_no_auto_open` so the screen itself can be inspected via adb.
        val qaHold = BuildConfig.DEBUG && intent.getBooleanExtra("qa_no_auto_open", false)
        // Not when it is back only to be skipped past (Home was pressed here, OnboardingHomeForward).
        if (savedInstanceState == null && !qaHold && !OnboardingHomeForward.isSavedFor(this)) autoOpenPending = true

        playMotion()
    }

    /** The chooser card and the swipe chip float (`floaty 4s`, the chip offset 1.4s). */
    private fun playMotion() {
        if (DesignMotion.animationsDisabled(this)) return
        animators += DesignMotion.floaty(binding.chooserCard)
        animators += DesignMotion.floaty(binding.chipSwipe, delayMs = 1400L)
    }

    // ===== the two-stage request =====

    /** Stage 1 — the settings page listing the installed home apps. */
    private fun openHomeSettings() {
        if (leaving || requestInFlight) return
        launchHomeSettings()
        // The coach-mark follows the list rather than racing it — see LauncherHintPrompt.
        LauncherHintPrompt.showAfterSettings(this)
    }

    private fun launchHomeSettings() {
        if (leaving) return
        val opened = launchForResult(Intent(Settings.ACTION_HOME_SETTINGS), REQ_HOME_SETTINGS) ||
            launchForResult(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), REQ_HOME_SETTINGS)
        // A ROM with neither page would otherwise dead-end the CTA, so skip to stage 2.
        if (!opened) promptForRole()
    }

    /** Stage 2 — the one-tap role dialog, for when stage 1 came back with nothing changed. */
    private fun promptForRole() {
        if (leaving || requestInFlight) return
        if (!isQPlus()) {
            goToNextStep()
            return
        }
        if (!launchForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE_HOME)) {
            goToNextStep()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchForResult(intent: Intent, requestCode: Int): Boolean = try {
        startActivityForResult(intent, requestCode)
        requestInFlight = true
        lastRequest = requestCode
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        requestInFlight = false
        if (leaving) return
        // Whether we hold the role is the only honest signal (both surfaces report CANCELED on Back).
        if (isDefaultLauncher()) return
        when (requestCode) {
            REQ_HOME_SETTINGS -> promptForRole()
            REQ_ROLE_HOME -> {
                // Still not the default home after the role popup: it was declined.
                logKeyEvent("default_home_declined")
                if (resultCode == RESULT_OK || !skipEnabled) goToNextStep() else askOnDecline()
            }
        }
    }

    /** Declined the home role: a one-time `permission_engine` ask for this screen, if any. */
    private fun askOnDecline() {
        if (askedOnDecline) return
        askedOnDecline = true
        AccessEngine.check(this)
    }

    /** The instance still waiting from before the grant, raised to finish it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ROLE_GRANTED, false)) {
            finishingGrant = true
            binding.root.isInvisible = true
        }
    }

    override fun onResume() {
        super.onResume()
        LauncherHintPrompt.dismiss()
        if (isDefaultLauncher()) {
            logGrant()
            if (finishingGrant) goHome() else advanceAfterGrant()
            return
        }
        if (autoOpenPending) {
            autoOpenPending = false
            openHomeSettings()
        }
    }

    /** The role was just granted from this step: which surface did it, once per process. */
    private fun logGrant() {
        if (grantLogged || lastRequest == 0) return
        grantLogged = true
        logKeyEvent(if (lastRequest == REQ_ROLE_HOME) "default_home_granted_dialog" else "default_home_granted_settings")
    }

    /** The role is held: `auto_next` (default on) moves on after `auto_next_delay_ms`. */
    private fun advanceAfterGrant() {
        val (auto, delayMs) = LauncherAdsConfig.onboardingAutoNext(this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT)
        if (!auto) return
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed) goHome()
        }, delayMs)
    }

    // ===== destinations =====

    private fun goHome() {
        if (leaving) return
        leaving = true
        val next = {
            LauncherFlow.advance(
                activity = this,
                skipRest = LauncherAdsConfig.defaultHomeStep(this).skipRestOnGrant,
            )
        }
        // Straight after the grant: the preloaded interstitial if it is ready, else the next
        // step at once — no loader, no fallback format in between.
        if (finishingGrant && !InterstitialNormal.hasReadyInter(this)) {
            Log.d(TAG, "grant finished, no interstitial ready → next step")
            return next()
        }
        LauncherAdsConfig.runOnboardingInter(this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT) { next() }
    }

    /** Home pressed here: on return, skip to the next step — Back would only ask for the role again. */
    override fun onHomeForward() = goToNextStep()

    private fun goToNextStep() {
        if (leaving) return
        leaving = true
        LauncherAdsConfig.runOnboardingInter(this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT) {
            LauncherFlow.advance(this)
        }
    }

    override fun onDestroy() {
        DesignMotion.cancelAll(animators)
        LauncherHintPrompt.dismiss()
        super.onDestroy()
    }
}
