package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.render.CoreOpenSpotBook
import com.find.phone.gps.live.location.tracker.monetize.render.InCoreRefreshObserver
import com.find.phone.gps.live.location.tracker.monetize.render.InCoreRefreshBook
import com.find.phone.gps.live.location.tracker.consent.ConsentDrawerModal
import com.find.phone.gps.live.location.tracker.screens.policy.DriftToolbox

class MainCasingDirector(private val host: MainCasingHolder) {
    private val activity get() = host.hostActivity

    private var registered = false
    private var primed = false
    private var shellHidden = false
    private var sheetDeferred = false
    private var selfRetired = false
    private var userDismissedSheet = false
    private var polling = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollHandler = Handler(Looper.getMainLooper())

    private val overlaySettingsResult = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        stopGrantPolling()
    }

    private val grantTick: Runnable = object : Runnable {
        override fun run() {
            when {
                activity.isDestroyed -> polling = false
                DriftToolbox.isGranted(activity) -> {
                    polling = false
                    host.bringHostToFront()
                }
                else -> pollHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    fun onHostCreated() {
        registered = true
        InCoreRefreshBook.enlistLauncher(activity)
        maybeOfferUpdate()
    }

    fun beginFirstRunPriming() {
        shellHidden = false
        when {
            !primed -> {
                primed = true
                tryAutoSheet()
            }
            sheetDeferred -> tryAutoSheet()
        }
    }

    fun onHostResume() {
        InCoreRefreshBook.resumeUpdate()
        maybeOfferUpdate()
    }

    fun onHostDestroy() {
        registered = false
        stopGrantPolling()
        mainHandler.removeCallbacksAndMessages(null)
        InCoreRefreshBook.destroy(activity)
    }

    private fun maybeOfferUpdate() {
        if (!registered) return
        val store = SpotsLocker.getInstance(activity)
        if (!store.getBoolean("In_App_Update_Show")) return

        val mandatory = store.getBoolean("In_App_Update_Force_Show")
        if (!mandatory && recentlyChecked(store)) return

        InCoreRefreshBook.init(
            activity = activity,
            isForceUpdate = mandatory,
            callback = updateCallback(),
        )
    }

    private fun recentlyChecked(store: SpotsLocker): Boolean {
        val elapsed = System.currentTimeMillis() - store.getLong(UPDATE_STAMP_KEY, 0L)
        if (elapsed in 0 until UPDATE_FRESH_MS) return true
        store.putLong(UPDATE_STAMP_KEY, System.currentTimeMillis())
        return false
    }

    private fun updateCallback() = object : InCoreRefreshObserver {
        override fun onUpdateSuccess() {}
        override fun onUpdateCanceled() {}
        override fun onUpdateFailed() {}
        override fun onUpdateDownloaded() {
            host.showUpdateReadyPrompt()
        }
    }

    fun presentPermissionSheet() {
        ConsentDrawerModal.show(activity) {
            when {
                selfRetired -> selfRetired = false
                else -> userDismissedSheet = true
            }
        }
    }

    fun onShellHidden() {
        shellHidden = true
        if (ConsentDrawerModal.isShowing(activity)) {
            selfRetired = true
            ConsentDrawerModal.retireIfShowing(activity)
            sheetDeferred = true
        }
    }

    private fun tryAutoSheet() {
        val visible = !shellHidden && host.isShellOnScreen
        sheetDeferred = !visible
        if (!visible) return
        if (ConsentDrawerModal.mustAutoShow(activity)) presentPermissionSheet()
    }

    fun mustShowPermissionHint(): Boolean =
        userDismissedSheet && ConsentDrawerModal.hasPending(activity)

    fun beginOverlayPermissionFlow() {
        val canAsk = DriftToolbox.isOfferable(activity) && !DriftToolbox.isGranted(activity)
        if (!canAsk) return

        CoreOpenSpotBook.skipNextAppOpenAd = true

        runCatching {
            overlaySettingsResult.launch(DriftToolbox.composeOverlayIntent(activity.packageName))
        }.onSuccess {
            DriftToolbox.presentGuide(activity)
            startGrantPolling()
        }
    }

    private fun startGrantPolling() {
        if (polling) return
        polling = true
        pollHandler.removeCallbacks(grantTick)
        pollHandler.postDelayed(grantTick, POLL_INTERVAL_MS)
    }

    private fun stopGrantPolling() {
        polling = false
        pollHandler.removeCallbacks(grantTick)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 350L
        private const val UPDATE_STAMP_KEY = "last_inapp_update_check_at"
        private const val UPDATE_FRESH_MS = 6 * 60 * 60 * 1000L

        const val REORDER_FLAGS =
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP

        const val EXTRA_SELF_REORDER = "extra_self_reorder"
    }
}
