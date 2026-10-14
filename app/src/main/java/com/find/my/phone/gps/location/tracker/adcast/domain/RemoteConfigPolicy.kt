package com.find.my.phone.gps.location.tracker.adcast.domain

import com.find.my.phone.gps.location.tracker.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings

/**
 * One place to decide how Remote Config fetches behave.
 *
 * [FirebaseRemoteConfig.setConfigSettingsAsync] applies to the singleton, so two callers with
 * different settings do not each get their own — the last one to run defines the behaviour for
 * everything after it. The splash asked for 1s and the permission engine for 3600s, which made
 * the effective interval a question of startup ordering rather than of intent.
 *
 * The values kept here are the splash's, since that is the call that decides how fresh the
 * config is at a cold start, and in practice it was already winning the race.
 *
 * With LiveConfigWatcher in place a change published while the app is running arrives by push
 * regardless of this interval. It still governs the cold-start case: a change published while
 * the app was closed is picked up by the next fetch, so raising the interval would delay
 * exactly that case.
 */
object RemoteConfigPolicy {

    /** Debug takes every publish immediately; release still fetches on each cold start. */
    private val MIN_FETCH_INTERVAL_SECONDS = if (BuildConfig.DEBUG) 0L else 1L

    /**
     * Caps the fetch so a slow network cannot park the splash on this call (it defaulted to
     * 60s; 37s stalls were observed). On timeout the fetch fails fast and the flow continues
     * on cached values.
     */
    private const val FETCH_TIMEOUT_SECONDS = 10L

    /**
     * The `setConfigSettingsAsync` task, kept so [withSettings] can wait for it.
     *
     * Not a bare boolean: the call is asynchronous, and firing `fetchAndActivate` before it
     * lands leaves the SDK on its DEFAULT 12-hour minimum fetch interval — which answers from
     * cache and still reports success, so a freshly published template silently never arrives.
     */
    @Volatile
    private var settingsTask: Task<Void>? = null

    fun settings(): FirebaseRemoteConfigSettings =
        FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(MIN_FETCH_INTERVAL_SECONDS)
            .setFetchTimeoutInSeconds(FETCH_TIMEOUT_SECONDS)
            .build()

    /** Applies the settings once per process; later calls reuse the first task. */
    fun applyTo(remoteConfig: FirebaseRemoteConfig): Task<Void>? {
        if (settingsTask == null) {
            synchronized(this) {
                if (settingsTask == null) {
                    settingsTask = remoteConfig.setConfigSettingsAsync(settings())
                }
            }
        }
        return settingsTask
    }

    /**
     * Runs [block] with the settings guaranteed to be in effect.
     *
     * Every fetch has to go through here. Already-complete is the steady state, so this costs
     * one branch on every fetch after the first — and buys the one case that matters: the very
     * first fetch of a cold start, which is exactly the fetch that decides whether a template
     * published while the app was closed arrives at all.
     */
    fun withSettings(remoteConfig: FirebaseRemoteConfig, block: () -> Unit) {
        val task = applyTo(remoteConfig)
        if (task == null || task.isComplete) block() else task.addOnCompleteListener { block() }
    }
}
