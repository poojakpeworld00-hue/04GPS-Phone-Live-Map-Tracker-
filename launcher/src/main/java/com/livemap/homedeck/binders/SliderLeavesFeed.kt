package com.livemap.homedeck.binders

import android.view.View
import com.livemap.homedeck.R
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.livemap.homedeck.screens.HomeBaseDeck
import com.livemap.homedeck.ports.AllProgramsObserver
import com.livemap.homedeck.entities.CoreHome

class SliderLeavesFeed(
    private val activity: HomeBaseDeck,
    private val listener: AllProgramsObserver,
    private val adSlot: View?,
    private val itemClick: (Any) -> Unit,
) : RecyclerView.Adapter<SliderLeavesFeed.PageHolder>() {
    private var pages: List<List<CoreHome>> = emptyList()
    private var columns = 4
    private var rows = 6

    var firstPageAdapter: HomesFeed? = null
        private set

    fun lodgePages(newPages: List<List<CoreHome>>, columnCount: Int, rowCount: Int) {
        pages = newPages
        columns = columnCount
        rows = rowCount.coerceAtLeast(1)
        notifyDataSetChanged()
    }

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val grid = RecyclerView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(
                0, parent.resources.getDimensionPixelSize(R.dimen.launcher_drawer_top_gap),
                0, parent.resources.getDimensionPixelSize(R.dimen.launcher_drawer_page_bottom_gap),
            )
            itemAnimator = null
            isNestedScrollingEnabled = false
        }
        return PageHolder(grid, parent)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val isFirst = position == 0
        holder.bind(pages[position], columns, rows, if (isFirst) adSlot else null)
        if (isFirst) firstPageAdapter = holder.adapter
    }

    inner class PageHolder(private val grid: RecyclerView, private val pager: ViewGroup) : RecyclerView.ViewHolder(grid) {
        var adapter: HomesFeed? = null
            private set
        private var hostsAdSlot = false
        private var rowCount = 6

        init {
            grid.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) grid.post { alignCellHeight(bottom - top) }
            }
        }

        private fun cellHeightFor(pageHeight: Int) =
            if (pageHeight > 0) (pageHeight - grid.paddingTop - grid.paddingBottom) / rowCount else 0

        private fun alignCellHeight(pageHeight: Int) {
            val lineup = adapter ?: return
            val cell = cellHeightFor(pageHeight)
            if (cell <= 0 || lineup.cellHeightPx == cell) return
            lineup.cellHeightPx = cell
            lineup.notifyItemRangeChanged(0, lineup.itemCount)
        }

        fun bind(apps: List<CoreHome>, columnCount: Int, rows: Int, slot: View?) {
            rowCount = rows

            val layoutManager = (grid.layoutManager as? GridLayoutManager)
                ?: object : GridLayoutManager(grid.context, columnCount) {
                    override fun canScrollVertically() = false
                }.also { grid.layoutManager = it }
            layoutManager.spanCount = columnCount

            if (adapter == null || hostsAdSlot != (slot != null)) {
                adapter = HomesFeed(activity, listener, slot, itemClick)
                hostsAdSlot = slot != null
                grid.adapter = adapter
            }
            val lineup = adapter!!
            layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (lineup.isAdSlotPosition(position)) layoutManager.spanCount else 1
            }

            cellHeightFor(grid.height.takeIf { it > 0 } ?: pager.height).takeIf { it > 0 }?.let { lineup.cellHeightPx = it }
            lineup.submitList(apps.toMutableList())
        }
    }
}
