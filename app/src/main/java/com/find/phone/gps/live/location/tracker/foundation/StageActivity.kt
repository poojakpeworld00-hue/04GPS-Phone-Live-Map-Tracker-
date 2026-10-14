package com.find.phone.gps.live.location.tracker.foundation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import com.facebook.shimmer.ShimmerFrameLayout
import com.find.phone.gps.live.location.tracker.monetize.logic.DisplayTeaserProfile
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordPermissionResult
import com.find.phone.gps.live.location.tracker.monetize.render.SpotBeaconActivity
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverRear
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverStandard
import com.find.phone.gps.live.location.tracker.store.LingoBook
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker
import com.find.phone.gps.live.location.tracker.helpers.enactNativeAdTheme
import com.find.phone.gps.live.location.tracker.helpers.followAdContainer
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker.THEME_DARK
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker.THEME_LIGHT
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker.THEME_SYSTEM
import java.util.Locale
import kotlin.sequences.ifEmpty

/**
 * Base class for every Activity in the app.
 *
 * Handles DataBinding inflation, binds the lifecycle owner and exposes
 * [initView] / [initObservers] hooks so subclasses stay lean.
 *
 * Usage:
 * ```
 * class CoreHubActivity : StageActivity<ScreenMainBinding>() {
 *     override val layoutId = R.layout.screen_main
 *     override fun initView() { ... }
 * }
 * ```
 */
abstract class StageActivity<DB : ViewDataBinding> : SpotBeaconActivity() {

    protected lateinit var binding: DB
        private set

    /** Layout resource that is wrapped in a `<layout>` tag for DataBinding. */
    @get:LayoutRes
    protected abstract val layoutId: Int

    override fun onCreate(savedInstanceState: Bundle?) {
        enactLocale()
        enactTheme(CoreLocker.effectiveTheme(this))
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, layoutId)

        recordKeyEvent("screen_${this::class.java.simpleName.lowercase(Locale.ROOT)}")
        binding.lifecycleOwner = this

        // Keep native-ad colors in sync with the active light/dark mode. Shared with the
        // launcher home, which needs it too but does not extend this class.
        enactNativeAdTheme()

        // Whole-app back-press → back interstitial, then finish. Registered here
        // (before initView) so any custom OnBackPressedCallback a subclass adds
        // in initView() is enqueued later and takes priority over this fallback.
        // Note: forward/back interstitials are preloaded in SpotBeaconActivity, so we
        // don't preload again here.
        onBackPressedDispatcher.addCallback(this, backAdCallback)

        initView()
        initObservers()

        // Auto on-load bottom banner for any screen whose layout includes
        // @layout/part_bottom_banner (no-op otherwise).
        showBottomBanner()
    }

    /** Default back-press handler for the whole app: back-ad then [performBack]. */
    private val backAdCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = goBack()
    }

    /**
     * Shows the back interstitial (when the remote-config gates allow it) and
     * then runs [performBack]. Toolbar back buttons can call this instead of
     * `finish()` to also surface a back ad.
     */
    protected fun goBack() {
        TakeoverRear().presentBackAds(this) { performBack() }
    }

    /** What "back" does after the ad — defaults to finishing. Override for custom nav. */
    protected open fun performBack() {
        if (!isFinishing) finish()
    }

    /**
     * Auto-loads the on-load bottom banner — but only if this screen's layout
     * includes `@layout/part_bottom_banner` (ids `bannerAdFrame` + `bannerShimmer`).
     * Called automatically after [initView]; screens without the include are a
     * no-op. The screen key is the activity's simple class name (e.g.
     * "BlocklistActivity"), which must match a key under `ScreenAds` in Remote
     * Config — otherwise it falls back to `ScreenAds.default`. Banner-first; a
     * native banner is shown if the banner fails.
     *
     * To put a banner on any screen: just add the include to its layout. No
     * Kotlin change needed. Override to customise.
     */
    protected open fun showBottomBanner() {
        val container = binding.root.findViewById<FrameLayout>(R.id.ribbonAdFrame) ?: return
        val shimmer = binding.root.findViewById<ShimmerFrameLayout>(R.id.ribbonShimmer)
        DisplayTeaserProfile.showAd(this::class.java.simpleName, this, container, shimmer)
        // The hairline above the slot only exists to fence off an advert — drop it
        // whenever the slot ends up empty (ads off, show:false, load failure).
        binding.root.findViewById<View>(R.id.spotBannerDivider)?.followAdContainer(container)
    }

    /** Set up views, listeners, adapters. */
    protected open fun initView() {}

    /** Subscribe to ViewModel LiveData / Flows. */
    protected open fun initObservers() {}

    // --- Shared runtime-permission handling ---

    /**
     * Requests [permission] through [launcher], but once the user has denied it twice
     * (permanently denied — the system no longer shows its dialog), opens the app's
     * settings page so they can enable it manually.
     */
    protected fun demandPermissionManaged(
        permission: String,
        launcher: ActivityResultLauncher<String>
    ) {
        val prefs = LockerBook(this)
        when {
            !prefs.hasRequestedPermission(permission) -> {
                prefs.flagPermissionRequested(permission)
                launcher.launch(permission)
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, permission) ->
                launcher.launch(permission)
            else -> enterAppSettings()
        }
    }

    /** Opens this app's system settings (App info) screen. */
    protected fun enterAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }
    }

    // --- Shared direct-calling (CALL_PHONE) ---

    private var pendingCallNumber: String? = null

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        recordPermissionResult(Manifest.permission.CALL_PHONE, granted)
        val number = pendingCallNumber
        pendingCallNumber = null
        if (number != null) if (granted) beginCall(number) else enterDialer(number)
    }

    /** Places the call directly (CALL_PHONE), requesting the permission if needed. */
    protected fun siteCall(number: String) {
        if (number.isBlank()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            beginCall(number)
        } else {
            pendingCallNumber = number
            demandPermissionManaged(Manifest.permission.CALL_PHONE, callPermissionLauncher)
        }
    }

    private fun beginCall(number: String) {
        val placed = runCatching {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))); true
        }.getOrDefault(false)
        if (!placed) enterDialer(number)
    }

    private fun enterDialer(number: String) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
    }

    override fun onResume() {
        super.onResume()
        enactLocale()
        TakeoverStandard.processTabReturn(this)
        // NOTE: no LightHouse.syncPermissionsAsync() here — the SDK syncs
        // permissions internally (≥0.6.4), so an explicit call is redundant.
    }
    /**
     * Applies the saved app language ([LockerBook.languageTag]) via AppCompat.
     *
     * Through [LingoBook], not AppCompat directly: the framework throws when the restart
     * it wants would touch the home task, and this runs before super.onCreate on every screen —
     * an escape here takes the whole activity down. Shared with the launcher home, which needs
     * it too but does not extend this class.
     */
    protected fun enactLocale() = LingoBook.enactSaved(this)

    /** Applies the saved night-mode ([LockerBook.themeMode]) app-wide. */
    protected open fun enactTheme(theme: String) {
        when (theme) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
