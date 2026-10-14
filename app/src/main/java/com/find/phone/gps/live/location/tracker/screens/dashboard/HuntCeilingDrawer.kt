package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.render.BonusTeaser
import com.find.phone.gps.live.location.tracker.store.plus.PlusProfile
import com.find.phone.gps.live.location.tracker.store.plus.HuntRation
import com.find.phone.gps.live.location.tracker.databinding.BsheetSearchLimitBinding
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class HuntCeilingDrawer(
    private val activity: Activity,
    private val onExtraSearch: () -> Unit,
    private val onPremium: () -> Unit,
) {
    private val themed = ContextThemeWrapper(activity, R.style.Theme_GpsTracker)
    private val binding = BsheetSearchLimitBinding.inflate(LayoutInflater.from(themed))
    private val dialog = BottomSheetDialog(themed, R.style.Ds_BottomSheetDialog)
    private var shine: ValueAnimator? = null
    private var watching = false

    fun show() {
        val limit = HuntRation.limit(activity)
        val used = HuntRation.used(activity).coerceAtMost(limit)
        binding.txtUsed.text = "$used/$limit"
        binding.lvRing.fraction = if (limit > 0) used.toFloat() / limit else 1f

        val offersAd = PlusProfile.read(activity).rewardedExtraSearches > 0
        binding.txtBody.text = themed.getString(
            if (offersAd) R.string.ds_limit_body else R.string.ds_limit_body_premium, limit,
        )
        binding.actWatch.isVisible = offersAd

        CraftMovement.pressScale(binding.actWatch)
        CraftMovement.pressScale(binding.actPremium)
        binding.actWatch.setOnClickListener { watch() }
        binding.actPremium.clipToOutline = true
        binding.actPremium.setOnClickListener {
            dialog.dismiss()
            onPremium()
        }
        if (offersAd) BonusTeaser.preload(activity)

        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { shine?.cancel() }
        dialog.show()
        beginShine()
    }

    private fun watch() {
        if (watching) return
        watching = true
        binding.txtWatch.isInvisible = true
        binding.spinWatch.isVisible = true
        BonusTeaser().show(activity) { result ->
            watching = false
            binding.txtWatch.isInvisible = false
            binding.spinWatch.isVisible = false
            when (result) {
                BonusTeaser.Result.EARNED -> {
                    HuntRation.grantRewarded(activity)
                    dialog.dismiss()
                    onExtraSearch()
                }
                BonusTeaser.Result.NOT_EARNED ->
                    Toast.makeText(activity, R.string.ds_reward_not_earned, Toast.LENGTH_SHORT).show()
                BonusTeaser.Result.CANCELED -> Unit
            }
        }
    }

    private fun beginShine() {
        if (CraftMovement.animationsDisabled(themed)) {
            binding.lvShine.isVisible = false
            return
        }
        binding.actPremium.post {
            val w = binding.actPremium.width.toFloat()
            shine = ObjectAnimator.ofFloat(binding.lvShine, View.TRANSLATION_X, -0.3f * w, 1.1f * w).apply {
                duration = 1_800L
                startDelay = 600L
                repeatCount = ValueAnimator.INFINITE
                interpolator = CraftMovement.EASE_IN_OUT
                start()
            }
        }
    }
}
