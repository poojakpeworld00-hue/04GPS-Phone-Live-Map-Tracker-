package com.find.phone.gps.live.location.tracker.induction

import android.animation.Animator
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.databinding.CellGestureTileBinding
import com.find.phone.gps.live.location.tracker.databinding.ScreenOnboardingWelcomeBinding
import com.find.phone.gps.live.location.tracker.consent.ConsentDriver
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.helpers.followAdContainer
import com.find.phone.gps.live.location.tracker.helpers.concealWhileAdVisible
import org.fossify.commons.extensions.viewBinding

/**
 * Welcome step (design 4a): the gesture guide. Continue hands off to [ConsentDriver], which
 * asks whatever `permission_engine` has configured for this Activity, then the flow moves on
 * to whatever `onboarding.order` puts next. Skip goes to the same place without asking.
 */
class HelloPhaseActivity : CasingDeckActivity() {

    private val binding by viewBinding(ScreenOnboardingWelcomeBinding::inflate)
    private val animators = mutableListOf<Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        excludeAppFromRecents()

        CraftMovement.pressScale(binding.inductContinue)
        binding.inductContinue.setOnClickListener { demandOnboardingPermissions() }
        binding.inductSkip.setOnClickListener { headToNextStep() }

        // `onboarding.welcome.skip_enabled: false` makes the screen a required step.
        val ui = HomeSpotsProfile.onboardingUi(this, HomeSpotsProfile.OnboardScreen.WELCOME)
        binding.inductSkip.isVisible = ui.skipEnabled

        // Paid users: Back behaves like Skip and moves the flow on. Organic users: Back is Back.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (HomeStream.backMovesForward(this@HelloPhaseActivity)) headToNextStep()
                else HomeStream.passBackThrough(this@HelloPhaseActivity, this)
            }
        })

        // Ad frame pinned at the bottom, `launcher_ads.onboarding.welcome.slot`. presentSlot hides
        // the frame outright when the slot is off, and followAdContainer drops the hairline.
        HomeSpotsProfile.presentSlot(
            activity = this,
            slot = HomeSpotsProfile.onboardingSlot(this, HomeSpotsProfile.OnboardScreen.WELCOME),
            container = binding.spotNativeFrame,
            shimmer = binding.spotShimmer,
        )
        binding.spotNativeDivider.followAdContainer(binding.spotNativeFrame)
        // Small phones: the hero card gives way to the ad while one is on screen.
        binding.inductHero.concealWhileAdVisible(binding.spotNativeFrame, binding.leadCompact)

        HomeStream.wireStepHeader(this, HomeSpotsProfile.OnboardScreen.WELCOME, binding.root)

        wireGestureTiles()
        playMotion()
    }

    /** The four launcher gestures: one arrow glyph rotated per direction; "Swipe right" is active. */
    private fun wireGestureTiles() = with(binding) {
        wireTile(cellSwipeUp, -90f, R.string.gesture_swipe_up, R.string.gesture_swipe_up_body, active = false)
        wireTile(cellSwipeRight, 0f, R.string.gesture_swipe_right, R.string.gesture_swipe_right_body, active = true)
        wireTile(cellSwipeDown, 90f, R.string.gesture_swipe_down, R.string.gesture_swipe_down_body, active = false)
        wireTile(cellSwipeLeft, 180f, R.string.gesture_swipe_left, R.string.gesture_swipe_left_body, active = false)
    }

    private fun wireTile(tile: CellGestureTileBinding, rotation: Float, title: Int, body: Int, active: Boolean) {
        tile.cellIcon.rotation = rotation
        tile.cellTitle.setText(title)
        tile.cellBody.setText(body)
        if (active) {
            tile.cellRoot.setBackgroundResource(R.drawable.lv_gesture_active_r16)
            tile.cellRoot.elevation = resources.displayMetrics.density * 6f
            tile.cellIconBox.setBackgroundResource(R.drawable.lv_tile_white20_r12)
            ImageViewCompat.setImageTintList(tile.cellIcon, ContextCompat.getColorStateList(this, R.color.white))
            tile.cellTitle.setTextColor(ContextCompat.getColor(this, R.color.white))
            tile.cellBody.setTextColor(ContextCompat.getColor(this, R.color.ds_white90))
        }
    }

    /** The design's loops: badge `fadein .6s .4s`, chips `floaty 4s` (the second offset 1.2s). */
    private fun playMotion() {
        if (CraftMovement.animationsDisabled(this)) return
        animators += CraftMovement.fadeIn(binding.inductBadge, durationMs = 600L, delayMs = 400L)
        animators += CraftMovement.floaty(binding.pillDial)
        animators += CraftMovement.floaty(binding.pillGpsVw, delayMs = 1200L)
    }

    override fun onDestroy() {
        CraftMovement.abortAll(animators)
        super.onDestroy()
    }

    private fun demandOnboardingPermissions() {
        // onComplete fires once the whole configured queue is done — or straight away when
        // there is nothing to ask.
        ConsentDriver.check(this) { headToNextStep() }
    }

    /** Whatever `launcher_ads.onboarding.order` puts after this screen, behind its exit interstitial. */
    private fun headToNextStep() {
        HomeSpotsProfile.execOnboardingInter(this, HomeSpotsProfile.OnboardScreen.WELCOME) {
            HomeStream.advance(this)
        }
    }
}
