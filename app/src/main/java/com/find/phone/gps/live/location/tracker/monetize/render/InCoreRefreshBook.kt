package com.find.phone.gps.live.location.tracker.monetize.render

import android.app.Activity
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.isFlexibleUpdateAllowed
import com.google.android.play.core.ktx.isImmediateUpdateAllowed
import java.lang.ref.WeakReference

object InCoreRefreshBook {
    private const val TAG = "InCoreRefreshBook"

    private val IN_FLIGHT_STATUSES = setOf(
        InstallStatus.PENDING, InstallStatus.DOWNLOADING, InstallStatus.INSTALLING
    )

    private var activityRef: WeakReference<Activity>? = null
    private var updateManager: AppUpdateManager? = null
    private var callback: InCoreRefreshObserver? = null
    private var updateType: Int = AppUpdateType.IMMEDIATE
    private var updateLauncher: ActivityResultLauncher<IntentSenderRequest>? = null

    val isForceUpdate: Boolean get() = updateType == AppUpdateType.IMMEDIATE

    fun enlistLauncher(activity: ComponentActivity) {
        activityRef = WeakReference(activity)
        updateLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    Log.d(TAG, "User accepted the update.")
                    if (updateType == AppUpdateType.IMMEDIATE) {
                        callback?.onUpdateSuccess()
                    }

                }
                Activity.RESULT_CANCELED -> {
                    Log.d(TAG, "User canceled the update.")
                    callback?.onUpdateCanceled()
                    if (updateType == AppUpdateType.IMMEDIATE) {
                        activityRef?.get()?.finish()
                    }
                }
                else -> {
                    Log.e(TAG, "Update flow failed, resultCode=${result.resultCode}")
                    callback?.onUpdateFailed()
                }
            }
        }
    }

    fun init(
        activity: Activity,
        isForceUpdate: Boolean = false,
        callback: InCoreRefreshObserver
    ) {
        this.activityRef = WeakReference(activity)
        this.callback = callback
        this.updateType = if (isForceUpdate) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE

        this.updateManager = AppUpdateManagerFactory.create(activity.applicationContext)

        if (updateType == AppUpdateType.FLEXIBLE) {
            updateManager?.registerListener(installStateUpdatedListener)
        }

        verifyForUpdates()
    }

    private val installStateUpdatedListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> {
                Log.d(TAG, "Flexible update downloaded — asking the host to install.")
                signalDownloaded()
            }
            InstallStatus.INSTALLED -> {
                Log.d(TAG, "Update installed successfully.")
                callback?.onUpdateSuccess()
                cleanup()
            }
            InstallStatus.FAILED, InstallStatus.CANCELED -> {
                Log.e(TAG, "Flexible update ended without installing: ${state.installStatus()}")
                callback?.onUpdateFailed()
            }
            else -> {
                Log.d(TAG, "Install status: ${state.installStatus()}")
            }
        }
    }

    private fun verifyForUpdates() {
        val manager = updateManager ?: return
        manager.appUpdateInfo
            .addOnSuccessListener { info ->

                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    Log.d(TAG, "Update already downloaded — asking the host to install.")
                    signalDownloaded()
                    return@addOnSuccessListener
                }

                if (info.installStatus() in IN_FLIGHT_STATUSES) {
                    Log.d(TAG, "Update already in progress (${info.installStatus()}) — no prompt.")
                    return@addOnSuccessListener
                }

                val isAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                val isAllowed = when (updateType) {
                    AppUpdateType.FLEXIBLE -> info.isFlexibleUpdateAllowed
                    AppUpdateType.IMMEDIATE -> info.isImmediateUpdateAllowed
                    else -> false
                }

                if (isAvailable && isAllowed) {
                    val launcher = updateLauncher
                    if (launcher != null) {
                        manager.startUpdateFlowForResult(
                            info,
                            launcher,
                            AppUpdateOptions.newBuilder(updateType).build()
                        )
                    } else {
                        activityRef?.get()?.let { activity ->
                            @Suppress("DEPRECATION")
                            manager.startUpdateFlowForResult(
                                info, updateType, activity, 123
                            )
                        }
                    }
                } else {
                    Log.d(TAG, "No update available or not allowed.")

                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Update check failed: ${e.message}")
                callback?.onUpdateFailed()
            }
    }

    fun resumeUpdate() {
        val manager = updateManager ?: return
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (updateType == AppUpdateType.IMMEDIATE &&
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
            ) {
                val launcher = updateLauncher
                if (launcher != null) {
                    manager.startUpdateFlowForResult(
                        info, launcher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                }
            } else if (updateType == AppUpdateType.FLEXIBLE &&
                info.installStatus() == InstallStatus.DOWNLOADED
            ) {
                signalDownloaded()
            }
        }
    }

    fun completeUpdate() {
        Log.d(TAG, "completeUpdate() — restarting to install.")
        updateManager?.completeUpdate()
    }

    fun retryCheck() {
        Log.d(TAG, "retryCheck()")
        verifyForUpdates()
    }

    private fun signalDownloaded() {
        val listener = callback
        if (listener == null) {
            Log.w(TAG, "Update downloaded but no listener attached — deferring to the next launch.")
            return
        }
        listener.onUpdateDownloaded()
    }

    fun destroy(owner: Activity? = null) {
        val current = activityRef?.get()
        if (owner != null && current != null && current !== owner) {
            Log.d(TAG, "destroy() from a stale host — keeping the live registration.")
            return
        }
        cleanup()
        updateLauncher = null
    }

    private fun cleanup() {
        try { updateManager?.unregisterListener(installStateUpdatedListener) } catch (_: Exception) {}
        updateManager = null
        activityRef = null
        callback = null
    }
}

interface InCoreRefreshObserver {
    fun onUpdateSuccess()
    fun onUpdateCanceled()

    fun onUpdateFailed()

    fun onUpdateDownloaded() {}
}
