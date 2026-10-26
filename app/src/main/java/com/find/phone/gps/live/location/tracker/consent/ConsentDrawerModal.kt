package com.find.phone.gps.live.location.tracker.consent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Outline
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordPermissionResult
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.consent.drawer.DrawerDrawings
import com.find.phone.gps.live.location.tracker.consent.drawer.DrawerFolio
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.dp
import com.find.phone.gps.live.location.tracker.screens.opening.OpeningUnveilProfile
import com.find.phone.gps.live.location.tracker.screens.opening.OpeningUnveilCharter
import com.find.phone.gps.live.location.tracker.helpers.WardenRail
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ConsentDrawerModal : BottomSheetDialogFragment() {
    var onFinished: (() -> Unit)? = null

    private enum class PageState { IDLE, REQUESTING, ALLOWED, DENIED, ALREADY }

    private lateinit var pages: List<DrawerFolio>
    private lateinit var pageStates: Array<PageState>
    private lateinit var folioViews: List<View>
    private val liveAnims = mutableMapOf<Int, MutableList<Animator>>()

    private var root: View? = null
    private var pager: ViewPager2? = null
    private var shownIdx = 0
    private var promptingIdx = -1
    private var slideAnim: ValueAnimator? = null

    private var chainActive = false
    private var lastPrompted = -1
    private var chainWaiting = false
    private val resumeChain = Runnable { stepChain(after = lastPrompted) }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result.forEach { (perm, granted) -> context?.recordPermissionResult(perm, granted) }
        afterPrompt()
    }

    override fun getTheme(): Int = R.style.Ds_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val view = inflater.inflate(R.layout.popup_permission_sheet, container, false)
        root = view

        pages = DrawerFolio.all(ctx)
        pageStates = Array(pages.size) { if (pages[it].isGranted(ctx)) PageState.ALREADY else PageState.IDLE }
        shownIdx = pages.indices.firstOrNull { !pages[it].isGranted(ctx) } ?: 0
        folioViews = pages.map { buildFolioView(inflater, it) }

        val pager = view.findViewById<ViewPager2>(R.id.swiper)
        this.pager = pager
        pager.adapter = FolioAdapter(folioViews)
        pager.offscreenPageLimit = pages.size.coerceAtLeast(1)
        pager.setCurrentItem(shownIdx, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = handlePageVisible(position)
        })

        pager.post { matchPagerToTallest() }

        buildBars(view.findViewById(R.id.progressBars))
        buildSteps(view.findViewById(R.id.stepRow))
        view.findViewById<View>(R.id.actPrimary).apply {
            CraftMovement.pressScale(this)
            setOnClickListener { startChain() }
        }
        view.findViewById<View>(R.id.actSkip).setOnClickListener { skipAll() }

        val basePadding = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            v.updatePadding(bottom = basePadding + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }

        handlePageVisible(shownIdx, animate = false)
        ctx.recordKeyEvent("PermissionSheet_Show")
        return view
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.let { d ->
            d.behavior.skipCollapsed = true
            d.behavior.state = BottomSheetBehavior.STATE_EXPANDED

            d.window?.setDimAmount(0.55f)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::pages.isInitialized || pages.isEmpty()) return

        val ctx = context ?: return
        for (i in pageStates.indices) promoteIfGranted(ctx, i)
        refreshUi(animate = false)
        if (chainWaiting && chainActive) {
            chainWaiting = false
            root?.postDelayed(resumeChain, RESULT_HOLD_MS)
        }
    }

    override fun onDestroyView() {
        root?.removeCallbacks(resumeChain)
        slideAnim?.cancel()
        if (::folioViews.isInitialized) {
            folioViews.forEachIndexed { i, v -> DrawerDrawings.stop(pages[i], v, liveAnims.getOrPut(i) { mutableListOf() }) }
        }
        pager = null
        root = null
        super.onDestroyView()
    }

    private fun buildFolioView(inflater: LayoutInflater, page: DrawerFolio): View {
        val v = inflater.inflate(R.layout.pmt_page, null, false)
        v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val host = v.findViewById<FrameLayout>(R.id.lv_illHost)
        val ill = inflater.inflate(page.illustration, host, false)
        host.addView(ill, 0)

        val radius = v.context.dp(24f)
        host.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        host.clipToOutline = true
        DrawerDrawings.bind(page, ill)
        return v
    }

    private fun matchPagerToTallest() {
        val pager = pager ?: return
        if (pager.width == 0) return
        val wSpec = View.MeasureSpec.makeMeasureSpec(pager.width, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val tallest = folioViews.maxOfOrNull { it.measure(wSpec, hSpec); it.measuredHeight } ?: return
        if (tallest > 0 && pager.layoutParams.height != tallest) {
            pager.layoutParams = pager.layoutParams.apply { height = tallest }
        }
    }

    private class FolioAdapter(private val views: List<View>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = position
        override fun getItemCount() = views.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(views[viewType]) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private fun handlePageVisible(position: Int, animate: Boolean = true) {
        if (position !in pages.indices) return
        val ctx = context ?: return

        folioViews.forEachIndexed { i, v ->
            if (i != position) DrawerDrawings.stop(pages[i], v, liveAnims.getOrPut(i) { mutableListOf() })
        }
        val list = liveAnims.getOrPut(position) { mutableListOf() }
        if (list.isEmpty()) list += DrawerDrawings.start(pages[position], folioViews[position])

        shownIdx = position
        promoteIfGranted(ctx, position)
        refreshUi(animate)
        if (animate && !CraftMovement.animationsDisabled(ctx)) fadeInCopy()
    }

    private fun fadeInCopy() {
        val view = root ?: return
        listOf<View>(view.findViewById(R.id.txtTitle), view.findViewById(R.id.txtBody)).forEach { t ->
            t.alpha = 0f
            t.translationY = view.context.dp(6f)
            t.animate().alpha(1f).translationY(0f).setDuration(220L).start()
        }
    }

    private fun slideTo(target: Int) {
        val pager = pager ?: return
        if (target == pager.currentItem || target !in pages.indices) return
        slideAnim?.cancel()
        val ctx = pager.context
        val canFakeDrag = !CraftMovement.animationsDisabled(ctx) &&
            pager.layoutDirection == View.LAYOUT_DIRECTION_LTR && pager.width > 0
        if (!canFakeDrag || !pager.beginFakeDrag()) {
            pager.setCurrentItem(target, !CraftMovement.animationsDisabled(ctx))
            return
        }
        val distance = (target - pager.currentItem) * pager.width.toFloat()
        var dragged = 0f
        slideAnim = ValueAnimator.ofFloat(0f, distance).apply {
            duration = SLIDE_MS
            interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
            addUpdateListener {
                val v = it.animatedValue as Float
                if (pager.isFakeDragging) pager.fakeDragBy(-(v - dragged))
                dragged = v
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (pager.isFakeDragging) pager.endFakeDrag()
                }
            })
            start()
        }
    }

    private fun promoteIfGranted(ctx: android.content.Context, index: Int) {
        if (pageStates[index] != PageState.IDLE) return
        if (pages[index].isGranted(ctx)) pageStates[index] = PageState.ALREADY
    }

    private fun stillAskable(index: Int): Boolean {
        val ctx = context ?: return false
        if (pages[index].isGranted(ctx)) return false
        return !blockedForever(index)
    }

    private fun nextAskable(after: Int): Int? {
        var idx = after + 1
        while (idx < pages.size) {
            if (stillAskable(idx)) return idx
            idx++
        }
        return null
    }

    private fun startChain() {
        if (chainActive) return
        if (pages.isEmpty()) return finishSheet()
        chainActive = true
        pager?.isUserInputEnabled = false
        context?.recordKeyEvent("PermissionSheet_AllowAll")
        stepChain(after = -1)
    }

    private fun stepChain(after: Int) {
        root?.removeCallbacks(resumeChain)
        if (!isAdded) return
        if (!isResumed) return run { chainWaiting = true; lastPrompted = after }
        val next = nextAskable(after) ?: return wrapUpChain()
        lastPrompted = next
        if (next == shownIdx) return promptFor(next)
        slideTo(next)

        root?.postDelayed({
            if (!isAdded || !chainActive) return@postDelayed
            // app left mid-slide: ask on return, never over the launcher
            if (isResumed) promptFor(next) else { chainWaiting = true; lastPrompted = next - 1 }
        }, SLIDE_MS + 80L)
    }

    private fun wrapUpChain() {
        context?.let { ctx ->
            if (pages.all { it.isGranted(ctx) }) ctx.recordKeyEvent("PermissionSheet_AllDone")
        }
        finishSheet()
    }

    private fun skipAll() {
        context?.recordKeyEvent("PermissionSheet_Skip")
        finishSheet()
    }

    private fun promptFor(index: Int) {
        val act = activity ?: return
        val page = pages[index]
        pageStates[index] = PageState.REQUESTING
        promptingIdx = index
        refreshUi(animate = true)
        if (page == DrawerFolio.NOTIFICATIONS) {
            ConsentDriver.request(act, page.key) { if (isAdded) afterPrompt() }
            return
        }
        val vault = LockerBook(act)
        page.permissions.forEach { vault.flagPermissionRequested(it) }
        act.recordKeyEvent("PermissionSheet_Ask_${page.key}")
        runCatching { permLauncher.launch(page.permissions) }.onFailure {
            WardenRail.error(TAG, "Permission request failed for ${page.key}", it)
            afterPrompt()
        }
    }

    private fun afterPrompt() {
        val i = promptingIdx
        promptingIdx = -1
        val ctx = context ?: return
        if (i !in pages.indices) return
        val granted = pages[i].isGranted(ctx)
        pageStates[i] = when (granted) {
            true -> PageState.ALLOWED
            false -> PageState.DENIED
        }
        if (i == shownIdx) {
            refreshUi(animate = true)
            if (granted) {
                bounceBadge(folioViews[i].findViewById(R.id.lv_checkBadge))
                bounceStepTick(i)
            }
        }

        root?.removeCallbacks(resumeChain)
        root?.postDelayed(resumeChain, RESULT_HOLD_MS)
    }

    private fun blockedForever(index: Int): Boolean {
        val act = activity ?: return false
        val perm = pages[index].primaryPermission ?: return false
        return isPermanentlyDenied(act, pages[index].key, perm)
    }

    private fun refreshUi(animate: Boolean) {
        val view = root ?: return
        val ctx = view.context
        if (pages.isEmpty()) return
        val i = shownIdx
        val page = pages[i]
        val status = pageStates[i]

        view.findViewById<TextView>(R.id.txtTitle).setText(page.title)
        view.findViewById<TextView>(R.id.txtBody).setText(page.body)
        view.findViewById<View>(R.id.actSkip).visibility = when {
            !ConsentFeed.skipVisible() -> View.GONE
            chainActive -> View.INVISIBLE
            else -> View.VISIBLE
        }
        paintBars(view.findViewById(R.id.progressBars))
        paintSteps(view.findViewById(R.id.stepRow))

        folioViews.forEachIndexed { idx, pv ->
            val badge = pv.findViewById<View>(R.id.lv_checkBadge)
            when (pageStates[idx]) {
                PageState.ALLOWED, PageState.ALREADY -> if (badge.visibility != View.VISIBLE) {
                    badge.visibility = View.VISIBLE
                    badge.scaleX = 1f
                    badge.scaleY = 1f
                }
                else -> badge.visibility = View.INVISIBLE
            }
        }

        val granted = status == PageState.ALLOWED || status == PageState.ALREADY
        drawPrimary(
            label = when {
                chainActive && !granted -> getString(R.string.ps_requesting)
                granted -> getString(R.string.ps_allowed)
                else -> getString(R.string.ps_allow_fmt, getString(page.shortName))
            },
            spinner = chainActive && !granted,
            done = granted,
            animate = animate && !CraftMovement.animationsDisabled(ctx),
        )
    }

    private fun drawPrimary(label: String, spinner: Boolean, done: Boolean, animate: Boolean) {
        val view = root ?: return
        val btn = view.findViewById<View>(R.id.actPrimary)
        val content = view.findViewById<View>(R.id.lv_primaryContent)
        val tv = view.findViewById<TextView>(R.id.txtPrimary)
        val apply = {
            tv.text = label
            view.findViewById<ProgressBar>(R.id.spinPrimary).isVisible = spinner
            view.findViewById<ImageView>(R.id.imgPrimary).isVisible = done
            btn.setBackgroundResource(if (done) R.drawable.pmt_bg_btn_ok else R.drawable.pmt_bg_btn_gradient)
        }

        btn.isEnabled = !spinner
        btn.alpha = if (spinner) 0.85f else 1f
        if (!animate || tv.text.isNullOrEmpty() || tv.text == label) {
            content.animate().cancel()
            content.alpha = 1f
            apply()
            return
        }

        content.animate().alpha(0f).setDuration(100L).withEndAction {
            apply()
            content.animate().alpha(1f).setDuration(120L).start()
        }.start()
    }

    private fun buildBars(container: LinearLayout) {
        val ctx = container.context
        container.removeAllViews()
        pages.indices.forEach { i ->
            container.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = ctx.dp(6f).toInt()
                }
            })
        }
    }

    private fun paintBars(container: LinearLayout) {
        for (i in 0 until container.childCount) {
            val filled = i <= shownIdx || pageStates[i] == PageState.ALLOWED || pageStates[i] == PageState.ALREADY
            container.getChildAt(i).setBackgroundResource(
                if (filled) R.drawable.pmt_bg_gradient_r3 else R.drawable.pmt_bg_bar_idle,
            )
        }
    }

    private fun buildSteps(container: LinearLayout) {
        val ctx = container.context
        container.removeAllViews()
        val tile = ctx.dp(40f).toInt()
        pages.forEachIndexed { i, page ->
            val cell = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(tile, tile).apply {
                    if (i > 0) marginStart = ctx.dp(10f).toInt()
                }
            }
            cell.addView(ImageView(ctx).apply {
                id = R.id.stepIcon
                layoutParams = FrameLayout.LayoutParams(ctx.dp(18f).toInt(), ctx.dp(18f).toInt(), Gravity.CENTER)
                setImageResource(page.stepIcon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            val tickSize = ctx.dp(15f).toInt()
            cell.addView(FrameLayout(ctx).apply {
                id = R.id.stepTick
                layoutParams = FrameLayout.LayoutParams(tickSize, tickSize, Gravity.END or Gravity.BOTTOM)
                background = ContextCompat.getDrawable(ctx, R.drawable.pmt_bg_step_tick)
                elevation = ctx.dp(2f)
                visibility = View.GONE
                addView(ImageView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(ctx.dp(8f).toInt(), ctx.dp(8f).toInt(), Gravity.CENTER)
                    setImageResource(R.drawable.pmt_ic_check)
                    imageTintList = ContextCompat.getColorStateList(ctx, android.R.color.white)
                })
            })
            container.addView(cell)
        }
    }

    private fun paintSteps(container: LinearLayout) {
        val ctx = container.context
        for (i in 0 until container.childCount) {
            val cell = container.getChildAt(i) as FrameLayout
            val icon = cell.findViewById<ImageView>(R.id.stepIcon)
            val tick = cell.findViewById<View>(R.id.stepTick)
            val done = pageStates[i] == PageState.ALLOWED || pageStates[i] == PageState.ALREADY
            val (bg, tint) = when {
                done -> R.drawable.pmt_bg_step_done to R.color.ps_success
                i == shownIdx -> R.drawable.pmt_bg_step_now to android.R.color.white
                else -> R.drawable.pmt_bg_step_idle to R.color.ps_dot_past
            }
            cell.setBackgroundResource(bg)
            icon.imageTintList = ContextCompat.getColorStateList(ctx, tint)
            if (!done) tick.visibility = View.GONE
            else if (tick.visibility != View.VISIBLE) { tick.visibility = View.VISIBLE; tick.scaleX = 1f; tick.scaleY = 1f }
        }
    }

    private fun bounceStepTick(index: Int) {
        val row = root?.findViewById<LinearLayout>(R.id.stepRow) ?: return
        val tick = (row.getChildAt(index) as? FrameLayout)?.findViewById<View>(R.id.stepTick) ?: return
        tick.visibility = View.VISIBLE
        if (CraftMovement.animationsDisabled(tick.context)) return
        tick.scaleX = 0f
        tick.scaleY = 0f
        tick.animate().scaleX(1f).scaleY(1f).setDuration(420L).setInterpolator(OvershootInterpolator(3f)).start()
    }

    private fun bounceBadge(badge: View) {
        badge.visibility = View.VISIBLE
        if (CraftMovement.animationsDisabled(badge.context)) return
        badge.scaleX = 0f
        badge.scaleY = 0f
        badge.animate().scaleX(1f).scaleY(1f).setDuration(420L).setInterpolator(OvershootInterpolator(3f)).start()
    }

    private fun fireFinishedOnce() {
        val cb = onFinished
        cb?.invoke()
        onFinished = null
    }

    private fun finishSheet() {
        fireFinishedOnce()
        runCatching { dismissAllowingStateLoss() }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        fireFinishedOnce()
    }

    companion object {
        const val TAG = "permission_sheet"
        private const val RESULT_HOLD_MS = 600L
        private const val SLIDE_MS = 450L

        fun isShowing(activity: FragmentActivity): Boolean =
            activity.supportFragmentManager.findFragmentByTag(TAG) != null

        fun retireIfShowing(activity: FragmentActivity) {
            val fm = activity.supportFragmentManager
            (fm.findFragmentByTag(TAG) as? ConsentDrawerModal)
                ?.let { runCatching { it.dismissAllowingStateLoss() } }
        }

        @JvmStatic
        fun hasPending(activity: FragmentActivity): Boolean {
            for (page in DrawerFolio.all(activity)) {
                val perm = page.primaryPermission ?: continue
                if (page.isGranted(activity)) continue
                if (!isPermanentlyDenied(activity, page.key, perm)) return true
            }
            return false
        }

        @JvmStatic
        fun isPermanentlyDenied(activity: FragmentActivity, key: String, perm: String): Boolean {
            val granted = ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
            return when {
                granted -> false
                !(ConsentLocker(activity).wasAsked(key) || LockerBook(activity).hasRequestedPermission(perm)) -> false
                else -> !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            }
        }

        @JvmStatic
        fun mustAutoShow(activity: FragmentActivity): Boolean =
            hasPending(activity) && OpeningUnveilCharter.mustShowPermissionSheet(activity)

        @JvmStatic
        @JvmOverloads
        fun show(activity: FragmentActivity, onFinished: (() -> Unit)? = null) {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return

            OpeningUnveilCharter.flagShown(activity, OpeningUnveilProfile.PERMISSION_SHEET)
            ConsentDrawerModal().apply { this.onFinished = onFinished }
                .show(fm, TAG)
        }
    }
}
