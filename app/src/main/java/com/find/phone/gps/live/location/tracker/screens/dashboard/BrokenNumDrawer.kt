package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.app.Activity
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.databinding.BsheetInvalidNumberBinding
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * The invalid-number sheet (design 11d), in place of a toast: which number doesn't match which
 * country's format, then Edit number (back to the field) or Change country (the picker).
 */
class BrokenNumDrawer(
    activity: Activity,
    private val number: String,
    private val country: String,
    private val onEdit: () -> Unit,
    private val onChangeCountry: () -> Unit,
) {
    private val themed = ContextThemeWrapper(activity, R.style.Theme_GpsTracker)
    private val binding = BsheetInvalidNumberBinding.inflate(LayoutInflater.from(themed))
    private val dialog = BottomSheetDialog(themed, R.style.Ds_BottomSheetDialog)

    fun show() {
        binding.txtBody.text = themed.getString(R.string.ds_invalid_body, number.trim(), country)
        CraftMovement.pressScale(binding.actEdit)
        binding.actEdit.setOnClickListener {
            dialog.dismiss()
            onEdit()
        }
        binding.actCountry.setOnClickListener {
            dialog.dismiss()
            onChangeCountry()
        }
        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }
}
