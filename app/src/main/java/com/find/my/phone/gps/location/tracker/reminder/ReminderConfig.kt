package com.find.my.phone.gps.location.tracker.reminder

import android.content.Context
import android.telephony.TelephonyManager
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.data.VaultRegistry
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.Locale

/** One sample reminder, as delivered inside `default_config_list`. */
data class ReminderSeedSpec(
    @SerializedName("title") val title: String = "",
    @SerializedName("note") val note: String = "",
    @SerializedName("daysAhead") val daysAhead: Int = 1,
)

/**
 * One audience branch of the `feature_config` Remote Config JSON. Field names mirror the JSON so
 * the Firebase value stays diff-able against this class. Every field has a default: Gson then
 * fills absent keys with it, and the defaults double as the safe "everything off" branch.
 */
data class ReminderSettings(
    @SerializedName("feature_title") val featureTitle: String = "",
    /** May contain `{title}` and `{time}`, filled from the reminder that was just saved. */
    @SerializedName("feature_desc") val featureDesc: String = "",
    @SerializedName("feature_action") val featureAction: String = "",

    /** CSV minutes, e.g. `5,10,30,60`: the alert's Snooze choices. */
    @SerializedName("action_delay_options") val actionDelayOptions: String = "5,10,30,60",

    @SerializedName("is_default_config") val isDefaultConfig: Boolean = false,
    @SerializedName("default_config_list") val defaultConfigList: List<ReminderSeedSpec> = emptyList(),

    /** Master for the "Turn on reminders" prompt shown after the first save. */
    @SerializedName("is_feature_prompt") val isFeaturePrompt: Boolean = false,
    /** Off → due reminders arrive as a normal notification instead of filling the screen. */
    @SerializedName("is_enhanced_mode") val isEnhancedMode: Boolean = true,
    /** The `reminders` onboarding step after Welcome. */
    @SerializedName("is_intro_screen") val isIntroScreen: Boolean = false,

    /** `always` | `once` | `every_days` | `app_launches` | `reminder_creations` | `never`. */
    @SerializedName("prompt_frequency") val promptFrequency: String = "always",
    @SerializedName("prompt_interval") val promptInterval: Int = 0,

    /** Country filter master. Off → shown in every country. */
    @SerializedName("is_country_filter") val isCountryFilter: Boolean = false,
    /** CSV ISO-2 codes where the feature is hidden (block list). */
    @SerializedName("excluded_countries") val excludedCountries: String = "",

    /** The drawer's Reminders row; it also has its own country block list. */
    @SerializedName("is_settings_row") val isSettingsRow: Boolean = false,
    @SerializedName("settings_row_excluded_countries") val settingsRowExcludedCountries: String = "",

    @SerializedName("intro_title") val introTitle: String = "",
    @SerializedName("intro_desc") val introDesc: String = "",
)

private data class ReminderConfigDocument(
    @SerializedName("marketing") val marketing: ReminderSettings? = null,
    @SerializedName("organic") val organic: ReminderSettings? = null,
)

/**
 * The reminders feature's only Remote Config surface: the top-level `feature_config` JSON
 * parameter, with a `marketing` and an `organic` branch. Read the way `permission_engine` is
 * (AccessSource): the activated Firebase value, else the compiled [DEFAULT_CONFIG]. The branch
 * follows the app's existing audience flag (`AdsVault` `OnMaketing`, set at splash), and the
 * country checks use the country the splash already resolved ([VaultRegistry]).
 *
 * Never throws: a missing parameter, bad JSON or a missing field all fall back to defaults.
 */
object ReminderConfig {

    const val RC_KEY = "feature_config"
    const val MARKETING = "marketing"
    const val ORGANIC = "organic"

    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedAudience: String? = null
    @Volatile private var cachedBranch: ReminderSettings = ReminderSettings()

    @Synchronized
    fun get(context: Context): ReminderSettings {
        val raw = runCatching { FirebaseRemoteConfig.getInstance().getString(RC_KEY) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_CONFIG
        val audience = currentAudience(context)
        if (raw == cachedRaw && audience == cachedAudience) return cachedBranch

        val doc = runCatching { Gson().fromJson(raw, ReminderConfigDocument::class.java) }.getOrNull()
            ?: runCatching { Gson().fromJson(DEFAULT_CONFIG, ReminderConfigDocument::class.java) }.getOrNull()
        val branch = (if (audience == MARKETING) doc?.marketing else doc?.organic) ?: ReminderSettings()

        cachedRaw = raw
        cachedAudience = audience
        cachedBranch = branch
        return branch
    }

    /** The app's own audience flag; an unresolved audience reads organic. */
    fun currentAudience(context: Context): String =
        if (runCatching { AdsVault.getInstance(context).getBoolean("OnMaketing") }.getOrDefault(false)) MARKETING else ORGANIC

    // ───────────────────────── country gates (block lists, fail-open) ─────────────────────────

    /** False only when the country filter is on and this device's country is excluded. */
    fun isCountryAllowed(context: Context): Boolean {
        val cfg = get(context)
        if (!cfg.isCountryFilter) return true
        return !isListed(country(context), cfg.excludedCountries)
    }

    /** The drawer row: its own master, the feature's country filter, then the row's own list. */
    fun isSettingsRowAllowed(context: Context): Boolean {
        val cfg = get(context)
        if (!cfg.isSettingsRow) return false
        if (!isCountryAllowed(context)) return false
        return !isListed(country(context), cfg.settingsRowExcludedCountries)
    }

    private fun isListed(iso: String?, csv: String): Boolean {
        if (iso.isNullOrBlank() || csv.isBlank()) return false   // unknown / empty list → shown
        return csv.split(',').any { it.trim().equals(iso, ignoreCase = true) }
    }

    /** IP country from the splash lookup first (a user's Home pick is a preference, not where
     *  they are), then that pick, then the SIM's network. Null when nothing is known. */
    private fun country(context: Context): String? {
        val vault = VaultRegistry(context)
        return vault.geoCountryIso.ifBlank { vault.homeCountryIso }.ifBlank {
            runCatching {
                (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
                    ?.networkCountryIso.orEmpty()
            }.getOrDefault("")
        }.uppercase(Locale.ROOT).ifBlank { null }
    }

    /**
     * In-app default: what users get before the first fetch, or when `feature_config` is not set
     * in Firebase. Keep it identical to the value published in the console.
     */
    private const val DEFAULT_CONFIG = """
{
  "marketing": {
    "feature_title": "Get your reminders on time",
    "feature_desc": "Allow reminders so \"{title}\" reaches you at {time}, even when your phone is asleep.",
    "feature_action": "Turn on reminders",
    "action_delay_options": "5,10,30,60",
    "is_default_config": false,
    "default_config_list": [],
    "is_feature_prompt": true,
    "is_enhanced_mode": true,
    "is_intro_screen": true,
    "intro_title": "Never forget a call back",
    "intro_desc": "Set a reminder for any number and we'll remind you right on time, even when your phone is asleep.",
    "is_country_filter": false,
    "excluded_countries": "",
    "is_settings_row": true,
    "settings_row_excluded_countries": "",
    "prompt_frequency": "always",
    "prompt_interval": 3
  },
  "organic": {
    "feature_title": "Get your reminders on time",
    "feature_desc": "Allow reminders so \"{title}\" reaches you at {time}, even when your phone is asleep.",
    "feature_action": "Turn on reminders",
    "action_delay_options": "5,10,30,60",
    "is_default_config": false,
    "default_config_list": [],
    "is_feature_prompt": true,
    "is_enhanced_mode": true,
    "is_intro_screen": false,
    "intro_title": "Never forget a call back",
    "intro_desc": "Set a reminder for any number and we'll remind you right on time, even when your phone is asleep.",
    "is_country_filter": false,
    "excluded_countries": "",
    "is_settings_row": true,
    "settings_row_excluded_countries": "",
    "prompt_frequency": "always",
    "prompt_interval": 3
  }
}
"""
}
