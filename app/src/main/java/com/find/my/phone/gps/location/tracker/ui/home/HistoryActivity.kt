package com.find.my.phone.gps.location.tracker.ui.home

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.databinding.TileHistorySectionBinding
import com.find.my.phone.gps.location.tracker.databinding.TileRecentSearchBinding
import com.find.my.phone.gps.location.tracker.databinding.ViewHistoryBinding
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.lookup.Territories
import com.google.android.material.snackbar.Snackbar
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Search history: every recent search grouped under Today / Yesterday / Earlier, newest first,
 * in the Home's recent-search row style. Tapping a row hands it back to Home ([EXTRA_DIGITS],
 * [EXTRA_DIAL]), which reopens its result or refills the search box exactly as the Home list
 * does; a long press removes the row (with Undo); Clear all empties the history.
 */
class HistoryActivity : CanvasActivity<ViewHistoryBinding>() {

    override val layoutId: Int = R.layout.view_history

    private val store by lazy { RecentSearchStore(this) }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.historyRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { goBack() }
        binding.btnClearAll.setOnClickListener {
            store.clear()
            render()
        }
        DesignMotion.pressScale(binding.btnClearAll)
        render()
    }

    private fun render() {
        val all = store.all()
        binding.sections.removeAllViews()
        binding.emptyState.isVisible = all.isEmpty()
        binding.btnClearAll.isVisible = all.isNotEmpty()
        binding.tvCount.text = resources.getQuantityString(R.plurals.ds_history_count, all.size, all.size)
        binding.tvCount.isVisible = all.isNotEmpty()
        if (all.isEmpty()) return

        val inflater = LayoutInflater.from(this)
        val groups = all.groupBy { bucket(it.searchedAt) }
        listOf(Bucket.TODAY, Bucket.YESTERDAY, Bucket.EARLIER).forEach { bucket ->
            val rows = groups[bucket] ?: return@forEach
            val section = TileHistorySectionBinding.inflate(inflater, binding.sections, false)
            section.tvSection.setText(bucket.label)
            rows.forEachIndexed { i, recent ->
                val row = TileRecentSearchBinding.inflate(inflater, section.rows, false)
                bindRow(row, recent, i, i < rows.lastIndex)
                section.rows.addView(row.root)
            }
            binding.sections.addView(section.root)
        }
    }

    private fun bindRow(row: TileRecentSearchBinding, recent: RecentSearch, index: Int, divider: Boolean) {
        val (tile, tint) = TILE_PALETTE[index % TILE_PALETTE.size]
        row.iconTile.setBackgroundResource(tile)
        row.icon.imageTintList = ContextCompat.getColorStateList(this, tint)
        row.tvNumber.text = recent.display
        val country = Territories.byIso(recent.iso2)?.name ?: recent.iso2
        row.tvMeta.text = getString(R.string.ds_recent_meta, Territories.flag(recent.iso2), country, relativeTime(recent.searchedAt))
        row.rowDivider.isVisible = divider
        row.row.setOnClickListener { pick(recent) }
        row.row.setOnLongClickListener {
            remove(recent)
            true
        }
    }

    /** Hands the row to Home, which owns the reopen / refill logic. */
    private fun pick(recent: RecentSearch) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_DIGITS, recent.digits).putExtra(EXTRA_DIAL, recent.dial))
        finish()
    }

    private fun remove(recent: RecentSearch) {
        store.remove(recent)
        render()
        Snackbar.make(binding.historyRoot, R.string.ds_history_removed, Snackbar.LENGTH_LONG)
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

        fun intent(context: Context): Intent = Intent(context, HistoryActivity::class.java)

        /** The Home list's tile palette, so the same number keeps the same colour here. */
        private val TILE_PALETTE = listOf(
            R.drawable.ds_tile_tint_r14 to R.color.ds_primary,
            R.drawable.ds_tile_green_r14 to R.color.ds_success,
            R.drawable.ds_tile_purple_r14 to R.color.ds_purple,
        )
    }
}
