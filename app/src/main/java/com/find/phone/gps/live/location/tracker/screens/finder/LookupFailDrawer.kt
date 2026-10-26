package com.find.phone.gps.live.location.tracker.screens.finder

import android.app.Activity
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.databinding.BsheetLookupFailedBinding
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class LookupFailDrawer(
    activity: Activity,
    private val onRetry: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private val themed = ContextThemeWrapper(activity, R.style.Theme_GpsTracker)
    private val binding = BsheetLookupFailedBinding.inflate(LayoutInflater.from(themed))
    private val dialog = BottomSheetDialog(themed, R.style.Ds_BottomSheetDialog)

    fun show() {
        CraftMovement.pressScale(binding.actRetry)
        binding.actRetry.setOnClickListener {
            dialog.dismiss()
            onRetry()
        }
        binding.actCancel.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }
        dialog.setContentView(binding.root)
        // No outside-dismiss: leaving the sheet open-ended would strand the user on a half-finished search.
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }
}
