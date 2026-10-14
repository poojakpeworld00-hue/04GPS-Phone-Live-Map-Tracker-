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
        /** The search cards are four across whatever the drawer's own column count (One UI's Finder is too). */
        private const val SEARCH_COLUMNS = 4
        private const val SEARCH_MAX_RESULTS = 8

        /** Rows of app cells inflated while idle so the first fling recycles instead of inflating. */
        private const val PREWARM_ROWS = 4
        private const val SEARCH_MAX_SHORTCUTS = 4
    }

    private var lastTouchCoords = Pair(0f, 0f)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    var touchDownY = -1
    var ignoreTouches = false

    private var launchers = emptyList<CoreHome>()

    /** The layout [launchers] was last submitted for - see [gotLaunchers]. */
    @Volatile
    private var submittedShape: List<Any>? = null

    /** Everything in the config that changes how the same list is laid out. */
    private fun drawerShape(): List<Any> = with(context.launcherConfig) {
        listOf(drawerMode, drawerColumnCount, drawerRowCount, showDrawerAppLabels)
    }

    /** Same apps, in the same order, with the same names, tints and decoded icons. */
    private fun sameLaunchers(a: List<CoreHome>, b: List<CoreHome>): Boolean =
        a.size == b.size && a.indices.all { i ->
            val x = a[i]
            val y = b[i]
            x.packageName == y.packageName && x.activityName == y.activityName && x.title == y.title &&
                x.thumbnailColor == y.thumbnailColor && x.drawable === y.drawable
        }

    /**
     * [setupViews] runs on every resume — HomeDeck.onResume re-runs it once the layout settles —
     * and everything in it is an assignment except the scroll listener, which appends. Left
     * unguarded the grid ends up with one listener per resume, each doing the `hasFocus` check on
     * every scrolled frame.
     */
    private var scrollListenerAdded = false

    /**
     * The drawer's native slot. Built here rather than in the layout because it is not a sibling of
     * the grid: [HomesFeed] hosts it in a full-span row of the grid — at the row
     * `launcher.screens.app_drawer.ad_row_position` names — so it scrolls away with the apps.
     * Starts GONE — the host's ad wiring reveals it only once a fill arrives, and until then the row
     * it lives in wraps to nothing.
     */
    private val adSlot by lazy {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
    }

    private var launchersAdapter: HomesFeed? = null

    // The paged (Samsung-style) drawer: one grid per page in the ViewPager2, dots underneath.
    // Which of the two surfaces is live follows Config.drawerMode - see setupAdapter.
    private var pagesAdapter: SliderLeavesFeed? = null
    private var shownPaged: Boolean? = null
    private var touchDownX = -1

    private val isPaged: Boolean get() = context.launcherConfig.drawerMode == SliderWay.PAGED

    @SuppressLint("ClickableViewAccessibility")
    override fun setupFragment(activity: HomeDeck) {
        this.activity = activity
        this.binding = HdAllAppsFragmentBinding.bind(this)

        // the slot's row spans the whole width; every other position is a single app icon. Which
        // position that is comes from the launcherConfig, so it is asked of the adapter rather than fixed
        // here — see submitList.
        val layoutManager = binding.everyAppsGridUi.layoutManager as MyGridLayoutManager
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (getAdapter()?.isAdSlotPosition(position) == true) layoutManager.spanCount else 1
        }

        binding.everyAppsGridUi.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchDownY = -1
            }

            return@setOnTouchListener false
        }

        binding.everyAppsPagerUi.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = paintDots(selected = position)
        })
    }

    /**
     * Requests the drawer's slot afresh.
     *
     * Driven by `HomeDeck.presentFragment` on every open rather than once from [setupFragment],
     * for the same reason as `ProgramsDeckPlane.renewAds`: the drawer is built at boot and then
     * only parked below the screen, so a single request there would pin one creative for the life
     * of the process.
     *
     * [adSlot] is a stable view that the adapter re-parents into whichever holder owns the ad row,
     * so it can be refreshed while it is detached — a fill that lands then is already in place by
     * the time the row scrolls back in.
     *
     * Whether the refresh happens at all is the console's call — see
     * [HomeSpotsProfile.reloadAdOnOpen]. The drawer is opened far more often than either panel, so
     * it is the placement most likely to want the flag off.
     */
    fun renewAds() {
        HomeBook.ads.wireNativeOnOpen(activity, HomeSpotsProfile.KEY_APP_DRAWER, adSlot)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupDrawerBackground(context.getColor(R.color.launcher_all_app_bg))
    }

    @SuppressLint("NotifyDataSetChanged")
    fun onResume() {
        if (binding.everyAppsGridUi.layoutManager == null || (binding.everyAppsGridUi.adapter == null && pagesAdapter == null)) {
            return
        }

        val layoutManager = binding.everyAppsGridUi.layoutManager as MyGridLayoutManager
        if (layoutManager.spanCount != context.launcherConfig.drawerColumnCount || shownPaged != isPaged) {
            onConfigurationChanged()
            // Force redraw due to changed item size
            getAdapter()?.notifyDataSetChanged()
        }
    }

    fun onConfigurationChanged() {
        binding.everyAppsGridUi.scrollToPosition(0)
        binding.everyAppsPagerUi.setCurrentItem(0, false)
        setupViews()

        val layoutManager = binding.everyAppsGridUi.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = context.launcherConfig.drawerColumnCount
        setupAdapter(launchers)
    }

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) {
            return super.onInterceptTouchEvent(event)
        }

        var shouldIntercept = false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = event.y.toInt()
                touchDownX = event.x.toInt()
            }

            MotionEvent.ACTION_MOVE -> {
                if (ignoreTouches) {
                    // some devices ACTION_MOVE keeps triggering for the whole long press duration, but we are interested in real moves only, when coords change
                    if (lastTouchCoords.first != event.x || lastTouchCoords.second != event.y) {
                        touchDownY = -1
                        return true
                    }
                }

                // pull the whole fragment down if it is scrolled way to the top and the user pulls it even further.
                // a held finger always drifts a pixel or two, so this has to clear the touch slop: intercepting on
                // any downward movement cancels the icon's pending long press and the popup menu never appears
                if (touchDownY != -1) {
                    val distance = event.y.toInt() - touchDownY
                    // On the pager a page swipe drifts a little vertically; only a pull that is
                    // mostly downward may take the sheet away from it.
                    val mostlyVertical = !isPaged || abs(distance) > abs(event.x.toInt() - touchDownX)
                    shouldIntercept = distance > touchSlop && mostlyVertical && isGridAtTop()
                    if (shouldIntercept) {
                        // Hiding is expensive, only do it if focused
                        if (binding.huntBarUi.hasFocus()) {
                            activity?.hideKeyboard()
                        }
                        activity?.beginHandlingTouches(touchDownY)
                        touchDownY = -1
                    }
                }
            }
        }

        lastTouchCoords = Pair(event.x, event.y)
        return shouldIntercept
    }

    /**
     * Whether the grid is scrolled all the way to its top, read off the first laid-out child.
     *
     * Not `computeVerticalScrollOffset() == 0`: that is an estimate, and GridLayoutManager skips
     * zero-height children when it looks for the first visible row. The ad row is exactly that
     * while its slot is GONE - which is every open until a fill arrives, and always when the
     * placement is off - so with the row at position 0 the estimate reads one row of "scrolled
     * away" for a grid that has never moved, and the pull-down never intercepted. The reference
     * app asks its scroll view for `scrollY == 0`; this is the same question put to the grid.
     */
    private fun isGridAtTop(): Boolean {
        // Pages never scroll vertically: the sheet is always at its top.
        if (isPaged) return true
        val grid = binding.everyAppsGridUi
        val first = grid.getChildAt(0) ?: return true
        val layoutManager = grid.layoutManager ?: return true
        return grid.getChildAdapterPosition(first) <= 0 &&
            layoutManager.getDecoratedTop(first) >= grid.paddingTop
    }

    /**
     * Shows [appLaunchers]. Every resume hands the drawer the launcher list twice (the cached one,
     * then the scan's), and almost always nothing has changed. The scrolling grid diffs that to
     * nothing, but the paged drawer rebuilds every page and jumps back to the first, which showed
     * as all the icons repainting each time the user came back - twice when an App Open Ad closed
     * over the launcher. So a list identical to the one on screen, for a drawer laid out the same
     * way, is not resubmitted. [force] resubmits anyway: a config change that moves rows (the ad
     * row) without changing the apps.
     */
    fun gotLaunchers(appLaunchers: List<CoreHome>, force: Boolean = false) {
        val sorted = appLaunchers.sortedWith(appLauncherComparator)
        val shape = drawerShape()
        if (!force && shape == submittedShape && sameLaunchers(sorted, launchers)) return
        launchers = sorted
        submittedShape = shape

        setupAdapter(launchers)
    }

    /** The current launcher entry (with its decoded icon) for each of [apps], by package and activity; dropped if gone. */
    private fun live(apps: List<CoreHome>, current: List<CoreHome>): List<CoreHome> {
        val byKey = current.associateBy { it.packageName to it.activityName }
        return apps.mapNotNull { byKey[it.packageName to it.activityName] ?: current.firstOrNull { c -> c.packageName == it.packageName } }
    }

    // held here rather than read back off the RecyclerView, which hands it back as a plain
    // RecyclerView.Adapter
    private fun getAdapter() = launchersAdapter

    // Gated here rather than inside fireApp: the left panel launches apps through the same
    // extension, and this placement is the *drawer's*. Shared by the scrolling grid and every page.
    private val onItemClick: (Any) -> Unit = {
        val launcher = it as CoreHome
        // The app-launch promo is applied inside fireApp (DRAWER_LAUNCH), for every surface that
        // opens an app; a sponsored tile opens its landing page through the host, no promo first.
        if (BackedSquares.isSponsored(launcher)) {
            activity?.let { a -> BackedSquares.open(a, launcher) }
        } else if (launcher.packageName == context.applicationContext.packageName) {
            // Our own app: the host panel (or the host's own screen), not our LAUNCHER entry.
            activity?.enterHostApp(launcher.activityName)
        } else {
            activity?.fireApp(
                launcher.packageName, launcher.activityName,
                com.livemap.homedeck.teaser.HomeSpotsProfile.DRAWER_LAUNCH,
            )
        }
        if (activity?.launcherConfig?.shutAppDrawer == true) {
            activity?.shutAppDrawer(delayed = true)
        }
        ignoreTouches = false
        touchDownY = -1
    }

    private fun setupAdapter(launchers: List<CoreHome>) {
        activity?.runOnUiThread {
            val paged = isPaged
            val layoutManager = binding.everyAppsGridUi.layoutManager as MyGridLayoutManager
            layoutManager.spanCount = context.launcherConfig.drawerColumnCount

            // One surface at a time. The slot is a single view, so the surface that goes away
            // must let go of it: its adapter is dropped and rebuilt on the way back.
            if (shownPaged != paged) {
                shownPaged = paged
                binding.everyAppsGridUi.beVisibleIf(!paged)
                binding.everyAppsPagerUi.beVisibleIf(paged)
                binding.everyAppsPageDotsUi.beVisibleIf(paged)
                (adSlot.parent as? ViewGroup)?.removeView(adSlot)
                if (paged) {
                    launchersAdapter = null
                    binding.everyAppsGridUi.adapter = null
                } else {
                    pagesAdapter = null
                    binding.everyAppsPagerUi.adapter = null
                }
            }

            if (paged) {
                if (pagesAdapter == null) {
                    pagesAdapter = SliderLeavesFeed(activity!!, this, adSlot, onItemClick)
                    binding.everyAppsPagerUi.adapter = pagesAdapter
                }
            } else if (getAdapter() == null) {
                HomesFeed(activity!!, this, adSlot, onItemClick).apply {
                    launchersAdapter = this
                    binding.everyAppsGridUi.itemAnimator = null
                    binding.everyAppsGridUi.adapter = this
                    prewarm(binding.everyAppsGridUi, layoutManager.spanCount * PREWARM_ROWS)
                }
            }

            submitList(launchers.toMutableList())
        }
    }

    /**
     * Cuts the list into pages of `drawerColumnCount` x `drawerRowCount`. The native slot takes a
     * full row of the first page, at the row the console names, so that page holds one row fewer.
     */
    private fun chunkPages(apps: List<CoreHome>): List<List<CoreHome>> {
        if (apps.isEmpty()) return emptyList()
        val columns = context.launcherConfig.drawerColumnCount.coerceAtLeast(1)
        val rows = context.launcherConfig.drawerRowCount.coerceAtLeast(2)
        val perPage = columns * rows
        val firstPageApps = (perPage - columns).coerceAtLeast(columns)
        val pages = ArrayList<List<CoreHome>>()
        val first = apps.take(firstPageApps).toMutableList()
        val adIndex = (HomeSpotsProfile.drawerAdRowPosition() * columns).coerceIn(0, first.size)
        first.add(adIndex, HomesFeed.AD_SLOT)
        pages.add(first)
        apps.drop(firstPageApps).chunked(perPage).forEach(pages::add)
        return pages
    }

    private fun paintDots(count: Int = pagesAdapter?.itemCount ?: 0, selected: Int = binding.everyAppsPagerUi.currentItem) {
        val dots = binding.everyAppsPageDotsUi
        val size = resources.getDimensionPixelSize(R.dimen.launcher_drawer_page_dot)
        val gap = resources.getDimensionPixelSize(R.dimen.launcher_drawer_page_dot_gap)
        while (dots.childCount > count) dots.removeViewAt(dots.childCount - 1)
        while (dots.childCount < count) {
            dots.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginStart = gap; it.marginEnd = gap }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(context.getColor(android.R.color.white))
                }
            })
        }
        for (i in 0 until dots.childCount) dots.getChildAt(i).alpha = if (i == selected) 1f else 0.35f
        dots.beVisibleIf(isPaged && count > 1)
    }

    /** Back to the first page, the way the scrolling drawer goes back to its top on close. */
    fun rewindToFirstPage() {
        if (binding.everyAppsPagerUi.adapter != null) binding.everyAppsPagerUi.setCurrentItem(0, false)
    }

    fun onIconHidden(item: MainDisplayLatticeElement) {
        val itemToRemove = launchers.firstOrNull {
            it.getLauncherIdentifier() == item.getItemIdentifier()
        }

        if (itemToRemove != null) {
            val position = launchers.indexOfFirst {
                it.getLauncherIdentifier() == item.getItemIdentifier()
            }

            launchers = launchers.toMutableList().apply {
                removeAt(position)
            }

            submitList(launchers.toMutableList())
        }
    }

    fun setupViews() {
        if (activity == null) {
            return
        }

        if (!scrollListenerAdded) {
            scrollListenerAdded = true
            binding.everyAppsGridUi.addOnScrollListener(object : OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // Hiding is expensive, only do it if focused
                    if (binding.huntBarUi.hasFocus() && dy > 0 && binding.everyAppsGridUi.computeVerticalScrollOffset() > 0) {
                        activity?.hideKeyboard()
                    }
                }
            })
        }

        setupDrawerBackground(context.getColor(R.color.launcher_all_app_bg))

        enactSearchPosition()
        enactBottomSearchClearance()
        binding.huntBarUi.beVisibleIf(context.launcherConfig.showSearchBar)
        binding.huntBarUi.requireToolbar().beGone()
        binding.huntBarUi.updateColors()
        binding.huntBarUi.enactDrawerSkin()
        binding.huntBarUi.setupMenu()

        binding.huntBarUi.onSearchTextChangedListener = {
            submitList(launchers)
        }
        binding.huntBarUi.onSearchOpenListener = { presentSearchPanel(true) }
        binding.huntBarUi.onSearchClosedListener = { presentSearchPanel(false) }

        binding.huntBarUi.binding.topToolbarSearch.setOnEditorActionListener { _, actionId, _ ->
            if (binding.huntBarUi.getCurrentQuery().isEmpty()) return@setOnEditorActionListener false
            when (actionId) {
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_GO -> (if (isPaged) pagesAdapter?.firstPageAdapter else getAdapter())?.fireFirstApp() == true
                else -> false
            }
        }
    }

    /**
     * Puts the search bar at the top (the default) or the bottom of the sheet, the way One UI 6.1
     * moved its Finder bar, and re-anchors the grid, the pager, its dots and the placeholder around
     * it. Pure RelativeLayout rules, so the same layout serves both.
     */
    private var appliedSearchAtBottom: Boolean? = null

    private fun enactSearchPosition() {
        val bottom = context.launcherConfig.drawerSearchAtBottom
        // setupViews runs from a global-layout hook; re-anchoring on every pass would request a
        // layout from inside a layout and starve the UI thread. Only a real change touches the rules.
        if (appliedSearchAtBottom == bottom) return
        appliedSearchAtBottom = bottom
        val search = binding.huntBarUi.id
        val dots = binding.everyAppsPageDotsUi.id

        fun RelativeLayout.LayoutParams.anchor(vararg rules: Pair<Int, Int>) {
            for (verb in listOf(RelativeLayout.ABOVE, RelativeLayout.BELOW, RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_BOTTOM)) {
                removeRule(verb)
            }
            rules.forEach { (verb, subject) -> addRule(verb, subject) }
        }

        (binding.huntBarUi.layoutParams as RelativeLayout.LayoutParams).anchor(
            if (bottom) RelativeLayout.ALIGN_PARENT_BOTTOM to RelativeLayout.TRUE else RelativeLayout.ALIGN_PARENT_TOP to RelativeLayout.TRUE
        )
        (binding.everyAppsGridUi.layoutParams as RelativeLayout.LayoutParams).anchor(
            if (bottom) RelativeLayout.ABOVE to search else RelativeLayout.BELOW to search
        )
        (binding.everyAppsPageDotsUi.layoutParams as RelativeLayout.LayoutParams).anchor(
            if (bottom) RelativeLayout.ABOVE to search else RelativeLayout.ALIGN_PARENT_BOTTOM to RelativeLayout.TRUE
        )
        (binding.everyAppsPagerUi.layoutParams as RelativeLayout.LayoutParams).anchor(
            if (bottom) RelativeLayout.ALIGN_PARENT_TOP to RelativeLayout.TRUE else RelativeLayout.BELOW to search,
            RelativeLayout.ABOVE to dots,
        )
        (binding.lvNoResultsPlaceholderUi.layoutParams as RelativeLayout.LayoutParams).anchor(
            if (bottom) RelativeLayout.ALIGN_PARENT_TOP to RelativeLayout.TRUE else RelativeLayout.BELOW to search
        )
        // The search cards always stack from the top of the sheet (One UI's Finder keeps them
        // there with its bar at the bottom); the bar's edge only decides where the panel ends.
        (binding.huntPanelUi.layoutParams as RelativeLayout.LayoutParams).anchor(
            if (bottom) RelativeLayout.ALIGN_PARENT_TOP to RelativeLayout.TRUE else RelativeLayout.BELOW to search,
            if (bottom) RelativeLayout.ABOVE to search else RelativeLayout.ALIGN_PARENT_BOTTOM to RelativeLayout.TRUE,
        )
        binding.huntPanelUi.gravity = Gravity.TOP
        binding.huntBarUi.requestLayout()
    }

    /**
     * A bottom search bar sits on the navigation bar (and the keyboard, once it opens). The grid
     * already carries exactly that clearance as bottom padding from setupEdgeToEdge - the window
     * root consumes the insets, so they cannot be read here - and padding a search menu itself
     * re-dispatches insets without settling. So the bar mirrors the grid's padding as a margin,
     * re-checked on every layout and written only when it differs (as padding: the menu's own
     * layout does not honour a bottom margin).
     */
    private fun enactBottomSearchClearance() {
        if (!clearanceWatchAdded) {
            clearanceWatchAdded = true
            viewTreeObserver.addOnGlobalLayoutListener { alignBottomSearchClearance() }
        }
        alignBottomSearchClearance()
    }

    private var clearanceWatchAdded = false

    private fun alignBottomSearchClearance() {
        val bottom = context.launcherConfig.drawerSearchAtBottom
        val wanted = if (bottom) binding.everyAppsGridUi.paddingBottom else 0
        val bar = binding.huntBarUi
        if (bar.paddingBottom != wanted) {
            bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, wanted)
        }
        // With the bar at the bottom nothing sits above the list, so its first row gets a gap of its
        // own below the status bar. Kept here, on every layout, because inset passes rewrite padding.
        val grid = binding.everyAppsGridUi
        val topGap = if (bottom) resources.getDimensionPixelSize(R.dimen.launcher_drawer_list_top_gap) else 0
        if (grid.paddingTop != topGap) {
            grid.setPadding(grid.paddingLeft, topGap, grid.paddingRight, grid.paddingBottom)
        }
        alignDotsToSearchPill(bottom)
    }

    /**
     * The page dots sit a fixed gap above the search *pill*, not above the search bar: the bar is an
     * app-bar layout with toolbar chrome above the pill, so anchoring to its edge left the dots
     * floating, and the pages above them short by as much. A negative bottom margin lets the dots
     * (and the pager anchored above them) reclaim that chrome. Applied only on a real change.
     */
    private fun alignDotsToSearchPill(bottom: Boolean) {
        val dots = binding.everyAppsPageDotsUi
        val params = dots.layoutParams as? RelativeLayout.LayoutParams ?: return
        // setupEdgeToEdge pads the dots by the navigation-bar inset for the top-bar layout, where
        // they sit on the nav bar. Under a bottom bar that clearance is already the bar's own
        // (alignBottomSearchClearance), so here the dots keep only the layout's 8 dp.
        if (bottom) {
            val own = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.medium_margin)
            if (dots.paddingBottom != own) dots.setPadding(dots.paddingLeft, dots.paddingTop, dots.paddingRight, own)
        }
        val margin = if (bottom) {
            val pill = binding.huntBarUi.binding.toolbarContainer
            var pillTop = 0
            var v: View? = pill
            while (v != null && v !== binding.huntBarUi) { pillTop += v.top; v = v.parent as? View }
            if (v == null || pill.height == 0) return
            val gap = resources.getDimensionPixelSize(R.dimen.launcher_page_dots_search_gap)
            -(pillTop - gap).coerceAtLeast(0)
        } else {
            0
        }
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin
            dots.layoutParams = params
        }
    }

    private fun presentNoResultsPlaceholderIfNeeded() {
        val itemCount = if (isPaged) pagesAdapter?.itemCount else getAdapter()?.itemCount
        binding.lvNoResultsPlaceholderUi.beVisibleIf(itemCount != null && itemCount == 0)
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

    /**
     * Closes the drawer's search if it is open.
     *
     * Called as the drawer is dismissed rather than instead of dismissing it: Back closes the
     * drawer in a single press (see HomeDeck.onBackPressedCompat), and this only makes sure the
     * next open does not come back with the last query still in the bar.
     */
    fun rewindSearch() {
        if (binding.huntBarUi.isSearchOpen) {
            binding.huntBarUi.closeSearch()
        }
    }

    private fun submitList(items: List<CoreHome>) {
        val searchQuery = binding.huntBarUi.getCurrentQuery()
        if (binding.huntBarUi.isSearchOpen) {
            paintSearchPanel(items, searchQuery)
            return
        }
        val filtered = if (searchQuery.isNotEmpty()) {
            items.filter {
                it.title.normalizeString()
                    .contains(searchQuery.normalizeString(), ignoreCase = true)
            }
        } else {
            BackedSquares.interleave(items)
        }

        if (isPaged) {
            pagesAdapter?.lodgePages(chunkPages(filtered), context.launcherConfig.drawerColumnCount, context.launcherConfig.drawerRowCount.coerceAtLeast(2))
            binding.everyAppsPagerUi.setCurrentItem(0, false)
            paintDots(selected = 0)
            presentNoResultsPlaceholderIfNeeded()
            return
        }
        getAdapter()?.submitList(withAdRow(filtered)) {
            presentNoResultsPlaceholderIfNeeded()
        }
    }

    // ---- Search panel -------------------------------------------------------------------------
    //
    // While the search bar is open the grid / pages step aside for two cards, the way One UI's
    // Finder does it: "Suggested apps" (CorePicks over the launcher's own launch log) until
    // something is typed, then "Searched apps" with the matched prefix tinted and, under it, the
    // top match's app shortcuts.

    private var searchAdapter: HomesFeed? = null
    private var suggestions: List<CoreHome>? = null
    private var suggestionsGeneration = 0

    private fun presentSearchPanel(open: Boolean) {
        binding.huntPanelUi.beVisibleIf(open)
        if (open) {
            binding.everyAppsGridUi.beGone()
            binding.everyAppsPagerUi.beGone()
            binding.everyAppsPageDotsUi.beGone()
            binding.lvNoResultsPlaceholderUi.beGone()
            suggestions = null
            fetchSuggestions()
            paintSearchPanel(launchers, binding.huntBarUi.getCurrentQuery())
        } else {
            val paged = isPaged
            binding.everyAppsGridUi.beVisibleIf(!paged)
            binding.everyAppsPagerUi.beVisibleIf(paged)
            binding.everyAppsPageDotsUi.beVisibleIf(paged)
            searchAdapter?.highlight = ""
            submitList(launchers)
        }
    }

    private fun searchGridAdapter(): HomesFeed {
        searchAdapter?.let { return it }
        val grid = binding.huntAppsGridUi
        grid.layoutManager = object : GridLayoutManager(context, SEARCH_COLUMNS) {
            override fun canScrollVertically() = false
        }
        grid.itemAnimator = null
        grid.isNestedScrollingEnabled = false
        // Rows breathe the way the Finder's do; the item layout alone packs them tighter.
        val rowGap = resources.getDimensionPixelSize(R.dimen.launcher_search_card_row_gap)
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                outRect.top = if (position >= SEARCH_COLUMNS) rowGap else 0
            }
        })
        return HomesFeed(activity!!, this, null, onItemClick).also {
            searchAdapter = it
            grid.adapter = it
        }
    }

    /** The launcher's own launch log ranked, off the main thread; the card fills in when it lands. */
    private fun fetchSuggestions() {
        val generation = ++suggestionsGeneration
        val installed = launchers
        val pm = context.packageManager
        ensureBackgroundThread {
            val usage = runCatching { context.appUsageDB.getAll() }.getOrDefault(emptyList())
            val facts = HashMap<String, CorePicks.Facts?>()
            val ranked = CorePicks.rank(installed, usage, { pkg ->
                facts.getOrPut(pkg) {
                    runCatching {
                        val info = pm.getPackageInfo(pkg, 0)
                        CorePicks.Facts(info.firstInstallTime, info.applicationInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED)
                    }.getOrNull()
                }
            }, System.currentTimeMillis())
            activity?.runOnUiThread {
                if (generation != suggestionsGeneration) return@runOnUiThread
                suggestions = ranked
                if (binding.huntBarUi.isSearchOpen && binding.huntBarUi.getCurrentQuery().isEmpty()) {
                    paintSearchPanel(launchers, "")
                }
            }
        }
    }

    private fun paintSearchPanel(items: List<CoreHome>, query: String) {
        val adapter = searchGridAdapter()
        if (query.isEmpty()) {
            // Resolved against the list the drawer holds now: the icon cache may have refreshed
            // (new CoreHome objects, decoded icons) since the ranking was taken.
            val apps = live(suggestions ?: emptyList(), items)
            binding.huntAppsTitleUi.setText(R.string.launcher_suggested_apps)
            adapter.highlight = ""
            adapter.submitList(apps.toMutableList())
            binding.huntAppsCardUi.beVisibleIf(apps.size >= CorePicks.MIN_TO_SHOW)
            binding.huntShortcutsCardUi.beGone()
            binding.lvNoResultsPlaceholderUi.beGone()
            return
        }
        val needle = query.normalizeString()
        val matches = items.filter { it.title.normalizeString().contains(needle, ignoreCase = true) }
            .sortedWith(compareBy<CoreHome> { !it.title.normalizeString().startsWith(needle, ignoreCase = true) }.thenBy { it.title.lowercase() })
        binding.huntAppsTitleUi.setText(R.string.launcher_searched_apps)
        adapter.highlight = query
        adapter.submitList(matches.take(SEARCH_MAX_RESULTS).toMutableList())
        binding.huntAppsCardUi.beVisibleIf(matches.isNotEmpty())
        binding.lvNoResultsPlaceholderUi.beVisibleIf(matches.isEmpty())
        paintShortcuts(matches.firstOrNull())
    }

    /** The top match's app shortcuts (dynamic + manifest), as the Finder lists them under the app's name. */
    private fun paintShortcuts(app: CoreHome?) {
        val card = binding.huntShortcutsCardUi
        val list = binding.huntShortcutsListUi
        list.removeAllViews()
        if (app == null) { card.beGone(); return }
        val launcherApps = context.applicationContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val shortcuts = runCatching {
            if (!launcherApps.hasShortcutHostPermission()) return@runCatching null
            val flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
            launcherApps.getShortcuts(LauncherApps.ShortcutQuery().setQueryFlags(flags).setPackage(app.packageName), Process.myUserHandle())
        }.getOrNull()?.filter { it.isEnabled }?.take(SEARCH_MAX_SHORTCUTS).orEmpty()
        if (shortcuts.isEmpty()) { card.beGone(); return }
        binding.huntShortcutsTitleUi.text = app.title
        val inflater = LayoutInflater.from(context)
        val density = resources.displayMetrics.densityDpi
        shortcuts.forEach { shortcut ->
            val row = HdItemSearchShortcutBinding.inflate(inflater, list, false)
            row.huntShortcutLabelUi.text = shortcut.shortLabel ?: shortcut.longLabel ?: ""
            row.huntShortcutIconUi.setImageDrawable(runCatching { launcherApps.getShortcutIconDrawable(shortcut, density) }.getOrNull())
            row.root.setOnClickListener {
                runCatching {
                    launcherApps.startShortcut(shortcut.`package`, shortcut.id, null, null, Process.myUserHandle())
                }.onFailure { activity?.showErrorToast(it as? Exception ?: Exception(it)) }
                if (activity?.launcherConfig?.shutAppDrawer == true) activity?.shutAppDrawer(delayed = true)
            }
            list.addView(row.root)
        }
        card.beVisible()
    }

    /**
     * Puts the ad row where `launcher.screens.app_drawer.ad_row_position` asks for it — counted in
     * rows of apps above it, so the same console value reads the same at any column count.
     *
     * Inserted into the list rather than concatenated ahead of it, because the position is not
     * fixed; [HomesFeed.AD_SLOT] is the marker the adapter renders as the slot's row.
     *
     * A position past the end lands the row after the last app instead of being dropped: an
     * over-large console value should still show the ad, at the bottom, rather than silently
     * turning the placement off. The list is left alone entirely while it is empty — a lone ad row
     * under "No items found" is not a drawer.
     */
    private fun withAdRow(apps: List<CoreHome>): MutableList<CoreHome> {
        if (apps.isEmpty()) {
            return apps.toMutableList()
        }

        val spanCount = (binding.everyAppsGridUi.layoutManager as MyGridLayoutManager).spanCount
        val row = HomeSpotsProfile.drawerAdRowPosition()
        val index = (row * spanCount).coerceIn(0, apps.size)
        return apps.toMutableList().apply { add(index, HomesFeed.AD_SLOT) }
    }
}
