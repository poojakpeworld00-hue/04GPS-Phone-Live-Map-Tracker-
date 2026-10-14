package com.find.my.phone.gps.location.tracker.adcast.domain

import android.content.Context
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.permission.AccessSource
import com.find.my.phone.gps.location.tracker.reminder.ReminderConfig
import com.find.my.phone.gps.location.tracker.util.GuardRail
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import org.json.JSONObject

/**
 * Applies Remote Config changes while the app is running.
 *
 * Without this the blob is only read at splash, so a value published in the console reaches a
 * device on its next cold start — which for a launcher can be days, since the home screen is
 * rarely killed.
 *
 * Realtime Remote Config pushes the change instead: [ConfigUpdateListener.onUpdate] fires,
 * the new values are activated, and the same ingest the splash runs re-populates AdsVault, so
 * every gate that reads from it — ad slots, the permission engine, the settings rows — picks
 * the change up on its next read.
 *
 * The install-referrer step is deliberately not re-run: the audience a user landed in does not
 * change because a config value did, and re-running it would re-POST attribution.
 */
object LiveConfigWatcher {

    private const val TAG = "LiveConfig"

    /** The permission engine's own parameter — a change there matters as much as the blob. */
    private const val RC_PERMISSION_KEY = "permission_engine"

    /** When the last successful fetch landed. Local bookkeeping, not a config value. */
    private const val LAST_SYNC_KEY = "__cfg_last_sync"

    /** Remote Config's own say over the backstop window, in hours. */
    private const val SYNC_HOURS_KEY = "Config_Sync_Hrs"

    /** Used when [SYNC_HOURS_KEY] is absent or negative. */
    private const val DEFAULT_STALE_HOURS = 6L

    /** Coalesces the foreground backstop with anything else already fetching. */
    @Volatile
    private var fetchInFlight = false

    private var registration: com.google.firebase.remoteconfig.ConfigUpdateListenerRegistration? = null

    /** Idempotent: a second call replaces the previous registration rather than stacking one. */
    fun start(context: Context) {
        val app = context.applicationContext
        stop()

        registration = runCatching {
            FirebaseRemoteConfig.getInstance().addOnConfigUpdateListener(
                object : ConfigUpdateListener {
                    override fun onUpdate(configUpdate: ConfigUpdate) {
                        GuardRail.log(TAG, "config update: ${configUpdate.updatedKeys}")
                        // Only re-ingest when something we actually read moved. Logged rather
                        // than dropped silently: "I published and nothing changed" is nearly
                        // always an edit to a key this app never looks at.
                        if (configUpdate.updatedKeys.none { AdConfigIngest.isBlobKey(it) || it == RC_PERMISSION_KEY || it == ReminderConfig.RC_KEY }) {
                            GuardRail.log(TAG, "none of those is ${AdConfigIngest.blobKey} → ignored")
                            return
                        }
                        FirebaseRemoteConfig.getInstance().activate()
                            .addOnCompleteListener { apply(app) }
                    }

                    override fun onError(error: FirebaseRemoteConfigException) {
                        // Not fatal: the splash fetch still applies the change on next launch.
                        GuardRail.error(TAG, "realtime updates unavailable", error)
                    }
                }
            )
        }.onFailure { GuardRail.error(TAG, "could not register for config updates", it) }
            .getOrNull()
    }

    fun stop() {
        runCatching { registration?.remove() }
        registration = null
    }

    /**
     * The backstop for the push channel: a device that was offline when the template was
     * published never gets the update, and for a launcher the next cold start can be days out.
     *
     * Throttled by [SYNC_HOURS_KEY], so the dozens of daily foregrounds that land inside the
     * window cost nothing.
     */
    fun refreshIfStale(context: Context, force: Boolean = false, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        val vault = AdsVault.getInstance(app)
        val window = staleAfterMs(app)
        val age = System.currentTimeMillis() - vault.getLong(LAST_SYNC_KEY, 0L)

        // `window == 0` → the range is empty and nothing is ever considered fresh.
        if (!force && age in 0 until window) {
            GuardRail.log(
                TAG,
                "config is ${age / 60_000}min old (window ${window / 60_000}min) — no fetch",
            )
            onDone?.invoke()
            return
        }
        if (fetchInFlight) {
            GuardRail.log(TAG, "a fetch is already running — joined")
            onDone?.invoke()
            return
        }

        fetchInFlight = true
        val rc = FirebaseRemoteConfig.getInstance()
        // Via withSettings: a fetch started before the settings land runs on the SDK's
        // 12-hour default, answers from cache, and still reports success.
        RemoteConfigPolicy.withSettings(rc) {
            runCatching {
                rc.fetchAndActivate().addOnCompleteListener { task ->
                    fetchInFlight = false
                    GuardRail.log(TAG, "backstop fetch success=${task.isSuccessful}")
                    if (task.isSuccessful) apply(app)
                    onDone?.invoke()
                }
            }.onFailure {
                fetchInFlight = false
                GuardRail.error(TAG, "backstop fetch failed", it)
                onDone?.invoke()
            }
        }
    }

    /**
     * The freshness window in millis.
     *
     * `> 0` is that many hours. **`0` means no window at all** — every [refreshIfStale] call
     * fetches, which is once per foreground, so it is a testing / force-fresh setting rather
     * than something to ship. Absent reads back as `-1` (see `AdsVault.getInt`) and falls back
     * to [DEFAULT_STALE_HOURS], as does any other negative value, so a typo can never turn
     * into a fetch-every-resume loop by accident.
     */
    private fun staleAfterMs(context: Context): Long {
        val hours = AdsVault.getInstance(context).getInt(SYNC_HOURS_KEY)
        if (hours == 0) return 0L
        return (if (hours > 0) hours.toLong() else DEFAULT_STALE_HOURS) * 60L * 60L * 1000L
    }

    private fun apply(context: Context) {
        val (blobKey, raw) = AdConfigIngest.readBlob(FirebaseRemoteConfig.getInstance())
        if (raw.isBlank()) {
            GuardRail.log(TAG, "$blobKey empty after update — keeping the values already in use")
            return
        }

        runCatching {
            val response = JSONObject(raw)
            val vault = AdsVault.getInstance(context)
            val onMarketing = vault.getBoolean("OnMaketing")

            // Unguarded: the two facts that decide which slice of the template this device
            // actually reads. Nearly every "I published and nothing changed" turns out to be
            // an edit to the OTHER blob or the OTHER audience half.
            GuardRail.log(
                TAG,
                "ingest ← $blobKey / ${if (onMarketing) "marketing" else "organic"}",
            )

            vault.putString("GET_DATA_RAW", raw)
            vault.putBoolean(
                "__cfg_audience_split",
                response.has("marketing") || response.has("organic"),
            )
            AdConfigIngest.ingest(
                context,
                AdConfigIngest.audienceRoot(response, onMarketing),
            )

            // The permission engine caches its own parsed copy of the same blob.
            AccessSource.reload()
            // Only on a clean ingest: a parse that threw leaves the vault half-written, and
            // stamping it fresh would park the backstop on that state for a whole window.
            vault.putLong(LAST_SYNC_KEY, System.currentTimeMillis())
            GuardRail.log(TAG, "applied live config (marketing=$onMarketing)")
        }.onFailure { GuardRail.error(TAG, "live config could not be applied", it) }
    }
}
