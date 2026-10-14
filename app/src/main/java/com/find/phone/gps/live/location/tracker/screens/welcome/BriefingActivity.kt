package com.find.phone.gps.live.location.tracker.screens.welcome

import android.animation.ValueAnimator
import android.content.Intent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.databinding.ScreenOnboardingBinding
import com.find.phone.gps.live.location.tracker.induction.HomeStream
import com.find.phone.gps.live.location.tracker.induction.MemoOpeningActivity
import com.find.phone.gps.live.location.tracker.screens.plus.PlusActivity
import com.find.phone.gps.live.location.tracker.consent.ConsentDriver
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.opening.OpeningUnveilProfile
import com.find.phone.gps.live.location.tracker.screens.opening.OpeningUnveilCharter
import kotlin.math.abs

class BriefingActivity : StageActivity<ScreenOnboardingBinding>() {
    override val layoutId: Int = R.layout.screen_onboarding

    private val prefs by lazy { LockerBook(this) }
    private val pages = OpeningLeaves.all
    private val dots = mutableListOf<View>()

    private val spring = PathInterpolator(0.34f, 1.56f, 0.64f, 1f)
    private var wasLast = false

    private var forwarding = false

    override fun initView() {
        OpeningUnveilCharter.flagShown(this, OpeningUnveilProfile.ONBOARDING)

        ViewCompat.setOnApplyWindowInsetsListener(binding.inductRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        HomeSpotsProfile.presentSlot(
            activity = this,
            slot = HomeSpotsProfile.onboardingSlot(this, HomeSpotsProfile.OnboardScreen.INTRO),
            container = binding.spotNativeFrame,
            shimmer = binding.spotShimmer,
        )

        binding.vwPager.adapter = OpeningAdapter(pages)
        binding.vwPager.offscreenPageLimit = 1
        composeDots()
        reviseDots(0)

        binding.vwPager.setPageTransformer { page, position ->
            val w = page.width.toFloat()
            page.findViewById<View?>(R.id.lv_artContainer)?.translationX = position * w * 0.4f
            page.findViewById<View?>(R.id.lv_copy)?.alpha = (1f - abs(position) * 5f).coerceIn(0f, 1f)
        }

        binding.vwPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = reviseDots(position)
        })

        val ui = HomeSpotsProfile.onboardingUi(this, HomeSpotsProfile.OnboardScreen.INTRO)
        binding.actSkip.isVisible = ui.skipEnabled

        CraftMovement.pressScale(binding.actNext)
        binding.actSkip.setOnClickListener { concludeOnboarding() }
        binding.actNext.setOnClickListener {
            val current = binding.vwPager.currentItem
            if (current < pages.lastIndex) {
                binding.vwPager.currentItem = current + 1
            } else {
                concludeOnboarding()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = binding.vwPager.currentItem
                if (!HomeStream.backMovesForward(this@BriefingActivity)) {
                    when {
                        current > 0 -> binding.vwPager.setCurrentItem(current - 1, true)

                        !HomeStream.isOnboardingActive(this@BriefingActivity) ->
                            HomeStream.goHome(this@BriefingActivity)
                        else -> HomeStream.passBackThrough(this@BriefingActivity, this)
                    }
                    return
                }
                if (!ui.backAdvances && current < pages.lastIndex) {
                    binding.vwPager.setCurrentItem(current + 1, true)
                } else if (!forwarding) {
                    forwarding = true
                    concludeOnboarding()
                }
            }
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun composeDots() {
        val size = dp(6)
        val gap = dp(6)
        pages.indices.forEach { i ->
            val dot = View(this)
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                if (i < pages.lastIndex) marginEnd = gap
            }
            binding.marks.addView(dot)
            dots.add(dot)
        }
    }

    private fun reviseDots(active: Int) {
        val activeWidth = dp(24)
        val size = dp(6)
        dots.forEachIndexed { i, dot ->
            tweenDotWidth(dot, if (i == active) activeWidth else size)
            dot.setBackgroundResource(if (i == active) R.drawable.lv_dot_active else R.drawable.lv_dot)
        }
        val last = active == pages.lastIndex
        binding.actNextLabel.setText(if (last) R.string.ds_get_started else R.string.ds_next)
        binding.actNextIcon.isVisible = !last
        if (last != wasLast) {
            wasLast = last
            popCta()
        }
    }

    private fun tweenDotWidth(dot: View, target: Int) {
        val lp = dot.layoutParams as LinearLayout.LayoutParams
        if (lp.width == target) return
        (dot.getTag(R.id.tag_dot_anim) as? ValueAnimator)?.cancel()
        ValueAnimator.ofInt(lp.width, target).apply {
            duration = 320L
            interpolator = spring
            addUpdateListener {
                lp.width = it.animatedValue as Int
                dot.layoutParams = lp
            }
            dot.setTag(R.id.tag_dot_anim, this)
            start()
        }
    }

    private fun popCta() {
        binding.actNext.animate().cancel()
        binding.actNext.scaleX = 0.94f
        binding.actNext.scaleY = 0.94f
        binding.actNext.animate()
            .scaleX(1f).scaleY(1f)
            .setInterpolator(spring)
            .setDuration(340L)
            .start()
    }

    private fun concludeOnboarding() {
        prefs.isOnboardingDone = true
        recordKeyEvent("onboarding_completed")
        ConsentDriver.check(this) {
            HomeSpotsProfile.execOnboardingInter(this, HomeSpotsProfile.OnboardScreen.INTRO) {
                if (PlusActivity.showsAfterIntro(this)) {
                    startActivity(MemoOpeningActivity.wrap(this, PlusActivity.afterIntro(this, HomeStream.isOnboarding(this))))
                    finish()
                } else if (HomeStream.isOnboardingActive(this)) {
                    HomeStream.advance(this)
                } else {
                    val home = Intent(this, HomeStream.homeActivity())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    val wrapped = MemoOpeningActivity.wrap(this, home)
                    if (wrapped === home) HomeStream.goHome(this) else { startActivity(wrapped); finish() }
                }
            }
        }
    }
}
