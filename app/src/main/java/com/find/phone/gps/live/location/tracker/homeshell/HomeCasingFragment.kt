package com.find.phone.gps.live.location.tracker.homeshell

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.databinding.LauncherShellPanelBinding
import com.find.phone.gps.live.location.tracker.induction.HomeStream
import com.find.phone.gps.live.location.tracker.screens.dashboard.MainHubFragment
import com.find.phone.gps.live.location.tracker.screens.dashboard.MainSliderOptions
import com.livemap.homedeck.contract.HomeDeckMatter

/**
 * What the launcher module swipe-in panel (the left drawer of the home screen) hosts: the app's
 * home content, [MainHubFragment] ("one task up front — number in, search out"), inside the same
 * DrawerLayout and 3e menu the standalone CoreHubActivity has, so the panel is the main page.
 *
 * Everything that must not happen unseen (ad requests, first-run permission priming) waits for
 * [setPanelVisible], because the launcher commits this fragment at `onCreate` and parks it off
 * screen.
 */
class HomeCasingFragment : Fragment(), HomeDeckMatter, MainHubFragment.MenuHost {

    private var _binding: LauncherShellPanelBinding? = null

    private var drawerMenu: MainSliderOptions? = null

    // Phone access for the drawer's "My number" card. Registered at construction (before STARTED).
    private val phonePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            drawerMenu?.onPhonePermissionResult(it)
        }

    private val host: HomeCasingHolder? get() = HomeCasingHolder.of(activity)

    private val home: MainHubFragment?
        get() = childFragmentManager.findFragmentById(R.id.panelContent) as? MainHubFragment

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Under the app theme: the launcher Activity carries the module's own theme, whose fonts
        // and text styles would otherwise restyle the drawer.
        val themed = inflater.cloneInContext(ContextThemeWrapper(inflater.context, R.style.Theme_GpsTracker))
        return LauncherShellPanelBinding.inflate(themed, container, false).also { _binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = _binding ?: return
        if (home == null) {
            // commitNow, so setPanelVisible on this same open reaches the content.
            childFragmentManager.beginTransaction()
                .replace(R.id.panelContent, MainHubFragment())
                .commitNow()
        }
        // The panel itself slides on horizontal gestures; the drawer opens from the menu button
        // only, so the two never fight over an edge swipe.
        binding.panelDrawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        (activity as? AppCompatActivity)?.let { activity ->
            drawerMenu = MainSliderOptions(
                activity,
                binding.drawerMenu,
                requestPhonePermission = { phonePermission.launch(it) },
            ) { closeMenu() }.also { it.bind() }
        }
    }

    override fun onDestroyView() {
        drawerMenu = null
        _binding = null
        super.onDestroyView()
    }

    /** 3e: the header's menu button opens the drawer, as on the main page. */
    override fun openHomeMenu() {
        _binding?.panelDrawer?.openDrawer(GravityCompat.START)
    }

    private fun closeMenu() {
        _binding?.panelDrawer?.closeDrawer(GravityCompat.START)
    }

    private val isMenuOpen: Boolean
        get() = _binding?.panelDrawer?.isDrawerOpen(GravityCompat.START) == true

    override fun setPanelVisible(visible: Boolean) {
        host?.shellVisible = visible
        // Every way the panel closes (back, HOME) comes through here: dialogs anchored to the
        // launcher Activity have to be taken down with it or they are left over the home grid.
        if (!visible) {
            host?.homeShellController?.onShellHidden()
            _binding?.panelDrawer?.closeDrawer(GravityCompat.START, false)
        }
        // The content requests its ads only while it is on screen.
        home?.setOnScreen(visible)
        if (!visible) return
        drawerMenu?.refresh()
        // First-run priming only once the user can see the panel, and never during onboarding.
        if (HomeStream.wasOnboardingCompleted(requireContext())) {
            host?.homeShellController?.startFirstRunPriming()
        }
    }

    /** Back closes the drawer first; with it shut, the panel closes. */
    override fun handleBack(): Boolean {
        if (!isMenuOpen) return false
        closeMenu()
        return true
    }
}
