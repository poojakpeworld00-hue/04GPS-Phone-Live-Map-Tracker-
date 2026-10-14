package com.find.my.phone.gps.location.tracker.permission

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
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.adcast.domain.logPermissionResult
import com.find.my.phone.gps.location.tracker.data.VaultRegistry
import com.find.my.phone.gps.location.tracker.permission.sheet.SheetIllustrations
import com.find.my.phone.gps.location.tracker.permission.sheet.SheetPage
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion.dp
import com.find.my.phone.gps.location.tracker.ui.intro.IntroRevealConfig
import com.find.my.phone.gps.location.tracker.ui.intro.IntroRevealPolicy
import com.find.my.phone.gps.location.tracker.util.GuardRail
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * The permission onboarding sheet (Claude Design "Permission Sheet"): one illustrated page per
 * permission — Notifications → SIM info → Contacts → Location — in a swipeable pager over Home.
 *
 * One Allow button runs the whole sequence: it slides to each page that still needs asking, opens
 * that page's Android prompt, shows the answer (a check pops and a green note for allowed, an amber
 * note for not allowed) and after 600 ms moves on to the next. When every page has been asked the
 * sheet closes. Pages already granted, and ones Android will no longer prompt for, are skipped;
 * the pager can't be swiped while the sequence runs.
 *
 * Skip, or dragging the sheet down, closes it without asking. Either way [onFinished] runs once.
 *
 * Notifications go through [AccessEngine] so its Remote Config switch and ledger keep working;
 * the rest are asked directly and stamped in [VaultRegistry] for the permanent-denial check.
 */
class AccessSheetDialog : BottomSheetDialogFragment() {

    /** Invoked once when the sheet finishes (sequence done, Skip, or dismiss). */
    var onFinished: (() -> Unit)? = null

    private enum class Status { IDLE, REQUESTING, ALLOWED, DENIED, ALREADY }

    private lateinit var pages: List<SheetPage>
    private lateinit var statuses: Array<Status>
    private lateinit var pageViews: List<View>
    private val running = mutableMapOf<Int, MutableList<Animator>>()

    private var root: View? = null
    private var pager: ViewPager2? = null
    private var current = 0
    private var requestingIndex = -1
    private var pageAnimator: ValueAnimator? = null
    private val dotAnimators = mutableListOf<ValueAnimator>()
    /** True from the Allow tap until every page has been asked. */
    private var sequenceRunning = false
    private var lastAsked = -1
    private val continueSequence = Runnable { askNext(after = lastAsked) }

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result.forEach { (perm, granted) -> context?.logPermissionResult(perm, granted) }
        onRequestResult()
    }

    override fun getTheme(): Int = R.style.Ds_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val view = inflater.inflate(R.layout.modal_permission_sheet, container, false)
        root = view

        pages = SheetPage.all(ctx)
        statuses = Array(pages.size) { if (pages[it].isGranted(ctx)) Status.ALREADY else Status.IDLE }
        current = pages.indices.firstOrNull { !pages[it].isGranted(ctx) } ?: 0
        pageViews = pages.map { buildPage(inflater, it) }

        val pager = view.findViewById<ViewPager2>(R.id.pager)
        this.pager = pager
        pager.adapter = PagesAdapter(pageViews)
        pager.offscreenPageLimit = pages.size.coerceAtLeast(1)
        pager.setCurrentItem(current, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = onPageShown(position)
        })
        // Size the pager to its tallest page, so the sheet doesn't jump between pages.
        pager.post { fitPagerHeight() }

        buildDots(view.findViewById(R.id.dots))
        view.findViewById<View>(R.id.btnPrimary).apply {
            DesignMotion.pressScale(this)
            setOnClickListener { onPrimaryClicked() }
        }
        view.findViewById<View>(R.id.btnSkip).setOnClickListener { onSkipClicked() }

        // 22dp + the navigation bar under the sheet.
        val basePadding = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            v.updatePadding(bottom = basePadding + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }

        onPageShown(current, animate = false)
        ctx.logKeyEvent("PermissionSheet_Show")
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
        statuses.indices.forEach { i ->
            if (statuses[i] == Status.IDLE && pages[i].isGranted(ctx)) statuses[i] = Status.ALREADY
        }
        render(animate = false)
    }

    override fun onDestroyView() {
        root?.removeCallbacks(continueSequence)
        pageAnimator?.cancel()
        dotAnimators.forEach { it.cancel() }
        if (::pageViews.isInitialized) {
            pageViews.forEachIndexed { i, v -> SheetIllustrations.stop(pages[i], v, running.getOrPut(i) { mutableListOf() }) }
        }
        pager = null
        root = null
        super.onDestroyView()
    }

    // ─────────────────────────── Pages ───────────────────────────

    private fun buildPage(inflater: LayoutInflater, page: SheetPage): View {
        val v = inflater.inflate(R.layout.ps_page, null, false)
        v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        v.findViewById<TextView>(R.id.tvTitle).setText(page.title)
        v.findViewById<TextView>(R.id.tvBody).setText(page.body)
        val host = v.findViewById<FrameLayout>(R.id.illHost)
        val ill = inflater.inflate(page.illustration, host, false)
        host.addView(ill, 0)
        // 24dp rounded illustration; everything inside is clipped to it.
        val radius = v.context.dp(24f)
        host.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        host.clipToOutline = true
        SheetIllustrations.bind(page, ill)
        return v
    }

    private fun fitPagerHeight() {
        val pager = pager ?: return
        if (pager.width == 0) return
        val wSpec = View.MeasureSpec.makeMeasureSpec(pager.width, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val tallest = pageViews.maxOfOrNull { it.measure(wSpec, hSpec); it.measuredHeight } ?: return
        if (tallest > 0 && pager.layoutParams.height != tallest) {
            pager.layoutParams = pager.layoutParams.apply { height = tallest }
        }
    }

    private class PagesAdapter(private val views: List<View>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        // One view type per page: every page owns exactly one pre-inflated view.
        override fun getItemViewType(position: Int) = position
        override fun getItemCount() = views.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(views[viewType]) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private fun onPageShown(position: Int, animate: Boolean = true) {
        if (position !in pages.indices) return
        val ctx = context ?: return
        // Only the page on screen animates.
        pageViews.forEachIndexed { i, v ->
            if (i != position) SheetIllustrations.stop(pages[i], v, running.getOrPut(i) { mutableListOf() })
        }
        val list = running.getOrPut(position) { mutableListOf() }
        if (list.isEmpty()) list += SheetIllustrations.start(pages[position], pageViews[position])

        current = position
        if (statuses[position] == Status.IDLE && pages[position].isGranted(ctx)) statuses[position] = Status.ALREADY
        render(animate)
    }

    /** Moves the pager to [target] with the design's 450 ms cubic-bezier(.2,.8,.2,1) slide. */
    private fun goTo(target: Int) {
        val pager = pager ?: return
        if (target == pager.currentItem || target !in pages.indices) return
        pageAnimator?.cancel()
        val ctx = pager.context
        val canFakeDrag = !DesignMotion.animationsDisabled(ctx) &&
            pager.layoutDirection == View.LAYOUT_DIRECTION_LTR && pager.width > 0
        if (!canFakeDrag || !pager.beginFakeDrag()) {
            pager.setCurrentItem(target, !DesignMotion.animationsDisabled(ctx))
            return
        }
        val distance = (target - pager.currentItem) * pager.width.toFloat()
        var dragged = 0f
        pageAnimator = ValueAnimator.ofFloat(0f, distance).apply {
            duration = PAGE_MS
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
    private fun needsAsking(index: Int): Boolean {
        val ctx = context ?: return false
        return !pages[index].isGranted(ctx) && !isPagePermanentlyDenied(index)
    }

    /** The next page after [after] that still needs asking; null when the sequence is complete. */
    private fun nextToAsk(after: Int): Int? = (after + 1 until pages.size).firstOrNull { needsAsking(it) }

    // ─────────────────────────── Sequence ───────────────────────────

    private fun onPrimaryClicked() {
        if (sequenceRunning) return
        if (pages.isEmpty()) { finishFlow(); return }
        sequenceRunning = true
        pager?.isUserInputEnabled = false
        context?.logKeyEvent("PermissionSheet_AllowAll")
        askNext(after = -1)
    }

    /** Slides to the next page still to ask and opens its prompt; closes the sheet when none is left. */
    private fun askNext(after: Int) {
        root?.removeCallbacks(continueSequence)
        if (!isAdded) return
        val next = nextToAsk(after)
        if (next == null) {
            val ctx = context
            if (ctx != null && pages.all { it.isGranted(ctx) }) ctx.logKeyEvent("PermissionSheet_AllDone")
            finishFlow()
            return
        }
        lastAsked = next
        if (next == current) {
            request(next)
            return
        }
        goTo(next)
        // Let the slide (450 ms) land before the Android prompt covers the sheet.
        root?.postDelayed({ if (isAdded && sequenceRunning) request(next) }, PAGE_MS + 80L)
    }

    private fun onSkipClicked() {
        context?.logKeyEvent("PermissionSheet_Skip")
        finishFlow()
    }

    private fun request(index: Int) {
        val act = activity ?: return
        val page = pages[index]
        statuses[index] = Status.REQUESTING
        requestingIndex = index
        render(animate = true)
        if (page == SheetPage.NOTIFICATIONS) {
            // RC-driven: the engine logs, stamps its ledger and honours the remote switch.
            AccessEngine.request(act, page.key) { if (isAdded) onRequestResult() }
            return
        }
        val vault = VaultRegistry(act)
        page.permissions.forEach { vault.markPermissionRequested(it) }
        act.logKeyEvent("PermissionSheet_Ask_${page.key}")
        runCatching { requestPerms.launch(page.permissions) }.onFailure {
            GuardRail.error(TAG, "Permission request failed for ${page.key}", it)
            onRequestResult()
        }
    }

    private fun onRequestResult() {
        val i = requestingIndex
        requestingIndex = -1
        val ctx = context ?: return
        if (i !in pages.indices) return
        val granted = pages[i].isGranted(ctx)
        statuses[i] = if (granted) Status.ALLOWED else Status.DENIED
        if (i == current) {
            render(animate = true)
            if (granted) popCheck(pageViews[i].findViewById(R.id.checkBadge))
        }
        // Show the answer for 600 ms, then on to the next permission.
        root?.removeCallbacks(continueSequence)
        root?.postDelayed(continueSequence, ADVANCE_DELAY_MS)
    }

    private fun isPagePermanentlyDenied(index: Int): Boolean {
        val act = activity ?: return false
        val perm = pages[index].primaryPermission ?: return false
        return isPermanentlyDenied(act, pages[index].key, perm)
    }

    // ─────────────────────────── Rendering ───────────────────────────

    private fun render(animate: Boolean) {
        val view = root ?: return
        val ctx = view.context
        if (pages.isEmpty()) return
        val i = current
        val page = pages[i]
        val status = statuses[i]

        view.findViewById<TextView>(R.id.tvCounterCurrent).text = (i + 1).toString()
        view.findViewById<TextView>(R.id.tvCounterTotal).text = getString(R.string.ps_counter_total, pages.size)
        view.findViewById<View>(R.id.btnSkip).visibility = if (sequenceRunning) View.INVISIBLE else View.VISIBLE
        renderDots(view.findViewById(R.id.dots), animate)

        // Check badge on the illustration: shown for allowed pages (popped by onGranted).
        pageViews.forEachIndexed { idx, pv ->
            val badge = pv.findViewById<View>(R.id.checkBadge)
            val show = statuses[idx] == Status.ALLOWED || statuses[idx] == Status.ALREADY
            if (!show) badge.visibility = View.INVISIBLE
            else if (badge.visibility != View.VISIBLE) { badge.visibility = View.VISIBLE; badge.scaleX = 1f; badge.scaleY = 1f }
        }

        val name = getString(page.shortName)
        when (status) {
            Status.IDLE, Status.REQUESTING -> showChip(null)
            Status.ALLOWED -> showChip(
                Chip.SUCCESS,
                getString(if (nextToAsk(i) != null) R.string.ps_allowed_moving_on else R.string.ps_allowed_done, name),
            )
            Status.DENIED -> showChip(Chip.WARNING, getString(R.string.ps_not_allowed, name))
            Status.ALREADY -> showChip(Chip.INFO, getString(R.string.ps_already_allowed))
        }

        // One button for the whole sequence: "Allow", or a spinner while it runs.
        renderPrimary(
            label = getString(if (sequenceRunning) R.string.ps_requesting else R.string.ps_allow),
            spinner = sequenceRunning,
            animate = animate && !DesignMotion.animationsDisabled(ctx),
        )
    }

    private enum class Chip(val bg: Int, val icon: Int, val iconTint: Int, val text: Int) {
        SUCCESS(R.drawable.ps_bg_status_success, R.drawable.ps_ic_check, R.color.ps_success, R.color.ps_success_ink),
        WARNING(R.drawable.ps_bg_status_warning, R.drawable.ps_ic_warning, R.color.ps_warning, R.color.ps_warning_ink),
        INFO(R.drawable.ps_bg_status_info, R.drawable.ps_ic_check, R.color.ps_accent_ink, R.color.ps_info_ink),
    }

    private fun showChip(kind: Chip?, text: String? = null) {
        val view = root ?: return
        val chip = view.findViewById<LinearLayout>(R.id.statusChip)
        if (kind == null || text == null) { chip.isVisible = false; return }
        val ctx = view.context
        chip.setBackgroundResource(kind.bg)
        view.findViewById<ImageView>(R.id.ivStatus).apply {
            setImageResource(kind.icon)
            imageTintList = ContextCompat.getColorStateList(ctx, kind.iconTint)
        }
        view.findViewById<TextView>(R.id.tvStatus).apply {
            setTextColor(ContextCompat.getColor(ctx, kind.text))
            this.text = text
        }
        if (!chip.isVisible) {
            chip.isVisible = true
            if (!DesignMotion.animationsDisabled(ctx)) {
                chip.alpha = 0f
                chip.translationY = ctx.dp(6f)
                chip.animate().alpha(1f).translationY(0f).setDuration(200L).start()
            }
        }
    }

    private fun renderPrimary(label: String, spinner: Boolean, animate: Boolean) {
        val view = root ?: return
        val btn = view.findViewById<View>(R.id.btnPrimary)
        val content = view.findViewById<View>(R.id.primaryContent)
        val tv = view.findViewById<TextView>(R.id.tvPrimary)
        val apply = {
            tv.text = label
            view.findViewById<ProgressBar>(R.id.pbPrimary).isVisible = spinner
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
    private fun renderDots(container: LinearLayout, animate: Boolean) {
        val ctx = container.context
        val small = ctx.dp(6f).toInt()
        val wide = ctx.dp(22f).toInt()
        dotAnimators.forEach { it.cancel() }
        dotAnimators.clear()
        for (i in 0 until container.childCount) {
            val dot = container.getChildAt(i)
            if (i == current) {
                dot.setBackgroundResource(R.drawable.ps_bg_gradient_r3)
                dot.backgroundTintList = null
            } else {
                dot.setBackgroundResource(R.drawable.ps_bg_dot)
                dot.backgroundTintList = ContextCompat.getColorStateList(
                    ctx, if (i < current) R.color.ps_dot_past else R.color.ps_dot_future,
                )
            }
            val target = if (i == current) wide else small
            val from = dot.layoutParams.width
            if (from == target) continue
            if (!animate || DesignMotion.animationsDisabled(ctx)) {
                dot.layoutParams = dot.layoutParams.apply { width = target }
                continue
            }
            dotAnimators += ValueAnimator.ofInt(from, target).apply {
                duration = 250L
                interpolator = DesignMotion.EASE
                addUpdateListener { a -> dot.layoutParams = dot.layoutParams.apply { width = a.animatedValue as Int } }
                start()
            }
        }
    }

    /** Granted check: springs in with an overshoot (dampingRatio ≈ 0.5). */
    private fun popCheck(badge: View) {
        badge.visibility = View.VISIBLE
        if (DesignMotion.animationsDisabled(badge.context)) return
        badge.scaleX = 0f
        badge.scaleY = 0f
        badge.animate().scaleX(1f).scaleY(1f).setDuration(420L).setInterpolator(OvershootInterpolator(3f)).start()
    }

    // ─────────────────────────── Finish ───────────────────────────

    private fun finishFlow() {
        onFinished?.invoke()
        onFinished = null
        runCatching { dismissAllowingStateLoss() }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // Dragged down / back pressed (not via finishFlow): still notify once.
        onFinished?.invoke()
        onFinished = null
    }

    companion object {
        const val TAG = "permission_sheet"
        private const val ADVANCE_DELAY_MS = 600L
        private const val PAGE_MS = 450L

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
        fun dismissIfShowing(activity: FragmentActivity) {
            val fm = activity.supportFragmentManager
            (fm.findFragmentByTag(TAG) as? AccessSheetDialog)
                ?.let { runCatching { it.dismissAllowingStateLoss() } }
        }

        /**
         * True when at least one page still needs asking: not granted and not permanently
         * denied (Android would no longer show its prompt). Use it to decide whether to trigger
         * the sheet at all.
         */
        @JvmStatic
        fun hasPending(activity: FragmentActivity): Boolean =
            SheetPage.all(activity).any { page ->
                val perm = page.primaryPermission ?: return@any false
                !page.isGranted(activity) && !isPermanentlyDenied(activity, page.key, perm)
            }

        /**
         * True once the user has denied [perm] to the point Android no longer
         * shows its system dialog — i.e. it was requested at least once (from any
         * screen: the engine records it in [AccessVault], the sheet and the Home quick
         * actions in [VaultRegistry]) and `shouldShowRequestPermissionRationale` is
         * now false while still ungranted. On Android 11+ this is reached after
         * the 2nd decline.
         */
        @JvmStatic
        fun isPermanentlyDenied(activity: FragmentActivity, key: String, perm: String): Boolean {
            if (ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED) {
                return false
            }
            val askedAnywhere = AccessVault(activity).wasAsked(key) ||
                VaultRegistry(activity).hasRequestedPermission(perm)
            if (!askedAnywhere) return false
            return !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        }

        /**
         * Decides whether the sheet should pop up **automatically** on app launch.
         * Combines [hasPending] with the shared [IntroRevealPolicy] frequency gate,
         * driven by the `permission_sheet` entry of the `intro_display` Remote Config
         * block (`enabled` + `prompt_frequency` = always | once | every_days |
         * app_launches | never, with `prompt_interval`). The ledger is stamped by
         * [IntroRevealPolicy.markShown] in [show]. This gate is for the
         * **auto-launch only** — a manual "Manage" tap calls [show] directly.
         */
        @JvmStatic
        fun shouldAutoShow(activity: FragmentActivity): Boolean {
            if (!hasPending(activity)) return false
            return IntroRevealPolicy.shouldShowPermissionSheet(activity)
        }

        /**
         * Shows the permission sheet on demand. Call from any click listener:
         *
         * ```
         * someButton.setOnClickListener { AccessSheetDialog.show(this) }
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
            IntroRevealPolicy.markShown(activity, IntroRevealConfig.PERMISSION_SHEET)
            AccessSheetDialog().apply { this.onFinished = onFinished }
                .show(fm, TAG)
        }
    }
}
