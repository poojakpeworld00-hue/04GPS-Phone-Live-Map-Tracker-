package com.find.my.phone.gps.location.tracker.ui.language

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.databinding.TileLanguageBinding

/**
 * Language rows (design 4c). The selected row gets the tint background, a primary code tile
 * and the filled radio; only the two affected rows are rebound on a change, with a payload,
 * so the radio can pop instead of snapping.
 */
class LocaleAdapter(
    private val onClick: (LocaleItem) -> Unit,
) : RecyclerView.Adapter<LocaleAdapter.VH>() {

    private val items = mutableListOf<LocaleItem>()
    private var selectedTag: String = ""

    /** The language currently applied; its row shows "Current language". */
    private var currentTag: String = ""

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<LocaleItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setSelected(tag: String) {
        if (tag == selectedTag) return
        val previous = items.indexOfFirst { it.tag == selectedTag }
        val next = items.indexOfFirst { it.tag == tag }
        selectedTag = tag
        if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous, PAYLOAD_SELECTION)
        if (next != RecyclerView.NO_POSITION) notifyItemChanged(next, PAYLOAD_SELECTION)
    }

    fun setCurrent(tag: String) {
        currentTag = tag
    }

    inner class VH(val binding: TileLanguageBinding) : RecyclerView.ViewHolder(binding.root) {

        private var running: Animator? = null

        init {
            binding.rowLanguage.setOnClickListener { view ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                tapFeedback(view)
                onClick(items[position])
            }
        }

        /** Quick press-in / release bounce so the tap registers immediately. */
        private fun tapFeedback(view: View) {
            view.animate().cancel()
            view.animate()
                .scaleX(TAP_SCALE).scaleY(TAP_SCALE)
                .setDuration(TAP_DOWN_MS)
                .withEndAction {
                    view.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(TAP_UP_MS)
                        .setInterpolator(OvershootInterpolator(TAP_OVERSHOOT))
                        .start()
                }
                .start()
        }

        fun cancelAnimations() {
            running?.cancel()
            running = null
            binding.radio.scaleX = 1f
            binding.radio.scaleY = 1f
        }

        fun bindSelection(selected: Boolean, animate: Boolean) {
            val ctx = binding.root.context
            cancelAnimations()
            binding.rowLanguage.background = if (selected) {
                ContextCompat.getDrawable(ctx, R.drawable.ds_row_selected_r14)
            } else {
                null
            }
            binding.tvCode.setBackgroundResource(if (selected) R.drawable.ds_tile_primary_r12 else R.drawable.ds_tile_gray_r12)
            binding.tvCode.setTextColor(ContextCompat.getColor(ctx, if (selected) R.color.white else R.color.ds_ink2))
            binding.radio.setBackgroundResource(if (selected) R.drawable.ds_radio_on else R.drawable.ds_radio_off)

            if (!animate || !selected) return
            running = ObjectAnimator.ofPropertyValuesHolder(
                binding.radio,
                PropertyValuesHolder.ofFloat(View.SCALE_X, POP_FROM, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, POP_FROM, 1f),
            ).apply {
                duration = POP_MS
                interpolator = OvershootInterpolator(POP_OVERSHOOT)
                start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(TileLanguageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            val ctx = root.context
            // The language's flag in the badge (the short code is gone: the name says it).
            tvCode.text = item.flag
            tvNative.text = item.nativeName
            tvName.text = if (item.tag == currentTag) ctx.getString(R.string.ds_current_language) else item.name
            rowDivider.isVisible = position < items.lastIndex
        }
        holder.bindSelection(item.tag == selectedTag, animate = false)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(items[position].tag == selectedTag, animate = true)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.cancelAnimations()
        holder.binding.rowLanguage.animate().cancel()
        holder.binding.rowLanguage.scaleX = 1f
        holder.binding.rowLanguage.scaleY = 1f
    }

    override fun getItemCount(): Int = items.size

    private companion object {
        const val PAYLOAD_SELECTION = "selection"
        const val TAP_SCALE = 0.97f
        const val TAP_DOWN_MS = 70L
        const val TAP_UP_MS = 160L
        const val TAP_OVERSHOOT = 2.2f
        const val POP_FROM = 0.72f
        const val POP_MS = 260L
        const val POP_OVERSHOOT = 3.0f
    }
}
