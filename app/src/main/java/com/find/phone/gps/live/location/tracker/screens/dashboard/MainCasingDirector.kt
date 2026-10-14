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

/**
 * Everything in the home shell that needs an **Activity** rather than a View.
 *
 * Result launchers must be registered before the Activity is STARTED, and a fragment hosted in
 * the launcher's side panel is committed long after that — so permission round-trips and the
 * Play update check live here, owned by the host Activity as a field initializer, reached
 * through [MainCasingHolder].
 *
 * Two-phase on purpose: [onHostCreated] registers (pre-STARTED, cheap, invisible) and
 * [beginFirstRunPriming] prompts (visible, deferred until the shell is actually on screen —
 * in the launcher that is when the panel opens, not when it is committed off-screen).
 *
 * Trimmed from the reference: the caller-ID specific pieces (full-screen-intent priming, the
 * one-time contact upload) are gone; the RC-driven permission sheet and in-app update stay.
 */
class MainCasingDirector(private val host: MainCasingHolder) {

    private val activity get() = host.hostActivity

    /** The sheet came due while the shell was off screen; flushed when it is back. */
    private var permissionSheetPending = false

    /** The shell is on its way off screen (the launcher's panel is closing). */
    private var shellOffScreen = false

    /** We took the sheet down ourselves, so its completion is not the user dismissing it. */
    private var sheetTakenDownByUs = false

    /** True once the first-run permission sheet has been dismissed by the user. */
    private var permissionSheetDismissed = false

    /** Guards [beginFirstRunPriming] so a re-opened panel doesn't prime twice per session. */
    private var primingStarted = false

    /** Whether [onHostCreated] ran (the launcher skips it while its first run is unfinished). */
    private var hostCreated = false

    private val handler = Handler(Looper.getMainLooper())

    // ─────────────────────────── Result launchers ───────────────────────────

    /** Returns from the overlay Settings page. */
    private val overlayLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        haltOverlayGrantPoll()
    }

    // ─────────────────────────── Host lifecycle ───────────────────────────

    /** Registration only — must run pre-STARTED, and must *not* show anything. */
    fun onHostCreated() {
        hostCreated = true
        InCoreRefreshBook.enlistLauncher(activity)
        perhapsCheckForUpdate()
    }

    /**
     * The visible first-run prompts (the RC-gated permission sheet). Call this when the shell is
     * actually on screen: CoreHubActivity from `initView`, the launcher once the panel slid in.
     */
    fun beginFirstRunPriming() {
        shellOffScreen = false
        if (primingStarted) {
            if (permissionSheetPending) perhapsAutoShowPermissionSheet()
            return
        }
        primingStarted = true
        perhapsAutoShowPermissionSheet()
    }

    /** Re-evaluate after returning from a permission/Settings round trip. */
    fun onHostResume() {
        // Resume an interrupted update (IMMEDIATE re-prompts; FLEXIBLE completes a download).
        InCoreRefreshBook.resumeUpdate()
        // The launcher home may not be re-created for days; re-check on resume (throttled).
        perhapsCheckForUpdate()
    }

    fun onHostDestroy() {
        hostCreated = false
        haltOverlayGrantPoll()
        handler.removeCallbacksAndMessages(null)
        // Owner-scoped: a late teardown here must not wipe the other host's registration.
        InCoreRefreshBook.destroy(activity)
    }

    // ─────────────────────────── In-app update ───────────────────────────

    /**
     * Triggers the Play in-app update flow when Remote Config enables it.
     *  - `In_App_Update_Show`       → master switch for offering an update.
     *  - `In_App_Update_Force_Show` → true = IMMEDIATE (mandatory), false = FLEXIBLE (optional).
     */
    private fun perhapsCheckForUpdate() {
        if (!hostCreated) return
        val pref = SpotsLocker.getInstance(activity)
        if (!pref.getBoolean("In_App_Update_Show")) return

        val isForceUpdate = pref.getBoolean("In_App_Update_Force_Show")

        // Throttled because this also runs on resume and the launcher home resumes on every
        // HOME press. A FORCE update is exempt — being unskippable is the whole point.
        if (!isForceUpdate) {
            val age = System.currentTimeMillis() - pref.getLong(LAST_UPDATE_CHECK_KEY, 0L)
            if (age in 0 until UPDATE_CHECK_WINDOW_MS) return
            pref.putLong(LAST_UPDATE_CHECK_KEY, System.currentTimeMillis())
        }

        InCoreRefreshBook.init(
            activity = activity,
            isForceUpdate = isForceUpdate,
            callback = object : InCoreRefreshObserver {
                override fun onUpdateSuccess() {}
                override fun onUpdateCanceled() {}
                override fun onUpdateFailed() {}
                override fun onUpdateDownloaded() {
                    host.showUpdateReadyPrompt()
                }
            }
        )
    }

    // ─────────────────────────── Permission sheet ───────────────────────────

    /** Shows the permission priming bottom sheet ([ConsentDrawerModal]). */
    fun presentPermissionSheet() {
        ConsentDrawerModal.show(activity) {
            // Only a dismissal the USER performed counts; when we closed the sheet because the
            // shell went away, the ask is deferred, not answered.
            if (sheetTakenDownByUs) sheetTakenDownByUs = false else permissionSheetDismissed = true
        }
    }

    /**
     * The shell is going off screen — on the launcher, the panel is closing. Anything anchored
     * to the Activity would otherwise be left over the launcher's home grid.
     */
    fun onShellHidden() {
        shellOffScreen = true
        if (!ConsentDrawerModal.isShowing(activity)) return
        sheetTakenDownByUs = true
        ConsentDrawerModal.retireIfShowing(activity)
        permissionSheetPending = true
    }

    /** Auto-shows the permission sheet when pending perms + the RC frequency gate allow. */
    private fun perhapsAutoShowPermissionSheet() {
        if (shellOffScreen || !host.isShellOnScreen) {
            permissionSheetPending = true
            return
        }
        permissionSheetPending = false
        if (ConsentDrawerModal.mustAutoShow(activity)) presentPermissionSheet()
    }

    /** True when the sheet was dismissed at least once and something is still missing. */
    fun mustShowPermissionHint(): Boolean =
        permissionSheetDismissed && ConsentDrawerModal.hasPending(activity)

    // ─────────────────────────── Overlay permission ───────────────────────────

    /**
     * Opens the system "display over other apps" page IN-TASK (for-result) and starts the
     * in-activity grant poll to catch the toggle and auto-return. Only reachable when Remote
     * Config lists the overlay row in the permission sheet.
     */
    fun beginOverlayPermissionFlow() {
        if (!DriftToolbox.isOfferable(activity) || DriftToolbox.isGranted(activity)) return

        // We open system Settings ourselves — the programmatic return must NOT trigger an
        // App Open ad. One-shot skip, consumed on next foreground.
        CoreOpenSpotBook.skipNextAppOpenAd = true

        val launched = runCatching {
            overlayLauncher.launch(DriftToolbox.composeOverlayIntent(activity.packageName))
        }.isSuccess
        if (!launched) return

        DriftToolbox.presentGuide(activity)
        beginOverlayGrantPoll()
    }

    private val overlayGrantPollHandler = Handler(Looper.getMainLooper())
    private var overlayGrantPolling = false
    private val overlayGrantPoll = object : Runnable {
        override fun run() {
            if (activity.isDestroyed) { overlayGrantPolling = false; return }
            if (DriftToolbox.isGranted(activity)) {
                overlayGrantPolling = false
                host.bringHostToFront()
            } else {
                overlayGrantPollHandler.postDelayed(this, GRANT_POLL_MS)
            }
        }
    }

    private fun beginOverlayGrantPoll() {
        if (overlayGrantPolling) return
        overlayGrantPolling = true
        overlayGrantPollHandler.removeCallbacks(overlayGrantPoll)
        overlayGrantPollHandler.postDelayed(overlayGrantPoll, GRANT_POLL_MS)
    }

    private fun haltOverlayGrantPoll() {
        overlayGrantPolling = false
        overlayGrantPollHandler.removeCallbacks(overlayGrantPoll)
    }

    companion object {
        /** Grant-poll cadence while the user is on a system Settings page. */
        private const val GRANT_POLL_MS = 350L

        /** Pref holding when the Play update check last ran. */
        private const val LAST_UPDATE_CHECK_KEY = "last_inapp_update_check_at"

        /** How long a Play update check stays fresh before a resume may run another. */
        private const val UPDATE_CHECK_WINDOW_MS = 6 * 60 * 60 * 1000L

        /** Flags for the in-task reorder every [MainCasingHolder.bringHostToFront] uses. */
        const val REORDER_FLAGS =
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP

        /** Marks a [MainCasingHolder.bringHostToFront] intent as the app pulling itself back. */
        const val EXTRA_SELF_REORDER = "extra_self_reorder"
    }
}
