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

abstract class StageActivity<DB : ViewDataBinding> : SpotBeaconActivity() {
    protected lateinit var binding: DB
        private set

    @get:LayoutRes
    protected abstract val layoutId: Int

    override fun onCreate(savedInstanceState: Bundle?) {
        enactLocale()
        enactTheme(CoreLocker.effectiveTheme(this))
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, layoutId)

        recordKeyEvent("screen_${this::class.java.simpleName.lowercase(Locale.ROOT)}")
        binding.lifecycleOwner = this

        enactNativeAdTheme()

        onBackPressedDispatcher.addCallback(this, backAdCallback)

        initView()
        initObservers()

        showBottomBanner()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        NavPressTracker.onLeaveHint(this)
    }

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        NavPressTracker.markAppLaunch()
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    private val backAdCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = goBack()
    }

    protected fun goBack() {
        TakeoverRear().presentBackAds(this) { performBack() }
    }

    protected open fun performBack() {
        if (!isFinishing) finish()
    }

    protected open fun showBottomBanner() {
        val container = binding.root.findViewById<FrameLayout>(R.id.ribbonAdFrame) ?: return
        val shimmer = binding.root.findViewById<ShimmerFrameLayout>(R.id.ribbonShimmer)
        DisplayTeaserProfile.showAd(this::class.java.simpleName, this, container, shimmer)

        binding.root.findViewById<View>(R.id.spotBannerDivider)?.followAdContainer(container)
    }

    protected open fun initView() {}

    protected open fun initObservers() {}

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
            else -> Unit
        }
    }


    private var pendingCallNumber: String? = null

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        recordPermissionResult(Manifest.permission.CALL_PHONE, granted)
        val number = pendingCallNumber
        pendingCallNumber = null
        if (number != null) if (granted) beginCall(number) else enterDialer(number)
    }

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

    }

    protected fun enactLocale() = LingoBook.enactSaved(this)

    protected open fun enactTheme(theme: String) {
        when (theme) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
