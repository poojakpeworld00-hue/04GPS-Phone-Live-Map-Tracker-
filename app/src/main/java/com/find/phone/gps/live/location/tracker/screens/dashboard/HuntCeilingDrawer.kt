package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isVisible
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.store.HuntProfile
import com.find.phone.gps.live.location.tracker.store.HuntRation
import com.find.phone.gps.live.location.tracker.databinding.BsheetSearchLimitBinding
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class HuntCeilingDrawer(
    private val activity: Activity,
    private val onReady: () -> Unit,
    private val onPremium: () -> Unit,
) {
    private val themed = ContextThemeWrapper(activity, R.style.Theme_GpsTracker)
    private val binding = BsheetSearchLimitBinding.inflate(LayoutInflater.from(themed))
    private val dialog = BottomSheetDialog(themed, R.style.Ds_BottomSheetDialog)
    private val handler = Handler(Looper.getMainLooper())
    private var fired = false

    private val tick = object : Runnable {
        override fun run() {
            val left = HuntRation.cooldownLeftMs(activity)
            if (left <= 0L) return finishReady()
            binding.txtCountdown.text = HuntRation.formatLeft(left)
            handler.postDelayed(this, 1_000L)
        }
    }

    fun show() {
        val limit = HuntRation.limit(activity)
        val used = HuntRation.used(activity).coerceAtMost(limit)
        binding.txtUsed.text = "$used/$limit"
        binding.lvRing.fraction = if (limit > 0) used.toFloat() / limit else 1f

        val timed = HuntProfile.read(activity).cooldownMinutes > 0 && HuntRation.coolingDown(activity)
        binding.txtBody.text = themed.getString(if (timed) R.string.ds_limit_body_timer else R.string.ds_limit_body, limit)
        binding.countdownBox.isVisible = timed
        binding.txtReset.isVisible = !timed
        if (timed) {
            binding.txtCountdown.text = HuntRation.formatLeft(HuntRation.cooldownLeftMs(activity))
            handler.post(tick)
        }

        CraftMovement.pressScale(binding.actPremium)
        binding.actPremium.clipToOutline = true
        binding.actPremium.setOnClickListener {
            dialog.dismiss()
            onPremium()
        }

        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        dialog.show()
    }

    private fun finishReady() {
        if (fired) return
        fired = true
        handler.removeCallbacks(tick)
        runCatching { dialog.dismiss() }
        if (!activity.isFinishing) onReady()
    }
}
