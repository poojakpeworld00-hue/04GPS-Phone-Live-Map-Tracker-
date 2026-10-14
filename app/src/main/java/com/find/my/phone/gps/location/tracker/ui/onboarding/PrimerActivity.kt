package com.find.my.phone.gps.location.tracker.ui.onboarding

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
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.LauncherAdsConfig
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.data.VaultRegistry
import com.find.my.phone.gps.location.tracker.databinding.ViewOnboardingBinding
import com.find.my.phone.gps.location.tracker.onboard.LauncherFlow
import com.find.my.phone.gps.location.tracker.onboard.ReminderIntroActivity
import com.find.my.phone.gps.location.tracker.ui.premium.PremiumActivity
import com.find.my.phone.gps.location.tracker.permission.AccessEngine
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.intro.IntroRevealConfig
import com.find.my.phone.gps.location.tracker.ui.intro.IntroRevealPolicy
import kotlin.math.abs

/**
 * The intro carousel (design 2a). Four hero-card pages; the card and CTA stay fixed while the
 * illustration parallaxes at 0.6× and the copy fades over ~200ms of the swipe. The native
 * banner slot (`launcher_ads.onboarding.intro.slot`) sits under the CTA on the third page.
 */
class PrimerActivity : CanvasActivity<ViewOnboardingBinding>() {

    override val layoutId: Int = R.layout.view_onboarding

    private val prefs by lazy { VaultRegistry(this) }
    private val pages = IntroPages.all
    private val dots = mutableListOf<View>()

    // Spring settle used by the dot pill stretch and the CTA morph.
    private val spring = PathInterpolator(0.34f, 1.56f, 0.64f, 1f)
    private var wasLast = false

    /** One-shot guard so a first-page back cannot fire the forward flow twice. */
    private var forwarding = false

    override fun initView() {
        IntroRevealPolicy.markShown(this, IntroRevealConfig.ONBOARDING)

        ViewCompat.setOnApplyWindowInsetsListener(binding.onboardingRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // The slot loads once; its container is only revealed on the page that carries it.
        LauncherAdsConfig.showSlot(
            activity = this,
            slot = LauncherAdsConfig.onboardingSlot(this, LauncherAdsConfig.OnboardScreen.INTRO),
            container = binding.adNativeFrame,
            shimmer = binding.adShimmer,
        )

        binding.viewPager.adapter = IntroAdapter(pages)
        binding.viewPager.offscreenPageLimit = 1
        buildDots()
        updateDots(0)

        // Swipe: the illustration moves at 0.6× the page (parallax), the copy fades out over
        // the first fifth of the swipe (≈200ms of a 1s page change).
        binding.viewPager.setPageTransformer { page, position ->
            val w = page.width.toFloat()
            page.findViewById<View?>(R.id.artContainer)?.translationX = position * w * 0.4f
            page.findViewById<View?>(R.id.copy)?.alpha = (1f - abs(position) * 5f).coerceIn(0f, 1f)
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateDots(position)
        })

        val ui = LauncherAdsConfig.onboardingUi(this, LauncherAdsConfig.OnboardScreen.INTRO)
        binding.btnSkip.isVisible = ui.skipEnabled

        DesignMotion.pressScale(binding.btnNext)
        binding.btnSkip.setOnClickListener { finishOnboarding() }
        binding.btnNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current < pages.lastIndex) {
                binding.viewPager.currentItem = current + 1
            } else {
                finishOnboarding()
            }
        }

        // Back walks FORWARD through the pages for paid users (every page is seen before the
        // user can leave); organic users get a real Back. `back_action: "next_screen"` leaves
        // for the next screen straight away.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = binding.viewPager.currentItem
                if (!LauncherFlow.backMovesForward(this@PrimerActivity)) {
                    when {
                        current > 0 -> binding.viewPager.setCurrentItem(current - 1, true)
                        // A later-launch intro (intro_display) has nothing behind it: land on Home
                        // instead of closing the app.
                        !LauncherFlow.isOnboardingActive(this@PrimerActivity) ->
                            LauncherFlow.goHome(this@PrimerActivity)
                        else -> LauncherFlow.passBackThrough(this@PrimerActivity, this)
                    }
                    return
                }
                if (!ui.backAdvances && current < pages.lastIndex) {
                    binding.viewPager.setCurrentItem(current + 1, true)
                } else if (!forwarding) {
                    forwarding = true
                    finishOnboarding()
                }
            }
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildDots() {
        val size = dp(6)
        val gap = dp(6)
        pages.indices.forEach { i ->
            val dot = View(this)
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                if (i < pages.lastIndex) marginEnd = gap
            }
            binding.dots.addView(dot)
            dots.add(dot)
        }
    }

    private fun updateDots(active: Int) {
        val activeWidth = dp(24)
        val size = dp(6)
        dots.forEachIndexed { i, dot ->
            animateDotWidth(dot, if (i == active) activeWidth else size)
            dot.setBackgroundResource(if (i == active) R.drawable.ds_dot_active else R.drawable.ds_dot)
        }
        val last = active == pages.lastIndex
        binding.btnNextLabel.setText(if (last) R.string.ds_get_started else R.string.ds_next)
        binding.btnNextIcon.isVisible = !last
        if (last != wasLast) {
            wasLast = last
            popCta()
        }
    }

    /** Springs a dot's width to [target] (the pill-stretch effect). */
    private fun animateDotWidth(dot: View, target: Int) {
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

    /** Scale spring when the CTA morphs between Next and Get Started. */
    private fun popCta() {
        binding.btnNext.animate().cancel()
        binding.btnNext.scaleX = 0.94f
        binding.btnNext.scaleY = 0.94f
        binding.btnNext.animate()
            .scaleX(1f).scaleY(1f)
            .setInterpolator(spring)
            .setDuration(340L)
            .start()
    }

    private fun finishOnboarding() {
        prefs.isOnboardingDone = true
        logKeyEvent("onboarding_completed")
        AccessEngine.check(this) {
            // Permission done → this screen's interstitial (`onboarding.intro.inter_enabled`,
            // the callback fires immediately when there is nothing to show) → THEN navigate.
            LauncherAdsConfig.runOnboardingInter(this, LauncherAdsConfig.OnboardScreen.INTRO) {
                // The Premium paywall (design 9c) comes next; it continues this same
                // navigation once it is closed or a plan is bought.
                // Later launches: the reminders intro sits between this screen and Premium / Home
                // (wrap() passes the intent straight through during the first-run onboarding).
                if (PremiumActivity.showsAfterIntro(this)) {
                    startActivity(ReminderIntroActivity.wrap(this, PremiumActivity.afterIntro(this, LauncherFlow.isOnboarding(this))))
                    finish()
                } else if (LauncherFlow.isOnboardingActive(this)) {
                    LauncherFlow.advance(this)
                } else {
                    val home = Intent(this, LauncherFlow.homeActivity())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    val wrapped = ReminderIntroActivity.wrap(this, home)
                    if (wrapped === home) LauncherFlow.goHome(this) else { startActivity(wrapped); finish() }
                }
            }
        }
    }
}
