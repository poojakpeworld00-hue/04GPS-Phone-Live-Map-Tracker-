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

/**
 * Bottom sheet that walks the user through the permission pages (Notifications, SIM info,
 * Contacts, Location) in a non-swipeable-while-running pager. A single Allow tap asks every page
 * that still can be asked, showing each answer for a moment before sliding on; Skip or a
 * swipe-down closes it. [onFinished] fires exactly once. Notifications are routed through
 * [ConsentDriver]; the others are requested directly and stamped in [LockerBook].
 */
class ConsentDrawerModal : BottomSheetDialogFragment() {

    /** Invoked once when the sheet finishes (sequence done, Skip, or dismiss). */
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
    /** True from the Allow tap until every page has been asked. */
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
        // Size the pager to its tallest page, so the sheet doesn't jump between pages.
        pager.post { matchPagerToTallest() }

        buildDots(view.findViewById(R.id.marks))
        view.findViewById<View>(R.id.actPrimary).apply {
            CraftMovement.pressScale(this)
            setOnClickListener { startChain() }
        }
        view.findViewById<View>(R.id.actSkip).setOnClickListener { skipAll() }

        // 22dp + the navigation bar under the sheet.
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
            // Scrim #080E1E at 55%.
            d.window?.setDimAmount(0.55f)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::pages.isInitialized || pages.isEmpty()) return
        // Granted elsewhere while we were away.
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

    // ─────────────────────────── Pages ───────────────────────────

    private fun buildFolioView(inflater: LayoutInflater, page: DrawerFolio): View {
        val v = inflater.inflate(R.layout.pmt_page, null, false)
        v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        v.findViewById<TextView>(R.id.txtTitle).setText(page.title)
        v.findViewById<TextView>(R.id.txtBody).setText(page.body)
        val host = v.findViewById<FrameLayout>(R.id.lv_illHost)
        val ill = inflater.inflate(page.illustration, host, false)
        host.addView(ill, 0)
        // 24dp rounded illustration; everything inside is clipped to it.
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
        // One view type per page: every page owns exactly one pre-inflated view.
        override fun getItemViewType(position: Int) = position
        override fun getItemCount() = views.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(views[viewType]) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private fun handlePageVisible(position: Int, animate: Boolean = true) {
        if (position !in pages.indices) return
        val ctx = context ?: return
        // Only the page on screen animates.
        folioViews.forEachIndexed { i, v ->
            if (i != position) DrawerDrawings.stop(pages[i], v, liveAnims.getOrPut(i) { mutableListOf() })
        }
        val list = liveAnims.getOrPut(position) { mutableListOf() }
        if (list.isEmpty()) list += DrawerDrawings.start(pages[position], folioViews[position])

        shownIdx = position
        promoteIfGranted(ctx, position)
        refreshUi(animate)
    }

    /** Moves the pager to [target] with the design's 450 ms cubic-bezier(.2,.8,.2,1) slide. */
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

    /** True when [index] still needs an Android prompt: not granted and Android will still show one. */
    private fun promoteIfGranted(ctx: android.content.Context, index: Int) {
        if (pageStates[index] != PageState.IDLE) return
        if (pages[index].isGranted(ctx)) pageStates[index] = PageState.ALREADY
    }

    private fun stillAskable(index: Int): Boolean {
        val ctx = context ?: return false
        if (pages[index].isGranted(ctx)) return false
        return !blockedForever(index)
    }

    /** The next page after [after] that still needs asking; null when the sequence is complete. */
    private fun nextAskable(after: Int): Int? {
        var idx = after + 1
        while (idx < pages.size) {
            if (stillAskable(idx)) return idx
            idx++
        }
        return null
    }

    // ─────────────────────────── Sequence ───────────────────────────

    private fun startChain() {
        if (chainActive) return
        if (pages.isEmpty()) return finishSheet()
        chainActive = true
        pager?.isUserInputEnabled = false
        context?.recordKeyEvent("PermissionSheet_AllowAll")
        stepChain(after = -1)
    }

    /** Slides to the next page still to ask and opens its prompt; closes the sheet when none is left. */
    private fun stepChain(after: Int) {
        root?.removeCallbacks(resumeChain)
        if (!isAdded) return
        val next = nextAskable(after) ?: return wrapUpChain()
        lastPrompted = next
        if (next == shownIdx) return promptFor(next)
        slideTo(next)
        // Let the slide (450 ms) land before the Android prompt covers the sheet.
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
            // RC-driven: the engine logs, stamps its ledger and honours the remote switch.
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
        // Show the answer for 600 ms, then on to the next permission.
        root?.removeCallbacks(resumeChain)
        root?.postDelayed(resumeChain, RESULT_HOLD_MS)
    }

    private fun blockedForever(index: Int): Boolean {
        val act = activity ?: return false
        val perm = pages[index].primaryPermission ?: return false
        return isPermanentlyDenied(act, pages[index].key, perm)
    }

    // ─────────────────────────── Rendering ───────────────────────────

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

        // Check badge on the illustration: shown for allowed pages (popped by onGranted).
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

        // One button for the whole sequence: "Allow", or a spinner while it runs.
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
        // Spinner + dimmed while the Android prompt is open.
        btn.isEnabled = !spinner
        btn.alpha = if (spinner) 0.8f else 1f
        if (!animate || tv.text.isNullOrEmpty() || tv.text == label) {
            content.animate().cancel()
            content.alpha = 1f
            apply()
            return
        }
        // Label crossfade, 200 ms.
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

    /** Past dots tinted, the active one a 22dp gradient pill (width animated, 250 ms), the rest grey. */
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

    /** Granted check: springs in with an overshoot (dampingRatio ≈ 0.5). */
    private fun bounceBadge(badge: View) {
        badge.visibility = View.VISIBLE
        if (CraftMovement.animationsDisabled(badge.context)) return
        badge.scaleX = 0f
        badge.scaleY = 0f
        badge.animate().scaleX(1f).scaleY(1f).setDuration(420L).setInterpolator(OvershootInterpolator(3f)).start()
    }

    // ─────────────────────────── Finish ───────────────────────────

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

        /** True while the sheet is on screen. */
        fun isShowing(activity: FragmentActivity): Boolean =
            activity.supportFragmentManager.findFragmentByTag(TAG) != null

        /**
         * Takes the sheet down. A no-op when it is not showing.
         *
         * The sheet is committed to the **Activity's** fragment manager, not to whatever view
         * it was raised over, so on the launcher it outlives the caller panel that triggered
         * it: close the panel and the sheet stays in front of the launcher's app grid. Callers
         * pair this with re-arming the sheet so the user is still asked next time the panel opens.
         */
        fun retireIfShowing(activity: FragmentActivity) {
            val fm = activity.supportFragmentManager
            (fm.findFragmentByTag(TAG) as? ConsentDrawerModal)
                ?.let { runCatching { it.dismissAllowingStateLoss() } }
        }

        /**
         * True when at least one page still needs asking: not granted and not permanently
         * denied (Android would no longer show its prompt). Use it to decide whether to trigger
         * the sheet at all.
         */
        @JvmStatic
        fun hasPending(activity: FragmentActivity): Boolean {
            for (page in DrawerFolio.all(activity)) {
                val perm = page.primaryPermission ?: continue
                if (page.isGranted(activity)) continue
                if (!isPermanentlyDenied(activity, page.key, perm)) return true
            }
            return false
        }

        /**
         * True once the user has denied [perm] to the point Android no longer
         * shows its system dialog — i.e. it was requested at least once (from any
         * screen: the engine records it in [ConsentLocker], the sheet and the Home quick
         * actions in [LockerBook]) and `shouldShowRequestPermissionRationale` is
         * now false while still ungranted. On Android 11+ this is reached after
         * the 2nd decline.
         */
        @JvmStatic
        fun isPermanentlyDenied(activity: FragmentActivity, key: String, perm: String): Boolean {
            val granted = ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
            return when {
                granted -> false
                !(ConsentLocker(activity).wasAsked(key) || LockerBook(activity).hasRequestedPermission(perm)) -> false
                else -> !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            }
        }

        /**
         * Decides whether the sheet should pop up **automatically** on app launch.
         * Combines [hasPending] with the shared [OpeningUnveilCharter] frequency gate,
         * driven by the `permission_sheet` entry of the `intro_display` Remote Config
         * block (`enabled` + `prompt_frequency` = always | once | every_days |
         * app_launches | never, with `prompt_interval`). The ledger is stamped by
         * [OpeningUnveilCharter.flagShown] in [show]. This gate is for the
         * **auto-launch only** — a manual "Manage" tap calls [show] directly.
         */
        @JvmStatic
        fun mustAutoShow(activity: FragmentActivity): Boolean =
            hasPending(activity) && OpeningUnveilCharter.mustShowPermissionSheet(activity)

        /**
         * Shows the permission sheet on demand. Call from any click listener:
         *
         * ```
         * someButton.setOnClickListener { ConsentDrawerModal.show(this) }
         * ```
         *
         * Safe to call repeatedly — it no-ops if the sheet is already showing or
         * the host isn't in a valid state to commit a transaction. [onFinished]
         * runs once when the sheet closes.
         */
        @JvmStatic
        @JvmOverloads
        fun show(activity: FragmentActivity, onFinished: (() -> Unit)? = null) {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            // Stamp the shared ledger so the frequency gate (once / every_days /
            // app_launches) can measure from here.
            OpeningUnveilCharter.flagShown(activity, OpeningUnveilProfile.PERMISSION_SHEET)
            ConsentDrawerModal().apply { this.onFinished = onFinished }
                .show(fm, TAG)
        }
    }
}
