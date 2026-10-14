package com.find.phone.gps.live.location.tracker.screens.finder

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.DisplayTeaserProfile
import com.find.phone.gps.live.location.tracker.monetize.render.EmbeddedTeaser
import com.find.phone.gps.live.location.tracker.monetize.render.EmbeddedTeaserRibbon
import com.find.phone.gps.live.location.tracker.monetize.render.BonusTeaser
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.store.inquiry.SectorPlaceCoder
import com.find.phone.gps.live.location.tracker.store.inquiry.SectorExactness
import com.find.phone.gps.live.location.tracker.store.inquiry.TerraTack
import com.find.phone.gps.live.location.tracker.store.inquiry.NumInquiry
import com.find.phone.gps.live.location.tracker.store.inquiry.NumDigest
import com.find.phone.gps.live.location.tracker.store.plus.PlusCheckout
import com.find.phone.gps.live.location.tracker.databinding.CellResultCellBinding
import com.find.phone.gps.live.location.tracker.databinding.ScreenLocatingBinding
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.dashboard.FreshHunt
import com.find.phone.gps.live.location.tracker.screens.dashboard.FreshHuntShelf
import com.find.phone.gps.live.location.tracker.screens.plus.PlusActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import kotlin.math.roundToInt

class PinpointingActivity : StageActivity<ScreenLocatingBinding>() {
    override val layoutId: Int = R.layout.screen_locating

    private enum class Phase { NATION, REGION, SPOT }

    private lateinit var hunt: FreshHunt
    private var reopened: NumDigest? = null
    private var priorUnlock = false
    private var outcomeDigest: NumDigest? = null
    private var pulseVeil: SectorThrobVeil? = null
    private var barAnim: ObjectAnimator? = null
    private var adShowing = false
    private val runningAnims = mutableListOf<Animator>()

    private val paywallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        outcomeDigest?.takeIf { PlusCheckout.isPremium(this) }?.let { openDetails(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        StreetCharts.init(this)
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        val digits = intent.getStringExtra(KEY_DIGITS)
        val iso = intent.getStringExtra(KEY_ISO)
        val dial = intent.getStringExtra(KEY_DIAL)
        if (listOf(digits, iso, dial).any { it.isNullOrEmpty() }) {
            finish()
            return
        }
        hunt = FreshHunt(digits!!, iso!!, dial!!, System.currentTimeMillis())
        reopened = IntentCompat.getSerializableExtra(intent, EXTRA_REPORT, NumDigest::class.java)
        priorUnlock = intent.getBooleanExtra(KEY_UNLOCKED, false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.lvLocRoot) { root, insets ->
            insets.getInsets(WindowInsetsCompat.Type.systemBars()).also { sb ->
                root.updatePadding(left = sb.left, top = sb.top, right = sb.right, bottom = sb.bottom)
            }
            insets
        }

        bindButtons()
        binding.chartCard.clipToOutline = true
        StreetCharts.style(binding.lvBigMap, interactive = false)

        val isPro = PlusCheckout.isPremium(this)
        if (!isPro) {
            BonusTeaser.preload(this)
            mountNative()
        }

        when (val known = reopened) {
            null -> {
                binding.pillCountry.text = countryLabel()
                binding.pillState.setText(R.string.ds_step_state)
                runLookup(if (isPro) PRO_HOLD_MS else FREE_HOLD_MS)
            }
            else -> {
                binding.chartCard.updateLayoutParams { height = dpi(CARD_MAP_DP) }
                lifecycleScope.launch { pinArea(known, animate = false) }
                showVerdict(known)
            }
        }
    }

    private fun bindButtons() {
        binding.txtSearchNumber.text = hunt.display
        binding.actBack.setOnClickListener { goBack() }
        binding.actNotNow.setOnClickListener { finish() }
        CraftMovement.pressScale(binding.actWatchAd)
        binding.actWatchAd.setOnClickListener { playRewarded() }
        binding.actSkipPro.setOnClickListener {
            outcomeDigest ?: return@setOnClickListener
            recordKeyEvent("result_skip_pro")
            paywallLauncher.launch(PlusActivity.intent(this))
        }
    }

    private fun countryLabel(): String = Realms.byIso(hunt.iso2)?.name ?: hunt.iso2

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun styleChipPair(
        stepView: TextView, chipView: TextView, caption: String,
        stepBg: Int, stepInk: Int, chipBg: Int, chipInk: Int,
    ) {
        stepView.text = caption
        stepView.setBackgroundResource(stepBg)
        stepView.setTextColor(color(stepInk))
        chipView.setBackgroundResource(chipBg)
        chipView.setTextColor(color(chipInk))
    }

    private fun paintPhases(now: Phase) {
        val rows = arrayOf(
            Triple(binding.lvStepCountry, binding.pillCountry, R.string.ds_step_country),
            Triple(binding.lvStepState, binding.pillState, R.string.ds_step_state),
            Triple(binding.lvStepArea, binding.pillArea, R.string.ds_step_area),
        )
        for (pos in rows.indices) {
            val (stepView, chipView, labelRes) = rows[pos]
            val label = getString(labelRes)
            if (pos < now.ordinal) {
                styleChipPair(
                    stepView, chipView, getString(R.string.ds_step_done, label),
                    R.drawable.lv_tile_success12_r10, R.color.ds_success, R.drawable.lv_tile_primary_r10, R.color.white,
                )
            } else if (pos == now.ordinal) {
                styleChipPair(
                    stepView, chipView, getString(R.string.ds_step_active, label),
                    R.drawable.lv_tile_tint_r10, R.color.ds_primary, R.drawable.lv_tile_white_r10, R.color.ds_primary,
                )
            } else {
                styleChipPair(
                    stepView, chipView, label,
                    R.drawable.lv_tile_gray_r10, R.color.ds_placeholder, R.drawable.lv_tile_white70_r10, R.color.ds_placeholder,
                )
            }
        }
    }

    private val nativeTemplate: String by lazy {
        DisplayTeaserProfile.resolve(this, ADS_SCREEN).nativeType.trim().lowercase()
    }

    private val bannerStyleNative: Boolean
        get() = nativeTemplate in COMPACT_TEMPLATES

    private fun mountNative() {
        binding.spotFrame.isVisible = true
        if (hasCachedNative()) {
            drawNative()
            return
        }
        when {
            bannerStyleNative -> EmbeddedTeaserRibbon().fetchNativeBannerAds(this)
            else -> EmbeddedTeaser().fetchNativeADs(this, object : EmbeddedTeaser.NativeAdObserver {
                override fun onNativeAdLoaded() = runOnUiThread { fillNativeIfBlank() }
                override fun onNativeAdFailed() = Unit
            })
        }

        for (wait in NATIVE_POLL_MS) binding.spotFrame.postDelayed({ fillNativeIfBlank() }, wait)
    }

    private fun hasCachedNative(): Boolean = when {
        bannerStyleNative -> EmbeddedTeaserRibbon.hasPreloadedNativeBanner()
        else -> EmbeddedTeaser.hasPreloadedNative()
    }

    private fun fillNativeIfBlank() {
        val gone = isFinishing || isDestroyed
        if (gone || binding.spotFrame.childCount > 0) return
        if (!hasCachedNative()) return
        drawNative()
    }

    private fun drawNative() {
        val slot = binding.spotFrame
        if (bannerStyleNative) {
            EmbeddedTeaserRibbon().presentNativeBannerNative(this, slot)
            return
        }
        val teaser = EmbeddedTeaser()
        when (nativeTemplate) {
            "big" -> teaser.presentBigNative(this, slot)
            "mid" -> teaser.presentMidNative(this, slot)
            else -> teaser.presentMidNative2(this, slot)
        }
    }

    private fun runLookup(holdMs: Long) = lifecycleScope.launch {
        val t0 = SystemClock.elapsedRealtime()
        fun leftUntil(mark: Long) = maxOf(0L, t0 + mark - SystemClock.elapsedRealtime())

        val nation = countryLabel()
        val pending = async { NumInquiry.run(this@PinpointingActivity, hunt.digits, hunt.iso2, hunt.dial) }

        enterPhase(getString(R.string.ds_locating_country, nation), toPercent = 30, overMs = holdMs * 3 / 10, phase = Phase.NATION)
        val nationPin = SectorPlaceCoder.locate(this@PinpointingActivity, null, null, null, nation)
        if (nationPin != null) {
            val point = GeoPoint(nationPin.lat, nationPin.lng)
            binding.lvBigMap.controller.setZoom(SectorExactness.COUNTRY.zoom - 2)
            binding.lvBigMap.controller.setCenter(point)
            binding.lvBigMap.controller.animateTo(point, SectorExactness.COUNTRY.zoom, FLIGHT_MS)
        }
        delay(leftUntil(holdMs * 3 / 10))

        val answer = pending.await()
        outcomeDigest = answer
        persistFound(answer)

        val region = answer.state?.takeIf { it.isNotBlank() }
        val regionPin = region?.let { SectorPlaceCoder.locate(this@PinpointingActivity, null, null, it, answer.countryName) }
        if (region != null && regionPin != null) {
            binding.pillState.text = region
            enterPhase(getString(R.string.ds_locating_state, region), toPercent = 70, overMs = holdMs * 4 / 10, phase = Phase.REGION)
            binding.lvBigMap.controller.animateTo(GeoPoint(regionPin.lat, regionPin.lng), SectorExactness.STATE.zoom - 1, FLIGHT_MS)
            delay(leftUntil(holdMs * 7 / 10))
        }

        enterPhase(getString(R.string.ds_locating_found, region ?: answer.countryName), toPercent = 100, overMs = leftUntil(holdMs), phase = Phase.SPOT)
        pinArea(answer, animate = true, knownState = regionPin)
        delay(leftUntil(holdMs))

        showVerdict(answer)
    }

    private fun enterPhase(text: String, toPercent: Int, overMs: Long, phase: Phase) {
        binding.txtStage.text = text
        paintPhases(phase)
        barAnim?.cancel()
        val bar = binding.prog
        val anim = ObjectAnimator.ofInt(bar, "progress", bar.progress, toPercent * 10)
        anim.duration = maxOf(300L, overMs)
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener { binding.txtPercent.text = getString(R.string.ds_locating_percent, bar.progress / 10) }
        anim.start()
        barAnim = anim
        runningAnims += anim
    }

    private suspend fun pinArea(result: NumDigest, animate: Boolean, knownState: TerraTack? = null) {
        val region = result.state?.takeIf { it.isNotBlank() }
        val ownPin = result.pin
        val target = ownPin ?: knownState
            ?: SectorPlaceCoder.locate(this, null, null, region, result.countryName)
            ?: return
        if (isFinishing || isDestroyed) return
        val accuracy = if (ownPin != null) result.precision
        else if (region != null) SectorExactness.STATE
        else SectorExactness.COUNTRY

        val zoom = StreetCharts.cardZoom(accuracy)
        if (animate) {
            binding.lvBigMap.controller.animateTo(GeoPoint(target.lat, target.lng), zoom, FLIGHT_MS)
            delay(FLIGHT_MS)
            if (isFinishing || isDestroyed) return
        }
        pulseVeil?.let { old ->
            old.stop()
            binding.lvBigMap.overlays.remove(old)
        }
        pulseVeil = StreetCharts.siteAt(binding.lvBigMap, target, accuracy, zoom)
    }

    private fun recordRecent(result: NumDigest, unlocked: Boolean) {
        val entry = hunt.copy(searchedAt = System.currentTimeMillis(), report = result, unlocked = unlocked)
        FreshHuntShelf(this).add(entry)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_FOUND, true))
    }

    private fun persistFound(result: NumDigest) = recordRecent(result, priorUnlock)

    private fun showVerdict(result: NumDigest) {
        outcomeDigest = result
        if (priorUnlock || PlusCheckout.isPremium(this)) {
            openDetails(result)
            return
        }

        with(binding) {
            txtTitle.setText(R.string.ds_location_found)
            txtSearchNumber.setText(R.string.ds_locating_complete)
            txtSearchNumber.setTextColor(color(R.color.ds_success))
            lvLivePill.isVisible = false

            lvScanGroup.isVisible = false
            chartChips.isVisible = false
            lvSearchingGroup.isVisible = false
            txtAreaPill.isVisible = true
        }
        collapseMap()

        binding.txtCountryTile.text = hunt.iso2.uppercase()
        binding.lvNameRow.isVisible = !result.name.isNullOrBlank()
        binding.txtName.text = result.name
        binding.txtName.setBlurred(true)
        binding.txtNumber.text = result.international
        binding.txtRegion.text = listOfNotNull(result.state, result.countryName).distinct().joinToString(", ")
        paintValidity(result.valid)

        paintFact(binding.boxCountry, R.string.ds_label_country, result.countryName, locked = false)
        paintFact(binding.boxLine, R.string.ds_fact_line, result.lineType.label(this), locked = false)
        paintFact(binding.boxCity, R.string.ds_label_city, result.city, locked = true)
        paintFact(binding.boxCarrier, R.string.ds_fact_carrier, result.carrier, locked = true)

        binding.verdictGroup.run {
            alpha = 0f
            translationY = dpi(40).toFloat()
            isVisible = true
            animate().alpha(1f).translationY(0f).setDuration(450)
                .setInterpolator(CraftMovement.EASE_IN_OUT).start()
        }
    }

    private fun paintValidity(valid: Boolean) = with(binding.txtValidity) {
        setText(if (valid) R.string.ds_result_valid_short else R.string.ds_verdict_unverified)
        setBackgroundResource(if (valid) R.drawable.lv_pill_success_soft else R.drawable.lv_pill_warn)
        setTextColor(color(if (valid) R.color.ds_success else R.color.ds_warn))
    }

    private fun collapseMap() {
        val startH = binding.chartCard.height
        if (startH <= 0) return
        val endH = dpi(CARD_MAP_DP)
        if (startH <= endH) return
        val keepCenter = binding.lvBigMap.mapCenter
        val anim = ValueAnimator.ofInt(startH, endH)
        anim.duration = 400L
        anim.interpolator = CraftMovement.EASE_IN_OUT
        anim.addUpdateListener { va ->
            binding.chartCard.updateLayoutParams { height = va.animatedValue as Int }
            binding.lvBigMap.controller.setCenter(keepCenter)
        }
        anim.start()
        runningAnims += anim
    }

    private fun paintFact(box: CellResultCellBinding, @StringRes caption: Int, value: String?, locked: Boolean) {
        box.txtLabel.setText(caption)
        val missing = value.isNullOrBlank()
        val hide = locked && !missing
        box.txtValue.text = if (missing) getString(R.string.ds_not_available) else value
        box.txtValue.setTextColor(color(if (missing) R.color.ds_muted else R.color.ds_ink))
        box.txtValue.setBlurred(hide)
        box.imgLock.isVisible = hide
    }

    private fun setAdSpinner(busy: Boolean) {
        adShowing = busy
        binding.txtWatchAd.isInvisible = busy
        binding.spinWatchAd.isVisible = busy
    }

    private fun playRewarded() {
        val result = outcomeDigest ?: return
        if (adShowing) return
        setAdSpinner(true)
        BonusTeaser().show(this) { outcome ->
            setAdSpinner(false)
            if (outcome == BonusTeaser.Result.EARNED) {
                openDetails(result)
            } else if (outcome == BonusTeaser.Result.NOT_EARNED) {
                Toast.makeText(this, R.string.ds_reward_not_earned, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openDetails(result: NumDigest) {
        if (isFinishing) return
        recordRecent(result, unlocked = true)
        startActivity(NumProfile2Activity.intent(this, result))
        finish()
    }

    private fun dpi(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onResume() {
        super.onResume()
        if (::hunt.isInitialized) binding.lvBigMap.onResume()
    }

    override fun onPause() {
        if (::hunt.isInitialized) binding.lvBigMap.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        CraftMovement.abortAll(runningAnims)
        pulseVeil?.stop()
        super.onDestroy()
    }

    companion object {
        private const val KEY_DIGITS = "digits"
        private const val KEY_ISO = "iso"
        private const val KEY_DIAL = "dial"
        private const val EXTRA_REPORT = "report"
        private const val KEY_UNLOCKED = "unlocked"

        const val EXTRA_FOUND = "found"

        private const val FREE_HOLD_MS = 10_000L
        private const val PRO_HOLD_MS = 4_000L
        private val NATIVE_POLL_MS = longArrayOf(2_500L, 5_000L, 8_000L)
        private const val ADS_SCREEN = "PinpointingActivity"
        private val COMPACT_TEMPLATES = setOf("small", "native_banner", "nativebanner")
        private const val FLIGHT_MS = 1_800L
        private const val CARD_MAP_DP = 150

        fun intent(context: Context, search: FreshHunt, saved: NumDigest?, unlocked: Boolean): Intent =
            Intent(context, PinpointingActivity::class.java)
                .putExtra(KEY_DIGITS, search.digits)
                .putExtra(KEY_ISO, search.iso2)
                .putExtra(KEY_DIAL, search.dial)
                .putExtra(KEY_UNLOCKED, unlocked)
                .apply { if (saved != null) putExtra(EXTRA_REPORT, saved) }
    }
}
