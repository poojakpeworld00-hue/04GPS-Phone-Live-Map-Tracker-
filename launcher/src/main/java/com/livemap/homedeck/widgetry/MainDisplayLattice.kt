package com.livemap.homedeck.widgetry

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Size
import android.util.SizeF
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.RelativeLayout
import androidx.core.graphics.createBitmap
import java.util.concurrent.Executors
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toRect
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.google.android.material.math.MathUtils
import kotlinx.collections.immutable.toImmutableList
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.helpers.FontHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isSPlus
import com.livemap.homedeck.R
import com.livemap.homedeck.screens.HomeDeck
import com.livemap.homedeck.databinding.HdHomeScreenGridBinding
import com.livemap.homedeck.ext.launcherConfig
import com.livemap.homedeck.ext.getDrawableForPackageName
import com.livemap.homedeck.ext.homeScreenGridItemsDB
import com.livemap.homedeck.support.AutoErasure
import com.livemap.homedeck.support.ITEM_TYPE_FOLDER
import com.livemap.homedeck.support.MAX_DOCK_SLOTS
import com.livemap.homedeck.support.ITEM_TYPE_ICON
import com.livemap.homedeck.support.ITEM_TYPE_SHORTCUT
import com.livemap.homedeck.support.ITEM_TYPE_WIDGET
import com.livemap.homedeck.support.PSEUDO_WIDGET_CLOCK
import com.livemap.homedeck.support.PSEUDO_WIDGET_GOOGLE_SEARCH
import com.livemap.homedeck.support.PSEUDO_WIDGET_SEARCH
import com.livemap.homedeck.support.PSEUDO_WIDGET_TIME
import com.livemap.homedeck.support.WIDGET_HOST_ID
import com.livemap.homedeck.entities.MainDisplayLatticeElement
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

class MainDisplayLattice(context: Context, attrs: AttributeSet, defStyle: Int) :
    RelativeLayout(context, attrs, defStyle) {
    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    private lateinit var binding: HdHomeScreenGridBinding
    private var columnCount = context.launcherConfig.homeColumnCount
    private var dockSlotCount = context.launcherConfig.dockColumnCount

    private var dockCellWidth = 0
    private var dockIconSize = 0
    private var dockIconMargin = 0

    private var pageTransitAlpha = 255

    private val pageTransitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var rowCount = context.launcherConfig.homeRowCount
    private var pageIndicatorsYPos = 0
    private val cells = mutableMapOf<Point, Rect>()
    private var dockCellY = 0
    var cellWidth = 0
    var cellHeight = 0

    private var iconMargin =
        (context.resources.getDimension(R.dimen.launcher_icon_side_margin) * 5 / columnCount).toInt()
    private var labelSideMargin =
        context.resources.getDimension(org.fossify.commons.R.dimen.small_margin).toInt()
    private var roundedCornerRadius =
        context.resources.getDimension(org.fossify.commons.R.dimen.activity_margin)
    private var folderPadding =
        context.resources.getDimension(org.fossify.commons.R.dimen.medium_margin)
    private var pageIndicatorRadius =
        context.resources.getDimension(R.dimen.launcher_page_indicator_dot_radius)
    private var pageIndicatorMargin = context.resources.getDimension(R.dimen.launcher_page_indicator_margin)
    private var pageIndicatorInactiveRadius =
        context.resources.getDimension(R.dimen.launcher_page_indicator_inactive_radius)
    private var pageIndicatorActiveExtra =
        context.resources.getDimension(R.dimen.launcher_page_indicator_active_extra)
    private var pageIndicatorClearance =
        context.resources.getDimension(R.dimen.launcher_page_indicator_clearance)
    private var dockBottomMargin =
        context.resources.getDimensionPixelSize(R.dimen.launcher_dock_bottom_margin)
    private var textPaint: TextPaint
    private var contrastTextPaint: TextPaint
    private var folderTitleTextPaint: TextPaint
    private var dragShadowCirclePaint: Paint
    private var emptyPageIndicatorPaint: Paint
    private var currentPageIndicatorPaint: Paint
    private var folderBackgroundPaint: Paint
    private var folderIconBackgroundPaint: Paint
    private var draggedItem: MainDisplayLatticeElement? = null
    private var resizedWidget: MainDisplayLatticeElement? = null
    private var isFirstDraw = true
    private var iconSize = 0

    private val pager = AnimatingLatticeSwiper(
        getMaxPage = ::getMaxPage,
        redrawGrid = ::redrawGrid,
        getWidth = { width },
        getHandler = { handler },
        getNextPageBound = { right - sideMargins.right - cellWidth / 2 },
        getPrevPageBound = { left + sideMargins.left + cellWidth / 2 },
        pageChangeStarted = {
            widgetViews.forEach { it.rewindTouches() }
            shutFolder()
            accessibilityHelper.invalidateRoot()
        }
    )

    private var currentlyOpenFolder: HomeScreenFolder? = null
    private var draggingLeftFolderAt: Long? = null
    private var draggingEnteredNewFolderAt: Long? = null

    var sideMargins = Rect()

    @Volatile
    private var gridItems: MutableList<MainDisplayLatticeElement> =
        java.util.concurrent.CopyOnWriteArrayList<MainDisplayLatticeElement>()
    private var gridCenters = ArrayList<Point>()
    private var draggedItemCurrentCoords = Pair(-1, -1)
    private var widgetViews = ArrayList<MineCoreAppletHolderView>()

    var onTimeWidgetBottomChanged: ((Int) -> Unit)? = null
    private var reportedTimeWidgetBottom = -1

    var onFirstPageScrolled: ((Float) -> Unit)? = null
    private var reportedFirstPageX = Float.NaN

    private val widgetViewItemIds = java.util.WeakHashMap<MineCoreAppletHolderView, Long>()

    private val bindAttempted = HashSet<Long>()

    private val bindPending = HashSet<Long>()

    val appWidgetHost = MineCoreAppletHolder(context, WIDGET_HOST_ID)
    private val appWidgetManager = AppWidgetManager.getInstance(context)

    private val accessibilityHelper = HomeScreenGridTouchHelper(this)
    var itemClickListener: ((MainDisplayLatticeElement) -> Unit)? = null
    var itemLongClickListener: ((MainDisplayLatticeElement) -> Unit)? = null

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)

        val customTypeface = FontHelper.getTypeface(context)

        val profileLabelSp = context.launcherConfig.labelTextSp
        val labelTextSize = if (profileLabelSp > 0f) {
            profileLabelSp * context.resources.displayMetrics.scaledDensity
        } else {
            context.resources.getDimension(org.fossify.commons.R.dimen.smaller_text_size)
        }
        textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = labelTextSize
            setShadowLayer(2f, 0f, 0f, Color.BLACK)
            typeface = customTypeface
        }

        contrastTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getProperTextColor()
            textSize = labelTextSize
            setShadowLayer(2f, 0f, 0f, context.getProperTextColor().getContrastColor())
            typeface = customTypeface
        }

        folderTitleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getProperTextColor()
            textSize = context.resources.getDimension(org.fossify.commons.R.dimen.medium_text_size)
            typeface = customTypeface
        }

        dragShadowCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.resources.getColor(org.fossify.commons.R.color.hint_white)
            strokeWidth = context.resources.getDimension(org.fossify.commons.R.dimen.small_margin)
            style = Paint.Style.STROKE
        }

        emptyPageIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.resources.getColor(android.R.color.white)
            style = Paint.Style.FILL
        }
        currentPageIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.resources.getColor(android.R.color.white)
            style = Paint.Style.FILL
        }

        folderBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getProperBackgroundColor()
            style = Paint.Style.FILL
        }

        folderIconBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getProperBackgroundColor().adjustAlpha(0.9f)
            style = Paint.Style.FILL
        }

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(Type.systemBars() or Type.displayCutout())
            val gestures = insets.getInsets(Type.systemGestures())
            val navIgnoring = insets.getInsetsIgnoringVisibility(Type.navigationBars())

            val sideMargin = context.resources
                .getDimension(org.fossify.commons.R.dimen.normal_margin).toInt()

            val newLeft   = sideMargin + bars.left
            val newRight  = sideMargin + bars.right
            insetTop = bars.top
            val newTop    = bars.top + topReserve
            val newBottom = max(bars.bottom, max(gestures.bottom, navIgnoring.bottom))
            val marginsChanged = sideMargins.left != newLeft
                    || sideMargins.top != newTop
                    || sideMargins.right != newRight
                    || sideMargins.bottom != newBottom
            if (marginsChanged) {
                sideMargins.set(newLeft, newTop, newRight, newBottom)
                cells.clear()
                gridCenters.clear()
                isFirstDraw = true
                redrawGrid()
            }

            insets
        }

        obtainGridItems()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (accessibilityHelper.dispatchHoverEvent(event)) return true
        return super.dispatchHoverEvent(event)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = HdHomeScreenGridBinding.bind(this)
    }

    fun obtainGridItems() {
        ensureBackgroundThread {
            val started = android.os.SystemClock.elapsedRealtime()
            val providers = appWidgetManager.installedProviders

            val loaded = gridItems
                .filter { it.type == ITEM_TYPE_ICON && it.drawable != null }
                .associateBy { Pair(it.id, it.packageName) }
            val items = context.homeScreenGridItemsDB.getAllItems() as ArrayList<MainDisplayLatticeElement>

            if (context.launcherConfig.selfIconHidden) {
                val own = context.packageName
                items.filter { AutoErasure.isOurRow(it, own) }.forEach { stray ->
                    items.remove(stray)
                    stray.id?.let { id -> runCatching { context.homeScreenGridItemsDB.eraseById(id) } }
                }
            }
            items.forEach { item ->
                if (item.type == ITEM_TYPE_ICON) {
                    loaded[Pair(item.id, item.packageName)]?.let { old ->
                        item.drawable = old.drawable
                        item.iconRaster = old.iconRaster
                        item.iconRasterSource = old.iconRasterSource
                    }
                } else if (item.type == ITEM_TYPE_FOLDER) {
                    item.drawable = item.toFolder().generateDrawable()
                } else if (item.type == ITEM_TYPE_SHORTCUT) {
                    if (item.icon != null) {
                        item.drawable = item.icon?.toDrawable(context.resources)
                    } else {
                        ensureBackgroundThread {
                            context.homeScreenGridItemsDB.eraseById(item.id!!)
                        }
                    }
                }

                item.providerInfo =
                    providers.firstOrNull { it.provider.className == item.className }
            }

            val missing = items.filter { it.type == ITEM_TYPE_ICON && it.drawable == null }
            val page = pager.getCurrentPage()
            val (now, later) = missing.partition { it.docked || it.parentId != null || it.page == page }
            decodeIcons(now)
            gridItems = java.util.concurrent.CopyOnWriteArrayList(items)
            redrawGrid()
            post { dropOrphanWidgetViews() }
            val shown = android.os.SystemClock.elapsedRealtime() - started
            if (later.isNotEmpty()) {
                decodeIcons(later.sortedBy { kotlin.math.abs(it.page - page) })
                redrawGrid()
            }
            timber.log.Timber.d(
                "MainDisplayLattice: fetched ${items.size} items - page in view drawn in $shown ms (${now.size} icons), " +
                    "all in ${android.os.SystemClock.elapsedRealtime() - started} ms (${later.size} more, " +
                    "${items.count { it.type == ITEM_TYPE_ICON } - missing.size} kept)"
            )
        }
    }

    private fun decodeIcons(icons: List<MainDisplayLatticeElement>) {
        if (icons.isEmpty()) return
        val pool = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        )
        try {
            icons.map { item ->
                pool.submit { item.drawable = context.getDrawableForPackageName(item.packageName) }
            }.forEach { future -> runCatching { future.get() } }
        } finally {
            pool.shutdown()
        }
    }

    private var insetTop = 0

    var topReserve = 0
        set(value) {
            if (field == value) return
            field = value
            val newTop = insetTop + value
            if (sideMargins.top != newTop) {
                sideMargins.top = newTop
                cells.clear()
                gridCenters.clear()
                isFirstDraw = true
                redrawGrid()
            }
        }

    fun resizeGrid(newRowCount: Int, newColumnCount: Int) {
        if (columnCount != newColumnCount || rowCount != newRowCount) {
            rowCount = newRowCount
            columnCount = newColumnCount
            cells.clear()
            gridCenters.clear()
            iconMargin =
                (context.resources.getDimension(R.dimen.launcher_icon_side_margin) * 5 / columnCount).toInt()
            isFirstDraw = true

            gridItems
                .filter { it.type == ITEM_TYPE_WIDGET && it.pseudoWidgetLayout() == null }
                .forEach { appWidgetHost.deleteAppWidgetId(it.widgetId) }
            widgetViews.forEach { removeView(it) }
            widgetViews.clear()
            redrawGrid()
        }
    }

    fun updateColors() {
        folderTitleTextPaint.color = context.getProperTextColor()
        contrastTextPaint.color = context.getProperTextColor()
        contrastTextPaint.setShadowLayer(
            2f, 0f, 0f, context.getProperTextColor().getContrastColor()
        )
        folderBackgroundPaint.color = context.getProperBackgroundColor()
    }

    fun dropAppIcon(item: MainDisplayLatticeElement) {
        ensureBackgroundThread {
            dropItemFromHomeScreen(item)
            post {
                removeView(widgetViews.firstOrNull { it.tag == item.widgetId })
            }

            gridItems.removeIf { it.id == item.id }
            if (pager.isOutsideOfPageRange()) {
                post {
                    prevPage()
                }
            }
            redrawGrid()
        }
    }

    private fun dropOrphanWidgetViews() {
        val live = gridItems.mapNotNullTo(HashSet()) { it.id }
        val orphans = orphanWidgetViews(widgetViews.associateWith { widgetViewItemIds[it] }, live)
        if (orphans.isEmpty()) return
        orphans.forEach { removeView(it) }
        widgetViews.removeAll(orphans.toSet())
        val detail = orphans.joinToString { "${it.appWidgetInfo?.provider?.flattenToShortString() ?: "pseudo"}#${widgetViewItemIds[it]}" }
        timber.log.Timber.w("MainDisplayLattice: dropped %d widget view(s) whose row is gone: %s", orphans.size, detail)
        runCatching {
            com.livemap.homedeck.contract.HomeBook.bridge.onNonFatal(IllegalStateException("Orphan widget view dropped: $detail"))
        }
    }

    fun dropPlacedSearchBars() {
        post {
            gridItems
                .filter { it.className == PSEUDO_WIDGET_SEARCH }
                .forEach { item ->
                    removeView(widgetViews.firstOrNull { it.tag == item.widgetId })
                    widgetViews.removeIf { it.tag == item.widgetId }
                }

            gridItems.removeIf { it.className == PSEUDO_WIDGET_SEARCH }
            redrawGrid()
        }
    }

    fun dropWidget(item: MainDisplayLatticeElement) {
        ensureBackgroundThread {
            dropItemFromHomeScreen(item)
            post {
                removeView(widgetViews.firstOrNull { it.tag == item.widgetId })
                widgetViews.removeIf { it.tag == item.widgetId }
            }
            gridItems.removeIf { it.id == item.id }
        }
    }

    private fun dropItemFromHomeScreen(item: MainDisplayLatticeElement) {
        ensureBackgroundThread {
            if (item.id != null) {
                context.homeScreenGridItemsDB.eraseById(item.id!!)
                if (item.parentId != null) {
                    gridItems
                        .filter {
                            it.parentId == item.parentId && it.left > item.left && it.id != item.id
                        }
                        .forEach {
                            it.left -= 1
                        }

                    context.homeScreenGridItemsDB.shiftFolderItems(
                        folderId = item.parentId!!,
                        shiftFrom = item.left,
                        shiftBy = -1,
                        excludingId = item.id
                    )
                }
            }

            if (item.type == ITEM_TYPE_WIDGET) {
                appWidgetHost.deleteAppWidgetId(item.widgetId)
            }

            if (
                item.page != 0 && gridItems
                    .none { it.page == item.page && it.id != item.id && it.parentId == null }
            ) {
                erasePage(item.page)
            }
        }
    }

    fun itemDraggingStarted(draggedGridItem: MainDisplayLatticeElement) {
        draggedItem = draggedGridItem

        if (draggedGridItem.type == ITEM_TYPE_WIDGET) {
            shutFolder()
        }

        if (draggedItem!!.drawable == null) {
            if (draggedItem?.type == ITEM_TYPE_FOLDER) {
                draggedItem!!.drawable = draggedGridItem.toFolder().generateDrawable()
            } else {
                draggedItem!!.drawable =
                    context.getDrawableForPackageName(draggedGridItem.packageName)
            }
        }

        redrawGrid()
    }

    fun draggedItemMoved(x: Int, y: Int) {
        if (draggedItem == null) {
            return
        }

        currentlyOpenFolder?.also { folder ->
            if (folder.getDrawingRect().contains(x.toFloat(), y.toFloat())) {
                draggingLeftFolderAt = null
            } else {
                draggingLeftFolderAt.also {
                    if (it == null) {
                        draggingLeftFolderAt = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - it > FOLDER_CLOSE_HOLD_THRESHOLD) {
                        shutFolder()
                    }
                }
            }
        }

        if (
            draggedItemCurrentCoords.first == -1
            && draggedItemCurrentCoords.second == -1 && draggedItem != null
        ) {
            if (draggedItem!!.type == ITEM_TYPE_WIDGET) {
                val draggedWidgetView = widgetViews.firstOrNull { it.tag == draggedItem?.widgetId }
                if (draggedWidgetView != null) {
                    draggedWidgetView.buildDrawingCache()
                    draggedItem!!.drawable = Bitmap.createBitmap(draggedWidgetView.drawingCache)
                        .toDrawable(context.resources)
                    draggedWidgetView.beGone()
                }
            }
        }

        draggedItemCurrentCoords = Pair(x, y)

        if (draggedItem?.type != ITEM_TYPE_FOLDER && draggedItem?.type != ITEM_TYPE_WIDGET) {
            val center = gridCenters.minBy {
                abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
            }
            val coveredCell = getClosestGridCells(center)
            if (coveredCell != null) {
                val coveredFolder = gridItems.firstOrNull {
                    it.type == ITEM_TYPE_FOLDER
                            && it.left == coveredCell.x && it.top == coveredCell.y
                }

                if (
                    coveredFolder != null
                    && coveredFolder.id != draggedItem?.id
                    && currentlyOpenFolder == null
                ) {
                    draggingEnteredNewFolderAt.also {
                        if (it == null) {
                            draggingEnteredNewFolderAt = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - it > FOLDER_OPEN_HOLD_THRESHOLD) {
                            if (
                                coveredFolder.toFolder().getItems()
                                    .count() >= MainDisplayLatticeElement.FOLDER_MAX_CAPACITY
                                && draggedItem?.parentId != coveredFolder.id
                            ) {
                                performHapticFeedback()
                                draggingEnteredNewFolderAt = null
                            } else {
                                enterFolder(coveredFolder)
                            }
                        }
                    }
                } else {
                    draggingEnteredNewFolderAt = null
                }
            } else {
                draggingEnteredNewFolderAt = null
            }
        }

        pager.processItemMovement(x, y)
        redrawGrid()
    }

    fun itemDraggingStopped() {
        widgetViews.forEach {
            it.hasLongPressed = false
        }

        if (draggedItem == null) {
            return
        }

        pager.itemMovementStopped()
        when (draggedItem!!.type) {
            ITEM_TYPE_FOLDER -> shiftItem()
            ITEM_TYPE_ICON, ITEM_TYPE_SHORTCUT -> appendAppIconOrShortcut()
            ITEM_TYPE_WIDGET -> appendWidget()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun widgetLongPressed(item: MainDisplayLatticeElement) {
        resizedWidget = item
        redrawGrid()

        val widgetView = widgetViews.firstOrNull { it.tag == resizedWidget!!.widgetId }
        binding.lvResizeFrameUi.beGone()
        if (widgetView != null) {
            val viewX = widgetView.x.toInt()
            val viewY = widgetView.y.toInt()
            val frameRect = Rect(viewX, viewY, viewX + widgetView.width, viewY + widgetView.height)
            val otherGridItems = gridItems.filterVisibleOnCurrentPageOnly()
                .filter { !it.outOfBounds() }
                .filter { it.widgetId != item.widgetId }
                .toMutableList() as ArrayList<MainDisplayLatticeElement>
            binding.lvResizeFrameUi.reviseFrameCoords(
                coords = frameRect,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                sideMargins = sideMargins,
                gridItem = item,
                allGridItems = otherGridItems
            )
            binding.lvResizeFrameUi.beVisible()
            binding.lvResizeFrameUi.z = 1f
            binding.lvResizeFrameUi.onClickListener = {
                concealResizeLines()
            }

            binding.lvResizeFrameUi.onResizeListener = { cellsRect ->
                item.left = cellsRect.left
                item.top = cellsRect.top
                item.right = cellsRect.right
                item.bottom = if (cellsRect.bottom > rowCount - 2) {
                    rowCount - 2
                } else {
                    cellsRect.bottom
                }
                reviseWidgetPositionAndSize(widgetView, item)

                val widgetTargetCells = ArrayList<Pair<Int, Int>>()
                for (xCell in item.left..item.right) {
                    for (yCell in item.top..item.bottom) {
                        widgetTargetCells.add(Pair(xCell, yCell))
                    }
                }

                gridItems.filterVisibleOnCurrentPageOnly().filter { it.id != item.id }
                    .forEach { gridItem ->
                        for (xCell in gridItem.left..gridItem.right) {
                            for (
                            yCell in gridItem.getDockAdjustedTop(rowCount)
                                .rangeTo(gridItem.getDockAdjustedBottom(rowCount))
                            ) {
                                val cell = Pair(xCell, yCell)
                                val isAnyCellOccupied = widgetTargetCells.contains(cell)
                                if (isAnyCellOccupied) {
                                    if (
                                        gridItem.type == ITEM_TYPE_WIDGET && gridItem.outOfBounds()
                                    ) {
                                        dropWidget(gridItem)
                                    }
                                }
                            }
                        }
                    }

                ensureBackgroundThread {
                    context.homeScreenGridItemsDB.reviseItemPosition(
                        left = item.left,
                        top = item.top,
                        right = item.right,
                        bottom = item.bottom,
                        page = item.page,
                        docked = false,
                        parentId = null,
                        id = item.id!!
                    )
                }
            }

            widgetView.ignoreTouches = true
            widgetView.setOnTouchListener { v, event ->
                binding.lvResizeFrameUi.onTouchEvent(event)
                return@setOnTouchListener true
            }
        }
    }

    fun concealResizeLines() {
        if (resizedWidget == null) {
            return
        }

        binding.lvResizeFrameUi.beGone()
        widgetViews.firstOrNull { it.tag == resizedWidget!!.widgetId }?.apply {
            ignoreTouches = false
            setOnTouchListener(null)
        }
        resizedWidget = null
    }

    private fun shiftItem() {
        val draggedHomeGridItem = gridItems.firstOrNull { it.id == draggedItem?.id }
        val center = gridCenters.minBy {
            abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
        }

        var redrawIcons = false
        val gridCells = getClosestGridCells(center)
        if (gridCells != null) {
            val xIndex = gridCells.x
            val yIndex = gridCells.y

            var isDroppingPositionValid = true
            val wantedCell = Pair(xIndex, yIndex)

            if (draggedHomeGridItem?.type == ITEM_TYPE_FOLDER && yIndex == rowCount - 1) {
                isDroppingPositionValid = false
            } else {
                gridItems.filterVisibleOnCurrentPageOnly().forEach { item ->
                    for (xCell in item.left..item.right) {
                        for (
                        yCell in item.getDockAdjustedTop(rowCount)
                            .rangeTo(item.getDockAdjustedBottom(rowCount))
                        ) {
                            val cell = Pair(xCell, yCell)
                            val isAnyCellOccupied = wantedCell == cell
                            if (isAnyCellOccupied) {
                                if (item.type == ITEM_TYPE_WIDGET && item.outOfBounds()) {
                                    dropWidget(item)
                                } else {
                                    isDroppingPositionValid = false
                                }
                                return@forEach
                            }
                        }
                    }
                }
            }

            if (isDroppingPositionValid) {
                draggedHomeGridItem?.apply {
                    val oldPage = page
                    left = xIndex
                    top = yIndex
                    right = xIndex
                    bottom = yIndex
                    page = pager.getCurrentPage()
                    docked = yIndex == rowCount - 1

                    ensureBackgroundThread {
                        context.homeScreenGridItemsDB.reviseItemPosition(
                            left = left,
                            top = top,
                            right = right,
                            bottom = bottom,
                            page = page,
                            docked = docked,
                            parentId = parentId,
                            id = id!!
                        )

                        if (page != oldPage && oldPage != 0) {
                            if (gridItems.none { it.page == oldPage && it.parentId == null }) {
                                erasePage(oldPage)
                            }
                        }
                    }
                }
                redrawIcons = true
            }
        }

        draggedItem = null
        draggedItemCurrentCoords = Pair(-1, -1)
        if (redrawIcons) {
            redrawGrid()
        }
    }

    private fun appendAppIconOrShortcut() {
        var isDroppingPositionValid = false
        var potentialParent: MainDisplayLatticeElement? = null
        var xIndex: Int? = null
        var yIndex: Int? = null
        var redrawIcons = false

        val folder = currentlyOpenFolder
        if (folder != null && folder.getItemsDrawingRect().contains(
                (draggedItemCurrentCoords.first).toFloat(),
                (draggedItemCurrentCoords.second).toFloat()
            )
        ) {
            val centers = folder.getItemsGridCenters()
            if (centers.isNotEmpty()) {
                val center = centers.minByOrNull {
                    abs(it.second - draggedItemCurrentCoords.first + sideMargins.left)
                        .plus(abs(it.third - draggedItemCurrentCoords.second + sideMargins.top))
                }
                xIndex = center?.first ?: 0
            } else {
                xIndex = 0
            }

            isDroppingPositionValid = true
            potentialParent = folder.item
            yIndex = 0
            redrawIcons = true
        } else {
            val center = gridCenters.minBy {
                abs(it.x - draggedItemCurrentCoords.first + sideMargins.left)
                    .plus(abs(it.y - draggedItemCurrentCoords.second + sideMargins.top))
            }

            val gridCells = getClosestGridCells(center)
            if (gridCells != null) {
                xIndex = gridCells.x
                yIndex = gridCells.y

                isDroppingPositionValid = true
                val wantedCell = Pair(xIndex, yIndex)
                gridItems.filterVisibleOnCurrentPageOnly().filter { it.id != draggedItem?.id }
                    .forEach { item ->
                        for (xCell in item.left..item.right) {
                            for (
                            yCell in item.getDockAdjustedTop(rowCount)
                                .rangeTo(item.getDockAdjustedBottom(rowCount))
                            ) {
                                val cell = Pair(xCell, yCell)
                                val isAnyCellOccupied = wantedCell == cell
                                if (isAnyCellOccupied) {
                                    if (item.type != ITEM_TYPE_WIDGET && !item.docked) {
                                        potentialParent = item
                                    } else {
                                        if (item.type == ITEM_TYPE_WIDGET && item.outOfBounds()) {
                                            dropWidget(item)
                                        } else {
                                            isDroppingPositionValid = false
                                        }
                                    }
                                    return@forEach
                                }
                            }
                        }
                    }
            }
        }

        if (isDroppingPositionValid) {
            val draggedHomeGridItem = gridItems.firstOrNull { it.id == draggedItem?.id }

            if (potentialParent != null) {
                if (potentialParent.type == ITEM_TYPE_FOLDER) {
                    appendAppIconOrShortcut(
                        draggedHomeGridItem,
                        xIndex!!,
                        yIndex!!,
                        potentialParent.id,
                        toFolderEnd = potentialParent != currentlyOpenFolder?.item
                    )
                } else {
                    val parentItem = potentialParent.copy(
                        type = ITEM_TYPE_FOLDER,
                        id = null,
                        title = resources.getString(org.fossify.commons.R.string.folder)
                    )
                    ensureBackgroundThread {
                        val newId = context.homeScreenGridItemsDB.insert(parentItem)
                        parentItem.id = newId
                        potentialParent.apply {
                            parentId = newId
                            left = 0
                            context.homeScreenGridItemsDB.reviseItemPosition(
                                left = left,
                                top = top,
                                right = right,
                                bottom = bottom,
                                page = page,
                                docked = docked,
                                parentId = newId,
                                id = id!!
                            )
                        }
                        (context as? HomeDeck)?.runOnUiThread {
                            gridItems.add(parentItem)
                            appendAppIconOrShortcut(draggedHomeGridItem, xIndex!!, yIndex!!, newId)
                        }
                    }
                }
                return
            } else {
                appendAppIconOrShortcut(draggedHomeGridItem, xIndex!!, yIndex!!)
                return
            }
        } else {
            performHapticFeedback()
            redrawIcons = true
        }

        draggedItem = null
        draggedItemCurrentCoords = Pair(-1, -1)
        if (redrawIcons) {
            redrawGrid()
        }
    }

    private fun appendAppIconOrShortcut(
        draggedHomeGridItem: MainDisplayLatticeElement?,
        xIndex: Int,
        yIndex: Int,
        newParentId: Long? = null,
        toFolderEnd: Boolean = true,
    ) {
        if (newParentId != null && newParentId != draggedHomeGridItem?.parentId) {
            gridItems.firstOrNull { it.id == newParentId }?.also {
                if (it.toFolder().getItems().count() >= MainDisplayLatticeElement.FOLDER_MAX_CAPACITY) {
                    performHapticFeedback()
                    draggedItem = null
                    draggedItemCurrentCoords = Pair(-1, -1)
                    redrawGrid()
                    return
                }
            }
        }

        val finalXIndex = if (newParentId != null) {
            if (toFolderEnd) {
                gridItems
                    .firstOrNull { it.id == newParentId }
                    ?.toFolder()
                    ?.getItems()
                    ?.maxOfOrNull { it.left + 1 } ?: 0
            } else {
                min(
                    a = xIndex,
                    b = gridItems
                        .firstOrNull { it.id == newParentId }
                        ?.toFolder()
                        ?.getItems()
                        ?.maxOfOrNull {
                            if (draggedHomeGridItem?.parentId == newParentId) {
                                it.left
                            } else {
                                it.left + 1
                            }
                        } ?: 0)
            }
        } else {
            xIndex
        }

        if (draggedHomeGridItem != null) {
            draggedHomeGridItem.apply {
                val oldParentId = parentId
                val oldLeft = left
                val oldPage = page
                left = finalXIndex
                top = yIndex
                right = finalXIndex
                bottom = yIndex
                page = pager.getCurrentPage()
                docked = yIndex == rowCount - 1
                parentId = newParentId

                val oldParent = gridItems.firstOrNull { it.id == oldParentId }
                val deleteOldParent = if (oldParent?.toFolder()?.getItems()?.isEmpty() == true) {
                    gridItems.remove(oldParent)
                    true
                } else {
                    false
                }

                ensureBackgroundThread {
                    context.homeScreenGridItemsDB.reviseItemPosition(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        page = page,
                        docked = docked,
                        parentId = newParentId,
                        id = id!!
                    )
                    if (deleteOldParent && oldParentId != null) {
                        context.homeScreenGridItemsDB.eraseById(oldParentId)
                    } else if (
                        oldParentId != null
                        && gridItems.none { it.parentId == oldParentId && it.left == oldLeft }
                    ) {
                        gridItems
                            .filter {
                                it.parentId == oldParentId && it.left > oldLeft && it.id != id
                            }
                            .forEach {
                                it.left -= 1
                            }
                        context.homeScreenGridItemsDB.shiftFolderItems(oldParentId, oldLeft, -1, id)
                    }

                    if (
                        newParentId != null
                        && gridItems.any { it.parentId == newParentId && it.left == left }
                        && (newParentId != oldParentId || left != oldLeft)
                    ) {
                        gridItems
                            .filter { it.parentId == newParentId && it.left >= left && it.id != id }
                            .forEach {
                                it.left += 1
                            }

                        context.homeScreenGridItemsDB.shiftFolderItems(
                            folderId = newParentId,
                            shiftFrom = left - 1,
                            shiftBy = +1,
                            excludingId = id
                        )
                    }

                    if (page != oldPage && oldPage != 0) {
                        if (gridItems.none { it.page == oldPage && it.parentId == null }) {
                            erasePage(oldPage)
                        }
                    }
                }
            }
        } else if (draggedItem != null) {
            val newHomeScreenGridItem = MainDisplayLatticeElement(
                id = null,
                left = finalXIndex,
                top = yIndex,
                right = finalXIndex,
                bottom = yIndex,
                page = pager.getCurrentPage(),
                packageName = draggedItem!!.packageName,
                activityName = draggedItem!!.activityName,
                title = draggedItem!!.title,
                type = draggedItem!!.type,
                className = "",
                widgetId = -1,
                shortcutId = "",
                icon = draggedItem!!.icon,
                docked = yIndex == rowCount - 1,
                parentId = newParentId,
                drawable = draggedItem!!.drawable,
                providerInfo = draggedItem!!.providerInfo,
                activityInfo = draggedItem!!.activityInfo
            )

            fun finalizeFolderOrder(newItem: MainDisplayLatticeElement) {
                if (
                    newParentId != null
                    && gridItems.any { it.parentId == newParentId && it.left == newItem.left }
                ) {
                    gridItems
                        .filter {
                            it.parentId == newParentId
                                    && it.left >= newItem.left && it.id != newItem.id
                        }
                        .forEach {
                            it.left += 1
                        }

                    context.homeScreenGridItemsDB.shiftFolderItems(
                        folderId = newParentId,
                        shiftFrom = newItem.left - 1,
                        shiftBy = +1,
                        excludingId = newItem.id
                    )
                }
            }

            if (newHomeScreenGridItem.type == ITEM_TYPE_ICON) {
                ensureBackgroundThread {
                    stashAndShowGridItem(newHomeScreenGridItem)
                    finalizeFolderOrder(newHomeScreenGridItem)
                }
            } else if (newHomeScreenGridItem.type == ITEM_TYPE_SHORTCUT) {
                (context as? HomeDeck)?.processShorcutCreation(newHomeScreenGridItem.activityInfo!!) { shortcutId, label, icon ->
                    ensureBackgroundThread {
                        newHomeScreenGridItem.shortcutId = shortcutId
                        newHomeScreenGridItem.title = label
                        newHomeScreenGridItem.icon = icon.toBitmap()
                        newHomeScreenGridItem.drawable = icon
                        stashAndShowGridItem(newHomeScreenGridItem)
                        finalizeFolderOrder(newHomeScreenGridItem)
                    }
                }
            }
        }

        draggedItem = null
        draggedItemCurrentCoords = Pair(-1, -1)
        redrawGrid()
    }

    fun stashAndShowGridItem(item: MainDisplayLatticeElement) {
        val newId = context.homeScreenGridItemsDB.insert(item)
        item.id = newId
        gridItems.add(item)
        redrawGrid()
    }

    private fun appendWidget() {
        val center = gridCenters.minBy {
            abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
        }

        val gridCells = getClosestGridCells(center)
        if (gridCells != null) {
            val widgetRect = getWidgetOccupiedRect(gridCells)
            val widgetTargetCells = ArrayList<Pair<Int, Int>>()
            for (xCell in widgetRect.left..widgetRect.right) {
                for (yCell in widgetRect.top..widgetRect.bottom) {
                    widgetTargetCells.add(Pair(xCell, yCell))
                }
            }

            var areAllCellsEmpty = true
            gridItems.filterVisibleOnCurrentPageOnly().filter { it.id != draggedItem?.id }
                .forEach { item ->
                    for (xCell in item.left..item.right) {
                        for (yCell in item.getDockAdjustedTop(rowCount)
                            .rangeTo(item.getDockAdjustedBottom(rowCount))
                        ) {
                            val cell = Pair(xCell, yCell)
                            val isAnyCellOccupied = widgetTargetCells.contains(cell)
                            if (isAnyCellOccupied) {
                                if (item.type == ITEM_TYPE_WIDGET && item.outOfBounds()) {
                                    dropWidget(item)
                                } else {
                                    areAllCellsEmpty = false
                                    return@forEach
                                }
                            }
                        }
                    }
                }

            if (areAllCellsEmpty) {
                val widgetItem = draggedItem!!.copy()
                val oldPage = widgetItem.page
                widgetItem.apply {
                    left = widgetRect.left
                    top = widgetRect.top
                    right = widgetRect.right
                    bottom = widgetRect.bottom
                    page = pager.getCurrentPage()
                }

                ensureBackgroundThread {
                    if (widgetItem.id == null) {
                        val itemId = context.homeScreenGridItemsDB.insert(widgetItem)
                        widgetItem.id = itemId
                        post {
                            wireWidget(widgetItem)
                        }
                    } else {
                        context.homeScreenGridItemsDB.reviseItemPosition(
                            widgetItem.left,
                            widgetItem.top,
                            widgetItem.right,
                            widgetItem.bottom,
                            pager.getCurrentPage(),
                            false,
                            null,
                            widgetItem.id!!
                        )
                        val widgetView = widgetViews.firstOrNull { it.tag == widgetItem.widgetId }
                        if (widgetView != null && !widgetItem.outOfBounds()) {
                            post {
                                val widgetPos = computeWidgetPos(widgetItem.getTopLeft(rowCount))
                                widgetView.x = widgetPos.x.toFloat()
                                widgetView.y = widgetPos.y.toFloat()
                                widgetView.beVisible()
                            }
                        }

                        gridItems.firstOrNull { it.id == widgetItem.id }?.apply {
                            left = widgetItem.left
                            right = widgetItem.right
                            top = widgetItem.top
                            bottom = widgetItem.bottom
                            page = pager.getCurrentPage()
                        }

                        if (widgetItem.page != oldPage && oldPage != 0) {
                            if (gridItems.none { it.page == oldPage && it.parentId == null }) {
                                erasePage(oldPage)
                            }
                        }
                    }
                }
            } else {
                performHapticFeedback()
                widgetViews.firstOrNull { it.tag == draggedItem?.widgetId }?.apply {
                    post {
                        beVisible()
                    }
                }
            }
        }

        draggedItem = null
        draggedItemCurrentCoords = Pair(-1, -1)
        redrawGrid()
    }

    private fun MainDisplayLatticeElement.pseudoWidgetLayout() = when (className) {
        PSEUDO_WIDGET_CLOCK -> R.layout.hd_pseudo_widget_digital_clock
        PSEUDO_WIDGET_SEARCH -> R.layout.hd_pseudo_widget_search_bar
        PSEUDO_WIDGET_TIME -> R.layout.hd_pseudo_widget_time
        PSEUDO_WIDGET_GOOGLE_SEARCH -> R.layout.hd_pseudo_widget_google_search
        else -> null
    }

    private fun enterClock() {
        val intent = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { timber.log.Timber.w(it, "MainDisplayLattice: no clock app") }
    }

    private fun enterGoogleSearch(voice: Boolean) {
        val google = "com.google.android.googlequicksearchbox"
        val attempts = listOf(
            Intent(if (voice) android.speech.RecognizerIntent.ACTION_WEB_SEARCH else "android.search.action.GLOBAL_SEARCH").setPackage(google),
            Intent(Intent.ACTION_WEB_SEARCH),
        )
        val opened = attempts.any { intent ->
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
        if (!opened) (context as? HomeDeck)?.onFlingLeft()
    }

    private fun wireWidget(item: MainDisplayLatticeElement) {
        if (item.outOfBounds()) {
            return
        }

        if (item.pseudoWidgetLayout() != null) {
            item.widgetId = appWidgetHost.allocateAppWidgetId()
            ensureBackgroundThread {
                context.homeScreenGridItemsDB.reviseWidgetId(item.widgetId, item.id!!)
            }

            siteAppWidget(null, item)
            return
        }

        val activity = context as HomeDeck
        val appWidgetProviderInfo = item.providerInfo
            ?: appWidgetManager!!.installedProviders
                .firstOrNull { it.provider.className == item.className }
        if (appWidgetProviderInfo != null) {
            item.widgetId = appWidgetHost.allocateAppWidgetId()
            ensureBackgroundThread {
                context.homeScreenGridItemsDB.reviseWidgetId(item.widgetId, item.id!!)
            }

            item.id?.let { bindPending.add(it) }
            activity.processWidgetBinding(
                appWidgetManager = appWidgetManager,
                appWidgetId = item.widgetId,
                appWidgetInfo = appWidgetProviderInfo
            ) { canBind ->
                item.id?.let { bindPending.remove(it) }
                if (canBind) {
                    if (appWidgetProviderInfo.configure != null &&
                        !com.livemap.homedeck.preset.MainAppletSower.configurationOptional(appWidgetProviderInfo)
                    ) {
                        item.id?.let { bindPending.add(it) }
                        activity.processWidgetConfigureScreen(
                            appWidgetHost = appWidgetHost,
                            appWidgetId = item.widgetId
                        ) { success ->
                            item.id?.let { bindPending.remove(it) }
                            if (success) {
                                siteAppWidget(appWidgetProviderInfo, item)
                            } else {
                                dropItemFromHomeScreen(item)
                            }
                        }
                    } else {
                        siteAppWidget(appWidgetProviderInfo, item)
                    }
                } else {
                    dropItemFromHomeScreen(item)
                }

                if (pager.isOutsideOfPageRange()) {
                    prevPage(redraw = true)
                }
            }
        }
    }

    private fun siteAppWidget(
        appWidgetProviderInfo: AppWidgetProviderInfo?,
        item: MainDisplayLatticeElement,
    ) {
        widgetViews.filter { (item.id != null && widgetViewItemIds[it] == item.id) || it.tag == item.widgetId }
            .forEach { stale ->
                removeView(stale)
                widgetViews.remove(stale)
                widgetViewItemIds.remove(stale)
            }
        val pseudoWidgetLayout = item.pseudoWidgetLayout()

        val widgetView = if (pseudoWidgetLayout != null) {
            MineCoreAppletHolderView((context as HomeDeck).baseContext).apply {
                View.inflate(context, pseudoWidgetLayout, this)
                when (item.className) {
                    PSEUDO_WIDGET_SEARCH -> setOnClickListener { (this@MainDisplayLattice.context as HomeDeck).onFlingLeft() }
                    PSEUDO_WIDGET_TIME -> setOnClickListener { enterClock() }
                    PSEUDO_WIDGET_GOOGLE_SEARCH -> {
                        setOnClickListener { enterGoogleSearch(voice = false) }
                        findViewById<View>(R.id.lv_google_bar_micUi)?.setOnClickListener { enterGoogleSearch(voice = true) }
                    }
                }
            }
        } else {
            appWidgetHost.createView(
                (context as HomeDeck).baseContext,
                item.widgetId,
                appWidgetProviderInfo
            ) as MineCoreAppletHolderView
        }

        widgetView.tag = item.widgetId
        widgetView.itemId = item.id

        val stale = widgetViews.filter { it.tag == item.widgetId || (item.id != null && it.itemId == item.id) }
        stale.forEach { removeView(it) }
        widgetViews.removeAll(stale.toSet())
        widgetView.longPressListener = { x, y ->
            val activity = context as? HomeDeck
            if (activity?.isAllAppsFragmentExpanded() == false) {
                activity.presentHomeIconMenu(x, widgetView.y, item, false)
                performHapticFeedback()
            }
        }

        widgetView.onIgnoreInterceptedListener = {
            concealResizeLines()
        }

        val widgetSizeUi = reviseWidgetPositionAndSize(widgetView, item)
        addView(widgetView, widgetSizeUi.width, widgetSizeUi.height)
        widgetViews.add(widgetView)
        item.id?.let { widgetViewItemIds[widgetView] = it }

        item.drawable = null

        gridItems.removeIf { it.id == item.id }
        gridItems.add(item)
        (context as HomeDeck).wipeWidgetsSearch()
    }

    private fun reviseWidgetPositionAndSize(
        widgetView: AppWidgetHostView,
        item: MainDisplayLatticeElement,
    ): Size {
        val currentViewPosition = pager.getCurrentViewPositionInFullPageSpace() * width.toFloat()
        val widgetPos = computeWidgetPos(item.getTopLeft(rowCount))
        widgetView.x = widgetPos.x + width * item.page - currentViewPosition
        widgetView.y = widgetPos.y.toFloat()
        val widgetWidth = item.getWidthInCells() * cellWidth
        val widgetHeight = item.getHeightInCells() * cellHeight

        val density = context.resources.displayMetrics.density
        val widgetDpWidth = (widgetWidth / density).toInt()
        val widgetDpHeight = (widgetHeight / density).toInt()

        if (widgetView.appWidgetInfo != null) relayWidgetSize(widgetView, widgetDpWidth, widgetDpHeight)

        widgetView.layoutParams?.width = widgetWidth
        widgetView.layoutParams?.height = widgetHeight
        return Size(widgetWidth, widgetHeight)
    }

    private val reportedWidgetSizes = java.util.WeakHashMap<AppWidgetHostView, Pair<Int, Int>>()

    private fun relayWidgetSize(widgetView: AppWidgetHostView, dpWidth: Int, dpHeight: Int) {
        val size = dpWidth to dpHeight
        if (reportedWidgetSizes[widgetView] == size) return
        reportedWidgetSizes[widgetView] = size
        try {
            if (isSPlus()) {
                widgetView.updateAppWidgetSize(Bundle(), listOf(SizeF(dpWidth.toFloat(), dpHeight.toFloat())))
            } else {
                widgetView.updateAppWidgetSize(Bundle(), dpWidth, dpHeight, dpWidth, dpHeight)
            }
        } catch (e: RuntimeException) {
            timber.log.Timber.w(e, "MainDisplayLattice: widget %d rejected its size - binding lost?", widgetView.appWidgetId)
        }
    }

    private fun computeWidgetPos(topLeft: Point): Point {
        val cell = cells[topLeft]!!
        return Point(
            cell.left + sideMargins.left,
            cell.top + sideMargins.top
        )
    }

    private fun getClosestGridCells(center: Point): Point? {
        return cells.entries.firstOrNull { (_, cell) -> center.x == cell.centerX() && center.y == cell.centerY() }?.key
    }

    private fun redrawGrid() {
        post {
            setWillNotDraw(false)
            invalidate()
            binding.lvDrawingAreaUi.invalidate()
        }
    }

    private fun getFakeWidth() = width - sideMargins.left - sideMargins.right

    private fun getFakeHeight() = height - sideMargins.top - sideMargins.bottom

    private val brokenItems = HashSet<Long>()

    private inline fun guardItem(canvas: Canvas, item: MainDisplayLatticeElement, draw: () -> Unit) {
        val saveCount = canvas.saveCount
        try {
            draw()
        } catch (e: RuntimeException) {
            runCatching { canvas.restoreToCount(saveCount) }
            if (brokenItems.add(item.id ?: -1L)) {
                timber.log.Timber.w(e, "MainDisplayLattice: skipping item %s (%s) that failed to draw", item.id, item.packageName)
                runCatching { com.livemap.homedeck.contract.HomeBook.bridge.onNonFatal(e) }
            }
        }
    }

    fun sketchInto(canvas: Canvas) {
        if (cells.isEmpty()) {
            stuffCellSizes()
        }

        val currentXFactor = pager.getXFactorForCurrentPage()
        val lastXFactor = pager.getXFactorForLastPage()

        fun processMainGridItemDrawing(item: MainDisplayLatticeElement, xFactor: Float) = guardItem(canvas, item) {
            val offsetX = sideMargins.left + (this@MainDisplayLattice.width * xFactor).toInt()
            val offsetY = sideMargins.top
            cells[item.getTopLeft(rowCount)]!!.withOffset(offsetX, offsetY) {
                canvas.inPageTransit(xFactor) { canvas.sketchItemInCell(item, this) }
            }
        }

        gridItems
            .filter {
                it.isSingleCellType() && pager.isItemOnCurrentPage(it)
                        && !it.docked && it.parentId == null
            }
            .forEach { item ->
                if (item.outOfBounds()) {
                    return@forEach
                }

                processMainGridItemDrawing(item, currentXFactor)
            }

        gridItems
            .filter { it.isSingleCellType() && it.docked && it.parentId == null }
            .forEach { item ->
                if (item.outOfBounds()) {
                    return@forEach
                }

                processMainGridItemDrawing(item, 0f)
            }

        if (pager.isAnimatingPageChange()) {
            gridItems
                .filter {
                    it.isSingleCellType() && pager.isItemOnLastPage(it)
                            && !it.docked && it.parentId == null
                }
                .forEach { item ->
                    if (item.outOfBounds()) {
                        return@forEach
                    }

                    processMainGridItemDrawing(item, lastXFactor)
                }
        }

        if (pager.isSwiped()) {
            gridItems
                .filter {
                    it.isSingleCellType()
                            && pager.isItemInSwipeRange(it)
                            && !it.docked
                            && it.parentId == null
                }.forEach { item ->
                    if (item.outOfBounds()) {
                        return@forEach
                    }

                    processMainGridItemDrawing(item, lastXFactor)
                }
        }

        val firstPageX = -pager.getCurrentViewPositionInFullPageSpace() * width
        if (firstPageX != reportedFirstPageX) {
            reportedFirstPageX = firstPageX
            onFirstPageScrolled?.invoke(firstPageX)
        }

        var timeWidgetBottom = 0
        gridItems
            .filter { it.type == ITEM_TYPE_WIDGET && !it.outOfBounds() }
            .forEach { item -> guardItem(canvas, item) {
                if (item.className == PSEUDO_WIDGET_TIME && item.page == 0 && !item.docked && item.top == 0) {
                    timeWidgetBottom = (item.bottom + 1) * cellHeight
                }
                val placed = placedWidgetView(widgetViews, { it.tag }, { widgetViewItemIds[it] }, item.widgetId, item.id)
                if (placed != null) {
                    val tag = placed.tag as? Int
                    if (tag != null && tag != item.widgetId) {
                        timber.log.Timber.w("MainDisplayLattice: row %s had widget id %d, its view %d - kept the view's", item.id, item.widgetId, tag)
                        item.widgetId = tag
                    }
                    reviseWidgetPositionAndSize(placed, item)
                    return@forEach
                }

                if (item.widgetId < 0) {
                    if (bindAttempted.add(item.id ?: return@forEach)) wireWidget(item)
                    return@forEach
                }

                if (item.id in bindPending) return@forEach
                val providerInfo = item.providerInfo
                    ?: appWidgetManager!!.installedProviders
                        .firstOrNull { it.provider.className == item.className }

                if (item.pseudoWidgetLayout() != null) {
                    siteAppWidget(null, item)
                } else if (providerInfo != null) {
                    siteAppWidget(providerInfo, item)
                } else {
                    dropWidget(item)
                }
            } }
        if (timeWidgetBottom != reportedTimeWidgetBottom) {
            reportedTimeWidgetBottom = timeWidgetBottom
            post { onTimeWidgetBottomChanged?.invoke(timeWidgetBottom) }
        }

        if (pager.mustDisplayPageChangeIndicator()) {
            val pageCount = pager.getPageCount()
            val step = pageIndicatorRadius * 2 + pageIndicatorMargin
            val rowWidth = (pageCount - 1) * step + 2 * pageIndicatorRadius
            val firstCenterX = (getFakeWidth() - rowWidth) / 2 + sideMargins.left + pageIndicatorRadius
            val centerY = pageIndicatorsYPos.toFloat() + sideMargins.top

            val emphasis = pager.getIndicatorsEmphasis()
            val strength = PAGE_INDICATOR_RESTING_ALPHA + (255 - PAGE_INDICATOR_RESTING_ALPHA) * emphasis
            val fadeIn = pager.getIndicatorsFadeIn()
            val alpha = (strength * fadeIn).toInt().coerceIn(0, 255)

            val appear = PAGE_INDICATOR_APPEAR_SCALE + (1f - PAGE_INDICATOR_APPEAR_SCALE) * fadeIn

            val position = pager.getCurrentViewPositionInFullPageSpace().coerceIn(0f, (pageCount - 1).toFloat())

            emptyPageIndicatorPaint.alpha = alpha * PAGE_INDICATOR_INACTIVE_ALPHA / 255
            for (page in 0 until pageCount) {
                val cx = firstCenterX + page * step

                val nearness = (1f - abs(position - page)).coerceAtLeast(0f)
                val radius = pageIndicatorInactiveRadius * (1f + PAGE_INDICATOR_DOT_SWELL * nearness) * appear
                canvas.drawCircle(cx, centerY, radius, emptyPageIndicatorPaint)
            }

            val fraction = position - floor(position)
            val stretch = 1f - abs(2f * fraction - 1f)
            val maxHalfLength = step / 2f - 1f
            val halfLength = (pageIndicatorRadius + pageIndicatorActiveExtra + PAGE_INDICATOR_STRETCH * step * stretch / 2f)
                .coerceAtMost(maxHalfLength) * appear
            val capsuleRadius = pageIndicatorRadius * appear
            val cx = firstCenterX + position * step
            currentPageIndicatorPaint.alpha = alpha
            canvas.drawRoundRect(
                cx - halfLength, centerY - capsuleRadius, cx + halfLength, centerY + capsuleRadius,
                capsuleRadius, capsuleRadius, currentPageIndicatorPaint,
            )

            if (pager.getIndicatorsFadeIn() < 1f) {
                postOnAnimation { redrawGrid() }
            }
        }

        val folder = currentlyOpenFolder
        if (folder != null && folder.getItems().isNotEmpty()) {
            val items = folder.getItems()
            val folderRect = folder.getDrawingRect()

            val currentViewPosition =
                pager.getCurrentViewPositionInFullPageSpace() * width.toFloat()
            val rectOffset = width * folder.item.page - currentViewPosition
            folderRect.offset(rectOffset, 0f)

            canvas.withScale(
                x = folder.scale,
                y = folder.scale,
                pivotX = folderRect.centerX(),
                pivotY = folderRect.centerY()
            ) {
                canvas.drawRoundRect(
                    folderRect,
                    roundedCornerRadius / folder.scale,
                    roundedCornerRadius / folder.scale,
                    folderBackgroundPaint
                )
                val textX = folderRect.left + folderPadding
                val textY = folderRect.top + folderPadding
                val staticLayout = StaticLayout.Builder
                    .obtain(
                        folder.item.title,
                        0,
                        folder.item.title.length,
                        folderTitleTextPaint,
                        (folderRect.width() - 2 * folderPadding * folder.scale).toInt()
                    )
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()

                withTranslation(textX, textY) {
                    staticLayout.draw(canvas)
                }

                items.forEach { item ->
                    val itemRect = folder.getItemRect(item)
                    canvas.sketchItemInCell(item, itemRect)
                }
            }
        }

        if (draggedItem != null && draggedItemCurrentCoords.first != -1 && draggedItemCurrentCoords.second != -1) {
            if (draggedItem!!.isSingleCellType()) {
                if (folder != null && folder.getItemsDrawingRect().contains(
                        (draggedItemCurrentCoords.first).toFloat(),
                        (draggedItemCurrentCoords.second).toFloat()
                    )
                ) {
                    val center = folder.getItemsGridCenters().minBy {
                        abs(it.second - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.third - draggedItemCurrentCoords.second + sideMargins.top)
                    }
                    val cellSize = folder.getCellSize()

                    val shadowX = center.second - cellSize / 2 + iconMargin + iconSize / 2f
                    val shadowY = center.third - cellSize / 2 + iconMargin + iconSize / 2

                    canvas.drawCircle(
                        shadowX,
                        shadowY.toFloat(),
                        iconSize / 2f,
                        dragShadowCirclePaint
                    )
                } else {
                    val center = gridCenters.minBy {
                        abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
                    }

                    val gridCells = getClosestGridCells(center)
                    if (gridCells != null) {
                        cells[gridCells]?.let { cell ->
                            val shadowX = cell.left + iconMargin + iconSize / 2f + sideMargins.left
                            val shadowY = if (gridCells.y == rowCount - 1) {
                                cellHeight - iconMargin - iconSize / 2f
                            } else {
                                iconMargin + iconSize / 2f
                            } + sideMargins.top + cell.top

                            canvas.drawCircle(
                                shadowX, shadowY, iconSize / 2f, dragShadowCirclePaint
                            )
                        }
                    }
                }

                val drawableX = (draggedItemCurrentCoords.first - iconSize / 1.5f).toInt()
                val drawableY = (draggedItemCurrentCoords.second - iconSize / 1.2f).toInt()
                val newDrawable = if (draggedItem?.type == ITEM_TYPE_FOLDER) {
                    draggedItem!!.toFolder().generateDrawable()
                } else {
                    draggedItem!!.drawable?.constantState?.newDrawable()?.mutate()
                }
                newDrawable?.setBounds(
                    drawableX,
                    drawableY,
                    drawableX + iconSize,
                    drawableY + iconSize
                )
                newDrawable?.draw(canvas)
            } else if (draggedItem!!.type == ITEM_TYPE_WIDGET) {
                if (!isFirstDraw) {
                    val center = gridCenters.minBy {
                        abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
                    }

                    val gridCells = getClosestGridCells(center)
                    if (gridCells != null) {
                        val widgetRect = getWidgetOccupiedRect(gridCells)
                        val widgetPos = computeWidgetPos(Point(widgetRect.left, widgetRect.top))
                        val leftSide = widgetPos.x.toFloat()
                        val topSide = widgetPos.y.toFloat()
                        val rightSide = leftSide + draggedItem!!.getWidthInCells() * cellWidth
                        val bottomSide = topSide + draggedItem!!.getHeightInCells() * cellHeight
                        canvas.drawRoundRect(
                            leftSide,
                            topSide,
                            rightSide,
                            bottomSide,
                            roundedCornerRadius,
                            roundedCornerRadius,
                            dragShadowCirclePaint
                        )
                    }

                    draggedItem!!.drawable?.also { drawable ->
                        val aspectRatio = drawable.minimumHeight / drawable.minimumWidth.toFloat()
                        val drawableX =
                            (draggedItemCurrentCoords.first - drawable.minimumWidth / 2f).toInt()
                        val drawableY =
                            (draggedItemCurrentCoords.second - drawable.minimumHeight / 3f).toInt()
                        val drawableWidth =
                            draggedItem!!.getWidthInCells() * cellWidth - iconMargin * (draggedItem!!.getWidthInCells() - 1)
                        drawable.setBounds(
                            drawableX,
                            drawableY,
                            drawableX + drawableWidth,
                            (drawableY + drawableWidth * aspectRatio).toInt()
                        )
                        drawable.draw(canvas)
                    }
                }
            }
        }

        isFirstDraw = false
    }

    private fun stuffCellSizes() {
        labelLayouts.clear()
        cellWidth = getFakeWidth() / columnCount
        cellHeight = getFakeHeight() / rowCount
        val extraXMargin = if (cellWidth > cellHeight) {
            (cellWidth - cellHeight) / 2
        } else {
            0
        }
        val extraYMargin = if (cellHeight > cellWidth) {
            (cellHeight - cellWidth) / 2
        } else {
            0
        }

        val baseMargin = (context.resources.getDimension(R.dimen.launcher_icon_side_margin) * 5 / columnCount).toInt()
        val cell = min(cellWidth, cellHeight)

        val profileIconDp = context.launcherConfig.iconSizeDp
        val scaledIcon = if (profileIconDp > 0) {
            (profileIconDp * context.resources.displayMetrics.density).toInt().coerceIn(1, cell)
        } else {
            ((cell - 2 * baseMargin) * context.launcherConfig.iconScale).toInt().coerceIn(1, cell)
        }
        iconMargin = ((cell - scaledIcon) / 2).coerceAtLeast(0)
        iconSize = cell - 2 * iconMargin

        dockSlotCount = context.launcherConfig.dockColumnCount.coerceIn(1, MAX_DOCK_SLOTS)
        val dockColumnCount = dockSlotCount
        val dockSlotWidth = getFakeWidth() / dockColumnCount

        dockCellWidth = min(cellWidth, dockSlotWidth)

        val dockDp = context.launcherConfig.dockIconSizeDp
        dockIconSize = if (dockDp > 0) {
            (dockDp * context.resources.displayMetrics.density).toInt().coerceIn(1, min(dockCellWidth, cellHeight))
        } else {
            min(iconSize, dockCellWidth - 2 * iconMargin).coerceAtLeast(1)
        }
        dockIconMargin = ((dockCellWidth - dockIconSize) / 2).coerceAtLeast(0)

        val dockRowTop = (rowCount - 1) * cellHeight
        val dockIconTop = dockRowTop + cellHeight - dockBottomMargin - dockIconSize
        val labelBottom = if (context.launcherConfig.showHomeAppLabels) {
            val lineHeight = textPaint.fontMetrics.bottom - textPaint.fontMetrics.top
            (rowCount - 2) * cellHeight + iconMargin + iconSize + labelSideMargin + (LABEL_MAX_LINES * lineHeight).toInt()
        } else {
            dockRowTop
        }
        val bandTop = max(dockRowTop, labelBottom)
        val clearance = pageIndicatorClearance.toInt()
        pageIndicatorsYPos = ((bandTop + dockIconTop) / 2)
            .coerceAtLeast(bandTop + clearance)
            .coerceAtMost(maxOf(dockIconTop - clearance, bandTop + clearance))
        val dockCellOffset = (dockSlotWidth - dockCellWidth) / 2
        val dockExtraXMargin = if (dockCellWidth < cellWidth) 0 else extraXMargin
        for (i in 0 until max(columnCount, dockColumnCount)) {
            for (j in 0 until rowCount) {
                val isDock = j == rowCount - 1
                if (isDock && i >= dockColumnCount) {
                    continue
                }
                if (!isDock && i >= columnCount) {
                    continue
                }
                val yMarginToAdd = if (isDock) 0 else extraYMargin
                val cellLeft = if (isDock) i * dockSlotWidth + dockCellOffset else i * cellWidth
                val rect = Rect(
                    cellLeft + (if (isDock) dockExtraXMargin else extraXMargin),
                    j * cellHeight + yMarginToAdd,
                    cellLeft + (if (isDock) dockCellWidth - dockExtraXMargin else cellWidth - extraXMargin),
                    (j + 1) * cellHeight - yMarginToAdd,
                )
                cells[Point(i, j)] = rect
                gridCenters.add(Point(rect.centerX(), rect.centerY()))
                if (isDock) {
                    dockCellY = j * cellHeight
                }
            }
        }
    }

    fun fragmentExpanded() {
        widgetViews.forEach {
            it.ignoreTouches = true
        }
        shutFolder(true)
    }

    fun fragmentCollapsed() {
        animate().cancel()
        alpha = 1f
        widgetViews.forEach {
            it.ignoreTouches = false
        }
    }

    fun getClickableRect(item: MainDisplayLatticeElement): Rect {
        if (cells.isEmpty()) {
            stuffCellSizes()
        }

        val folder = currentlyOpenFolder
        val clickableLeft: Int
        val clickableTop: Int
        if (folder != null && item.parentId == folder.item.id) {
            val itemRect = folder.getItemRect(item)
            clickableLeft = itemRect.left
            clickableTop = itemRect.top - iconMargin
        } else {
            val cell = cells[item.getTopLeft(rowCount)] ?: return Rect(0, 0, 0, 0)
            clickableLeft = cell.left + sideMargins.left
            clickableTop = if (item.docked) {
                dockCellY + cellHeight - dockIconSize - dockBottomMargin
            } else {
                cell.top - iconMargin
            } + sideMargins.top
        }
        val additionalHeight = if (!item.docked && context.launcherConfig.showHomeAppLabels) {
            (LABEL_MAX_LINES * (textPaint.fontMetrics.bottom - textPaint.fontMetrics.top)).toInt()
        } else 0
        val size = if (item.docked) dockIconSize else iconSize
        val margin = if (item.docked) dockIconMargin else iconMargin
        return Rect(
            clickableLeft,
            clickableTop,
            clickableLeft + size + 2 * margin,
            clickableTop + size + 2 * margin + additionalHeight
        )
    }

    private fun getWidgetOccupiedRect(item: Point): Rect {
        val left = item.x - floor((draggedItem!!.getWidthInCells() - 1) / 2.0).toInt()
        val rect = Rect(
            left,
            item.y,
            left + draggedItem!!.getWidthInCells() - 1,
            item.y + draggedItem!!.getHeightInCells() - 1
        )
        if (rect.left < 0) {
            rect.right -= rect.left
            rect.left = 0
        } else if (rect.right > columnCount - 1) {
            val diff = rect.right - columnCount + 1
            rect.right -= diff
            rect.left -= diff
        }

        if (rect.top < 0) {
            rect.bottom -= rect.top
            rect.top = 0
        } else if (rect.bottom > rowCount - 2) {
            val diff = rect.bottom - rowCount + 2
            rect.bottom -= diff
            rect.top -= diff
        }

        return rect
    }

    fun isClickingGridItem(x: Int, y: Int): MainDisplayLatticeElement? {
        if (pager.isAnimatingPageChange() || pager.isSwiped()) {
            return null
        }

        currentlyOpenFolder?.also { folder ->
            folder.getItems().forEach { gridItem ->
                val rect = getClickableRect(gridItem)
                if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                    return gridItem
                }
            }
        }

        if (currentlyOpenFolder != null) {
            return null
        }

        for (gridItem in gridItems.filterVisibleOnCurrentPageOnly()) {
            if (gridItem.outOfBounds()) {
                continue
            }

            if (gridItem.isSingleCellType()) {
                val rect = getClickableRect(gridItem)
                if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                    return gridItem
                }
            } else if (gridItem.type == ITEM_TYPE_WIDGET) {
                val widgetPos = computeWidgetPos(gridItem.getTopLeft(rowCount))
                val left = widgetPos.x.toFloat()
                val top = widgetPos.y.toFloat()
                val right = left + gridItem.getWidthInCells() * cellWidth
                val bottom = top + gridItem.getHeightInCells() * cellHeight

                if (x >= left && x <= right && y >= top && y <= bottom) {
                    return gridItem
                }
            }
        }

        return null
    }

    fun intoViewSpaceCoords(screenSpaceX: Float, screenSpaceY: Float): Pair<Float, Float> {
        val viewLocation = IntArray(2)
        getLocationOnScreen(viewLocation)
        val x = screenSpaceX - viewLocation[0]
        val y = screenSpaceY - viewLocation[1]
        return Pair(x, y)
    }

    private fun MainDisplayLatticeElement.outOfBounds(): Boolean {
        return ((!docked && (left >= columnCount || right >= columnCount))

                || (docked && right >= dockSlotCount)
                || (!docked && (top >= rowCount - 1 || bottom >= rowCount - 1))
                || (type == ITEM_TYPE_WIDGET && (bottom - top > rowCount - 1 || right - left > columnCount - 1))
                )
    }

    private inner class HomeScreenGridTouchHelper(host: View) : ExploreByTouchHelper(host) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val item = isClickingGridItem(x.toInt(), y.toInt())

            return if (item != null) {
                item.id?.toInt() ?: INVALID_ID
            } else {
                INVALID_ID
            }
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>?) {
            val sorted =
                gridItems
                    .filter {
                        it.visibleOnCurrentPage()
                                || (
                                currentlyOpenFolder != null
                                        && it.parentId == currentlyOpenFolder?.item?.id
                                )
                    }
                    .sortedBy {
                        (if (it.parentId == null) {
                            it.getDockAdjustedTop(rowCount)
                        } else {
                            1
                        }) * 100 + it.left
                    }
            sorted.forEachIndexed { index, homeScreenGridItem ->
                virtualViewIds?.add(index, homeScreenGridItem.id?.toInt() ?: index)
            }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            val viewLocation = IntArray(2)
            getLocationOnScreen(viewLocation)

            if (virtualViewId == -1) {
                node.text = runCatching {
                    context.applicationInfo.loadLabel(context.packageManager).toString()
                }.getOrElse { context.getString(R.string.launcher_app_launcher_name) }
                val viewBounds = Rect(left, top, right, bottom)
                val onScreenBounds = Rect(viewBounds)
                onScreenBounds.offset(viewLocation[0], viewLocation[1])
                node.setBoundsInScreen(onScreenBounds)
                node.setBoundsInParent(viewBounds)

                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
                node.setParent(this@MainDisplayLattice)
            }

            val item = gridItems.firstOrNull { it.id?.toInt() == virtualViewId }
                ?: throw IllegalArgumentException("Unknown id")

            node.text = if (item.type == ITEM_TYPE_WIDGET) {
                item.providerInfo?.loadLabel(context.packageManager) ?: item.title
            } else {
                item.title
            }

            val viewBounds = if (item == currentlyOpenFolder?.item) {
                currentlyOpenFolder?.getDrawingRect()?.toRect()
            } else if (item.type == ITEM_TYPE_WIDGET) {
                val widgetPos = computeWidgetPos(item.getTopLeft(rowCount))
                val left = widgetPos.x
                val top = widgetPos.y
                val right = left + item.getWidthInCells() * cellWidth
                val bottom = top + item.getHeightInCells() * cellHeight

                Rect(left, top, right, bottom)
            } else {
                getClickableRect(item)
            }
            val onScreenBounds = Rect(viewBounds)
            onScreenBounds.offset(viewLocation[0], viewLocation[1])
            node.setBoundsInScreen(onScreenBounds)
            node.setBoundsInParent(viewBounds)

            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
            node.setParent(this@MainDisplayLattice)
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            val item = gridItems.firstOrNull { it.id?.toInt() == virtualViewId }
                ?: throw IllegalArgumentException("Unknown id")
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> itemClickListener?.apply {
                    if (item == currentlyOpenFolder?.item) {
                        shutFolder(true)
                    } else {
                        invoke(item)
                    }
                    return true
                }

                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> itemLongClickListener?.apply {
                    invoke(item)
                    return true
                }
            }

            return false
        }
    }

    private fun erasePage(page: Int) {
        gridItems.filter { it.page > page }.forEach {
            it.page -= 1
        }
        context.homeScreenGridItemsDB.shiftPage(page, -1)

        if (pager.isOutsideOfPageRange()) {
            post {
                prevPage()
            }
        }
    }

    private fun getMaxPage() =
        gridItems.filter { !it.docked && !it.outOfBounds() }.maxOfOrNull { it.page } ?: 0

    fun nextPage(redraw: Boolean = false): Boolean {
        return pager.nextPage(redraw)
    }

    fun prevPage(redraw: Boolean = false): Boolean {
        return pager.prevPage(redraw)
    }

    fun skipToPage(targetPage: Int): Boolean {
        return pager.skipToPage(targetPage)
    }

    fun getCurrentIconSize(): Int = iconSize

    fun setSwipeMovement(diffX: Float) {
        if (draggedItem == null) {
            pager.setSwipeMovement(diffX)
        }
    }

    fun finalizeSwipe() {
        pager.finalizeSwipe()
    }

    fun flingSwipe(towardsNext: Boolean): Boolean = draggedItem == null && pager.flingSwipe(towardsNext)

    fun isOnFirstPage() = pager.getCurrentPage() == 0 && !pager.isPaging()

    fun isOnLastPage() = pager.getCurrentPage() >= getMaxPage() && !pager.isPaging()

    fun enterFolder(folder: MainDisplayLatticeElement) {
        if (currentlyOpenFolder == null) {
            currentlyOpenFolder = folder.toFolder(animateOpening = true)
            redrawGrid()
        } else if (currentlyOpenFolder?.item?.id != folder.id) {
            shutFolder()
        }
        accessibilityHelper.invalidateRoot()
    }

    fun shutFolder(redraw: Boolean = false) {
        currentlyOpenFolder?.tweenClosing {
            currentlyOpenFolder = null
            if (redraw) {
                redrawGrid()
            }
            accessibilityHelper.invalidateRoot()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return if (currentlyOpenFolder != null) {
            true
        } else {
            super.onInterceptTouchEvent(ev)
        }
    }

    private fun Canvas.sketchItemInCell(item: MainDisplayLatticeElement, cell: Rect) {
        if (item.id != draggedItem?.id) {
            val drawableX = cell.left + (if (item.docked) dockIconMargin else iconMargin)

            val drawable = if (item.type == ITEM_TYPE_FOLDER) {
                item.toFolder().generateDrawable()
            } else {
                null
            }
            val bitmap = if (drawable == null) item.iconBitmap(if (item.docked) dockIconSize else iconSize) else null

            if (item.docked) {
                val drawableY = dockCellY + cellHeight - dockBottomMargin - dockIconSize + sideMargins.top

                drawable?.setBounds(
                    drawableX,
                    drawableY,
                    drawableX + dockIconSize,
                    drawableY + dockIconSize
                )
                bitmap?.let { drawBitmap(it, drawableX.toFloat(), drawableY.toFloat(), transitPaint()) }
            } else {
                val drawableY = cell.top + iconMargin
                drawable?.setBounds(
                    drawableX,
                    drawableY,
                    drawableX + iconSize,
                    drawableY + iconSize
                )
                bitmap?.let { drawBitmap(it, drawableX.toFloat(), drawableY.toFloat(), transitPaint()) }

                if (item.id != draggedItem?.id && item.title.isNotEmpty() && context.launcherConfig.showHomeAppLabels) {
                    val textX = cell.left.toFloat() + labelSideMargin
                    val textY = cell.top.toFloat() + iconSize + iconMargin + labelSideMargin
                    val textPaintToUse = if (item.parentId == null) {
                        textPaint
                    } else {
                        contrastTextPaint
                    }
                    val staticLayout = labelLayout(item.title, textPaintToUse)

                    val fullAlpha = textPaintToUse.alpha
                    textPaintToUse.alpha = pageTransitAlpha
                    withTranslation(textX, textY) {
                        staticLayout.draw(this)
                    }
                    textPaintToUse.alpha = fullAlpha
                }
            }

            drawable?.alpha = pageTransitAlpha
            drawable?.draw(this)
        }
    }

    private fun transitPaint(): Paint? = if (pageTransitAlpha >= 255) {
        null
    } else {
        pageTransitPaint.apply { alpha = pageTransitAlpha }
    }

    private fun MainDisplayLatticeElement.iconBitmap(size: Int): Bitmap? {
        val source = drawable ?: return null
        if (size <= 0) return null
        iconRaster?.takeIf { iconRasterSource === source && it.width == size }?.let { return it }
        val bitmap = createBitmap(size, size)
        val painter = source.constantState?.newDrawable()?.mutate() ?: source
        painter.setBounds(0, 0, size, size)
        painter.draw(Canvas(bitmap))
        iconRaster = bitmap
        iconRasterSource = source
        return bitmap
    }

    private val labelLayouts = HashMap<String, StaticLayout>()

    private fun labelLayout(title: String, paint: TextPaint): StaticLayout {
        val key = if (paint === contrastTextPaint) "c:" + title else "n:" + title
        return labelLayouts.getOrPut(key) {
            StaticLayout.Builder
                .obtain(title, 0, title.length, paint, cellWidth - 2 * labelSideMargin)
                .setMaxLines(LABEL_MAX_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
        }
    }

    private fun Canvas.inPageTransit(xFactor: Float, block: () -> Unit) {
        val progress = min(abs(xFactor), 1f)
        if (progress == 0f) {
            block()
            return
        }
        pageTransitAlpha = (255 * (1f - PAGE_TRANSIT_FADE * progress)).toInt().coerceIn(0, 255)
        val scale = 1f - PAGE_TRANSIT_SCALE * progress
        val pivotY = sideMargins.top + (rowCount - 1) * cellHeight / 2f
        withScale(scale, scale, width / 2f, pivotY) {
            block()
        }
        pageTransitAlpha = 255
    }

    private fun Rect.withOffset(offsetX: Int, offsetY: Int, block: Rect.() -> Unit) {
        offset(offsetX, offsetY)
        try {
            block()
        } finally {
            offset(-offsetX, -offsetY)
        }
    }

    private fun List<MainDisplayLatticeElement>.filterVisibleOnCurrentPageOnly() =
        filter { it.visibleOnCurrentPage() }

    private fun MainDisplayLatticeElement.visibleOnCurrentPage() =
        (pager.isItemOnCurrentPage(this) || docked) && parentId == null

    private fun MainDisplayLatticeElement.isSingleCellType() =
        (type == ITEM_TYPE_ICON || type == ITEM_TYPE_SHORTCUT || type == ITEM_TYPE_FOLDER)

    private fun MainDisplayLatticeElement.toFolder(animateOpening: Boolean = false) =
        HomeScreenFolder(this, animateOpening)

    private inner class HomeScreenFolder(
        val item: MainDisplayLatticeElement,
        animateOpening: Boolean,
    ) {
        var scale: Float = 1f
        private var closing = false

        init {
            if (animateOpening) {
                scale = 0f
                post {
                    ValueAnimator.ofFloat(0f, 1f)
                        .apply {
                            interpolator = DecelerateInterpolator()
                            addUpdateListener {
                                scale = it.animatedValue as Float
                                redrawGrid()
                            }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    super.onAnimationEnd(animation)
                                    scale = 1f
                                    redrawGrid()
                                }
                            })
                            duration = FOLDER_ANIMATION_DURATION
                            start()
                        }
                }
            }
        }

        fun getItems(): List<MainDisplayLatticeElement> {
            return gridItems
                .toList()
                .filter { it.isSingleCellType() && it.parentId == item.id }
        }

        fun generateDrawable(): Drawable? {
            if (iconSize == 0) {
                return null
            }

            val items = getItems()
            val itemsCount = getItems().count()

            if (itemsCount == 0) {
                return null
            }

            val bitmap = createBitmap(iconSize, iconSize)
            val canvas = Canvas(bitmap)
            val circlePath = Path().apply {
                addCircle(
                    (iconSize / 2).toFloat(),
                    (iconSize / 2).toFloat(),
                    (iconSize / 2).toFloat(),
                    Path.Direction.CCW
                )
            }
            canvas.clipPath(circlePath)
            canvas.drawPaint(folderIconBackgroundPaint)
            val folderColumnCount = ceil(sqrt(itemsCount.toDouble())).roundToInt()
            val folderRowCount = ceil(itemsCount.toFloat() / folderColumnCount).roundToInt()
            val scaledCellSize = (iconSize.toFloat() / folderColumnCount)
            val scaledGap = scaledCellSize / 4f
            val scaledIconSize =
                (iconSize - (folderColumnCount + 1) * scaledGap) / folderColumnCount
            val extraYMargin =
                if (folderRowCount < folderColumnCount) (scaledIconSize + scaledGap) / 2 else 0f
            items.forEach {
                val (row, column) = getItemPosition(it)
                val drawableX = (scaledGap + column * scaledIconSize + column * scaledGap).toInt()
                val drawableY =
                    (extraYMargin + scaledGap + row * scaledIconSize + row * scaledGap).toInt()
                val newDrawable = it.drawable?.constantState?.newDrawable()?.mutate()
                newDrawable?.setBounds(
                    drawableX,
                    drawableY,
                    drawableX + scaledIconSize.toInt(),
                    drawableY + scaledIconSize.toInt()
                )
                newDrawable?.draw(canvas)
            }
            return bitmap.toDrawable(resources)
        }

        fun getDrawingRect(): RectF {
            val count = getItems().count()
            if (count == 0) {
                return RectF(0f, 0f, 0f, 0f)
            }
            val columnsCount = ceil(sqrt(count.toDouble())).toInt()
            val rowsCount = ceil(count.toFloat() / columnsCount).toInt()
            val cellSize = getCellSize()
            val gap = getGapSize()
            val yGap = if (context.launcherConfig.showHomeAppLabels) {
                gap + textPaint.textSize + 2 * labelSideMargin
            } else gap
            val cell = cells[item.getTopLeft(rowCount)] ?: return RectF(0f, 0f, 0f, 0f)
            val centerX = sideMargins.left + cell.centerX()
            val centerY = sideMargins.top + cell.centerY()
            val folderDialogWidth =
                columnsCount * cellSize + 2 * folderPadding + (columnsCount - 1) * gap
            val folderDialogHeight =
                rowsCount * cellSize + 3 * folderPadding + folderTitleTextPaint.textSize + rowsCount * yGap
            var folderDialogTop = centerY - folderDialogHeight / 2
            var folderDialogLeft = centerX - folderDialogWidth / 2

            if (folderDialogLeft < left + sideMargins.left) {
                folderDialogLeft += left + sideMargins.left - folderDialogLeft
            }
            if (folderDialogLeft + folderDialogWidth > right - sideMargins.right) {
                folderDialogLeft -= folderDialogLeft + folderDialogWidth - (right - sideMargins.right)
            }
            if (folderDialogTop < top + sideMargins.top) {
                folderDialogTop += top + sideMargins.top - folderDialogTop
            }
            if (folderDialogTop + folderDialogHeight > bottom - sideMargins.bottom) {
                folderDialogTop -= folderDialogTop + folderDialogHeight - (bottom - sideMargins.bottom)
            }

            return RectF(
                folderDialogLeft,
                folderDialogTop,
                folderDialogLeft + folderDialogWidth,
                folderDialogTop + folderDialogHeight
            )
        }

        fun getItemsDrawingRect(): RectF {
            val folderRect = getDrawingRect()
            return RectF(
                folderRect.left + folderPadding,
                folderRect.top + folderPadding * 2 + folderTitleTextPaint.textSize,
                folderRect.right - folderPadding,
                folderRect.bottom - folderPadding
            )
        }

        fun getItemsGridCenters(): List<Triple<Int, Int, Int>> {
            val count = getItems().count()
            if (count == 0) {
                return emptyList()
            }

            val columnsCount = ceil(sqrt(count.toDouble())).roundToInt()
            val rowsCount = ceil(count.toFloat() / columnsCount).roundToInt()
            val folderItemsRect = getItemsDrawingRect()
            val cellSize = getCellSize()
            val gap = getGapSize()
            val yGap = if (context.launcherConfig.showHomeAppLabels) {
                gap + textPaint.textSize + 2 * labelSideMargin
            } else gap
            return (0 until columnsCount * rowsCount)
                .toList()
                .map { Pair(it % columnsCount, it / columnsCount) }
                .mapIndexed { index, (x, y) ->
                    Triple(
                        index,
                        (folderItemsRect.left + x * cellSize + x * gap + cellSize / 2).toInt(),
                        (folderItemsRect.top + y * cellSize + y * yGap + cellSize / 2).toInt()
                    )
                }
        }

        private fun getItemPosition(item: MainDisplayLatticeElement): Pair<Int, Int> {
            val count = getItems().count()
            val columnsCount = ceil(sqrt(count.toDouble())).roundToInt()
            val column = item.left % columnsCount
            val row = item.left / columnsCount
            return Pair(row, column)
        }

        fun getItemRect(item: MainDisplayLatticeElement): Rect {
            val (row, column) = getItemPosition(item)
            val itemsRect = getItemsDrawingRect()
            val cellSize = getCellSize()
            val gapSize = getGapSize()
            val yGapSize = if (context.launcherConfig.showHomeAppLabels) {
                gapSize + textPaint.textSize + 2 * labelSideMargin
            } else gapSize
            val left = (itemsRect.left + column * cellSize + column * gapSize).roundToInt()
            val top = (itemsRect.top + row * cellSize + row * yGapSize).roundToInt()
            return Rect(
                left,
                top,
                left + cellSize,
                top + cellSize
            )
        }

        fun tweenClosing(callback: () -> Unit) {
            post {
                if (closing) {
                    return@post
                }
                closing = true
                ValueAnimator.ofFloat(scale, 0.2f)
                    .apply {
                        interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            scale = it.animatedValue as Float
                            redrawGrid()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                super.onAnimationEnd(animation)
                                scale = 0.2f
                                callback()
                            }
                        })
                        duration = (FOLDER_ANIMATION_DURATION * (max(0f, scale - 0.2f))).toLong()
                        start()
                    }
            }
        }

        fun getCellSize(): Int = min(cellWidth, cellHeight)

        private fun getGapSize(): Float {
            val cellSize = getCellSize()
            return if (cellSize == cellWidth) {
                0f
            } else {
                cellSize / 5f
            }
        }
    }

    companion object {
        internal fun <V> placedWidgetView(
            views: List<V>,
            tagOf: (V) -> Any?,
            itemIdOf: (V) -> Long?,
            widgetId: Int,
            itemId: Long?,
        ): V? = views.firstOrNull { itemId != null && itemIdOf(it) == itemId }
            ?: views.firstOrNull { tagOf(it) == widgetId }

        internal fun <V> orphanWidgetViews(viewItemIds: Map<V, Long?>, liveItemIds: Set<Long>): List<V> =
            viewItemIds.filter { (_, id) -> id != null && id !in liveItemIds }.keys.toList()

        private const val LABEL_MAX_LINES = 1

        private const val PAGE_INDICATOR_STRETCH = 0.6f
        private const val PAGE_INDICATOR_INACTIVE_ALPHA = 112

        private const val PAGE_INDICATOR_RESTING_ALPHA = 210f

        private const val PAGE_TRANSIT_SCALE = 0.08f
        private const val PAGE_TRANSIT_FADE = 0.45f

        private const val PAGE_INDICATOR_DOT_SWELL = 0.18f

        private const val PAGE_INDICATOR_APPEAR_SCALE = 0.75f
        private const val FOLDER_OPEN_HOLD_THRESHOLD = 500L
        private const val FOLDER_CLOSE_HOLD_THRESHOLD = 300L
        private const val FOLDER_ANIMATION_DURATION = 200L
    }
}

private class AnimatingLatticeSwiper(
    private val getMaxPage: () -> Int,
    private val redrawGrid: () -> Unit,
    private val getWidth: () -> Int,
    private val getHandler: () -> Handler,
    private val getNextPageBound: () -> Int,
    private val getPrevPageBound: () -> Int,
    private val pageChangeStarted: () -> Unit,
) {
    companion object {
        private const val PAGE_CHANGE_HOLD_THRESHOLD = 500L

        private const val PAGE_SETTLE_MS = 220L

        private const val PAGE_SETTLE_QUEUED_MS = 160L

        private const val PAGE_SETTLE_MIN_MS = 80L
        private const val PAGE_INDICATORS_FADE_DELAY = 600L
        private const val PAGE_INDICATORS_FADE_IN = 120L

        private enum class PageChangeArea {
            LEFT,
            MIDDLE,
            RIGHT
        }
    }

    private var lastPage = 0
    private var currentPage = 0
    private var pageChangeLastArea = PageChangeArea.MIDDLE
    private var pageChangeLastAreaEntryTime = 0L
    private var pageChangeAnimLeftPercentage = 0f
    private var pageChangeEnabled = true
    private var pageChangeIndicatorsAlpha = 0f

    private var indicatorsShownAt = 0L
    private var pageChangeSwipedPercentage = 0f

    private var pendingStep = 0

    fun getCurrentPage() = currentPage

    fun isPaging() = isSwiped() || isAnimatingPageChange() || !pageChangeEnabled

    fun isItemOnCurrentPage(item: MainDisplayLatticeElement) = item.page == currentPage

    fun isItemOnLastPage(item: MainDisplayLatticeElement) = item.page == lastPage

    fun getPageCount() = max(getMaxPage(), currentPage) + 1

    fun isOutsideOfPageRange() = currentPage > getMaxPage()

    fun isItemInSwipeRange(item: MainDisplayLatticeElement) =
        ((pageChangeSwipedPercentage > 0f && item.page == currentPage - 1) || (pageChangeSwipedPercentage < 0f && item.page == currentPage + 1))

    fun isSwiped() = abs(pageChangeSwipedPercentage) > 0f

    fun isAnimatingPageChange() = pageChangeAnimLeftPercentage != 0f

    fun mustDisplayPageChangeIndicator() =
        getPageCount() > 1 || isSwiped() || isAnimatingPageChange() || pageChangeIndicatorsAlpha != 0f

    fun getIndicatorsEmphasis(): Float =
        if (isSwiped() || isAnimatingPageChange()) 1f else pageChangeIndicatorsAlpha

    fun getIndicatorsFadeIn(): Float =
        ((SystemClock.uptimeMillis() - indicatorsShownAt).toFloat() / PAGE_INDICATORS_FADE_IN).coerceIn(0f, 1f)

    private fun flagIndicatorsShown() {
        if (!mustDisplayPageChangeIndicator()) {
            indicatorsShownAt = SystemClock.uptimeMillis()
        }
    }

    fun getPageChangeIndicatorsAlpha() = if (pageChangeIndicatorsAlpha != 0f) {
        (pageChangeIndicatorsAlpha * 255.0f).toInt()
    } else {
        255
    }

    fun getXFactorForCurrentPage(): Float {
        return if (abs(pageChangeSwipedPercentage) > 0f) {
            pageChangeSwipedPercentage
        } else {
            if (currentPage > lastPage) {
                pageChangeAnimLeftPercentage
            } else {
                -pageChangeAnimLeftPercentage
            }
        }
    }

    fun getXFactorForLastPage(): Float {
        return if (abs(pageChangeSwipedPercentage) > 0f) {
            (1 - abs(pageChangeSwipedPercentage)) * -sign(pageChangeSwipedPercentage)
        } else {
            if (currentPage > lastPage) {
                pageChangeAnimLeftPercentage - 1
            } else {
                1 - pageChangeAnimLeftPercentage
            }
        }
    }

    fun getCurrentViewPositionInFullPageSpace(): Float {
        val rangeStart = lastPage.toFloat()
        val rangeEndPage = if (abs(pageChangeSwipedPercentage) > 0f) {
            if (pageChangeSwipedPercentage > 0f) {
                currentPage - 1
            } else {
                currentPage + 1
            }
        } else {
            currentPage
        }
        val rangeEnd = rangeEndPage.toFloat()
        val lerpAmount = if (pageChangeAnimLeftPercentage != 0f) {
            1 - pageChangeAnimLeftPercentage
        } else {
            abs(pageChangeSwipedPercentage)
        }
        return MathUtils.lerp(rangeStart, rangeEnd, lerpAmount)
    }

    fun setSwipeMovement(diffX: Float) {
        if (!pageChangeEnabled) {
            return
        }

        if (currentPage < getMaxPage() && diffX > 0f || currentPage > 0 && diffX < 0f) {
            flagIndicatorsShown()
            pageChangeSwipedPercentage = (-diffX / getWidth().toFloat()).coerceIn(-1f, 1f)
            pageChangeStarted()
            redrawGrid()
        }
    }

    fun flingSwipe(towardsNext: Boolean): Boolean {
        if (isAnimatingPageChange() || !pageChangeEnabled) {
            pendingStep += if (towardsNext) 1 else -1
            return true
        }
        if (!isSwiped()) {
            return false
        }

        if ((pageChangeSwipedPercentage < 0f) != towardsNext) {
            return true
        }
        lastPage = currentPage
        currentPage += if (towardsNext) 1 else -1
        processPageChange(true)
        return true
    }

    fun finalizeSwipe() {
        if (abs(pageChangeSwipedPercentage) == 0f) {
            return
        }

        if (abs(pageChangeSwipedPercentage) > 0.5f) {
            lastPage = currentPage
            currentPage = if (pageChangeSwipedPercentage > 0f) {
                currentPage - 1
            } else {
                currentPage + 1
            }
            processPageChange(true)
        } else {
            lastPage = if (pageChangeSwipedPercentage > 0f) {
                currentPage - 1
            } else {
                currentPage + 1
            }
            pageChangeSwipedPercentage =
                sign(pageChangeSwipedPercentage) * (1 - abs(pageChangeSwipedPercentage))
            processPageChange(true)
        }
    }

    fun processItemMovement(x: Int, y: Int) {
        presentIndicators()
        if (x > getNextPageBound()) {
            doWithPageChangeDelay(PageChangeArea.RIGHT) {
                nextOrAdditionalPage()
            }
        } else if (x < getPrevPageBound()) {
            doWithPageChangeDelay(PageChangeArea.LEFT) {
                prevPage()
            }
        } else {
            wipePageChangeFlags()
        }
    }

    fun itemMovementStopped() {
        planIndicatorsFade()
    }

    fun nextPage(redraw: Boolean = false): Boolean {
        if (currentPage < getMaxPage() && pageChangeEnabled) {
            lastPage = currentPage
            currentPage++
            processPageChange(redraw)
            return true
        }

        return false
    }

    fun prevPage(redraw: Boolean = false): Boolean {
        if (currentPage > 0 && pageChangeEnabled) {
            lastPage = currentPage
            currentPage--
            processPageChange(redraw)
            return true
        }

        return false
    }

    fun skipToPage(targetPage: Int): Boolean {
        if (currentPage != targetPage && targetPage < getMaxPage() + 1) {
            lastPage = currentPage
            currentPage = targetPage
            processPageChange()
            return true
        }

        return false
    }

    private val checkAndExecuteDelayedPageChange: Runnable = Runnable {
        if (System.currentTimeMillis() - pageChangeLastAreaEntryTime > PAGE_CHANGE_HOLD_THRESHOLD) {
            when (pageChangeLastArea) {
                PageChangeArea.RIGHT -> nextOrAdditionalPage(true)
                PageChangeArea.LEFT -> prevPage(true)
                else -> wipePageChangeFlags()
            }
        }
    }

    private val startFadingIndicators: Runnable = Runnable {
        ValueAnimator.ofFloat(1f, 0f)
            .apply {
                addUpdateListener {
                    pageChangeIndicatorsAlpha = it.animatedValue as Float
                    redrawGrid()
                }
                start()
            }
    }

    private fun presentIndicators() {
        flagIndicatorsShown()
        pageChangeIndicatorsAlpha = 1f
        getHandler().removeCallbacks(startFadingIndicators)
    }

    private fun wipePageChangeFlags() {
        pageChangeLastArea = PageChangeArea.MIDDLE
        pageChangeLastAreaEntryTime = 0
        getHandler().removeCallbacks(checkAndExecuteDelayedPageChange)
    }

    private fun planPageChange() {
        pageChangeLastAreaEntryTime = System.currentTimeMillis()
        getHandler().postDelayed(checkAndExecuteDelayedPageChange, PAGE_CHANGE_HOLD_THRESHOLD)
    }

    private fun planIndicatorsFade() {
        pageChangeIndicatorsAlpha = 1f
        getHandler().postDelayed(startFadingIndicators, PAGE_INDICATORS_FADE_DELAY)
    }

    private fun doWithPageChangeDelay(needed: PageChangeArea, pageChangeFunction: () -> Boolean) {
        if (pageChangeLastArea != needed) {
            pageChangeLastArea = needed
            planPageChange()
        } else if (System.currentTimeMillis() - pageChangeLastAreaEntryTime > PAGE_CHANGE_HOLD_THRESHOLD) {
            if (pageChangeFunction()) {
                wipePageChangeFlags()
            }
        }
    }

    private fun nextOrAdditionalPage(redraw: Boolean = false): Boolean {
        if (currentPage < getMaxPage() + 1 && pageChangeEnabled) {
            lastPage = currentPage
            currentPage++
            processPageChange(redraw)
            return true
        }

        return false
    }

    private fun processPageChange(redraw: Boolean = false) {
        flagIndicatorsShown()
        pageChangeEnabled = false
        pageChangeIndicatorsAlpha = 0f
        pageChangeStarted()
        val startingAt = 1 - abs(pageChangeSwipedPercentage)
        pageChangeSwipedPercentage = 0f
        getHandler().removeCallbacks(startFadingIndicators)
        pageChangeStarted()
        if (redraw) {
            redrawGrid()
        }
        ValueAnimator.ofFloat(startingAt, 0f)
            .apply {
                val full = if (pendingStep != 0) PAGE_SETTLE_QUEUED_MS else PAGE_SETTLE_MS
                duration = (full * startingAt).toLong().coerceAtLeast(PAGE_SETTLE_MIN_MS)

                interpolator = PathInterpolator(0.2f, 0f, 0.2f, 1f)
                addUpdateListener {
                    if (it.animatedValue != 0f) {
                        pageChangeAnimLeftPercentage = it.animatedValue as Float
                        redrawGrid()
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        super.onAnimationEnd(animation)
                        pageChangeAnimLeftPercentage = 0f
                        pageChangeEnabled = true
                        lastPage = currentPage
                        wipePageChangeFlags()

                        val step = pendingStep.sign
                        if (step > 0 && currentPage < getMaxPage() || step < 0 && currentPage > 0) {
                            pendingStep -= step
                            currentPage += step
                            processPageChange(true)
                            return
                        }
                        pendingStep = 0
                        planIndicatorsFade()
                        redrawGrid()
                    }
                })
                start()
            }
    }
}
