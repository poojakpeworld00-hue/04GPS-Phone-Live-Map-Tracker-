package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.databinding.CellRecentSearchBinding
import com.find.phone.gps.live.location.tracker.screens.finder.Realms
import java.util.concurrent.TimeUnit

/** Recent-search rows (design 3b): the pin tile cycles tint / green / purple down the list. */
class FreshHuntAdapter(
    private val onClick: (FreshHunt) -> Unit,
) : RecyclerView.Adapter<FreshHuntAdapter.VH>() {

    private val items = mutableListOf<FreshHunt>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<FreshHunt>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val binding: CellRecentSearchBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.line.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(items[position])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(CellRecentSearchBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.binding.root.context
        val (tile, tint) = TILE_PALETTE[position % TILE_PALETTE.size]
        with(holder.binding) {
            badgeTile.setBackgroundResource(tile)
            ImageViewCompat.setImageTintList(badgeVw, ContextCompat.getColorStateList(ctx, tint))
            txtNumber.text = item.display
            val country = Realms.byIso(item.iso2)?.name ?: item.iso2
            txtMeta.text = ctx.getString(R.string.ds_recent_meta, Realms.flag(item.iso2), country, relativeTime(ctx, item.searchedAt))
            lineDivider.isVisible = position < items.lastIndex
        }
    }

    override fun getItemCount(): Int = items.size

    private fun relativeTime(ctx: Context, at: Long): String {
        val elapsed = System.currentTimeMillis() - at
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
        val days = TimeUnit.MILLISECONDS.toDays(elapsed)
        return when {
            minutes < 1 -> ctx.getString(R.string.ds_time_just_now)
            hours < 1 -> ctx.getString(R.string.ds_time_minutes_ago, minutes)
            days < 1 -> ctx.getString(R.string.ds_time_hours_ago, hours)
            days < 2 -> ctx.getString(R.string.ds_time_yesterday)
            else -> ctx.getString(R.string.ds_time_days_ago, days)
        }
    }

    private companion object {
        val TILE_PALETTE = listOf(
            R.drawable.lv_tile_tint_r14 to R.color.ds_primary,
            R.drawable.lv_tile_green_r14 to R.color.ds_success,
            R.drawable.lv_tile_purple_r14 to R.color.ds_purple,
        )
    }
}
