package com.find.phone.gps.live.location.tracker.induction

import com.find.phone.gps.live.location.tracker.BuildConfig
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
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverStandard
import com.find.phone.gps.live.location.tracker.databinding.ScreenOnboardingDefaultLauncherBinding
import com.find.phone.gps.live.location.tracker.consent.ConsentDriver
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.helpers.concealWhileAdVisible
import com.livemap.homedeck.ext.isDefaultLauncher
import com.livemap.homedeck.ext.roleManager
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.isQPlus

class MainPartPhaseActivity : CasingDeckActivity(), InductionMainOnward.Step {
    private val binding by viewBinding(ScreenOnboardingDefaultLauncherBinding::inflate)
    private val floatAnims = mutableListOf<Animator>()

    private var exitCommitted = false
    private var awaitingResult = false
    private var openSettingsOnResume = false
    private var declineAskDone = false
    private var skipShown = true
    private var grantOnlyVisit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        excludeAppFromRecents()

        if (arrivedWithGrant(intent)) return

        if (savedInstanceState == null) recordKeyEvent("default_home_view")

        wireButtons()

        val stepUi = HomeSpotsProfile.onboardingUi(this, HomeSpotsProfile.OnboardScreen.SET_DEFAULT)
        skipShown = stepUi.skipEnabled
        binding.inductSkip.isVisible = stepUi.skipEnabled

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val host = this@MainPartPhaseActivity
                when {
                    HomeStream.backMovesForward(host) -> askRoleDialog()
                    else -> HomeStream.passBackThrough(host, this)
                }
            }
        })

        HomeSpotsProfile.presentSlot(
            activity = this,
            slot = HomeSpotsProfile.onboardingSlot(this, HomeSpotsProfile.OnboardScreen.SET_DEFAULT),
            container = binding.spotNativeFrame,
            shimmer = binding.spotShimmer,
        )

        binding.pictureHero.concealWhileAdVisible(binding.spotNativeFrame, binding.leadCompact)

        HomeStream.wireStepHeader(this, HomeSpotsProfile.OnboardScreen.SET_DEFAULT, binding.root)

        val holdForQa = BuildConfig.DEBUG && intent.getBooleanExtra("qa_no_auto_open", false)
        openSettingsOnResume = savedInstanceState == null && !holdForQa && !InductionMainOnward.isSavedFor(this)

        startFloatAnimations()
    }

    private fun arrivedWithGrant(source: Intent): Boolean {
        if (!source.getBooleanExtra(EXTRA_ROLE_GRANTED, false)) return false
        grantOnlyVisit = true
        binding.root.isInvisible = true
        return true
    }

    private fun wireButtons() {
        binding.inductSetDefault.let { cta ->
            CraftMovement.pressScale(cta)
            cta.setOnClickListener {
                when {
                    isDefaultLauncher() -> completeToHome()
                    else -> openDefaultAppsPage()
                }
            }
        }
        binding.inductSkip.setOnClickListener {
            recordKeyEvent("default_home_skip")
            skipToNextStep()
        }
    }

    private fun startFloatAnimations() {
        if (CraftMovement.animationsDisabled(this)) return
        floatAnims.add(CraftMovement.floaty(binding.lvChooserCard))
        floatAnims.add(CraftMovement.floaty(binding.pillSwipe, delayMs = 1400L))
    }

    private fun openDefaultAppsPage() {
        if (exitCommitted || awaitingResult) return
        launchHomeSettingsPages()
        HomeClueCue.presentAfterSettings(this)
    }

    private fun launchHomeSettingsPages() {
        if (exitCommitted) return
        val pageOpened = sequenceOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            .any { action -> startForResultSafely(Intent(action), REQ_HOME_SETTINGS) }

        if (!pageOpened) askRoleDialog()
    }

    private fun askRoleDialog() {
        if (exitCommitted || awaitingResult) return
        val launched = isQPlus() &&
            startForResultSafely(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE_HOME)
        if (!launched) skipToNextStep()
    }

    @Suppress("DEPRECATION")
    private fun startForResultSafely(request: Intent, code: Int): Boolean {
        try {
            startActivityForResult(request, code)
        } catch (_: ActivityNotFoundException) {
            return false
        }
        awaitingResult = true
        lastSurfaceCode = code
        return true
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        awaitingResult = false

        if (exitCommitted || isDefaultLauncher()) return
        if (requestCode == REQ_HOME_SETTINGS) {
            askRoleDialog()
        } else if (requestCode == REQ_ROLE_HOME) {
            recordKeyEvent("default_home_declined")
            val moveOn = resultCode == RESULT_OK || !skipShown
            if (moveOn) skipToNextStep() else askOnceAfterDecline()
        }
    }

    private fun askOnceAfterDecline() {
        if (declineAskDone) return
        declineAskDone = true
        ConsentDriver.check(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        arrivedWithGrant(intent)
    }

    override fun onResume() {
        super.onResume()
        HomeClueCue.dismiss()
        if (isDefaultLauncher()) {
            reportGrantSurface()
            if (grantOnlyVisit) completeToHome() else scheduleAutoNext()
        } else if (openSettingsOnResume) {
            openSettingsOnResume = false
            openDefaultAppsPage()
        }
    }

    private fun reportGrantSurface() {
        if (grantReported || lastSurfaceCode == 0) return
        grantReported = true
        val event = when (lastSurfaceCode) {
            REQ_ROLE_HOME -> "default_home_granted_dialog"
            else -> "default_home_granted_settings"
        }
        recordKeyEvent(event)
    }

    private fun scheduleAutoNext() {
        val (enabled, waitMs) = HomeSpotsProfile.onboardingAutoNext(this, HomeSpotsProfile.OnboardScreen.SET_DEFAULT)
        if (!enabled) return
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed) completeToHome()
        }, waitMs)
    }

    private fun completeToHome() {
        if (exitCommitted) return
        exitCommitted = true
        val proceed = {
            HomeStream.advance(
                activity = this,
                skipRest = HomeSpotsProfile.defaultHomeStep(this).skipRestOnGrant,
            )
        }
        val skipInter = grantOnlyVisit && !TakeoverStandard.hasReadyInter(this)
        if (skipInter) {
            Log.d(TAG, "grant finished, no interstitial ready → next step")
            proceed()
            return
        }
        HomeSpotsProfile.execOnboardingInter(this, HomeSpotsProfile.OnboardScreen.SET_DEFAULT) { proceed() }
    }

    override fun onHomeForward() = skipToNextStep()

    private fun skipToNextStep() {
        if (exitCommitted) return
        exitCommitted = true
        HomeSpotsProfile.execOnboardingInter(this, HomeSpotsProfile.OnboardScreen.SET_DEFAULT) {
            HomeStream.advance(this)
        }
    }

    override fun onDestroy() {
        CraftMovement.abortAll(floatAnims)
        HomeClueCue.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HomeRoleStep"
        private const val REQ_HOME_SETTINGS = 7011
        private const val REQ_ROLE_HOME = 7012

        const val EXTRA_ROLE_GRANTED = "role_granted"

        private var lastSurfaceCode = 0
        private var grantReported = false
    }
}
