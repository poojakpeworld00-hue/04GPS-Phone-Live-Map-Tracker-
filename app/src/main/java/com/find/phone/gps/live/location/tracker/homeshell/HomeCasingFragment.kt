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
import com.find.phone.gps.live.location.tracker.databinding.HomeshellShellPanelBinding
import com.find.phone.gps.live.location.tracker.induction.HomeStream
import com.find.phone.gps.live.location.tracker.screens.dashboard.MainHubFragment
import com.find.phone.gps.live.location.tracker.screens.dashboard.MainSliderOptions
import com.livemap.homedeck.contract.HomeDeckMatter

class HomeCasingFragment : Fragment(), HomeDeckMatter, MainHubFragment.MenuHost {
    private var _binding: HomeshellShellPanelBinding? = null

    private var drawerMenu: MainSliderOptions? = null

    private val phonePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            drawerMenu?.onPhonePermissionResult(it)
        }

    private val host: HomeCasingHolder? get() = HomeCasingHolder.of(activity)

    private val home: MainHubFragment?
        get() = childFragmentManager.findFragmentById(R.id.deckContent) as? MainHubFragment

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val themed = inflater.cloneInContext(ContextThemeWrapper(inflater.context, R.style.Theme_GpsTracker))
        return HomeshellShellPanelBinding.inflate(themed, container, false).also { _binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = _binding ?: return
        if (home == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.deckContent, MainHubFragment())
                .commitNow()
        }

        binding.deckDrawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        (activity as? AppCompatActivity)?.let { activity ->
            drawerMenu = MainSliderOptions(
                activity,
                binding.sliderMenu,
                requestPhonePermission = { phonePermission.launch(it) },
            ) { shutMenu() }.also { it.bind() }
        }
    }

    override fun onDestroyView() {
        drawerMenu = null
        _binding = null
        super.onDestroyView()
    }

    override fun openHomeMenu() {
        _binding?.deckDrawer?.openDrawer(GravityCompat.START)
    }

    private fun shutMenu() {
        _binding?.deckDrawer?.closeDrawer(GravityCompat.START)
    }

    private val isMenuOpen: Boolean
        get() = _binding?.deckDrawer?.isDrawerOpen(GravityCompat.START) == true

    override fun setPanelVisible(visible: Boolean) {
        host?.shellVisible = visible

        if (!visible) {
            host?.homeShellController?.onShellHidden()
            _binding?.deckDrawer?.closeDrawer(GravityCompat.START, false)
        }

        home?.setOnScreen(visible)
        if (!visible) return
        drawerMenu?.refresh()

        if (HomeStream.wasOnboardingCompleted(requireContext())) {
            host?.homeShellController?.beginFirstRunPriming()
        }
    }

    override fun handleBack(): Boolean {
        if (!isMenuOpen) return false
        shutMenu()
        return true
    }
}
