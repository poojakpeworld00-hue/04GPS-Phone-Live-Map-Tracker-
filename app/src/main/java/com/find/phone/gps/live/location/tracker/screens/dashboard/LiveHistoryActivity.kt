package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.databinding.CellHistorySectionBinding
import com.find.phone.gps.live.location.tracker.databinding.CellRecentSearchBinding
import com.find.phone.gps.live.location.tracker.databinding.ScreenHistoryBinding
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.finder.Realms
import com.google.android.material.snackbar.Snackbar
import java.util.Calendar
import java.util.concurrent.TimeUnit

class LiveHistoryActivity : StageActivity<ScreenHistoryBinding>() {
    override val layoutId: Int = R.layout.screen_history

    private val store by lazy { FreshHuntShelf(this) }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.pastRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        binding.actBack.setOnClickListener { goBack() }
        binding.actClearAll.setOnClickListener {
            store.clear()
            render()
        }
        CraftMovement.pressScale(binding.actClearAll)
        render()
    }

    private fun render() {
        val all = store.all()
        binding.lvSections.removeAllViews()
        binding.blankState.isVisible = all.isEmpty()
        binding.actClearAll.isVisible = all.isNotEmpty()
        binding.txtCount.text = resources.getQuantityString(R.plurals.ds_history_count, all.size, all.size)
        binding.txtCount.isVisible = all.isNotEmpty()
        if (all.isEmpty()) return

        val inflater = LayoutInflater.from(this)
        val groups = all.groupBy { bucket(it.searchedAt) }
        listOf(Bucket.TODAY, Bucket.YESTERDAY, Bucket.EARLIER).forEach { bucket ->
            val rows = groups[bucket] ?: return@forEach
            val section = CellHistorySectionBinding.inflate(inflater, binding.lvSections, false)
            section.txtSection.setText(bucket.label)
            rows.forEachIndexed { i, recent ->
                val row = CellRecentSearchBinding.inflate(inflater, section.lvRows, false)
                wireRow(row, recent, i, i < rows.lastIndex)
                section.lvRows.addView(row.root)
            }
            binding.lvSections.addView(section.root)
        }
    }

    private fun wireRow(row: CellRecentSearchBinding, recent: FreshHunt, index: Int, divider: Boolean) {
        val (tile, tint) = TILE_PALETTE[index % TILE_PALETTE.size]
        row.badgeTile.setBackgroundResource(tile)
        row.badgeVw.imageTintList = ContextCompat.getColorStateList(this, tint)
        row.txtNumber.text = recent.display
        val country = Realms.byIso(recent.iso2)?.name ?: recent.iso2
        row.txtMeta.text = getString(R.string.ds_recent_meta, Realms.flag(recent.iso2), country, relativeTime(recent.searchedAt))
        row.lineDivider.isVisible = divider
        row.line.setOnClickListener { pick(recent) }
        row.line.setOnLongClickListener {
            remove(recent)
            true
        }
    }

    private fun pick(recent: FreshHunt) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_DIGITS, recent.digits).putExtra(EXTRA_DIAL, recent.dial))
        finish()
    }

    private fun remove(recent: FreshHunt) {
        store.remove(recent)
        render()
        Snackbar.make(binding.pastRoot, R.string.ds_history_removed, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.ds_snackbar_bg))
            .setTextColor(ContextCompat.getColor(this, R.color.white))
            .setActionTextColor(ContextCompat.getColor(this, R.color.ds_cyan))
            .setAction(R.string.ds_undo) {
                store.add(recent)
                render()
            }
            .show()
    }

    private enum class Bucket(val label: Int) {
        TODAY(R.string.ds_history_today), YESTERDAY(R.string.ds_history_yesterday), EARLIER(R.string.ds_history_earlier)
    }

    private fun bucket(at: Long): Bucket {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when {
            at >= startOfToday -> Bucket.TODAY
            at >= startOfToday - TimeUnit.DAYS.toMillis(1) -> Bucket.YESTERDAY
            else -> Bucket.EARLIER
        }
    }

    private fun relativeTime(at: Long): String {
        val elapsed = System.currentTimeMillis() - at
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
        val days = TimeUnit.MILLISECONDS.toDays(elapsed)
        return when {
            minutes < 1 -> getString(R.string.ds_time_just_now)
            hours < 1 -> getString(R.string.ds_time_minutes_ago, minutes)
            days < 1 -> getString(R.string.ds_time_hours_ago, hours)
            days < 2 -> getString(R.string.ds_time_yesterday)
            else -> getString(R.string.ds_time_days_ago, days)
        }
    }

    companion object {
        const val EXTRA_DIGITS = "digits"
        const val EXTRA_DIAL = "dial"

        fun intent(context: Context): Intent = Intent(context, LiveHistoryActivity::class.java)

        private val TILE_PALETTE = listOf(
            R.drawable.lv_tile_tint_r14 to R.color.ds_primary,
            R.drawable.lv_tile_green_r14 to R.color.ds_success,
            R.drawable.lv_tile_purple_r14 to R.color.ds_purple,
        )
    }
}
