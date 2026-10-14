package com.find.phone.gps.live.location.tracker.launcher

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.find.phone.gps.live.location.tracker.adcast.presentation.InCoreRefreshBook
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.ui.home.MainCasingDirector
import com.find.phone.gps.live.location.tracker.ui.home.MainCasingHolder
import com.google.android.material.snackbar.Snackbar
import io.launcher.home.activities.HomeDeck
import java.util.WeakHashMap

/**
 * [MainCasingHolder] for the launcher module's home screen.
 *
 * [HomeDeck] is the module's Activity, so it cannot implement our interface the way the old
 * in-app launcher did. This object stands in for it, one per HomeDeck instance, and the shell
 * fragments find it through [of].
 *
 * It must be created from [RingHomeGateway.onLauncherStart]: that runs inside the launcher's
 * `onCreate`, and [MainCasingDirector] registers its result launchers on construction, which only
 * works before the Activity is STARTED.
 */
class HomeCasingHolder private constructor(private val panel: HomeDeck) : MainCasingHolder {

    override val hostActivity: AppCompatActivity get() = panel

    override val homeShellController = MainCasingDirector(this)

    /** Set by [HomeCasingFragment] as the right-hand panel slides in and out. */
    var shellVisible = false

    private var updateReadySnackbar: Snackbar? = null

    override val isShellOnScreen: Boolean get() = shellVisible

    /** Back inside the panel with the shell's own tab history exhausted just closes it. */
    override fun onShellBackExhausted() = panel.closeHostPanel()

    /**
     * With the panel open the shell's own Snackbar is right; with it shut the shell is parked off
     * screen, so the home grid carries the prompt or a downloaded update has nowhere to install from.
     */
    override fun showUpdateReadyPrompt() {
        if (updateReadySnackbar?.isShown == true) return
        updateReadySnackbar = Snackbar
            .make(panel.findViewById(android.R.id.content), R.string.update_ready_msg, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.update_restart) { InCoreRefreshBook.completeUpdate() }
            .also { it.show() }
    }

    /** Back from a Settings round trip (overlay / FSI grant): land in the panel, not on the grid. */
    override fun bringHostToFront() {
        runCatching {
            panel.startActivity(
                Intent(panel, HomeDeck::class.java)
                    .addFlags(MainCasingDirector.REORDER_FLAGS)
                    .putExtra(HomeDeck.EXTRA_OPEN_HOST_PANEL, true)
            )
        }
    }

    companion object {

        private val hosts = WeakHashMap<Activity, HomeCasingHolder>()

        fun attach(panel: HomeDeck) {
            if (hosts.containsKey(panel)) return
            val host = HomeCasingHolder(panel)
            hosts[panel] = host
            host.homeShellController.onHostCreated()
            panel.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    hosts.remove(panel)?.homeShellController?.onHostDestroy()
                }
            })
        }

        fun of(activity: Activity?): HomeCasingHolder? = activity?.let { hosts[it] }
    }
}
