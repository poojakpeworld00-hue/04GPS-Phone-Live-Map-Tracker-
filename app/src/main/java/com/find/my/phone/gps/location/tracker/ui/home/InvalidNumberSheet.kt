package com.find.my.phone.gps.location.tracker.ui.home

import android.app.Activity
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.databinding.SheetInvalidNumberBinding
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * The invalid-number sheet (design 11d), in place of a toast: which number doesn't match which
 * country's format, then Edit number (back to the field) or Change country (the picker).
 */
class InvalidNumberSheet(
    activity: Activity,
    private val number: String,
    private val country: String,
    private val onEdit: () -> Unit,
    private val onChangeCountry: () -> Unit,
) {
    private val themed = ContextThemeWrapper(activity, R.style.Theme_GpsTracker)
    private val binding = SheetInvalidNumberBinding.inflate(LayoutInflater.from(themed))
    private val dialog = BottomSheetDialog(themed, R.style.Ds_BottomSheetDialog)

    fun show() {
        binding.tvBody.text = themed.getString(R.string.ds_invalid_body, number.trim(), country)
        DesignMotion.pressScale(binding.btnEdit)
        binding.btnEdit.setOnClickListener {
            dialog.dismiss()
            onEdit()
        }
        binding.btnCountry.setOnClickListener {
            dialog.dismiss()
            onChangeCountry()
        }
        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }
}
