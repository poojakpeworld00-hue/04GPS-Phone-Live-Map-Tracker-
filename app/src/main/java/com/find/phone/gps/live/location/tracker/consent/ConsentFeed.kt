package com.find.phone.gps.live.location.tracker.consent

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.helpers.WardenRail
import org.json.JSONObject
import com.find.phone.gps.live.location.tracker.monetize.logic.CloudProfileCharter

object ConsentFeed {
    private const val TAG = "ConsentDriver"
    private const val RC_KEY = "permission_engine"

    private const val DEFAULT_CONFIG = """
        {
          "permission_engine": {
            "notification": { "enabled": true, "activities": ["BootActivity", "HelloPhaseActivity", "CoreHubActivity", "HomeDeck"], "delay": 0, "priority": 1 }
          }
        }
    """

    @Volatile
    private var cached: List<ConsentClause>? = null

    @Volatile
    private var skipShown: Boolean = true

    fun rules(): List<ConsentClause> = cached ?: reload()

    fun skipVisible(): Boolean {
        if (cached == null) reload()
        return skipShown
    }

    @Synchronized
    fun reload(): List<ConsentClause> {
        val raw = rawConfig()
        val parsed = FbConsentReader.parse(raw)
        skipShown = readSkipFlag(raw)
        cached = parsed
        return parsed
    }

    private fun readSkipFlag(json: String?): Boolean {
        if (json.isNullOrBlank()) return true
        return runCatching {
            val root = JSONObject(json)
            (root.optJSONObject(RC_KEY) ?: root).optBoolean("show_skip", true)
        }.getOrDefault(true)
    }

    fun renewFromRemote(onReady: (() -> Unit)? = null) {
        try {
            val rc = FirebaseRemoteConfig.getInstance()

            CloudProfileCharter.withSettings(rc) {
                rc.fetchAndActivate().addOnCompleteListener { task ->
                    WardenRail.log(TAG, "Remote Config fetch success=${task.isSuccessful}")
                    reload()
                    onReady?.invoke()
                }
            }
        } catch (e: Exception) {
            WardenRail.error(TAG, "refreshFromRemote failed; using cached config", e)
            reload()
            onReady?.invoke()
        }
    }

    private fun rawConfig(): String {
        return try {
            val rc = FirebaseRemoteConfig.getInstance()

            rc.getString(RC_KEY).takeIf { it.isNotBlank() }?.let { return it }

            val (_, blob) = com.find.phone.gps.live.location.tracker.monetize.logic.SpotProfileAbsorb.pullBlob(rc)
            if (blob.isNotBlank()) {
                val obj = JSONObject(blob)

                val root = audienceRoot(obj)
                if (root.has(RC_KEY)) return root.getJSONObject(RC_KEY).toString()
                if (obj.has(RC_KEY)) return obj.getJSONObject(RC_KEY).toString()
            }

            WardenRail.log(TAG, "No remote permission_engine config; using compiled-in default")
            DEFAULT_CONFIG
        } catch (e: Exception) {
            WardenRail.error(TAG, "Failed to read Remote Config; using compiled-in default", e)
            DEFAULT_CONFIG
        }
    }

    private fun audienceRoot(obj: JSONObject): JSONObject {
        val isMarketing = SpotsLocker.getOrNull()?.getBoolean("OnMaketing") ?: false
        val preferred = if (isMarketing) "marketing" else "organic"
        val fallback = if (isMarketing) "organic" else "marketing"
        obj.optJSONObject(preferred)?.let { return it }
        obj.optJSONObject(fallback)?.let { return it }
        return obj
    }
}
