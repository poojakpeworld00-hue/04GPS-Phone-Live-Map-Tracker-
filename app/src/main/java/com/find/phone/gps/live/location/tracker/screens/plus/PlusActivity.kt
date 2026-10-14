package com.find.phone.gps.live.location.tracker.screens.plus

import android.graphics.Paint
import android.os.Build
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.store.plus.PlusCheckout
import com.find.phone.gps.live.location.tracker.store.plus.PlusProfile
import com.find.phone.gps.live.location.tracker.store.plus.PlusOffer
import com.find.phone.gps.live.location.tracker.databinding.CellPremiumPlanBinding
import com.find.phone.gps.live.location.tracker.databinding.ScreenPremiumBinding
import com.find.phone.gps.live.location.tracker.induction.HomeStream
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.helpers.enterPolicyLink
import com.find.phone.gps.live.location.tracker.helpers.enterTermLink
import kotlin.math.roundToInt

/**
 * Premium paywall. Plans are read from Remote Config, prices only from Play; while Play is being
 * asked the prices show a placeholder and the CTA spins, and a failed fetch turns the CTA into
 * Retry. The X (and Back) become active after the configured delay. When opened as part of the
 * first run, leaving or buying continues onboarding.
 */
class PlusActivity : StageActivity<ScreenPremiumBinding>() {

    override val layoutId: Int = R.layout.screen_premium

    private enum class QuoteStatus { PENDING, OK, FAILED }

    private lateinit var offerCfg: PlusProfile
    private lateinit var planCells: List<CellPremiumPlanBinding>
    private var offers: List<PlusOffer> = emptyList()
    private var pickedIdx = 0
    private var checkoutBusy = false
    private var quoteStatus = QuoteStatus.PENDING
    private var quoteTries = 0
    private var ctaSweep: ValueAnimator? = null

    private val fromIntro: Boolean get() = intent.getBooleanExtra(EXTRA_AFTER_INTRO, false)

    override fun initView() {
        offerCfg = PlusProfile.read(this)
        offers = offerCfg.plans.take(3)
        pickedIdx = maxOf(0, offers.indexOfFirst { it.preselected })
        recordKeyEvent(if (fromIntro) "premium_view_after_intro" else "premium_view")

        goEdgeToEdge()

        planCells = listOf(binding.lvPlan1, binding.lvPlan2, binding.lvPlan3)
        for ((idx, cell) in planCells.withIndex()) {
            cell.root.isVisible = idx < offers.size
            cell.lvPlanCard.setOnClickListener { choosePlan(idx) }
        }
        renderPlans()

        bindControls()

        PlusCheckout.onPurchaseResult = { handleOutcome(it) }
        requestQuotes()
        runCtaSweep()
    }

    /** The map header sits under a transparent status bar with dark icons. */
    private fun goEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = true
        val baseCloseTop = (binding.actClose.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val baseBottomPad = binding.lowerBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.plusRoot) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.actClose.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = baseCloseTop + sb.top }
            binding.lead.updateLayoutParams { height = dpPx(300) + sb.top }
            binding.lowerBar.updatePadding(bottom = baseBottomPad + sb.bottom)
            binding.plusRoot.updatePadding(left = sb.left, right = sb.right)
            insets
        }
    }

    private fun bindControls() {
        CraftMovement.pressScale(binding.actContinue)
        with(binding) {
            actContinue.clipToOutline = true
            actContinue.setOnClickListener { startCheckout() }
            actRestore.setOnClickListener { restorePurchases() }
            actTerms.setOnClickListener { enterTermLink() }
            actPrivacy.setOnClickListener { enterPolicyLink() }
            actClose.setOnClickListener { if (actClose.closable) exitPaywall() }
            actClose.start(offerCfg.closeDelaySec, offerCfg.closeButtonOpacity) {}
            actContinueFree.paintFlags = actContinueFree.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            // Available immediately; only the X waits for close_delay_sec.
            actContinueFree.setOnClickListener {
                recordKeyEvent("premium_continue_free")
                exitPaywall()
            }
        }

        // Back is ignored until the X is shown, then behaves like it.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!binding.actClose.closable) return
                exitPaywall()
            }
        })
    }

    private fun dpPx(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    // ── Plan cards ────────────────────────────────────────────────────────

    private fun choosePlan(idx: Int) {
        if (idx != pickedIdx) {
            pickedIdx = idx
            renderPlans()
        }
    }

    private fun cardPrice(plan: PlusOffer): String? = when (plan.kind) {
        PlusOffer.Kind.MONTHLY -> PlusCheckout.weeklyPrice(plan)
        else -> PlusCheckout.price(plan)
    }

    private fun renderCell(cell: CellPremiumPlanBinding, plan: PlusOffer, active: Boolean) {
        val ink = if (active) getColor(R.color.white) else getColor(R.color.ds_ink)
        cell.lvPlanCard.setBackgroundResource(if (active) R.drawable.lv_plan_on_r18 else R.drawable.lv_plan_off_r18)
        cell.txtName.setTextColor(ink)
        cell.txtPrice.setTextColor(ink)
        cell.txtPeriod.setTextColor(
            if (active) getColor(R.color.white) and 0xD9FFFFFF.toInt() else getColor(R.color.ds_muted)
        )
        cell.lvPlanCard.elevation = if (active) dpPx(10).toFloat() else 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadow = getColor(R.color.ds_primary)
            cell.lvPlanCard.outlineSpotShadowColor = shadow
            cell.lvPlanCard.outlineAmbientShadowColor = shadow
        }
        val factor = if (active) PICKED_SCALE else 1f
        cell.root.animate().scaleX(factor).scaleY(factor).setDuration(180L).start()
        cell.txtName.setText(titleRes(plan))
        // Monthly shows its weekly equivalent; the CTA and price line keep the billed amount.
        cell.txtPrice.text = cardPrice(plan) ?: QUOTE_PLACEHOLDER
        cell.txtPeriod.setText(periodRes(plan))
        cell.txtTag.text = plan.tag
        cell.txtTag.isVisible = plan.tag.isNotBlank()
    }

    private fun consentTail(plan: PlusOffer, trial: Int): Int = when {
        trial > 0 -> R.string.ds_premium_consent_trial
        plan.isSubscription -> R.string.ds_premium_consent_subs
        else -> R.string.ds_premium_consent_lifetime
    }

    private fun ctaText(plan: PlusOffer, trial: Int, price: String): String {
        if (trial > 0) return getString(R.string.ds_premium_cta_trial, trial)
        return when (plan.kind) {
            PlusOffer.Kind.WEEKLY -> getString(R.string.ds_premium_cta_week, price)
            PlusOffer.Kind.MONTHLY -> getString(R.string.ds_premium_cta_month, price)
            else -> getString(R.string.ds_premium_cta_lifetime, price)
        }
    }

    private fun priceLineText(plan: PlusOffer, trial: Int, price: String): String = when (plan.kind) {
        PlusOffer.Kind.WEEKLY ->
            if (trial > 0) getString(R.string.ds_premium_price_trial_week, price, trial)
            else getString(R.string.ds_premium_price_week, price)
        PlusOffer.Kind.MONTHLY ->
            if (trial > 0) getString(R.string.ds_premium_price_trial_month, price, trial)
            else getString(R.string.ds_premium_price_month, price)
        PlusOffer.Kind.LIFETIME -> getString(R.string.ds_premium_price_lifetime, price)
    }

    private fun renderPlans() {
        offers.forEachIndexed { idx, plan -> renderCell(planCells[idx], plan, idx == pickedIdx) }
        val current = offers.getOrNull(pickedIdx) ?: return
        val trial = trialDays(current)
        renderConsent(consentTail(current, trial))
        val price = PlusCheckout.price(current) ?: return renderMissingPrice()
        if (!checkoutBusy) {
            binding.txtCta.isInvisible = false
            binding.spinCta.isVisible = false
        }
        binding.txtCta.text = ctaText(current, trial, price)
        binding.txtPriceLine.text = priceLineText(current, trial, price)
    }

    /** No Play price yet: spinner while it's being fetched, Retry after it failed. */
    private fun renderMissingPrice() {
        val gaveUp = quoteStatus == QuoteStatus.FAILED
        binding.txtPriceLine.text = if (gaveUp) getString(R.string.ds_premium_unavailable) else ""
        binding.txtCta.text = getString(R.string.ds_retry)
        if (checkoutBusy) return
        binding.txtCta.isInvisible = !gaveUp
        binding.spinCta.isVisible = !gaveUp
    }

    private fun alive(): Boolean = !isFinishing && !isDestroyed

    /** Fetches localized prices from Play; two silent retries, then the CTA offers Retry. */
    private fun requestQuotes() {
        quoteStatus = QuoteStatus.PENDING
        renderPlans()
        PlusCheckout.fetchDetails(this, offers) {
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                quoteTries += 1
                val complete = offers.all { PlusCheckout.price(it) != null }
                quoteStatus = if (complete) {
                    QuoteStatus.OK
                } else if (quoteTries < AUTO_QUOTE_TRIES) {
                    binding.root.postDelayed({ if (alive()) requestQuotes() }, QUOTE_BACKOFF_MS * quoteTries)
                    QuoteStatus.PENDING
                } else {
                    QuoteStatus.FAILED
                }
                if (quoteStatus == QuoteStatus.FAILED) recordKeyEvent("premium_price_unavailable")
                renderPlans()
            }
        }
    }

    /** Play's trial length once known (0 = none), else the configured fallback. */
    private fun trialDays(plan: PlusOffer): Int = PlusCheckout.trialDays(plan) ?: plan.fallbackTrialDays

    private fun renderConsent(tailRes: Int) {
        val termsWord = getString(R.string.ds_premium_terms)
        val privacyWord = getString(R.string.ds_premium_privacy)
        val body = getString(R.string.ds_premium_consent, termsWord, privacyWord) + " " + getString(tailRes)
        val styled = SpannableString(body)
        val linkFor = mapOf<String, () -> Unit>(
            termsWord to { enterTermLink() },
            privacyWord to { enterPolicyLink() },
        )
        listOf(termsWord, privacyWord).forEach { word ->
            val from = body.indexOf(word)
            if (from >= 0) {
                val onTap = linkFor.getValue(word)
                styled.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = onTap()
                }, from, from + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        binding.txtNote.text = styled
        binding.txtNote.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun titleRes(plan: PlusOffer) = when (plan.kind) {
        PlusOffer.Kind.WEEKLY -> R.string.ds_premium_week
        PlusOffer.Kind.MONTHLY -> R.string.ds_premium_month
        PlusOffer.Kind.LIFETIME -> R.string.ds_premium_lifetime
    }

    private fun periodRes(plan: PlusOffer) = when (plan.kind) {
        PlusOffer.Kind.WEEKLY -> R.string.ds_premium_per_week
        PlusOffer.Kind.MONTHLY -> R.string.ds_premium_per_week_billed_monthly
        PlusOffer.Kind.LIFETIME -> R.string.ds_premium_one_time
    }

    // ── Checkout / restore ────────────────────────────────────────────────

    private fun startCheckout() {
        if (checkoutBusy) return
        val plan = offers.getOrNull(pickedIdx) ?: return
        PlusCheckout.price(plan) ?: run {
            // Never sell without a Play price shown: wait if loading, re-fetch if it failed.
            if (quoteStatus == QuoteStatus.FAILED) {
                quoteTries = 0
                requestQuotes()
            }
            return
        }
        recordKeyEvent("premium_cta_${plan.kind.name.lowercase()}")
        val launched = PlusCheckout.purchase(this, plan)
        if (launched) {
            toggleCheckoutBusy(true)
        } else {
            Toast.makeText(this, R.string.ds_premium_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleOutcome(outcome: PlusCheckout.PurchaseOutcome) {
        toggleCheckoutBusy(false)
        if (outcome == PlusCheckout.PurchaseOutcome.PURCHASED) {
            recordKeyEvent("premium_purchased")
            Toast.makeText(this, R.string.ds_premium_welcome, Toast.LENGTH_SHORT).show()
            exitPaywall()
            return
        }
        val (msg, length) = when (outcome) {
            PlusCheckout.PurchaseOutcome.PENDING -> R.string.ds_premium_pending to Toast.LENGTH_LONG
            PlusCheckout.PurchaseOutcome.FAILED -> R.string.ds_premium_failed to Toast.LENGTH_SHORT
            else -> return
        }
        Toast.makeText(this, msg, length).show()
    }

    private fun restorePurchases() {
        binding.actRestore.isEnabled = false
        PlusCheckout.sync(this) { active ->
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                binding.actRestore.isEnabled = true
                val msg = when (active) {
                    true -> R.string.ds_premium_restored
                    false -> R.string.ds_premium_nothing_to_restore
                    // Play unreachable: say that instead of "nothing found".
                    null -> R.string.ds_premium_unavailable
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                if (active == true) exitPaywall()
            }
        }
    }

    private fun toggleCheckoutBusy(busy: Boolean) {
        checkoutBusy = busy
        binding.txtCta.isInvisible = busy
        binding.spinCta.isVisible = busy
    }

    /** After the intro this continues the first-run flow; otherwise it just closes. */
    private fun exitPaywall() {
        if (isFinishing) return
        when {
            !fromIntro -> finish()
            HomeStream.isOnboardingActive(this) -> HomeStream.advance(this)
            else -> HomeStream.goHome(this)
        }
    }

    // ── CTA sweep ─────────────────────────────────────────────────────────

    private fun runCtaSweep() {
        if (CraftMovement.animationsDisabled(this)) {
            binding.lvShine.isVisible = false
            return
        }
        binding.actContinue.post {
            val span = binding.actContinue.width.toFloat()
            ctaSweep = ObjectAnimator.ofFloat(binding.lvShine, View.TRANSLATION_X, -0.3f * span, 1.1f * span).also { a ->
                a.duration = 1_800L
                a.startDelay = 400L
                a.repeatCount = ValueAnimator.INFINITE
                a.repeatMode = ValueAnimator.RESTART
                a.interpolator = AccelerateDecelerateInterpolator()
                a.start()
            }
        }
    }

    override fun onDestroy() {
        ctaSweep?.cancel()
        PlusCheckout.onPurchaseResult?.let { PlusCheckout.onPurchaseResult = null }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_AFTER_INTRO = "after_intro"

        private const val PICKED_SCALE = 1.05f
        private const val QUOTE_PLACEHOLDER = "…"
        private const val AUTO_QUOTE_TRIES = 3
        private const val QUOTE_BACKOFF_MS = 1_500L

        /** Whether the paywall should follow the intro: switched on, and the user isn't premium. */
        fun showsAfterIntro(context: Context): Boolean {
            val config = PlusProfile.read(context)
            return config.enabled && config.showAfterIntro && !PlusCheckout.isPremium(context)
        }

        /** The paywall as the step after the intro; [onboarding] carries the first-run marker. */
        fun afterIntro(context: Context, onboarding: Boolean): Intent {
            val intent = if (onboarding) HomeStream.onboardingIntent(context, PlusActivity::class.java)
            else Intent(context, PlusActivity::class.java)
            return intent.putExtra(EXTRA_AFTER_INTRO, true)
        }

        fun intent(context: Context): Intent = Intent(context, PlusActivity::class.java)
    }
}
