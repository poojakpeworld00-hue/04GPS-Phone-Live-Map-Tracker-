package com.livemap.homedeck.binders

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.realScreenSize
import com.livemap.homedeck.R
import com.livemap.homedeck.teaser.BackedSquares
import com.bumptech.glide.Glide
import com.livemap.homedeck.screens.HomeBaseDeck
import com.livemap.homedeck.databinding.HdItemLauncherLabelBinding
import com.livemap.homedeck.ext.tweenScale
import com.livemap.homedeck.ext.launcherConfig
import com.livemap.homedeck.support.BadgePuller
import com.livemap.homedeck.support.MarkContour
import com.livemap.homedeck.ports.AllProgramsObserver
import com.livemap.homedeck.entities.CoreHome

class HomesFeed(
    val activity: HomeBaseDeck,
    val allAppsListener: AllProgramsObserver,
    private val adSlot: View? = null,
    val itemClick: (Any) -> Unit
) : ListAdapter<CoreHome, RecyclerView.ViewHolder>(CoreHomeDeltaHook()),
    RecyclerViewFastScroller.OnPopupTextUpdate {
    private var fallbackInset = 0
    private var targetIconPx = 0

    private val cellSidePad = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)

    private var hostGrid: RecyclerView? = null

    private val gridWidthWatcher = View.OnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
        val newWidth = right - left
        if (newWidth <= 0 || newWidth == oldRight - oldLeft) return@OnLayoutChangeListener
        v.post { if (itemCount > 0) notifyItemRangeChanged(0, itemCount) }
    }

    var highlight: String = ""
        set(value) { field = value.trim() }
    private var labelSizeSp = 0f

    private val sponsorLogoPx = activity.resources.getDimensionPixelSize(R.dimen.launcher_icon_cache_size)
    private val sponsorMask = MarkContour(activity.resources, activity.launcherConfig.legacyIconTray)

    var cellHeightPx = 0

    init {
        setHasStableIds(true)
        refreshIconMetrics()
    }

    override fun getItemId(position: Int): Long =
        getItem(position).getLauncherIdentifier().hashCode().toLong()

    override fun getItemViewType(position: Int) = when {
        isAdSlot(getItem(position)) -> VIEW_TYPE_AD
        else -> VIEW_TYPE_LAUNCHER
    }

    fun isAdSlotPosition(position: Int) =
        position in 0 until itemCount && isAdSlot(getItem(position))

    fun fireFirstApp(): Boolean {
        val first = currentList.firstOrNull { !isAdSlot(it) }
        if (first == null) return false
        itemClick(first)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        VIEW_TYPE_AD -> AdViewHolder(
            FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        )

        else -> ViewHolder(
            HdItemLauncherLabelBinding.inflate(LayoutInflater.from(parent.context), parent, false).root
        )
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        hostGrid = recyclerView
        recyclerView.addOnLayoutChangeListener(gridWidthWatcher)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnLayoutChangeListener(gridWidthWatcher)
        if (hostGrid === recyclerView) hostGrid = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun prewarm(grid: RecyclerView, count: Int) {
        val pool = grid.recycledViewPool
        pool.setMaxRecycledViews(VIEW_TYPE_LAUNCHER, count)
        Looper.myQueue().addIdleHandler {
            val keepGoing = grid.adapter === this && pool.getRecycledViewCount(VIEW_TYPE_LAUNCHER) < count
            if (keepGoing) pool.putRecycledView(createViewHolder(grid, VIEW_TYPE_LAUNCHER))
            keepGoing
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AdViewHolder -> holder.wireSlot()
            is ViewHolder -> holder.bindView(getItem(position))
        }
    }

    inner class AdViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun wireSlot() {
            val slot = adSlot ?: return
            val frame = itemView as FrameLayout
            if (slot.parent === frame) return
            (slot.parent as? ViewGroup)?.removeView(slot)
            frame.addView(
                slot,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    override fun submitList(list: MutableList<CoreHome>?) {
        refreshIconMetrics()
        super.submitList(list)
    }

    private fun refreshIconMetrics() {
        val cfg = activity.launcherConfig
        labelSizeSp = cfg.labelTextSp
        val cellWidth = activity.realScreenSize.x / cfg.drawerColumnCount

        val sizeDp = cfg.drawerIconSizeDp.takeIf { it > 0 } ?: cfg.iconSizeDp
        targetIconPx = (sizeDp * activity.resources.displayMetrics.density).toInt()

        val insetShare = (0.5f - 0.35f * cfg.iconScale).coerceIn(0f, 0.45f)
        fallbackInset = (cellWidth * insetShare).toInt()
    }

    private fun insetToIcon(view: View, width: Int) {
        if (width <= 0) return
        val sideInset = ((width - targetIconPx) / 2).coerceIn(0, (width * 0.45f).toInt())
        val topInset = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)
        val paddingMatches = view.paddingLeft == sideInset && view.paddingTop == topInset &&
            view.paddingRight == sideInset && view.paddingBottom == 0
        if (!paddingMatches) view.setPadding(sideInset, topInset, sideInset, 0)
        val wantedHeight = targetIconPx + topInset
        if (view.layoutParams.height == wantedHeight) return
        view.layoutParams = view.layoutParams.apply { height = wantedHeight }
    }

    private fun makeSquare(view: View, width: Int) {
        if (width <= 0 || view.layoutParams.height == width) return
        view.layoutParams = view.layoutParams.apply { height = width }
    }

    private fun tintMatch(title: String): CharSequence {
        val query = highlight
        if (query.isEmpty()) return title
        val at = title.indexOf(query, ignoreCase = true)
        if (at < 0) return title
        val spanned = SpannableString(title)
        spanned.setSpan(
            ForegroundColorSpan(activity.getColor(R.color.lnch_search_highlight)),
            at, at + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spanned
    }

    private val iconWidthWatcher = View.OnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
        val w = right - left
        if (w == oldRight - oldLeft) return@OnLayoutChangeListener
        when {
            targetIconPx > 0 -> insetToIcon(v, w)
            else -> makeSquare(v, w)
        }
    }

    private fun measuredIconWidth(): Int {
        val grid = hostGrid ?: return 0
        val usable = grid.width - grid.paddingLeft - grid.paddingRight
        val spanCount = (grid.layoutManager as? GridLayoutManager)?.spanCount ?: return 0
        return when {
            usable <= 0 || spanCount <= 0 -> 0
            else -> maxOf(0, usable / spanCount - 2 * cellSidePad)
        }
    }

    private fun applyIconSize(view: View) {
        val known = measuredIconWidth()
        val width = if (known > 0) known else view.width
        if (targetIconPx > 0) {
            insetToIcon(view, width)
        } else {
            view.setPadding(fallbackInset, fallbackInset, fallbackInset, 0)
            makeSquare(view, width)
        }
        view.removeOnLayoutChangeListener(iconWidthWatcher)
        view.addOnLayoutChangeListener(iconWidthWatcher)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private fun matchCellHeight() {
            val row = itemView as? RelativeLayout ?: return
            val fixed = cellHeightPx > 0
            val targetGravity = if (fixed) Gravity.CENTER else Gravity.START or Gravity.TOP
            val targetHeight = if (fixed) cellHeightPx else ViewGroup.LayoutParams.WRAP_CONTENT
            if (row.gravity != targetGravity) row.gravity = targetGravity
            val lp = row.layoutParams ?: return
            if (lp.height != targetHeight) row.layoutParams = lp.apply { height = targetHeight }
        }

        private fun showLabel(binding: HdItemLauncherLabelBinding, launcher: CoreHome, sponsored: Boolean) {
            val label = binding.homexLabelUi

            label.text = when {
                sponsored -> itemView.context.getString(R.string.lnch_sponsored_label, launcher.title)
                else -> tintMatch(launcher.title)
            }

            label.setTextColor(Color.WHITE)
            if (labelSizeSp > 0f) label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSizeSp)
            label.beVisibleIf(activity.launcherConfig.showDrawerAppLabels)
        }

        private fun showIcon(binding: HdItemLauncherLabelBinding, launcher: CoreHome, sponsored: Boolean) {
            val icon = binding.homexIconUi

            Glide.with(activity).clear(icon)
            if (sponsored) {
                icon.tag = null
                Glide.with(activity)
                    .load(BackedSquares.logo(launcher))
                    .override(sponsorLogoPx)
                    .transform(sponsorMask)
                    .placeholder(R.drawable.hd_placeholder_drawable)
                    .error(R.drawable.hd_placeholder_drawable)
                    .into(icon)
                return
            }
            val inMemory = launcher.drawable ?: BadgePuller.cached(launcher.packageName, launcher.activityName)
            if (inMemory != null) {
                launcher.drawable = inMemory
                icon.setImageDrawable(inMemory)
                icon.tag = null
                return
            }

            val pendingKey = launcher.packageName + "/" + launcher.activityName
            icon.tag = pendingKey
            icon.setImageDrawable(
                activity.resources.getColoredDrawableWithColor(
                    drawableId = R.drawable.hd_placeholder_drawable,
                    color = launcher.thumbnailColor
                )
            )
            BadgePuller.load(activity, launcher.packageName, launcher.activityName) { loaded ->
                if (icon.tag != pendingKey) return@load
                launcher.drawable = loaded
                icon.setImageDrawable(loaded)
                icon.tag = null
            }
        }

        private fun pressFeedback(binding: HdItemLauncherLabelBinding, pressed: Boolean) {
            val tile = itemView
            if (pressed) {
                binding.homexIconUi.drawable?.alpha = LAUNCHER_ALPHA_PRESSED
                tile.tweenScale(from = LAUNCHER_SCALE_NORMAL, to = LAUNCHER_SCALE_PRESSED, duration = LAUNCHER_SCALE_UP_DURATION)
            } else {
                binding.homexIconUi.drawable?.alpha = LAUNCHER_ALPHA_NORMAL
                tile.tweenScale(from = LAUNCHER_SCALE_PRESSED, to = LAUNCHER_SCALE_NORMAL, duration = LAUNCHER_SCALE_DOWN_DURATION)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun wireGestures(binding: HdItemLauncherLabelBinding, launcher: CoreHome, sponsored: Boolean) {
            val tile = itemView
            tile.setOnClickListener { itemClick(launcher) }
            tile.setOnLongClickListener {
                if (!sponsored) {
                    val onScreen = IntArray(2)
                    tile.getLocationOnScreen(onScreen)
                    allAppsListener.onAppLauncherLongPressed(
                        x = (onScreen[0] + tile.width / 2).toFloat(),
                        y = onScreen[1].toFloat(),
                        appLauncher = launcher
                    )
                }
                true
            }
            tile.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> pressFeedback(binding, pressed = true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressFeedback(binding, pressed = false)
                }
                false
            }
        }

        fun bindView(launcher: CoreHome): View {
            val binding = HdItemLauncherLabelBinding.bind(itemView)
            matchCellHeight()
            val sponsored = BackedSquares.isSponsored(launcher)
            showLabel(binding, launcher, sponsored)
            applyIconSize(binding.homexIconUi)
            showIcon(binding, launcher, sponsored)
            wireGestures(binding, launcher, sponsored)
            return itemView
        }
    }

    override fun onChange(position: Int): String {
        val item = currentList.getOrNull(position) ?: return ""
        return item.getBubbleText()
    }

    companion object {
        private const val VIEW_TYPE_LAUNCHER = 0
        private const val VIEW_TYPE_AD = 1

        private const val LAUNCHER_ALPHA_NORMAL = 255
        private const val LAUNCHER_ALPHA_PRESSED = 220
        private const val LAUNCHER_SCALE_NORMAL = 1f
        private const val LAUNCHER_SCALE_PRESSED = 1.15f
        private const val LAUNCHER_SCALE_UP_DURATION = 100L
        private const val LAUNCHER_SCALE_DOWN_DURATION = 50L

        private const val AD_SLOT_PACKAGE = "com.livemap.homedeck.ad_slot"

        val AD_SLOT = CoreHome(
            id = null,
            title = "",
            packageName = AD_SLOT_PACKAGE,
            activityName = "",
            order = 0,
            thumbnailColor = 0,
            drawable = null
        )

        fun isAdSlot(item: CoreHome) = item.packageName == AD_SLOT_PACKAGE
    }
}

class CoreHomeDeltaHook : DiffUtil.ItemCallback<CoreHome>() {
    override fun areItemsTheSame(oldItem: CoreHome, newItem: CoreHome): Boolean =
        oldItem.getLauncherIdentifier().hashCode() == newItem.getLauncherIdentifier().hashCode()

    override fun areContentsTheSame(oldItem: CoreHome, newItem: CoreHome): Boolean {
        val oldIsAd = HomesFeed.isAdSlot(oldItem)
        val newIsAd = HomesFeed.isAdSlot(newItem)

        if (oldIsAd || newIsAd) return oldIsAd && newIsAd
        if (oldItem.drawable == null || newItem.drawable == null) return false
        return oldItem.title == newItem.title &&
            oldItem.order == newItem.order &&
            oldItem.thumbnailColor == newItem.thumbnailColor
    }
}
