package com.find.phone.gps.live.location.tracker.homeshell

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.find.phone.gps.live.location.tracker.monetize.render.InCoreRefreshBook
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.screens.dashboard.MainCasingDirector
import com.find.phone.gps.live.location.tracker.screens.dashboard.MainCasingHolder
import com.google.android.material.snackbar.Snackbar
import com.livemap.homedeck.screens.HomeDeck
import java.util.WeakHashMap

class HomeCasingHolder private constructor(private val panel: HomeDeck) : MainCasingHolder {
    override val hostActivity: AppCompatActivity get() = panel

    override val homeShellController = MainCasingDirector(this)

    var shellVisible = false

    private var updateReadySnackbar: Snackbar? = null

    override val isShellOnScreen: Boolean get() = shellVisible

    override fun onShellBackExhausted() = panel.shutHostPanel()

    override fun showUpdateReadyPrompt() {
        if (updateReadySnackbar?.isShown == true) return
        updateReadySnackbar = Snackbar
            .make(panel.findViewById(android.R.id.content), R.string.update_ready_msg, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.update_restart) { InCoreRefreshBook.completeUpdate() }
            .also { it.show() }
    }

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
