package com.find.phone.gps.live.location.tracker.screens

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.render.InCoreRefreshBook
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.databinding.ScreenMainBinding
import com.find.phone.gps.live.location.tracker.homeshell.HomeFlip
import com.find.phone.gps.live.location.tracker.screens.dashboard.MainHubFragment
import com.find.phone.gps.live.location.tracker.screens.dashboard.MainSliderOptions
import com.find.phone.gps.live.location.tracker.screens.dashboard.MainCasingDirector
import com.find.phone.gps.live.location.tracker.screens.dashboard.MainCasingHolder
import com.google.android.material.snackbar.Snackbar
import com.livemap.homedeck.screens.HomeDeck as LauncherHomeActivity

class CoreHubActivity : StageActivity<ScreenMainBinding>(), MainCasingHolder, MainHubFragment.MenuHost {
    override val layoutId: Int = R.layout.screen_main

    override val hostActivity: AppCompatActivity get() = this

    override val homeShellController = MainCasingDirector(this)

    override val isShellOnScreen: Boolean get() = true

    private var lastBackAt = 0L
    private var exitToast: Toast? = null

    private var drawerMenu: MainSliderOptions? = null

    private val phonePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            drawerMenu?.onPhonePermissionResult(it)
        }

    private val homeFragment: MainHubFragment?
        get() = supportFragmentManager.findFragmentById(R.id.deskContainer) as? MainHubFragment

    override fun showUpdateReadyPrompt() {
        Snackbar.make(binding.core, R.string.update_ready_msg, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.update_restart) { InCoreRefreshBook.completeUpdate() }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        com.find.phone.gps.live.location.tracker.monetize.logic.EventSession.endFirstSession(this)
        homeShellController.onHostCreated()
    }

    override fun initView() {
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        homeFragment?.setOnScreen(true)

        drawerMenu = MainSliderOptions(
            this,
            binding.sliderMenu,
            requestPhonePermission = { phonePermission.launch(it) },
        ) {
            binding.core.closeDrawer(GravityCompat.START)
        }.also { it.bind(); it.watch(binding.core) }

        onBackPressedDispatcher.addCallback(this) {
            if (binding.core.isDrawerOpen(GravityCompat.START)) binding.core.closeDrawer(GravityCompat.START)
            else onShellBackExhausted()
        }

        homeShellController.beginFirstRunPriming()
    }

    override fun showBottomBanner() = Unit

    override fun openHomeMenu() {
        binding.core.openDrawer(GravityCompat.START)
    }

    override fun onResume() {
        super.onResume()
        homeShellController.onHostResume()

        drawerMenu?.refresh()
    }

    override fun onDestroy() {
        homeShellController.onHostDestroy()
        super.onDestroy()
    }

    override fun onShellBackExhausted() {
        if (!HomeFlip.isEnabled(this)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackAt <= EXIT_WINDOW_MS) {
                exitToast?.cancel()
                finish()
            } else {
                lastBackAt = now
                exitToast?.cancel()
                exitToast = Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).also { it.show() }
            }
            return
        }
        runCatching {
            startActivity(
                Intent(this, LauncherHomeActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
        finish()
    }

    override fun bringHostToFront() {
        runCatching {
            startActivity(Intent(this, CoreHubActivity::class.java).addFlags(MainCasingDirector.REORDER_FLAGS))
        }
    }

    private companion object {
        const val EXIT_WINDOW_MS = 2_000L
    }
}
