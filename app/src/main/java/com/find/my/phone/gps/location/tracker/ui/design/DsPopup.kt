package com.find.my.phone.gps.location.tracker.ui.design

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.databinding.PiecePopupMenuBinding
import com.find.my.phone.gps.location.tracker.databinding.TilePopupRowBinding

/**
 * A menu popup in the design system: an r16 surface card with a hairline border and shadow,
 * 44dp rows with a 30dp icon tile, the label, an optional count pill, and a check on the
 * selected row. Replaces the platform PopupMenu wherever a screen offers a short choice.
 */
class DsPopup(private val context: Context) {

    private val binding = PiecePopupMenuBinding.inflate(LayoutInflater.from(context))
    private val window = PopupWindow(
        binding.root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true,
    ).apply {
        elevation = dp(16f)
        // A transparent window background: the card drawable draws the shape, the elevation the shadow.
        setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.color.transparent))
        isOutsideTouchable = true
    }

    fun add(
        label: CharSequence,
        @DrawableRes icon: Int,
        badge: CharSequence? = null,
        selected: Boolean = false,
        onClick: () -> Unit,
    ): DsPopup {
        val row = TilePopupRowBinding.inflate(LayoutInflater.from(context), binding.rows, false)
        row.tvLabel.text = label
        row.ivIcon.setImageResource(icon)
        row.tvBadge.text = badge
        row.tvBadge.isVisible = !badge.isNullOrEmpty()
        row.ivCheck.isVisible = selected
        row.iconTile.setBackgroundResource(if (selected) R.drawable.ds_tile_primary_r12 else R.drawable.ds_tile_tint_r12)
        row.ivIcon.imageTintList = ContextCompat.getColorStateList(context, if (selected) R.color.white else R.color.ds_primary)
        row.root.setOnClickListener {
            window.dismiss()
            onClick()
        }
        binding.rows.addView(row.root)
        return this
    }

    /** Drops down under [anchor], 8dp below it, aligned to its start or end edge. */
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
