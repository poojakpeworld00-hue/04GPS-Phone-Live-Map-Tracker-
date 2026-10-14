package com.find.phone.gps.live.location.tracker.consent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Outline
import android.os.Bundle
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
    private val dotAnims = mutableListOf<ValueAnimator>()

    private var chainActive = false
    private var lastPrompted = -1
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

        buildDots(view.findViewById(R.id.marks))
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
    }

    override fun onDestroyView() {
        root?.removeCallbacks(resumeChain)
        slideAnim?.cancel()
        dotAnims.forEach { it.cancel() }
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
        v.findViewById<TextView>(R.id.txtTitle).setText(page.title)
        v.findViewById<TextView>(R.id.txtBody).setText(page.body)
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
        val next = nextAskable(after) ?: return wrapUpChain()
        lastPrompted = next
        if (next == shownIdx) return promptFor(next)
        slideTo(next)

        root?.postDelayed({ if (isAdded && chainActive) promptFor(next) }, SLIDE_MS + 80L)
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
            if (granted) bounceBadge(folioViews[i].findViewById(R.id.lv_checkBadge))
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

        view.findViewById<TextView>(R.id.txtCounterCurrent).text = (i + 1).toString()
        view.findViewById<TextView>(R.id.txtCounterTotal).text = getString(R.string.ps_counter_total, pages.size)
        view.findViewById<View>(R.id.actSkip).visibility = if (chainActive) View.INVISIBLE else View.VISIBLE
        drawDots(view.findViewById(R.id.marks), animate)

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

        val name = getString(page.shortName)
        when (status) {
            PageState.IDLE, PageState.REQUESTING -> showStatusChip(null)
            PageState.ALLOWED -> showStatusChip(
                BadgeTone.SUCCESS,
                getString(if (nextAskable(i) != null) R.string.ps_allowed_moving_on else R.string.ps_allowed_done, name),
            )
            PageState.DENIED -> showStatusChip(BadgeTone.WARNING, getString(R.string.ps_not_allowed, name))
            PageState.ALREADY -> showStatusChip(BadgeTone.INFO, getString(R.string.ps_already_allowed))
        }

        drawPrimary(
            label = getString(if (chainActive) R.string.ps_requesting else R.string.ps_allow),
            spinner = chainActive,
            animate = animate && !CraftMovement.animationsDisabled(ctx),
        )
    }

    private enum class BadgeTone(val bg: Int, val icon: Int, val iconTint: Int, val text: Int) {
        SUCCESS(R.drawable.pmt_bg_status_success, R.drawable.pmt_ic_check, R.color.ps_success, R.color.ps_success_ink),
        WARNING(R.drawable.pmt_bg_status_warning, R.drawable.pmt_ic_warning, R.color.ps_warning, R.color.ps_warning_ink),
        INFO(R.drawable.pmt_bg_status_info, R.drawable.pmt_ic_check, R.color.ps_accent_ink, R.color.ps_info_ink),
    }

    private fun showStatusChip(kind: BadgeTone?, text: String? = null) {
        val view = root ?: return
        val chip = view.findViewById<LinearLayout>(R.id.condChip)
        if (kind == null || text == null) { chip.isVisible = false; return }
        val ctx = view.context
        chip.setBackgroundResource(kind.bg)
        view.findViewById<ImageView>(R.id.imgStatus).apply {
            setImageResource(kind.icon)
            imageTintList = ContextCompat.getColorStateList(ctx, kind.iconTint)
        }
        view.findViewById<TextView>(R.id.txtStatus).apply {
            setTextColor(ContextCompat.getColor(ctx, kind.text))
            this.text = text
        }
        if (!chip.isVisible) {
            chip.isVisible = true
            if (!CraftMovement.animationsDisabled(ctx)) {
                chip.alpha = 0f
                chip.translationY = ctx.dp(6f)
                chip.animate().alpha(1f).translationY(0f).setDuration(200L).start()
            }
        }
    }

    private fun drawPrimary(label: String, spinner: Boolean, animate: Boolean) {
        val view = root ?: return
        val btn = view.findViewById<View>(R.id.actPrimary)
        val content = view.findViewById<View>(R.id.lv_primaryContent)
        val tv = view.findViewById<TextView>(R.id.txtPrimary)
        val apply = {
            tv.text = label
            view.findViewById<ProgressBar>(R.id.spinPrimary).isVisible = spinner
        }

        btn.isEnabled = !spinner
        btn.alpha = if (spinner) 0.8f else 1f
        if (!animate || tv.text.isNullOrEmpty() || tv.text == label) {
            content.animate().cancel()
            content.alpha = 1f
            apply()
            return
        }

        content.animate().alpha(0f).setDuration(100L).withEndAction {
            apply()
            content.animate().alpha(1f).setDuration(100L).start()
        }.start()
    }

    private fun buildDots(container: LinearLayout) {
        val ctx = container.context
        container.removeAllViews()
        pages.indices.forEach { i ->
            container.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(6f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    if (i > 0) marginStart = ctx.dp(6f).toInt()
                }
            })
        }
    }

    private fun drawDots(container: LinearLayout, animate: Boolean) {
        val ctx = container.context
        val small = ctx.dp(6f).toInt()
        val wide = ctx.dp(22f).toInt()
        dotAnims.forEach { it.cancel() }
        dotAnims.clear()
        for (i in 0 until container.childCount) {
            val dot = container.getChildAt(i)
            if (i == shownIdx) {
                dot.setBackgroundResource(R.drawable.pmt_bg_gradient_r3)
                dot.backgroundTintList = null
            } else {
                dot.setBackgroundResource(R.drawable.pmt_bg_dot)
                dot.backgroundTintList = ContextCompat.getColorStateList(
                    ctx, if (i < shownIdx) R.color.ps_dot_past else R.color.ps_dot_future,
                )
            }
            val target = if (i == shownIdx) wide else small
            val from = dot.layoutParams.width
            if (from == target) continue
            if (!animate || CraftMovement.animationsDisabled(ctx)) {
                dot.layoutParams = dot.layoutParams.apply { width = target }
                continue
            }
            dotAnims += ValueAnimator.ofInt(from, target).apply {
                duration = 250L
                interpolator = CraftMovement.EASE
                addUpdateListener { a -> dot.layoutParams = dot.layoutParams.apply { width = a.animatedValue as Int } }
                start()
            }
        }
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
