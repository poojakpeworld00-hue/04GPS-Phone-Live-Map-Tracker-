package com.find.phone.gps.live.location.tracker.monetize.logic

import com.find.phone.gps.live.location.tracker.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings

object CloudProfileCharter {
    private val MIN_FETCH_INTERVAL_SECONDS = if (BuildConfig.DEBUG) 0L else 12L * 60L * 60L

    private const val FETCH_TIMEOUT_SECONDS = 10L

    @Volatile
    private var settingsTask: Task<Void>? = null

    fun settings(): FirebaseRemoteConfigSettings =
        FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(MIN_FETCH_INTERVAL_SECONDS)
            .setFetchTimeoutInSeconds(FETCH_TIMEOUT_SECONDS)
            .build()

    fun enactTo(remoteConfig: FirebaseRemoteConfig): Task<Void>? {
        if (settingsTask == null) {
            synchronized(this) {
                if (settingsTask == null) {
                    settingsTask = remoteConfig.setConfigSettingsAsync(settings())
                }
            }
        }
        return settingsTask
    }

    fun withSettings(remoteConfig: FirebaseRemoteConfig, block: () -> Unit) {
        val task = enactTo(remoteConfig)
        if (task == null || task.isComplete) block() else task.addOnCompleteListener { block() }
    }
}
