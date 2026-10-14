package com.livemap.homedeck.parts

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.livemap.homedeck.databinding.HdItemSearchShortcutBinding
import com.livemap.homedeck.ext.appUsageDB
import com.livemap.homedeck.support.CorePicks
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.views.MyGridLayoutManager
import com.livemap.homedeck.R
import com.livemap.homedeck.screens.HomeDeck
import com.livemap.homedeck.binders.SliderLeavesFeed
import com.livemap.homedeck.binders.HomesFeed
import com.livemap.homedeck.preset.SliderWay
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import com.livemap.homedeck.databinding.HdAllAppsFragmentBinding
import com.livemap.homedeck.ext.enactDrawerSkin
import com.livemap.homedeck.ext.launcherConfig
import com.livemap.homedeck.ext.fireApp
import com.livemap.homedeck.teaser.HomeSpotsProfile
import com.livemap.homedeck.contract.HomeBook
import com.livemap.homedeck.teaser.BackedSquares
import com.livemap.homedeck.ext.setupDrawerBackground
import com.livemap.homedeck.support.ITEM_TYPE_ICON
import com.livemap.homedeck.ports.AllProgramsObserver
import com.livemap.homedeck.entities.CoreHome
import com.livemap.homedeck.entities.MainDisplayLatticeElement
import com.livemap.homedeck.entities.appLauncherComparator

class SliderPlane(
    context: Context,
    attributeSet: AttributeSet
) : HomePlane<HdAllAppsFragmentBinding>(context, attributeSet), AllProgramsObserver {
    companion object {
        private const val SEARCH_COLUMNS = 4
        private const val SEARCH_MAX_RESULTS = 8

        private const val PREWARM_ROWS = 4
        private const val SEARCH_MAX_SHORTCUTS = 4
    }

    private var prevPointer = Pair(0f, 0f)
    private val pullSlop = ViewConfiguration.get(context).scaledTouchSlop
    var touchDownY = -1
    var ignoreTouches = false

    private var appList = emptyList<CoreHome>()

    @Volatile
    private var lastShape: List<Any>? = null

    private fun layoutKey(): List<Any> = with(context.launcherConfig) {
        listOf(drawerMode, drawerColumnCount, drawerRowCount, showDrawerAppLabels)
    }

    private fun isSameList(a: List<CoreHome>, b: List<CoreHome>): Boolean {
        if (a.size != b.size) return false
        for ((left, right) in a.zip(b)) {
            val same = left.packageName == right.packageName &&
                left.activityName == right.activityName &&
                left.title == right.title &&
                left.thumbnailColor == right.thumbnailColor &&
                left.drawable === right.drawable
            if (!same) return false
        }
        return true
    }

    private var scrollHookInstalled = false

    private val adSlot by lazy {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
    }

    private var gridAdapter: HomesFeed? = null

    private var pagerAdapter: SliderLeavesFeed? = null
    private var visiblePaged: Boolean? = null
    private var downX = -1

    private val pagedMode: Boolean get() = context.launcherConfig.drawerMode == SliderWay.PAGED

    @SuppressLint("ClickableViewAccessibility")
    override fun setupFragment(activity: HomeDeck) {
        this.activity = activity
        this.binding = HdAllAppsFragmentBinding.bind(this)

        val layoutManager = binding.everyAppsGridUi.layoutManager as MyGridLayoutManager
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (scrollAdapter()?.isAdSlotPosition(position) == true) layoutManager.spanCount else 1
        }

        binding.everyAppsGridUi.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchDownY = -1
            }
            false
        }

        binding.everyAppsPagerUi.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = drawPageDots(selected = position)
        })
    }

    fun renewAds() {
        HomeBook.ads.wireNativeOnOpen(activity, HomeSpotsProfile.KEY_APP_DRAWER, adSlot)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupDrawerBackground(context.getColor(R.color.launcher_all_app_bg))
    }

    @SuppressLint("NotifyDataSetChanged")
    fun onResume() {
        val grid = binding.everyAppsGridUi
        val manager = grid.layoutManager ?: return
        if (grid.adapter == null && pagerAdapter == null) return

        val columnsChanged = (manager as MyGridLayoutManager).spanCount != context.launcherConfig.drawerColumnCount
        if (!columnsChanged && visiblePaged == pagedMode) return
        onConfigurationChanged()

        scrollAdapter()?.notifyDataSetChanged()
    }

    fun onConfigurationChanged() {
        binding.everyAppsGridUi.scrollToPosition(0)
        binding.everyAppsPagerUi.setCurrentItem(0, false)
        setupViews()

        val layoutManager = binding.everyAppsGridUi.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = context.launcherConfig.drawerColumnCount
        mountSurface(appList)
    }

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        event ?: return super.onInterceptTouchEvent(event)

        val steal = when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = event.y.toInt()
                downX = event.x.toInt()
                false
            }

            MotionEvent.ACTION_MOVE -> {
                val moved = prevPointer.first != event.x || prevPointer.second != event.y
                if (ignoreTouches && moved) {
                    touchDownY = -1
                    return true
                }
                claimPullDown(event)
            }

            else -> false
        }

        prevPointer = Pair(event.x, event.y)
        return steal
    }

    private fun claimPullDown(event: MotionEvent): Boolean {
        if (touchDownY == -1) return false
        val dy = event.y.toInt() - touchDownY
        val vertical = !pagedMode || abs(dy) > abs(event.x.toInt() - downX)
        if (dy <= pullSlop || !vertical || !gridAtTop()) return false

        if (binding.huntBarUi.hasFocus()) activity?.hideKeyboard()
        activity?.beginHandlingTouches(touchDownY)
        touchDownY = -1
        return true
    }

    private fun gridAtTop(): Boolean {
        if (pagedMode) return true
        val grid = binding.everyAppsGridUi
        val first = grid.getChildAt(0) ?: return true
        val layoutManager = grid.layoutManager ?: return true
        return grid.getChildAdapterPosition(first) <= 0 &&
            layoutManager.getDecoratedTop(first) >= grid.paddingTop
    }

    fun gotLaunchers(appLaunchers: List<CoreHome>, force: Boolean = false) {
        val sorted = appLaunchers.sortedWith(appLauncherComparator)
        val shape = layoutKey()
        if (!force && shape == lastShape && isSameList(sorted, appList)) return
        appList = sorted
        lastShape = shape

        mountSurface(appList)
    }

    private fun resolveCurrent(apps: List<CoreHome>, current: List<CoreHome>): List<CoreHome> {
        val exact = current.associateBy { it.packageName to it.activityName }
        val firstOfPackage = HashMap<String, CoreHome>()
        current.forEach { firstOfPackage.putIfAbsent(it.packageName, it) }
        return apps.mapNotNull { wanted ->
            exact[wanted.packageName to wanted.activityName] ?: firstOfPackage[wanted.packageName]
        }
    }

    private fun scrollAdapter() = gridAdapter

    private val tileClick: (Any) -> Unit = { picked ->
        val app = picked as CoreHome

        when {
            BackedSquares.isSponsored(app) -> activity?.let { host -> BackedSquares.open(host, app) }

            app.packageName == context.applicationContext.packageName -> activity?.enterHostApp(app.activityName)
            else -> activity?.fireApp(app.packageName, app.activityName, HomeSpotsProfile.DRAWER_LAUNCH)
        }
        if (activity?.launcherConfig?.shutAppDrawer == true) activity?.shutAppDrawer(delayed = true)
        ignoreTouches = false
        touchDownY = -1
    }

    private fun mountSurface(entries: List<CoreHome>) {
        activity?.runOnUiThread {
            val paged = pagedMode
            val layoutManager = binding.everyAppsGridUi.layoutManager as MyGridLayoutManager
            layoutManager.spanCount = context.launcherConfig.drawerColumnCount

            if (visiblePaged != paged) {
                visiblePaged = paged
                binding.everyAppsGridUi.beVisibleIf(!paged)
                binding.everyAppsPagerUi.beVisibleIf(paged)
                binding.everyAppsPageDotsUi.beVisibleIf(paged)
                (adSlot.parent as? ViewGroup)?.removeView(adSlot)
                if (paged) {
                    gridAdapter = null
                    binding.everyAppsGridUi.adapter = null
                } else {
                    pagerAdapter = null
                    binding.everyAppsPagerUi.adapter = null
                }
            }

            when {
                paged -> if (pagerAdapter == null) {
                    pagerAdapter = SliderLeavesFeed(activity!!, this, adSlot, tileClick)
                    binding.everyAppsPagerUi.adapter = pagerAdapter
                }

                scrollAdapter() == null -> {
                    val feed = HomesFeed(activity!!, this, adSlot, tileClick)
                    gridAdapter = feed
                    binding.everyAppsGridUi.itemAnimator = null
                    binding.everyAppsGridUi.adapter = feed
                    feed.prewarm(binding.everyAppsGridUi, layoutManager.spanCount * PREWARM_ROWS)
                }
            }

            renderList(entries.toMutableList())
        }
    }

    private fun splitIntoPages(apps: List<CoreHome>): List<List<CoreHome>> {
        if (apps.isEmpty()) return emptyList()
        val cfg = context.launcherConfig
        val columns = maxOf(1, cfg.drawerColumnCount)
        val pageSize = columns * maxOf(2, cfg.drawerRowCount)
        val leadCount = maxOf(columns, pageSize - columns)
        val lead = apps.take(leadCount).toMutableList()
        lead.add((HomeSpotsProfile.drawerAdRowPosition() * columns).coerceIn(0, lead.size), HomesFeed.AD_SLOT)
        return buildList {
            add(lead)
            addAll(apps.drop(leadCount).chunked(pageSize))
        }
    }

    private fun drawPageDots(count: Int = pagerAdapter?.itemCount ?: 0, selected: Int = binding.everyAppsPagerUi.currentItem) {
        val strip = binding.everyAppsPageDotsUi
        val diameter = resources.getDimensionPixelSize(R.dimen.launcher_drawer_page_dot)
        val spacing = resources.getDimensionPixelSize(R.dimen.launcher_drawer_page_dot_gap)
        repeat(maxOf(0, strip.childCount - count)) { strip.removeViewAt(strip.childCount - 1) }
        repeat(maxOf(0, count - strip.childCount)) { strip.addView(pageDot(diameter, spacing)) }
        for (index in 0 until strip.childCount) {
            strip.getChildAt(index).alpha = if (index == selected) 1f else 0.35f
        }
        strip.beVisibleIf(pagedMode && count > 1)
    }

    private fun pageDot(diameter: Int, spacing: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(diameter, diameter).apply {
            marginStart = spacing
            marginEnd = spacing
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(context.getColor(android.R.color.white))
        }
    }

    fun rewindToFirstPage() {
        if (binding.everyAppsPagerUi.adapter != null) binding.everyAppsPagerUi.setCurrentItem(0, false)
    }

    fun onIconHidden(item: MainDisplayLatticeElement) {
        val hiddenId = item.getItemIdentifier()
        val index = appList.indexOfFirst { it.getLauncherIdentifier() == hiddenId }
        if (index < 0) return
        appList = appList.toMutableList().apply { removeAt(index) }
        renderList(appList.toMutableList())
    }

    fun setupViews() {
        if (activity == null) {
            return
        }

        if (!scrollHookInstalled) {
            scrollHookInstalled = true
            binding.everyAppsGridUi.addOnScrollListener(object : OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (binding.huntBarUi.hasFocus() && dy > 0 && binding.everyAppsGridUi.computeVerticalScrollOffset() > 0) {
                        activity?.hideKeyboard()
                    }
                }
            })
        }

        setupDrawerBackground(context.getColor(R.color.launcher_all_app_bg))

        anchorSearchBar()
        watchBottomClearance()
        binding.huntBarUi.beVisibleIf(context.launcherConfig.showSearchBar)
        binding.huntBarUi.requireToolbar().beGone()
        binding.huntBarUi.updateColors()
        binding.huntBarUi.enactDrawerSkin()
        binding.huntBarUi.setupMenu()

        binding.huntBarUi.onSearchTextChangedListener = {
            renderList(appList)
        }
        binding.huntBarUi.onSearchOpenListener = { toggleFinder(true) }
        binding.huntBarUi.onSearchClosedListener = { toggleFinder(false) }

        binding.huntBarUi.binding.topToolbarSearch.setOnEditorActionListener { _, actionId, _ ->
            val submits = actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO
            if (binding.huntBarUi.getCurrentQuery().isEmpty() || !submits) return@setOnEditorActionListener false
            val target = if (pagedMode) pagerAdapter?.firstPageAdapter else scrollAdapter()
            target?.fireFirstApp() == true
        }
    }

    private var anchoredBottom: Boolean? = null

    private fun anchorSearchBar() {
        val bottom = context.launcherConfig.drawerSearchAtBottom

        if (anchoredBottom == bottom) return
        anchoredBottom = bottom
        val barId = binding.huntBarUi.id
        val dotsId = binding.everyAppsPageDotsUi.id
        val toParentTop = RelativeLayout.ALIGN_PARENT_TOP to RelativeLayout.TRUE
        val toParentBottom = RelativeLayout.ALIGN_PARENT_BOTTOM to RelativeLayout.TRUE
        val aboveBar = RelativeLayout.ABOVE to barId
        val belowBar = RelativeLayout.BELOW to barId
        val clearedVerbs = intArrayOf(
            RelativeLayout.ABOVE, RelativeLayout.BELOW, RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_BOTTOM,
        )

        fun View.reanchor(vararg rules: Pair<Int, Int>) {
            val params = this.layoutParams as RelativeLayout.LayoutParams
            clearedVerbs.forEach(params::removeRule)
            for ((verb, subject) in rules) params.addRule(verb, subject)
        }

        binding.huntBarUi.reanchor(if (bottom) toParentBottom else toParentTop)
        binding.everyAppsGridUi.reanchor(if (bottom) aboveBar else belowBar)
        binding.everyAppsPageDotsUi.reanchor(if (bottom) aboveBar else toParentBottom)
        binding.everyAppsPagerUi.reanchor(if (bottom) toParentTop else belowBar, RelativeLayout.ABOVE to dotsId)
        binding.lvNoResultsPlaceholderUi.reanchor(if (bottom) toParentTop else belowBar)

        binding.huntPanelUi.reanchor(
            if (bottom) toParentTop else belowBar,
            if (bottom) aboveBar else toParentBottom,
        )
        binding.huntPanelUi.gravity = Gravity.TOP
        binding.huntBarUi.requestLayout()
    }

    private fun watchBottomClearance() {
        if (!clearanceHooked) {
            clearanceHooked = true
            viewTreeObserver.addOnGlobalLayoutListener { syncBottomClearance() }
        }
        syncBottomClearance()
    }

    private var clearanceHooked = false

    private fun syncBottomClearance() {
        val bottom = context.launcherConfig.drawerSearchAtBottom
        val wanted = if (bottom) binding.everyAppsGridUi.paddingBottom else 0
        val bar = binding.huntBarUi
        if (bar.paddingBottom != wanted) {
            bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, wanted)
        }

        val grid = binding.everyAppsGridUi
        val topGap = if (bottom) resources.getDimensionPixelSize(R.dimen.launcher_drawer_list_top_gap) else 0
        if (grid.paddingTop != topGap) {
            grid.setPadding(grid.paddingLeft, topGap, grid.paddingRight, grid.paddingBottom)
        }
        pinDotsAbovePill(bottom)
    }

    private fun pinDotsAbovePill(bottom: Boolean) {
        val dots = binding.everyAppsPageDotsUi
        val params = dots.layoutParams as? RelativeLayout.LayoutParams ?: return

        if (bottom) {
            val own = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.medium_margin)
            if (dots.paddingBottom != own) dots.setPadding(dots.paddingLeft, dots.paddingTop, dots.paddingRight, own)
        }
        val margin = if (bottom) (pillLiftMargin() ?: return) else 0
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin
            dots.layoutParams = params
        }
    }

    private fun pillLiftMargin(): Int? {
        val bar = binding.huntBarUi
        val pill = bar.binding.toolbarContainer
        var offset = 0
        var node: View? = pill
        while (node != null && node !== bar) {
            offset += node.top
            node = node.parent as? View
        }
        if (node == null || pill.height == 0) return null
        val gap = resources.getDimensionPixelSize(R.dimen.launcher_page_dots_search_gap)
        return -maxOf(0, offset - gap)
    }

    private fun refreshEmptyState() {
        val shown = if (pagedMode) pagerAdapter?.itemCount else scrollAdapter()?.itemCount
        binding.lvNoResultsPlaceholderUi.beVisibleIf(shown == 0)
    }

    override fun onAppLauncherLongPressed(x: Float, y: Float, appLauncher: CoreHome) {
        val gridItem = MainDisplayLatticeElement(
            id = null,
            left = -1,
            top = -1,
            right = -1,
            bottom = -1,
            page = 0,
            packageName = appLauncher.packageName,
            activityName = appLauncher.activityName,
            title = appLauncher.title,
            type = ITEM_TYPE_ICON,
            className = "",
            widgetId = -1,
            shortcutId = "",
            icon = null,
            docked = false,
            parentId = null,
            drawable = appLauncher.drawable
        )

        activity?.presentHomeIconMenu(x, y, gridItem, true)
        ignoreTouches = true

        binding.huntBarUi.closeSearch()
    }

    fun rewindSearch() {
        if (binding.huntBarUi.isSearchOpen) {
            binding.huntBarUi.closeSearch()
        }
    }

    private fun renderList(items: List<CoreHome>) {
        val bar = binding.huntBarUi
        val query = bar.getCurrentQuery()
        if (bar.isSearchOpen) {
            drawFinder(items, query)
            return
        }
        val visible = when {
            query.isEmpty() -> BackedSquares.interleave(items)
            else -> {
                val needle = query.normalizeString()
                items.filter { it.title.normalizeString().contains(needle, ignoreCase = true) }
            }
        }

        if (!pagedMode) {
            scrollAdapter()?.submitList(insertAdRow(visible)) { refreshEmptyState() }
            return
        }
        pagerAdapter?.lodgePages(
            splitIntoPages(visible),
            context.launcherConfig.drawerColumnCount,
            context.launcherConfig.drawerRowCount.coerceAtLeast(2),
        )
        binding.everyAppsPagerUi.setCurrentItem(0, false)
        drawPageDots(selected = 0)
        refreshEmptyState()
    }

    private var finderAdapter: HomesFeed? = null
    private var suggested: List<CoreHome>? = null
    private var suggestToken = 0

    private fun toggleFinder(open: Boolean) {
        binding.huntPanelUi.beVisibleIf(open)
        if (open) {
            binding.everyAppsGridUi.beGone()
            binding.everyAppsPagerUi.beGone()
            binding.everyAppsPageDotsUi.beGone()
            binding.lvNoResultsPlaceholderUi.beGone()
            suggested = null
            loadSuggestions()
            drawFinder(appList, binding.huntBarUi.getCurrentQuery())
        } else {
            val paged = pagedMode
            binding.everyAppsGridUi.beVisibleIf(!paged)
            binding.everyAppsPagerUi.beVisibleIf(paged)
            binding.everyAppsPageDotsUi.beVisibleIf(paged)
            finderAdapter?.highlight = ""
            renderList(appList)
        }
    }

    private fun finderGrid(): HomesFeed {
        finderAdapter?.let { return it }
        val grid = binding.huntAppsGridUi
        grid.layoutManager = object : GridLayoutManager(context, SEARCH_COLUMNS) {
            override fun canScrollVertically() = false
        }
        grid.itemAnimator = null
        grid.isNestedScrollingEnabled = false

        val rowGap = resources.getDimensionPixelSize(R.dimen.launcher_search_card_row_gap)
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                outRect.top = if (position >= SEARCH_COLUMNS) rowGap else 0
            }
        })
        return HomesFeed(activity!!, this, null, tileClick).also {
            finderAdapter = it
            grid.adapter = it
        }
    }

    private fun loadSuggestions() {
        val ticket = ++suggestToken
        val snapshot = appList
        val packages = context.packageManager
        ensureBackgroundThread {
            val log = runCatching { context.appUsageDB.getAll() }.getOrDefault(emptyList())
            val memo = HashMap<String, CorePicks.Facts?>()
            val lookup: (String) -> CorePicks.Facts? = { pkg ->
                memo.getOrPut(pkg) {
                    runCatching {
                        val pkgInfo = packages.getPackageInfo(pkg, 0)
                        val category = pkgInfo.applicationInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED
                        CorePicks.Facts(pkgInfo.firstInstallTime, category)
                    }.getOrNull()
                }
            }
            val ranked = CorePicks.rank(snapshot, log, lookup, System.currentTimeMillis())
            activity?.runOnUiThread {
                if (ticket != suggestToken) return@runOnUiThread
                suggested = ranked
                val bar = binding.huntBarUi
                if (bar.isSearchOpen && bar.getCurrentQuery().isEmpty()) drawFinder(appList, "")
            }
        }
    }

    private fun drawFinder(items: List<CoreHome>, query: String) {
        val adapter = finderGrid()
        if (query.isEmpty()) drawSuggestedCard(adapter, items) else drawSearchedCard(adapter, items, query)
    }

    private fun drawSuggestedCard(adapter: HomesFeed, items: List<CoreHome>) {
        val picks = resolveCurrent(suggested.orEmpty(), items)
        binding.huntAppsTitleUi.setText(R.string.launcher_suggested_apps)
        adapter.highlight = ""
        adapter.submitList(picks.toMutableList())
        binding.huntAppsCardUi.beVisibleIf(picks.size >= CorePicks.MIN_TO_SHOW)
        binding.huntShortcutsCardUi.beGone()
        binding.lvNoResultsPlaceholderUi.beGone()
    }

    private fun drawSearchedCard(adapter: HomesFeed, items: List<CoreHome>, query: String) {
        val needle = query.normalizeString()
        val prefixFirst = compareBy<CoreHome>(
            { !it.title.normalizeString().startsWith(needle, ignoreCase = true) },
            { it.title.lowercase() },
        )
        val hits = items.filter { it.title.normalizeString().contains(needle, ignoreCase = true) }.sortedWith(prefixFirst)
        binding.huntAppsTitleUi.setText(R.string.launcher_searched_apps)
        adapter.highlight = query
        adapter.submitList(hits.take(SEARCH_MAX_RESULTS).toMutableList())
        binding.huntAppsCardUi.beVisibleIf(hits.isNotEmpty())
        binding.lvNoResultsPlaceholderUi.beVisibleIf(hits.isEmpty())
        drawShortcutCard(hits.firstOrNull())
    }

    private fun shortcutsOf(launcherApps: LauncherApps, packageName: String): List<ShortcutInfo> {
        val found = runCatching {
            if (!launcherApps.hasShortcutHostPermission()) return@runCatching null
            val query = LauncherApps.ShortcutQuery()
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
                .setPackage(packageName)
            launcherApps.getShortcuts(query, Process.myUserHandle())
        }.getOrNull() ?: return emptyList()
        return found.filter { it.isEnabled }.take(SEARCH_MAX_SHORTCUTS)
    }

    private fun drawShortcutCard(app: CoreHome?) {
        val card = binding.huntShortcutsCardUi
        val list = binding.huntShortcutsListUi
        list.removeAllViews()
        if (app == null) {
            card.beGone()
            return
        }
        val launcherApps = context.applicationContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val shortcuts = shortcutsOf(launcherApps, app.packageName)
        if (shortcuts.isEmpty()) {
            card.beGone()
            return
        }
        binding.huntShortcutsTitleUi.text = app.title
        val inflater = LayoutInflater.from(context)
        val dpi = resources.displayMetrics.densityDpi
        for (shortcut in shortcuts) {
            val row = HdItemSearchShortcutBinding.inflate(inflater, list, false)
            row.huntShortcutLabelUi.text = shortcut.shortLabel ?: shortcut.longLabel ?: ""
            row.huntShortcutIconUi.setImageDrawable(runCatching { launcherApps.getShortcutIconDrawable(shortcut, dpi) }.getOrNull())
            row.root.setOnClickListener { launchShortcut(launcherApps, shortcut) }
            list.addView(row.root)
        }
        card.beVisible()
    }

    private fun launchShortcut(launcherApps: LauncherApps, shortcut: ShortcutInfo) {
        runCatching {
            launcherApps.startShortcut(shortcut.`package`, shortcut.id, null, null, Process.myUserHandle())
        }.onFailure { activity?.showErrorToast(it as? Exception ?: Exception(it)) }
        if (activity?.launcherConfig?.shutAppDrawer == true) activity?.shutAppDrawer(delayed = true)
    }

    private fun insertAdRow(apps: List<CoreHome>): MutableList<CoreHome> {
        val rows = apps.toMutableList()
        if (rows.isEmpty()) return rows
        val span = (binding.everyAppsGridUi.layoutManager as MyGridLayoutManager).spanCount
        val at = (HomeSpotsProfile.drawerAdRowPosition() * span).coerceIn(0, rows.size)
        rows.add(at, HomesFeed.AD_SLOT)
        return rows
    }
}
