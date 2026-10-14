package com.find.phone.gps.live.location.tracker.screens.craft

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.databinding.PartPopupMenuBinding
import com.find.phone.gps.live.location.tracker.databinding.CellPopupRowBinding

class KitFlyout(private val context: Context) {
    private val binding = PartPopupMenuBinding.inflate(LayoutInflater.from(context))
    private val window = PopupWindow(
        binding.root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true,
    ).apply {
        elevation = dp(16f)

        setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.color.transparent))
        isOutsideTouchable = true
    }

    fun add(
        label: CharSequence,
        @DrawableRes icon: Int,
        badge: CharSequence? = null,
        selected: Boolean = false,
        onClick: () -> Unit,
    ): KitFlyout {
        val row = CellPopupRowBinding.inflate(LayoutInflater.from(context), binding.lvRows, false)
        row.txtLabel.text = label
        row.imgIcon.setImageResource(icon)
        row.txtBadge.text = badge
        row.txtBadge.isVisible = !badge.isNullOrEmpty()
        row.imgCheckVw.isVisible = selected
        row.badgeTile.setBackgroundResource(if (selected) R.drawable.lv_tile_primary_r12 else R.drawable.lv_tile_tint_r12)
        row.imgIcon.imageTintList = ContextCompat.getColorStateList(context, if (selected) R.color.white else R.color.ds_primary)
        row.root.setOnClickListener {
            window.dismiss()
            onClick()
        }
        binding.lvRows.addView(row.root)
        return this
    }

    fun show(anchor: View, gravity: Int = Gravity.START) {
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val xOff = if (gravity == Gravity.END) anchor.width - binding.root.measuredWidth else 0
        window.showAsDropDown(anchor, xOff, dp(8f).toInt())
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density
}
