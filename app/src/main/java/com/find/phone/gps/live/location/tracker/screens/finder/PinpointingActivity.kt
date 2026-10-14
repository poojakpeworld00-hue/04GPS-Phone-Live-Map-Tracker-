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
import com.find.phone.gps.live.location.tracker.monetize.logic.logKeyEvent
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

/**
 * Locating a searched number, full page (design 13a / 13b, Search Result).
 *
 * Searching (13a): the lookup runs while the map flies from the country to the state to the
 * approximate area under the scan, with the stage line, a progress bar and the three step
 * chips (country · state · area) under it, for at least [MIN_SEARCH_MS] (Premium:
 * [MIN_SEARCH_PREMIUM_MS]); the mid2 native is docked at the bottom of the page meanwhile (not
 * for Premium). The map ends on the report's own area (city or pincode when known), the same
 * point Number Details shows.
 *
 * Found (13b): "Location found · Search complete", the map shrinks to the approximate-area
 * card and the locked result card comes in under it. "Watch Ad · View full details" plays the
 * rewarded ad directly, which unlocks Number Details; "Skip with PRO" goes to the paywall;
 * Premium users, and numbers unlocked before, go straight to Number Details.
 *
 * With [EXTRA_REPORT] set (a locked recent search reopened) it opens at the result, no lookup.
 * The result intent carries [EXTRA_FOUND] once the lookup has answered, so Home can clear the box.
 */
class PinpointingActivity : StageActivity<ScreenLocatingBinding>() {

    override val layoutId: Int = R.layout.screen_locating

    private lateinit var search: FreshHunt
    private var saved: NumDigest? = null
    private var alreadyUnlocked = false
    private var report: NumDigest? = null
    private var overlay: SectorThrobVeil? = null
    private val animators = mutableListOf<Animator>()
    private var progressAnim: ObjectAnimator? = null
    private var watching = false

    private val premiumPage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val result = report ?: return@registerForActivityResult
        if (PlusCheckout.isPremium(this)) unlock(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // osmdroid must be configured before the MapView is inflated (in super.onCreate).
        StreetCharts.init(this)
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        val digits = intent.getStringExtra(EXTRA_DIGITS)
        val iso = intent.getStringExtra(EXTRA_ISO)
        val dial = intent.getStringExtra(EXTRA_DIAL)
        if (digits.isNullOrEmpty() || iso.isNullOrEmpty() || dial.isNullOrEmpty()) {
            finish()
            return
        }
        search = FreshHunt(digits, iso, dial, System.currentTimeMillis())
        saved = IntentCompat.getSerializableExtra(intent, EXTRA_REPORT, NumDigest::class.java)
        alreadyUnlocked = intent.getBooleanExtra(EXTRA_UNLOCKED, false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.lvLocRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.txtSearchNumber.text = search.display
        binding.actBack.setOnClickListener { goBack() }
        binding.actNotNow.setOnClickListener { finish() }
        CraftMovement.pressScale(binding.actWatchAd)
        binding.actWatchAd.setOnClickListener { reveal() }
        binding.actSkipPro.setOnClickListener {
            if (report == null) return@setOnClickListener
            logKeyEvent("result_skip_pro")
            premiumPage.launch(PlusActivity.intent(this))
        }
        binding.chartCard.clipToOutline = true
        StreetCharts.style(binding.lvBigMap, interactive = false)

        val premium = PlusCheckout.isPremium(this)
        if (!premium) {
            BonusTeaser.preload(this)
            showNative()
        }

        val given = saved
        if (given != null) {
            binding.chartCard.updateLayoutParams { height = dp(MAP_RESULT_DP) }
            lifecycleScope.launch { placeArea(given, animate = false) }
            showResult(given)
        } else {
            binding.pillCountry.text = Realms.byIso(search.iso2)?.name ?: search.iso2
            binding.pillState.setText(R.string.ds_step_state)
            locate(if (premium) MIN_SEARCH_PREMIUM_MS else MIN_SEARCH_MS)
        }
    }

    /** The three search stages of design 13a, in order. */
    private enum class Step { COUNTRY, STATE, AREA }

    /**
     * The step chips under the progress bar and the chips over the map for [current]: the
     * stages before it are done (✓, green / filled primary), it is active (●, tint / white),
     * the rest wait (gray / white 70).
     */
    private fun bindSteps(current: Step) {
        val steps = listOf(
            Triple(binding.lvStepCountry, binding.pillCountry, R.string.ds_step_country),
            Triple(binding.lvStepState, binding.pillState, R.string.ds_step_state),
            Triple(binding.lvStepArea, binding.pillArea, R.string.ds_step_area),
        )
        steps.forEachIndexed { index, (step, chip, label) ->
            val name = getString(label)
            when {
                index < current.ordinal -> {
                    step.text = getString(R.string.ds_step_done, name)
                    step.setBackgroundResource(R.drawable.lv_tile_success12_r10)
                    step.setTextColor(ContextCompat.getColor(this, R.color.ds_success))
                    chip.setBackgroundResource(R.drawable.lv_tile_primary_r10)
                    chip.setTextColor(ContextCompat.getColor(this, R.color.white))
                }
                index == current.ordinal -> {
                    step.text = getString(R.string.ds_step_active, name)
                    step.setBackgroundResource(R.drawable.lv_tile_tint_r10)
                    step.setTextColor(ContextCompat.getColor(this, R.color.ds_primary))
                    chip.setBackgroundResource(R.drawable.lv_tile_white_r10)
                    chip.setTextColor(ContextCompat.getColor(this, R.color.ds_primary))
                }
                else -> {
                    step.text = name
                    step.setBackgroundResource(R.drawable.lv_tile_gray_r10)
                    step.setTextColor(ContextCompat.getColor(this, R.color.ds_placeholder))
                    chip.setBackgroundResource(R.drawable.lv_tile_white70_r10)
                    chip.setTextColor(ContextCompat.getColor(this, R.color.ds_placeholder))
                }
            }
        }
    }

    // ── Bottom native ─────────────────────────────────────────────────────

    /**
     * The template of the bottom native, from `ScreenAds.PinpointingActivity.nativeType`: `small`
     * (or `native_banner`) is the one-line native banner of design 13b, which keeps Not now and
     * Skip with PRO above the fold; `big` / `mid` / `mid2` are the body natives.
     */
    private val nativeType: String by lazy {
        DisplayTeaserProfile.resolve(this, SCREEN_KEY).nativeType.trim().lowercase()
    }

    private val compactNative: Boolean
        get() = nativeType == "small" || nativeType == "native_banner" || nativeType == "nativebanner"

    /**
     * The native at the bottom. The shared native pools are filled by the splash chain and
     * emptied by every screen that shows one, so when the pool is empty here the page loads
     * its own and shows it once it lands — otherwise the slot would sit blank for the whole search.
     */
    private fun showNative() {
        binding.spotFrame.isVisible = true
        if (nativeReady()) return renderNative()
        if (compactNative) {
            EmbeddedTeaserRibbon().loadNativeBannerAds(this)
        } else {
            EmbeddedTeaser().loadNativeADs(this, object : EmbeddedTeaser.NativeAdObserver {
                override fun onNativeAdLoaded() = runOnUiThread { fillNativeIfEmpty() }
                override fun onNativeAdFailed() = Unit
            })
        }
        // A load already in flight elsewhere doesn't call back here: look again once it may have landed.
        NATIVE_RECHECK_MS.forEach { binding.spotFrame.postDelayed({ fillNativeIfEmpty() }, it) }
    }

    private fun nativeReady(): Boolean =
        if (compactNative) EmbeddedTeaserRibbon.hasPreloadedNativeBanner() else EmbeddedTeaser.hasPreloadedNative()

    private fun fillNativeIfEmpty() {
        if (isFinishing || isDestroyed || binding.spotFrame.childCount > 0) return
        if (nativeReady()) renderNative()
    }

    private fun renderNative() {
        when {
            compactNative -> EmbeddedTeaserRibbon().showNativeBannerNative(this, binding.spotFrame)
            nativeType == "big" -> EmbeddedTeaser().showBigNative(this, binding.spotFrame)
            nativeType == "mid" -> EmbeddedTeaser().showMidNative(this, binding.spotFrame)
            else -> EmbeddedTeaser().showMidNative2(this, binding.spotFrame)
        }
    }

    // ── Searching ─────────────────────────────────────────────────────────

    /**
     * Country → state → approximate area, over at least [minMs]. The lookup starts at once; the
     * country stage covers it, and the state stage only starts once the answer names a state.
     */
    private fun locate(minMs: Long) = lifecycleScope.launch {
        val started = SystemClock.elapsedRealtime()
        fun at(ms: Long) = (started + ms - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

        val countryName = Realms.byIso(search.iso2)?.name ?: search.iso2
        val lookup = async { NumInquiry.run(this@PinpointingActivity, search.digits, search.iso2, search.dial) }

        // Stage 1 — the country, zooming in from further out.
        stage(getString(R.string.ds_locating_country, countryName), toPercent = 30, overMs = minMs * 3 / 10, step = Step.COUNTRY)
        SectorPlaceCoder.locate(this@PinpointingActivity, null, null, null, countryName)?.let { pin ->
            val point = GeoPoint(pin.lat, pin.lng)
            binding.lvBigMap.controller.setZoom(SectorExactness.COUNTRY.zoom - 2)
            binding.lvBigMap.controller.setCenter(point)
            binding.lvBigMap.controller.animateTo(point, SectorExactness.COUNTRY.zoom, FLY_MS)
        }
        delay(at(minMs * 3 / 10))

        val result = lookup.await()
        report = result
        onFound(result)

        // Stage 2 — the state, when the answer has one the map can find.
        val state = result.state?.takeIf { it.isNotBlank() }
        val statePin = state?.let { SectorPlaceCoder.locate(this@PinpointingActivity, null, null, it, result.countryName) }
        if (state != null && statePin != null) {
            binding.pillState.text = state
            stage(getString(R.string.ds_locating_state, state), toPercent = 70, overMs = minMs * 4 / 10, step = Step.STATE)
            binding.lvBigMap.controller.animateTo(GeoPoint(statePin.lat, statePin.lng), SectorExactness.STATE.zoom - 1, FLY_MS)
            delay(at(minMs * 7 / 10))
        }

        // Stage 3 — the approximate area, pulsing, until the minimum time is up.
        stage(getString(R.string.ds_locating_found, state ?: result.countryName), toPercent = 100, overMs = at(minMs), step = Step.AREA)
        placeArea(result, animate = true, knownState = statePin)
        delay(at(minMs))

        showResult(result)
    }

    /** The stage line, the step chips at [step], and the progress bar running on to [toPercent] over [overMs]. */
    private fun stage(text: String, toPercent: Int, overMs: Long, step: Step) {
        binding.txtStage.text = text
        bindSteps(step)
        progressAnim?.cancel()
        progressAnim = ObjectAnimator.ofInt(binding.prog, "progress", binding.prog.progress, toPercent * 10).apply {
            duration = overMs.coerceAtLeast(300L)
            interpolator = LinearInterpolator()
            addUpdateListener { binding.txtPercent.text = getString(R.string.ds_locating_percent, binding.prog.progress / 10) }
            start()
        }
        animators += progressAnim!!
    }

    /**
     * The approximate area with its pulse, on the report's own point: the most precise place the
     * lookup knows (pincode, city, then state), the same point Number Details shows. Centring on
     * the state's middle instead put a Surat number near Surendranagar, 250 km away. Falls back to
     * the state's (or country's) centre only when the report has no point of its own.
     */
    private suspend fun placeArea(result: NumDigest, animate: Boolean, knownState: TerraTack? = null) {
        val state = result.state?.takeIf { it.isNotBlank() }
        val own = result.pin
        val pin = own ?: knownState ?: SectorPlaceCoder.locate(this, null, null, state, result.countryName) ?: return
        if (isFinishing || isDestroyed) return
        val precision = when {
            own != null -> result.precision
            state != null -> SectorExactness.STATE
            else -> SectorExactness.COUNTRY
        }
        // This map ends as the small result card, where a city's circle at its own zoom would
        // cover the whole card; the card zoom keeps it a ring around the place.
        val zoom = StreetCharts.cardZoom(precision)
        if (animate) {
            binding.lvBigMap.controller.animateTo(GeoPoint(pin.lat, pin.lng), zoom, FLY_MS)
            delay(FLY_MS)
            if (isFinishing || isDestroyed) return
        }
        overlay?.let { it.stop(); binding.lvBigMap.overlays.remove(it) }
        overlay = StreetCharts.placeAt(binding.lvBigMap, pin, precision, zoom)
    }

    /** The lookup answered: saved to Recents (locked, unless it was unlocked before). */
    private fun onFound(result: NumDigest) {
        FreshHuntShelf(this).add(search.copy(searchedAt = System.currentTimeMillis(), report = result, unlocked = alreadyUnlocked))
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_FOUND, true))
    }

    // ── Found ─────────────────────────────────────────────────────────────

    /** The result (design 13b); Premium users and numbers unlocked before go straight through. */
    private fun showResult(result: NumDigest) {
        report = result
        if (alreadyUnlocked || PlusCheckout.isPremium(this)) return unlock(result)

        // Top bar: "Location found · Search complete · 100%", the Live pill gone.
        binding.txtTitle.setText(R.string.ds_location_found)
        binding.txtSearchNumber.setText(R.string.ds_locating_complete)
        binding.txtSearchNumber.setTextColor(ContextCompat.getColor(this, R.color.ds_success))
        binding.lvLivePill.isVisible = false
        // The map keeps only the area pin and its pill: the scan and the stage chips go.
        binding.lvScanGroup.isVisible = false
        binding.chartChips.isVisible = false
        binding.lvSearchingGroup.isVisible = false
        binding.txtAreaPill.isVisible = true
        shrinkMap()

        binding.txtCountryTile.text = search.iso2.uppercase()
        binding.lvNameRow.isVisible = !result.name.isNullOrBlank()
        binding.txtName.text = result.name
        binding.txtName.setBlurred(true)
        binding.txtNumber.text = result.international
        binding.txtRegion.text = listOfNotNull(result.state, result.countryName).distinct().joinToString(", ")
        if (result.valid) {
            binding.txtValidity.setText(R.string.ds_result_valid_short)
            binding.txtValidity.setBackgroundResource(R.drawable.lv_pill_success_soft)
            binding.txtValidity.setTextColor(ContextCompat.getColor(this, R.color.ds_success))
        } else {
            binding.txtValidity.setText(R.string.ds_verdict_unverified)
            binding.txtValidity.setBackgroundResource(R.drawable.lv_pill_warn)
            binding.txtValidity.setTextColor(ContextCompat.getColor(this, R.color.ds_warn))
        }

        cell(binding.boxCountry, R.string.ds_label_country, result.countryName, locked = false)
        cell(binding.boxLine, R.string.ds_fact_line, result.lineType.label(this), locked = false)
        cell(binding.boxCity, R.string.ds_label_city, result.city, locked = true)
        cell(binding.boxCarrier, R.string.ds_fact_carrier, result.carrier, locked = true)

        binding.verdictGroup.alpha = 0f
        binding.verdictGroup.translationY = dp(40).toFloat()
        binding.verdictGroup.isVisible = true
        binding.verdictGroup.animate().alpha(1f).translationY(0f).setDuration(450)
            .setInterpolator(CraftMovement.EASE_IN_OUT).start()
    }

    /** The big search map settles into the result's area card; the area stays centred. */
    private fun shrinkMap() {
        val from = binding.chartCard.height.takeIf { it > 0 } ?: return
        val to = dp(MAP_RESULT_DP)
        if (from <= to) return
        val center = binding.lvBigMap.mapCenter
        ValueAnimator.ofInt(from, to).apply {
            duration = 400L
            interpolator = CraftMovement.EASE_IN_OUT
            addUpdateListener {
                binding.chartCard.updateLayoutParams { height = it.animatedValue as Int }
                binding.lvBigMap.controller.setCenter(center)
            }
            start()
        }.also { animators += it }
    }

    /** A fact cell; a locked one blurs its value, a missing value just says so (no lock). */
    private fun cell(cell: CellResultCellBinding, @StringRes label: Int, value: String?, locked: Boolean) {
        cell.txtLabel.setText(label)
        val known = !value.isNullOrBlank()
        cell.txtValue.text = if (known) value else getString(R.string.ds_not_available)
        cell.txtValue.setTextColor(ContextCompat.getColor(this, if (known) R.color.ds_ink else R.color.ds_muted))
        cell.txtValue.setBlurred(locked && known)
        cell.imgLock.isVisible = locked && known
    }

    /**
     * Watch Ad: the rewarded ad plays straight away (no reveal dialog in between); earned →
     * Number Details. Closing it early, or cancelling the "try again" dialog, keeps it locked here.
     */
    private fun reveal() {
        val result = report ?: return
        if (watching) return
        watching = true
        binding.txtWatchAd.isInvisible = true
        binding.spinWatchAd.isVisible = true
        BonusTeaser().show(this) { outcome ->
            watching = false
            binding.txtWatchAd.isInvisible = false
            binding.spinWatchAd.isVisible = false
            when (outcome) {
                BonusTeaser.Result.EARNED -> unlock(result)
                BonusTeaser.Result.NOT_EARNED ->
                    Toast.makeText(this, R.string.ds_reward_not_earned, Toast.LENGTH_SHORT).show()
                BonusTeaser.Result.CANCELED -> Unit
            }
        }
    }

    /** Saves the result as unlocked and opens Number Details in this page's place. */
    private fun unlock(result: NumDigest) {
        if (isFinishing) return
        FreshHuntShelf(this).add(search.copy(searchedAt = System.currentTimeMillis(), report = result, unlocked = true))
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_FOUND, true))
        startActivity(NumProfile2Activity.intent(this, result))
        finish()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (::search.isInitialized) binding.lvBigMap.onResume()
    }

    override fun onPause() {
        if (::search.isInitialized) binding.lvBigMap.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        CraftMovement.cancelAll(animators)
        overlay?.stop()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_DIGITS = "digits"
        private const val EXTRA_ISO = "iso"
        private const val EXTRA_DIAL = "dial"
        private const val EXTRA_REPORT = "report"
        private const val EXTRA_UNLOCKED = "unlocked"

        /** Result extra: the lookup answered (the search box can be cleared). */
        const val EXTRA_FOUND = "found"

        /** The search animation's minimum length, so the native has its time on screen. */
        private const val MIN_SEARCH_MS = 10_000L

        /** Premium sees no ad, so there is nothing to hold the page for. */
        private const val MIN_SEARCH_PREMIUM_MS = 4_000L

        /** When to look for a native that another screen's load delivered to the shared slot. */
        private val NATIVE_RECHECK_MS = longArrayOf(2_500L, 5_000L, 8_000L)

        /** This page's `ScreenAds` entry (its `show` and `nativeType`). */
        private const val SCREEN_KEY = "PinpointingActivity"

        private const val FLY_MS = 1_800L

        /** The approximate-area card of design 13b. */
        private const val MAP_RESULT_DP = 150

        /**
         * A new search ([saved] null) or a locked recent one reopened at its result ([saved]).
         * [unlocked]: the number was unlocked before, or the search was bought with an ad.
         */
        fun intent(context: Context, search: FreshHunt, saved: NumDigest?, unlocked: Boolean): Intent =
            Intent(context, PinpointingActivity::class.java)
                .putExtra(EXTRA_DIGITS, search.digits)
                .putExtra(EXTRA_ISO, search.iso2)
                .putExtra(EXTRA_DIAL, search.dial)
                .putExtra(EXTRA_UNLOCKED, unlocked)
                .apply { if (saved != null) putExtra(EXTRA_REPORT, saved) }
    }
}
