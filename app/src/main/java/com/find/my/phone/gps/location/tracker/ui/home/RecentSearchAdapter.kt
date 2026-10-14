package com.find.my.phone.gps.location.tracker.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.databinding.TileRecentSearchBinding
import com.find.my.phone.gps.location.tracker.ui.lookup.Territories
import java.util.concurrent.TimeUnit

/** Recent-search rows (design 3b): the pin tile cycles tint / green / purple down the list. */
class RecentSearchAdapter(
    private val onClick: (RecentSearch) -> Unit,
) : RecyclerView.Adapter<RecentSearchAdapter.VH>() {

    private val items = mutableListOf<RecentSearch>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<RecentSearch>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val binding: TileRecentSearchBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.row.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(items[position])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(TileRecentSearchBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.binding.root.context
        val (tile, tint) = TILE_PALETTE[position % TILE_PALETTE.size]
        with(holder.binding) {
            iconTile.setBackgroundResource(tile)
            ImageViewCompat.setImageTintList(icon, ContextCompat.getColorStateList(ctx, tint))
            tvNumber.text = item.display
            val country = Territories.byIso(item.iso2)?.name ?: item.iso2
            tvMeta.text = ctx.getString(R.string.ds_recent_meta, Territories.flag(item.iso2), country, relativeTime(ctx, item.searchedAt))
            rowDivider.isVisible = position < items.lastIndex
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
            R.drawable.ds_tile_tint_r14 to R.color.ds_primary,
            R.drawable.ds_tile_green_r14 to R.color.ds_success,
            R.drawable.ds_tile_purple_r14 to R.color.ds_purple,
        )
    }
}
