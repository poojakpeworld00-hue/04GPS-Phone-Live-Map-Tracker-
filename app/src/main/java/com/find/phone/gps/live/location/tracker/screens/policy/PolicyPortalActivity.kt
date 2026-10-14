package com.find.phone.gps.live.location.tracker.screens.policy

import com.find.phone.gps.live.location.tracker.monetize.logic.recordPermissionResult
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.find.phone.gps.live.location.tracker.induction.MemoOpeningActivity
import com.find.phone.gps.live.location.tracker.screens.CoreHubActivity
import com.find.phone.gps.live.location.tracker.screens.opening.OpeningUnveilProfile
import com.find.phone.gps.live.location.tracker.induction.HomeStream
import com.find.phone.gps.live.location.tracker.screens.opening.OpeningUnveilCharter
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.databinding.ScreenTermsBinding
import com.find.phone.gps.live.location.tracker.screens.welcome.BriefingActivity

class PolicyPortalActivity : StageActivity<ScreenTermsBinding>() {
    override val layoutId: Int = R.layout.screen_terms

    private val prefs by lazy { LockerBook(this) }

    private var awaitingOverlay = false
    private var settingsShown = false
    private var proceeded = false
    private var watchRegistered = false
    private var overlayAsked = false

    private val overlayGrantedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            proceedToNextScreen()
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        proceedToNextScreen() }

    override fun initView() {
        OpeningUnveilCharter.flagShown(this, OpeningUnveilProfile.TERMS)

        ViewCompat.setOnApplyWindowInsetsListener(binding.policyRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.checkAgree.isChecked = true
        setAcceptEnabled(true)

        binding.checkAgree.setOnCheckedChangeListener { _, checked -> setAcceptEnabled(checked) }

        binding.txtAgree.setOnClickListener { binding.checkAgree.toggle() }
        binding.actAccept.setOnClickListener { onAccept() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                prefs.isTermsAccepted = true
                proceedToNextScreen()
            }
        })
    }

    override fun onResume() {
        super.onResume()

        if (awaitingOverlay && settingsShown) {
            awaitingOverlay = false
            settingsShown = false
            proceedToNextScreen()
        }
    }

    override fun onPause() {
        super.onPause()

        if (awaitingOverlay) settingsShown = true
    }

    override fun onDestroy() {
        haltWatch()
        super.onDestroy()
    }

    private fun setAcceptEnabled(enabled: Boolean) {
        binding.actAccept.isEnabled = enabled
        binding.actAccept.alpha = if (enabled) 1f else 0.5f
    }

    private fun onAccept() {
        if (!binding.checkAgree.isChecked) {
            Toast.makeText(this, R.string.terms_please_accept, Toast.LENGTH_SHORT).show()
            return
        }
        prefs.isTermsAccepted = true
        enterOverlayPermission()
    }

    private fun enterOverlayPermission() {
        if (DriftToolbox.isGranted(this) || !DriftToolbox.isOfferable(this)) {
            if (DriftToolbox.isGranted(this)) recordKeyEvent("Permission_SYSTEM_ALERT_WINDOW_Bypass")
            proceedToNextScreen()
            return
        }

        val launched = runCatching {
            overlayLauncher.launch(DriftToolbox.composeOverlayIntent(packageName))
        }.isSuccess

        if (!launched) {
            recordKeyEvent("Permission_SYSTEM_ALERT_WINDOW_Bypass")
            proceedToNextScreen()
            return
        }

        awaitingOverlay = true
        settingsShown = false
        overlayAsked = true
        recordKeyEvent("Permission_SYSTEM_ALERT_WINDOW_Show")

        ContextCompat.registerReceiver(
            this,
            overlayGrantedReceiver,
            IntentFilter(DriftWatchService.ACTION_OVERLAY_GRANTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        watchRegistered = true
        DriftWatchService.start(this)
    }

    private fun proceedToNextScreen() {
        if (proceeded) return
        proceeded = true
        haltWatch()
        if (overlayAsked) {
            recordPermissionResult(android.Manifest.permission.SYSTEM_ALERT_WINDOW, DriftToolbox.isGranted(this))
        }

        val next = if (OpeningUnveilCharter.mustShowOnboarding(this)) BriefingActivity::class.java
        else HomeStream.homeActivity()
        if (next == HomeStream.homeActivity()) {
            HomeStream.flagOnboardingCompleted(this)
        }
        val intent = Intent(this, next).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        startActivity(if (next == HomeStream.homeActivity()) MemoOpeningActivity.wrap(this, intent) else intent)
        finish()
    }

    private fun haltWatch() {
        if (watchRegistered) {
            runCatching { unregisterReceiver(overlayGrantedReceiver) }
            watchRegistered = false
        }
        DriftWatchService.stop(this)
    }
}
