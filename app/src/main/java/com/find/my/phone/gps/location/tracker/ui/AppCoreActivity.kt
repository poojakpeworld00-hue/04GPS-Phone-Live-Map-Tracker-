package com.find.my.phone.gps.location.tracker.ui

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
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.presentation.InAppUpdateRegistry
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.databinding.ViewMainBinding
import com.find.my.phone.gps.location.tracker.launcher.LauncherSwitch
import com.find.my.phone.gps.location.tracker.ui.home.HomeCoreFragment
import com.find.my.phone.gps.location.tracker.ui.home.HomeDrawerMenu
import com.find.my.phone.gps.location.tracker.ui.home.HomeShellController
import com.find.my.phone.gps.location.tracker.ui.home.HomeShellHost
import com.google.android.material.snackbar.Snackbar
import io.launcher.home.activities.LauncherPanel as LauncherHomeActivity

/**
 * The standalone home: [HomeCoreFragment] (design 3a–3e, "one task up front — number in, search
 * out") inside a DrawerLayout whose left drawer is the 3e menu ([HomeDrawerMenu]). The same
 * fragment rides in the launcher's swipe-in panel.
 *
 * The Activity-bound flows (RC permission sheet, in-app update) live in [HomeShellController],
 * exactly as in the reference.
 */
class AppCoreActivity : CanvasActivity<ViewMainBinding>(), HomeShellHost, HomeCoreFragment.MenuHost {

    override val layoutId: Int = R.layout.view_main

    override val hostActivity: AppCompatActivity get() = this

    // Not `by lazy`: constructing this registers result launchers, which must happen before
    // the Activity is STARTED.
    override val homeShellController = HomeShellController(this)

    override val isShellOnScreen: Boolean get() = true

    /** When the last "press back again" Back came, for double-back-to-exit (launcher mode off). */
    private var lastBackAt = 0L
    private var exitToast: Toast? = null

    /** The 3e drawer menu; bound once the layout is up. */
    private var drawerMenu: HomeDrawerMenu? = null

    // Phone access for the drawer's "My number" card. Registered at construction (before STARTED).
    private val phonePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            drawerMenu?.onPhonePermissionResult(it)
        }

    private val homeFragment: HomeCoreFragment?
        get() = supportFragmentManager.findFragmentById(R.id.homeContainer) as? HomeCoreFragment

    override fun showUpdateReadyPrompt() {
        Snackbar.make(binding.main, R.string.update_ready_msg, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.update_restart) { InAppUpdateRegistry.completeUpdate() }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        homeShellController.onHostCreated()
    }

    override fun initView() {
        // The hero gradient runs under the status bar (light icons); the fragment pads its header.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        // The content is the screen here, so its ads may load right away.
        homeFragment?.setOnScreen(true)

        drawerMenu = HomeDrawerMenu(
            this,
            binding.drawerMenu,
            requestPhonePermission = { phonePermission.launch(it) },
        ) {
            binding.main.closeDrawer(GravityCompat.START)
        }.also { it.bind() }

        // Back: close the drawer first; then back leaves for the launcher home (launcher mode) or,
        // with launcher mode off, a double back exits the app.
        onBackPressedDispatcher.addCallback(this) {
            if (binding.main.isDrawerOpen(GravityCompat.START)) binding.main.closeDrawer(GravityCompat.START)
            else onShellBackExhausted()
        }

        homeShellController.startFirstRunPriming()
    }

    /** The home content requests its own bottom banner (its view is not in place when this runs). */
    override fun showBottomBanner() = Unit

    /** 3e: the header's menu button opens the left drawer. */
    override fun openHomeMenu() {
        binding.main.openDrawer(GravityCompat.START)
    }

    override fun onResume() {
        super.onResume()
        homeShellController.onHostResume()
        // The language value can change on the picker; the theme on the segment itself.
        drawerMenu?.refresh()
    }

    override fun onDestroy() {
        homeShellController.onHostDestroy()
        super.onDestroy()
    }

    /**
     * Returns to the launcher home screen explicitly (a HOME intent could go elsewhere). Only this
     * Activity finishes: opened from the launcher's dock it shares the launcher's task, and a
     * finishAffinity there took the launcher down too and dropped the user on the system home.
     */
    override fun onShellBackExhausted() {
        // Launcher mode off: there is no launcher to return to — a second Back within
        // EXIT_WINDOW_MS leaves the app; the first only says so.
        if (!LauncherSwitch.isEnabled(this)) {
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
            startActivity(Intent(this, AppCoreActivity::class.java).addFlags(HomeShellController.REORDER_FLAGS))
        }
    }

    private companion object {
        /** How long after the first Back a second one exits the app. */
        const val EXIT_WINDOW_MS = 2_000L
    }
}
