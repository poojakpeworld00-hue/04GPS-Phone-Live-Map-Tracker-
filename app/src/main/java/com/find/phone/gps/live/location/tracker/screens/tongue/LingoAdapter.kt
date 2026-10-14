package com.find.phone.gps.live.location.tracker.screens.tongue

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
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.databinding.CellLanguageBinding

/**
 * Language rows (design 4c). The selected row gets the tint background, a primary code tile
 * and the filled radio; only the two affected rows are rebound on a change, with a payload,
 * so the radio can pop instead of snapping.
 */
class LingoAdapter(
    private val onClick: (LingoElement) -> Unit,
) : RecyclerView.Adapter<LingoAdapter.VH>() {

    private val items = mutableListOf<LingoElement>()
    private var selectedTag: String = ""

    /** The language currently applied; its row shows "Current language". */
    private var currentTag: String = ""

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<LingoElement>) {
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

    inner class VH(val binding: CellLanguageBinding) : RecyclerView.ViewHolder(binding.root) {

        private var running: Animator? = null

        init {
            binding.lineLanguage.setOnClickListener { view ->
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

        fun abortAnimations() {
            running?.cancel()
            running = null
            binding.lvRadio.scaleX = 1f
            binding.lvRadio.scaleY = 1f
        }

        fun wireSelection(selected: Boolean, animate: Boolean) {
            val ctx = binding.root.context
            abortAnimations()
            binding.lineLanguage.background = if (selected) {
                ContextCompat.getDrawable(ctx, R.drawable.lv_row_selected_r14)
            } else {
                null
            }
            binding.txtCode.setBackgroundResource(if (selected) R.drawable.lv_tile_primary_r12 else R.drawable.lv_tile_gray_r12)
            binding.txtCode.setTextColor(ContextCompat.getColor(ctx, if (selected) R.color.white else R.color.ds_ink2))
            binding.lvRadio.setBackgroundResource(if (selected) R.drawable.lv_radio_on else R.drawable.lv_radio_off)

            if (!animate || !selected) return
            running = ObjectAnimator.ofPropertyValuesHolder(
                binding.lvRadio,
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
        VH(CellLanguageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            val ctx = root.context
            // The language's flag in the badge (the short code is gone: the name says it).
            txtCode.text = item.flag
            txtNative.text = item.nativeName
            txtName.text = if (item.tag == currentTag) ctx.getString(R.string.ds_current_language) else item.name
            lineDivider.isVisible = position < items.lastIndex
        }
        holder.wireSelection(item.tag == selectedTag, animate = false)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.wireSelection(items[position].tag == selectedTag, animate = true)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.abortAnimations()
        holder.binding.lineLanguage.animate().cancel()
        holder.binding.lineLanguage.scaleX = 1f
        holder.binding.lineLanguage.scaleY = 1f
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
