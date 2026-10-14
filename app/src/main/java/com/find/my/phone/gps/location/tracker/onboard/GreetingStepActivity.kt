package com.find.my.phone.gps.location.tracker.onboard

import android.animation.Animator
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.LauncherAdsConfig
import com.find.my.phone.gps.location.tracker.databinding.TileGestureTileBinding
import com.find.my.phone.gps.location.tracker.databinding.ViewOnboardingWelcomeBinding
import com.find.my.phone.gps.location.tracker.permission.AccessEngine
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.util.followAdContainer
import com.find.my.phone.gps.location.tracker.util.hideWhileAdVisible
import org.fossify.commons.extensions.viewBinding

/**
 * Welcome step (design 4a): the gesture guide. Continue hands off to [AccessEngine], which
 * asks whatever `permission_engine` has configured for this Activity, then the flow moves on
 * to whatever `onboarding.order` puts next. Skip goes to the same place without asking.
 */
class GreetingStepActivity : ShellDeckActivity() {

    private val binding by viewBinding(ViewOnboardingWelcomeBinding::inflate)
    private val animators = mutableListOf<Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        excludeAppFromRecents()

        DesignMotion.pressScale(binding.onboardingContinue)
        binding.onboardingContinue.setOnClickListener { requestOnboardingPermissions() }
        binding.onboardingSkip.setOnClickListener { goToNextStep() }

        // `onboarding.welcome.skip_enabled: false` makes the screen a required step.
        val ui = LauncherAdsConfig.onboardingUi(this, LauncherAdsConfig.OnboardScreen.WELCOME)
        binding.onboardingSkip.isVisible = ui.skipEnabled

        // Paid users: Back behaves like Skip and moves the flow on. Organic users: Back is Back.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (LauncherFlow.backMovesForward(this@GreetingStepActivity)) goToNextStep()
                else LauncherFlow.passBackThrough(this@GreetingStepActivity, this)
            }
        })

        // Ad frame pinned at the bottom, `launcher_ads.onboarding.welcome.slot`. showSlot hides
        // the frame outright when the slot is off, and followAdContainer drops the hairline.
        LauncherAdsConfig.showSlot(
            activity = this,
            slot = LauncherAdsConfig.onboardingSlot(this, LauncherAdsConfig.OnboardScreen.WELCOME),
            container = binding.adNativeFrame,
            shimmer = binding.adShimmer,
        )
        binding.adNativeDivider.followAdContainer(binding.adNativeFrame)
        // Small phones: the hero card gives way to the ad while one is on screen.
        binding.onboardingHero.hideWhileAdVisible(binding.adNativeFrame, binding.heroCompact)

        LauncherFlow.bindStepHeader(this, LauncherAdsConfig.OnboardScreen.WELCOME, binding.root)

        bindGestureTiles()
        playMotion()
    }

    /** The four launcher gestures: one arrow glyph rotated per direction; "Swipe right" is active. */
    private fun bindGestureTiles() = with(binding) {
        bindTile(tileSwipeUp, -90f, R.string.gesture_swipe_up, R.string.gesture_swipe_up_body, active = false)
        bindTile(tileSwipeRight, 0f, R.string.gesture_swipe_right, R.string.gesture_swipe_right_body, active = true)
        bindTile(tileSwipeDown, 90f, R.string.gesture_swipe_down, R.string.gesture_swipe_down_body, active = false)
        bindTile(tileSwipeLeft, 180f, R.string.gesture_swipe_left, R.string.gesture_swipe_left_body, active = false)
    }

    private fun bindTile(tile: TileGestureTileBinding, rotation: Float, title: Int, body: Int, active: Boolean) {
        tile.tileIcon.rotation = rotation
        tile.tileTitle.setText(title)
        tile.tileBody.setText(body)
        if (active) {
            tile.tileRoot.setBackgroundResource(R.drawable.ds_gesture_active_r16)
            tile.tileRoot.elevation = resources.displayMetrics.density * 6f
            tile.tileIconBox.setBackgroundResource(R.drawable.ds_tile_white20_r12)
            ImageViewCompat.setImageTintList(tile.tileIcon, ContextCompat.getColorStateList(this, R.color.white))
            tile.tileTitle.setTextColor(ContextCompat.getColor(this, R.color.white))
            tile.tileBody.setTextColor(ContextCompat.getColor(this, R.color.ds_white90))
        }
    }

    /** The design's loops: badge `fadein .6s .4s`, chips `floaty 4s` (the second offset 1.2s). */
    private fun playMotion() {
        if (DesignMotion.animationsDisabled(this)) return
        animators += DesignMotion.fadeIn(binding.onboardingBadge, durationMs = 600L, delayMs = 400L)
        animators += DesignMotion.floaty(binding.chipDial)
        animators += DesignMotion.floaty(binding.chipGps, delayMs = 1200L)
    }

    override fun onDestroy() {
        DesignMotion.cancelAll(animators)
        super.onDestroy()
    }

    private fun requestOnboardingPermissions() {
        // onComplete fires once the whole configured queue is done — or straight away when
        // there is nothing to ask.
        AccessEngine.check(this) { goToNextStep() }
    }

    /** Whatever `launcher_ads.onboarding.order` puts after this screen, behind its exit interstitial. */
    private fun goToNextStep() {
        LauncherAdsConfig.runOnboardingInter(this, LauncherAdsConfig.OnboardScreen.WELCOME) {
            LauncherFlow.advance(this)
        }
    }
}
