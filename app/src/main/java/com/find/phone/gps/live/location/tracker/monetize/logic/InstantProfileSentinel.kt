package com.find.phone.gps.live.location.tracker.monetize.logic

import android.content.Context
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.consent.ConsentFeed
import com.find.phone.gps.live.location.tracker.memo.MemoProfile
import com.find.phone.gps.live.location.tracker.helpers.WardenRail
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import org.json.JSONObject

object InstantProfileSentinel {
    private const val TAG = "LiveConfig"

    private const val RC_PERMISSION_KEY = "permission_engine"

    private const val LAST_SYNC_KEY = "__cfg_last_sync"

    private const val SYNC_HOURS_KEY = "Config_Sync_Hrs"

    private const val DEFAULT_STALE_HOURS = 6L

    @Volatile
    private var fetchInFlight = false

    private var registration: com.google.firebase.remoteconfig.ConfigUpdateListenerRegistration? = null

    fun start(context: Context) {
        val app = context.applicationContext
        stop()

        registration = runCatching {
            FirebaseRemoteConfig.getInstance().addOnConfigUpdateListener(
                object : ConfigUpdateListener {
                    override fun onUpdate(configUpdate: ConfigUpdate) {
                        WardenRail.log(TAG, "config update: ${configUpdate.updatedKeys}")

                        if (configUpdate.updatedKeys.none { SpotProfileAbsorb.isBlobKey(it) || it == RC_PERMISSION_KEY || it == MemoProfile.RC_KEY }) {
                            WardenRail.log(TAG, "none of those is ${SpotProfileAbsorb.blobKey} → ignored")
                            return
                        }
                        FirebaseRemoteConfig.getInstance().activate()
                            .addOnCompleteListener { apply(app) }
                    }

                    override fun onError(error: FirebaseRemoteConfigException) {
                        WardenRail.error(TAG, "realtime updates unavailable", error)
                    }
                }
            )
        }.onFailure { WardenRail.error(TAG, "could not register for config updates", it) }
            .getOrNull()
    }

    fun stop() {
        runCatching { registration?.remove() }
        registration = null
    }

    fun renewIfStale(context: Context, force: Boolean = false, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        val vault = SpotsLocker.getInstance(app)
        val window = staleAfterMs(app)
        val age = System.currentTimeMillis() - vault.getLong(LAST_SYNC_KEY, 0L)

        if (!force && age in 0 until window) {
            WardenRail.log(
                TAG,
                "config is ${age / 60_000}min old (window ${window / 60_000}min) — no fetch",
            )
            onDone?.invoke()
            return
        }
        if (fetchInFlight) {
            WardenRail.log(TAG, "a fetch is already running — joined")
            onDone?.invoke()
            return
        }

        fetchInFlight = true
        val rc = FirebaseRemoteConfig.getInstance()

        CloudProfileCharter.withSettings(rc) {
            runCatching {
                rc.fetchAndActivate().addOnCompleteListener { task ->
                    fetchInFlight = false
                    WardenRail.log(TAG, "backstop fetch success=${task.isSuccessful}")
                    if (task.isSuccessful) apply(app)
                    onDone?.invoke()
                }
            }.onFailure {
                fetchInFlight = false
                WardenRail.error(TAG, "backstop fetch failed", it)
                onDone?.invoke()
            }
        }
    }

    private fun staleAfterMs(context: Context): Long {
        val hours = SpotsLocker.getInstance(context).getInt(SYNC_HOURS_KEY)
        if (hours == 0) return 0L
        return (if (hours > 0) hours.toLong() else DEFAULT_STALE_HOURS) * 60L * 60L * 1000L
    }

    private fun apply(context: Context) {
        val (blobKey, raw) = SpotProfileAbsorb.pullBlob(FirebaseRemoteConfig.getInstance())
        if (raw.isBlank()) {
            WardenRail.log(TAG, "$blobKey empty after update — keeping the values already in use")
            return
        }

        runCatching {
            val response = JSONObject(raw)
            val vault = SpotsLocker.getInstance(context)
            val onMarketing = vault.getBoolean("OnMaketing")

            WardenRail.log(
                TAG,
                "ingest ← $blobKey / ${if (onMarketing) "marketing" else "organic"}",
            )

            vault.putString("GET_DATA_RAW", raw)
            vault.putBoolean(
                "__cfg_audience_split",
                response.has("marketing") || response.has("organic"),
            )
            SpotProfileAbsorb.ingest(
                context,
                SpotProfileAbsorb.audienceRoot(response, onMarketing),
            )

            ConsentFeed.reload()

            vault.putLong(LAST_SYNC_KEY, System.currentTimeMillis())
            WardenRail.log(TAG, "applied live config (marketing=$onMarketing)")
        }.onFailure { WardenRail.error(TAG, "live config could not be applied", it) }
    }
}
