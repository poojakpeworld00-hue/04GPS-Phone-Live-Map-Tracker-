package com.find.my.phone.gps.location.tracker.ui.lookup

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
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.adcast.domain.ScreenPromoConfig
import com.find.my.phone.gps.location.tracker.adcast.presentation.NativePromo
import com.find.my.phone.gps.location.tracker.adcast.presentation.NativePromoBanner
import com.find.my.phone.gps.location.tracker.adcast.presentation.RewardedPromo
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.data.lookup.AreaGeocoder
import com.find.my.phone.gps.location.tracker.data.lookup.AreaPrecision
import com.find.my.phone.gps.location.tracker.data.lookup.GeoPin
import com.find.my.phone.gps.location.tracker.data.lookup.NumberLookup
import com.find.my.phone.gps.location.tracker.data.lookup.NumberReport
import com.find.my.phone.gps.location.tracker.data.premium.PremiumBilling
import com.find.my.phone.gps.location.tracker.databinding.TileResultCellBinding
import com.find.my.phone.gps.location.tracker.databinding.ViewLocatingBinding
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.home.RecentSearch
import com.find.my.phone.gps.location.tracker.ui.home.RecentSearchStore
import com.find.my.phone.gps.location.tracker.ui.premium.PremiumActivity
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
class LocatingActivity : CanvasActivity<ViewLocatingBinding>() {

    override val layoutId: Int = R.layout.view_locating

    private lateinit var search: RecentSearch
    private var saved: NumberReport? = null
    private var alreadyUnlocked = false
    private var report: NumberReport? = null
    private var overlay: AreaPulseOverlay? = null
    private val animators = mutableListOf<Animator>()
    private var progressAnim: ObjectAnimator? = null
    private var watching = false

    private val premiumPage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val result = report ?: return@registerForActivityResult
        if (PremiumBilling.isPremium(this)) unlock(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // osmdroid must be configured before the MapView is inflated (in super.onCreate).
        OsmMaps.init(this)
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
        search = RecentSearch(digits, iso, dial, System.currentTimeMillis())
        saved = IntentCompat.getSerializableExtra(intent, EXTRA_REPORT, NumberReport::class.java)
        alreadyUnlocked = intent.getBooleanExtra(EXTRA_UNLOCKED, false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.locRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.tvSearchNumber.text = search.display
        binding.btnBack.setOnClickListener { goBack() }
        binding.btnNotNow.setOnClickListener { finish() }
        DesignMotion.pressScale(binding.btnWatchAd)
        binding.btnWatchAd.setOnClickListener { reveal() }
        binding.btnSkipPro.setOnClickListener {
            if (report == null) return@setOnClickListener
            logKeyEvent("result_skip_pro")
            premiumPage.launch(PremiumActivity.intent(this))
        }
        binding.mapCard.clipToOutline = true
        OsmMaps.style(binding.bigMap, interactive = false)

        val premium = PremiumBilling.isPremium(this)
        if (!premium) {
            RewardedPromo.preload(this)
            showNative()
        }

        val given = saved
        if (given != null) {
            binding.mapCard.updateLayoutParams { height = dp(MAP_RESULT_DP) }
            lifecycleScope.launch { placeArea(given, animate = false) }
            showResult(given)
        } else {
            binding.chipCountry.text = Territories.byIso(search.iso2)?.name ?: search.iso2
            binding.chipState.setText(R.string.ds_step_state)
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
            Triple(binding.stepCountry, binding.chipCountry, R.string.ds_step_country),
            Triple(binding.stepState, binding.chipState, R.string.ds_step_state),
            Triple(binding.stepArea, binding.chipArea, R.string.ds_step_area),
        )
        steps.forEachIndexed { index, (step, chip, label) ->
            val name = getString(label)
            when {
                index < current.ordinal -> {
                    step.text = getString(R.string.ds_step_done, name)
                    step.setBackgroundResource(R.drawable.ds_tile_success12_r10)
                    step.setTextColor(ContextCompat.getColor(this, R.color.ds_success))
                    chip.setBackgroundResource(R.drawable.ds_tile_primary_r10)
                    chip.setTextColor(ContextCompat.getColor(this, R.color.white))
                }
                index == current.ordinal -> {
                    step.text = getString(R.string.ds_step_active, name)
                    step.setBackgroundResource(R.drawable.ds_tile_tint_r10)
                    step.setTextColor(ContextCompat.getColor(this, R.color.ds_primary))
                    chip.setBackgroundResource(R.drawable.ds_tile_white_r10)
                    chip.setTextColor(ContextCompat.getColor(this, R.color.ds_primary))
                }
                else -> {
                    step.text = name
                    step.setBackgroundResource(R.drawable.ds_tile_gray_r10)
                    step.setTextColor(ContextCompat.getColor(this, R.color.ds_placeholder))
                    chip.setBackgroundResource(R.drawable.ds_tile_white70_r10)
                    chip.setTextColor(ContextCompat.getColor(this, R.color.ds_placeholder))
                }
            }
        }
    }

    // ── Bottom native ─────────────────────────────────────────────────────

    /**
     * The template of the bottom native, from `ScreenAds.LocatingActivity.nativeType`: `small`
     * (or `native_banner`) is the one-line native banner of design 13b, which keeps Not now and
     * Skip with PRO above the fold; `big` / `mid` / `mid2` are the body natives.
     */
    private val nativeType: String by lazy {
        ScreenPromoConfig.resolve(this, SCREEN_KEY).nativeType.trim().lowercase()
    }

    private val compactNative: Boolean
        get() = nativeType == "small" || nativeType == "native_banner" || nativeType == "nativebanner"

    /**
     * The native at the bottom. The shared native pools are filled by the splash chain and
     * emptied by every screen that shows one, so when the pool is empty here the page loads
     * its own and shows it once it lands — otherwise the slot would sit blank for the whole search.
     */
    private fun showNative() {
        binding.adFrame.isVisible = true
        if (nativeReady()) return renderNative()
        if (compactNative) {
            NativePromoBanner().loadNativeBannerAds(this)
        } else {
            NativePromo().loadNativeADs(this, object : NativePromo.NativeAdObserver {
                override fun onNativeAdLoaded() = runOnUiThread { fillNativeIfEmpty() }
                override fun onNativeAdFailed() = Unit
            })
        }
        // A load already in flight elsewhere doesn't call back here: look again once it may have landed.
        NATIVE_RECHECK_MS.forEach { binding.adFrame.postDelayed({ fillNativeIfEmpty() }, it) }
    }

    private fun nativeReady(): Boolean =
        if (compactNative) NativePromoBanner.hasPreloadedNativeBanner() else NativePromo.hasPreloadedNative()

    private fun fillNativeIfEmpty() {
        if (isFinishing || isDestroyed || binding.adFrame.childCount > 0) return
        if (nativeReady()) renderNative()
    }

    private fun renderNative() {
        when {
            compactNative -> NativePromoBanner().showNativeBannerNative(this, binding.adFrame)
            nativeType == "big" -> NativePromo().showBigNative(this, binding.adFrame)
            nativeType == "mid" -> NativePromo().showMidNative(this, binding.adFrame)
            else -> NativePromo().showMidNative2(this, binding.adFrame)
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

        val countryName = Territories.byIso(search.iso2)?.name ?: search.iso2
        val lookup = async { NumberLookup.run(this@LocatingActivity, search.digits, search.iso2, search.dial) }

        // Stage 1 — the country, zooming in from further out.
        stage(getString(R.string.ds_locating_country, countryName), toPercent = 30, overMs = minMs * 3 / 10, step = Step.COUNTRY)
        AreaGeocoder.locate(this@LocatingActivity, null, null, null, countryName)?.let { pin ->
            val point = GeoPoint(pin.lat, pin.lng)
            binding.bigMap.controller.setZoom(AreaPrecision.COUNTRY.zoom - 2)
            binding.bigMap.controller.setCenter(point)
            binding.bigMap.controller.animateTo(point, AreaPrecision.COUNTRY.zoom, FLY_MS)
        }
        delay(at(minMs * 3 / 10))

        val result = lookup.await()
        report = result
        onFound(result)

        // Stage 2 — the state, when the answer has one the map can find.
        val state = result.state?.takeIf { it.isNotBlank() }
        val statePin = state?.let { AreaGeocoder.locate(this@LocatingActivity, null, null, it, result.countryName) }
        if (state != null && statePin != null) {
            binding.chipState.text = state
            stage(getString(R.string.ds_locating_state, state), toPercent = 70, overMs = minMs * 4 / 10, step = Step.STATE)
            binding.bigMap.controller.animateTo(GeoPoint(statePin.lat, statePin.lng), AreaPrecision.STATE.zoom - 1, FLY_MS)
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
        binding.tvStage.text = text
        bindSteps(step)
        progressAnim?.cancel()
        progressAnim = ObjectAnimator.ofInt(binding.progress, "progress", binding.progress.progress, toPercent * 10).apply {
            duration = overMs.coerceAtLeast(300L)
            interpolator = LinearInterpolator()
            addUpdateListener { binding.tvPercent.text = getString(R.string.ds_locating_percent, binding.progress.progress / 10) }
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
    private suspend fun placeArea(result: NumberReport, animate: Boolean, knownState: GeoPin? = null) {
        val state = result.state?.takeIf { it.isNotBlank() }
        val own = result.pin
        val pin = own ?: knownState ?: AreaGeocoder.locate(this, null, null, state, result.countryName) ?: return
        if (isFinishing || isDestroyed) return
        val precision = when {
            own != null -> result.precision
            state != null -> AreaPrecision.STATE
            else -> AreaPrecision.COUNTRY
        }
        // This map ends as the small result card, where a city's circle at its own zoom would
        // cover the whole card; the card zoom keeps it a ring around the place.
        val zoom = OsmMaps.cardZoom(precision)
        if (animate) {
            binding.bigMap.controller.animateTo(GeoPoint(pin.lat, pin.lng), zoom, FLY_MS)
            delay(FLY_MS)
            if (isFinishing || isDestroyed) return
        }
        overlay?.let { it.stop(); binding.bigMap.overlays.remove(it) }
        overlay = OsmMaps.placeAt(binding.bigMap, pin, precision, zoom)
    }

    /** The lookup answered: saved to Recents (locked, unless it was unlocked before). */
    private fun onFound(result: NumberReport) {
        RecentSearchStore(this).add(search.copy(searchedAt = System.currentTimeMillis(), report = result, unlocked = alreadyUnlocked))
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_FOUND, true))
    }

    // ── Found ─────────────────────────────────────────────────────────────

    /** The result (design 13b); Premium users and numbers unlocked before go straight through. */
    private fun showResult(result: NumberReport) {
        report = result
        if (alreadyUnlocked || PremiumBilling.isPremium(this)) return unlock(result)

        // Top bar: "Location found · Search complete · 100%", the Live pill gone.
        binding.tvTitle.setText(R.string.ds_location_found)
        binding.tvSearchNumber.setText(R.string.ds_locating_complete)
        binding.tvSearchNumber.setTextColor(ContextCompat.getColor(this, R.color.ds_success))
        binding.livePill.isVisible = false
        // The map keeps only the area pin and its pill: the scan and the stage chips go.
        binding.scanGroup.isVisible = false
        binding.mapChips.isVisible = false
        binding.searchingGroup.isVisible = false
        binding.tvAreaPill.isVisible = true
        shrinkMap()

        binding.tvCountryTile.text = search.iso2.uppercase()
        binding.nameRow.isVisible = !result.name.isNullOrBlank()
        binding.tvName.text = result.name
        binding.tvName.setBlurred(true)
        binding.tvNumber.text = result.international
        binding.tvRegion.text = listOfNotNull(result.state, result.countryName).distinct().joinToString(", ")
        if (result.valid) {
            binding.tvValidity.setText(R.string.ds_result_valid_short)
            binding.tvValidity.setBackgroundResource(R.drawable.ds_pill_success_soft)
            binding.tvValidity.setTextColor(ContextCompat.getColor(this, R.color.ds_success))
        } else {
            binding.tvValidity.setText(R.string.ds_verdict_unverified)
            binding.tvValidity.setBackgroundResource(R.drawable.ds_pill_warn)
            binding.tvValidity.setTextColor(ContextCompat.getColor(this, R.color.ds_warn))
        }

        cell(binding.cellCountry, R.string.ds_label_country, result.countryName, locked = false)
        cell(binding.cellLine, R.string.ds_fact_line, result.lineType.label(this), locked = false)
        cell(binding.cellCity, R.string.ds_label_city, result.city, locked = true)
        cell(binding.cellCarrier, R.string.ds_fact_carrier, result.carrier, locked = true)

        binding.resultGroup.alpha = 0f
        binding.resultGroup.translationY = dp(40).toFloat()
        binding.resultGroup.isVisible = true
        binding.resultGroup.animate().alpha(1f).translationY(0f).setDuration(450)
            .setInterpolator(DesignMotion.EASE_IN_OUT).start()
    }

    /** The big search map settles into the result's area card; the area stays centred. */
    private fun shrinkMap() {
        val from = binding.mapCard.height.takeIf { it > 0 } ?: return
        val to = dp(MAP_RESULT_DP)
        if (from <= to) return
        val center = binding.bigMap.mapCenter
        ValueAnimator.ofInt(from, to).apply {
            duration = 400L
            interpolator = DesignMotion.EASE_IN_OUT
            addUpdateListener {
                binding.mapCard.updateLayoutParams { height = it.animatedValue as Int }
                binding.bigMap.controller.setCenter(center)
            }
            start()
        }.also { animators += it }
    }

    /** A fact cell; a locked one blurs its value, a missing value just says so (no lock). */
    private fun cell(cell: TileResultCellBinding, @StringRes label: Int, value: String?, locked: Boolean) {
        cell.tvLabel.setText(label)
        val known = !value.isNullOrBlank()
        cell.tvValue.text = if (known) value else getString(R.string.ds_not_available)
        cell.tvValue.setTextColor(ContextCompat.getColor(this, if (known) R.color.ds_ink else R.color.ds_muted))
        cell.tvValue.setBlurred(locked && known)
        cell.ivLock.isVisible = locked && known
    }

    /**
     * Watch Ad: the rewarded ad plays straight away (no reveal dialog in between); earned →
     * Number Details. Closing it early, or cancelling the "try again" dialog, keeps it locked here.
     */
    private fun reveal() {
        val result = report ?: return
        if (watching) return
        watching = true
        binding.tvWatchAd.isInvisible = true
        binding.pbWatchAd.isVisible = true
        RewardedPromo().show(this) { outcome ->
            watching = false
            binding.tvWatchAd.isInvisible = false
            binding.pbWatchAd.isVisible = false
            when (outcome) {
                RewardedPromo.Result.EARNED -> unlock(result)
                RewardedPromo.Result.NOT_EARNED ->
                    Toast.makeText(this, R.string.ds_reward_not_earned, Toast.LENGTH_SHORT).show()
                RewardedPromo.Result.CANCELED -> Unit
            }
        }
    }

    /** Saves the result as unlocked and opens Number Details in this page's place. */
    private fun unlock(result: NumberReport) {
        if (isFinishing) return
        RecentSearchStore(this).add(search.copy(searchedAt = System.currentTimeMillis(), report = result, unlocked = true))
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_FOUND, true))
        startActivity(NumberDetailActivity.intent(this, result))
        finish()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (::search.isInitialized) binding.bigMap.onResume()
    }

    override fun onPause() {
        if (::search.isInitialized) binding.bigMap.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        DesignMotion.cancelAll(animators)
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
        private const val SCREEN_KEY = "LocatingActivity"

        private const val FLY_MS = 1_800L

        /** The approximate-area card of design 13b. */
        private const val MAP_RESULT_DP = 150

        /**
         * A new search ([saved] null) or a locked recent one reopened at its result ([saved]).
         * [unlocked]: the number was unlocked before, or the search was bought with an ad.
         */
        fun intent(context: Context, search: RecentSearch, saved: NumberReport?, unlocked: Boolean): Intent =
            Intent(context, LocatingActivity::class.java)
                .putExtra(EXTRA_DIGITS, search.digits)
                .putExtra(EXTRA_ISO, search.iso2)
                .putExtra(EXTRA_DIAL, search.dial)
                .putExtra(EXTRA_UNLOCKED, unlocked)
                .apply { if (saved != null) putExtra(EXTRA_REPORT, saved) }
    }
}
