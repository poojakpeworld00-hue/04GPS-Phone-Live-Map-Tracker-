package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.databinding.CellHomeTimelineBinding
import com.find.phone.gps.live.location.tracker.screens.finder.Realms
import java.util.concurrent.TimeUnit

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

    inner class VH(val binding: CellHomeTimelineBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.line.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(items[position])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(CellHomeTimelineBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.binding.root.context
        with(holder.binding) {
            txtNumber.text = item.display
            val country = Realms.byIso(item.iso2)?.name ?: item.iso2
            txtMeta.text = ctx.getString(R.string.home_recent_place, Realms.flag(item.iso2), country)
            txtWhen.text = relativeTime(ctx, item.searchedAt)
            lineDivider.visibility = if (position < items.lastIndex) View.VISIBLE else View.INVISIBLE
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
}
