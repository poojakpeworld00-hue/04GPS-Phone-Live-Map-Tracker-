package com.find.my.phone.gps.location.tracker.ui.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.presentation.RewardedPromo
import com.find.my.phone.gps.location.tracker.data.premium.PremiumConfig
import com.find.my.phone.gps.location.tracker.data.premium.SearchQuota
import com.find.my.phone.gps.location.tracker.databinding.SheetSearchLimitBinding
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Search limit reached (design 9b), every time a free user is out of searches: Go Premium goes to
 * [onPremium]. Only when Remote Config's `rewarded_extra_searches` is above 0 does it also offer a
 * rewarded ad, which adds that many searches and [onExtraSearch] carries on with the search that
 * was asked for. Closing the ad early adds nothing.
 */
class SearchLimitSheet(
    private val activity: Activity,
    private val onExtraSearch: () -> Unit,
    private val onPremium: () -> Unit,
) {
    private val themed = ContextThemeWrapper(activity, R.style.Theme_GpsTracker)
    private val binding = SheetSearchLimitBinding.inflate(LayoutInflater.from(themed))
    private val dialog = BottomSheetDialog(themed, R.style.Ds_BottomSheetDialog)
    private var shine: ValueAnimator? = null
    private var watching = false

    fun show() {
        val limit = SearchQuota.limit(activity)
        val used = SearchQuota.used(activity).coerceAtMost(limit)
        binding.tvUsed.text = "$used/$limit"
        binding.ring.fraction = if (limit > 0) used.toFloat() / limit else 1f
        // `rewarded_extra_searches` 0 (the default): Premium is the only way past the limit.
        val offersAd = PremiumConfig.read(activity).rewardedExtraSearches > 0
        binding.tvBody.text = themed.getString(
            if (offersAd) R.string.ds_limit_body else R.string.ds_limit_body_premium, limit,
        )
        binding.btnWatch.isVisible = offersAd

        DesignMotion.pressScale(binding.btnWatch)
        DesignMotion.pressScale(binding.btnPremium)
        binding.btnWatch.setOnClickListener { watch() }
        binding.btnPremium.clipToOutline = true
        binding.btnPremium.setOnClickListener {
            dialog.dismiss()
            onPremium()
        }
        if (offersAd) RewardedPromo.preload(activity)

        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { shine?.cancel() }
        dialog.show()
        startShine()
    }

    private fun watch() {
        if (watching) return
        watching = true
        binding.tvWatch.isInvisible = true
        binding.pbWatch.isVisible = true
        RewardedPromo().show(activity) { result ->
            watching = false
            binding.tvWatch.isInvisible = false
            binding.pbWatch.isVisible = false
            when (result) {
                RewardedPromo.Result.EARNED -> {
                    SearchQuota.grantRewarded(activity)
                    dialog.dismiss()
                    onExtraSearch()
                }
                RewardedPromo.Result.NOT_EARNED ->
                    Toast.makeText(activity, R.string.ds_reward_not_earned, Toast.LENGTH_SHORT).show()
                RewardedPromo.Result.CANCELED -> Unit
            }
        }
    }

    /** The light streak sweeping across the gold button (design `shine`). */
    private fun startShine() {
        if (DesignMotion.animationsDisabled(themed)) {
            binding.shine.isVisible = false
            return
        }
        binding.btnPremium.post {
            val w = binding.btnPremium.width.toFloat()
            shine = ObjectAnimator.ofFloat(binding.shine, View.TRANSLATION_X, -0.3f * w, 1.1f * w).apply {
                duration = 1_800L
                startDelay = 600L
                repeatCount = ValueAnimator.INFINITE
                interpolator = DesignMotion.EASE_IN_OUT
                start()
            }
        }
    }
}
