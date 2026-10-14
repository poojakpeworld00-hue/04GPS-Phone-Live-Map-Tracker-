package com.find.phone.gps.live.location.tracker.memo

import android.content.Context
import android.telephony.TelephonyManager
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.Locale

/** One sample reminder, as delivered inside `default_config_list`. */
data class MemoSproutSchema(
    @SerializedName("title") val title: String = "",
    @SerializedName("note") val note: String = "",
    @SerializedName("daysAhead") val daysAhead: Int = 1,
)

/**
 * One audience branch of the `feature_config` Remote Config JSON. Field names mirror the JSON so
 * the Firebase value stays diff-able against this class. Every field has a default: Gson then
 * fills absent keys with it, and the defaults double as the safe "everything off" branch.
 */
data class MemoOptions(
    @SerializedName("feature_title") val featureTitle: String = "",
    /** May contain `{title}` and `{time}`, filled from the reminder that was just saved. */
    @SerializedName("feature_desc") val featureDesc: String = "",
    @SerializedName("feature_action") val featureAction: String = "",

    /** CSV minutes, e.g. `5,10,30,60`: the alert's Snooze choices. */
    @SerializedName("action_delay_options") val actionDelayOptions: String = "5,10,30,60",

    @SerializedName("is_default_config") val isDefaultConfig: Boolean = false,
    @SerializedName("default_config_list") val defaultConfigList: List<MemoSproutSchema> = emptyList(),

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

private data class MemoProfileDocument(
    @SerializedName("marketing") val marketing: MemoOptions? = null,
    @SerializedName("organic") val organic: MemoOptions? = null,
)

/**
 * Reader for the `feature_config` Remote Config JSON (branches `marketing` / `organic`). Uses the
 * activated Firebase value when present, otherwise [DEFAULT_CONFIG]. The branch is chosen by the
 * `OnMaketing` flag in [SpotsLocker]; country checks rely on [LockerBook]. Never throws.
 */
object MemoProfile {

    const val RC_KEY = "feature_config"
    const val MARKETING = "marketing"
    const val ORGANIC = "organic"

    @Volatile private var lastJson: String? = null
    @Volatile private var lastAudience: String? = null
    @Volatile private var lastOptions: MemoOptions = MemoOptions()

    private fun remoteJson(): String {
        val fetched = runCatching { FirebaseRemoteConfig.getInstance().getString(RC_KEY) }.getOrNull()
        return if (fetched.isNullOrBlank()) DEFAULT_CONFIG else fetched
    }

    private fun decode(json: String): MemoProfileDocument? =
        runCatching { Gson().fromJson(json, MemoProfileDocument::class.java) }.getOrNull()

    @Synchronized
    fun get(context: Context): MemoOptions {
        val json = remoteJson()
        val audience = currentAudience(context)
        if (json == lastJson && audience == lastAudience) return lastOptions

        val document = decode(json) ?: decode(DEFAULT_CONFIG)
        val picked = when (audience) {
            MARKETING -> document?.marketing
            else -> document?.organic
        } ?: MemoOptions()

        lastJson = json
        lastAudience = audience
        lastOptions = picked
        return picked
    }

    fun currentAudience(context: Context): String {
        val marketing = runCatching { SpotsLocker.getInstance(context).getBoolean("OnMaketing") }.getOrElse { false }
        return if (marketing) MARKETING else ORGANIC
    }

    fun isCountryAllowed(context: Context): Boolean {
        val options = get(context)
        return !options.isCountryFilter || !blocked(deviceCountry(context), options.excludedCountries)
    }

    fun isSettingsRowAllowed(context: Context): Boolean {
        val options = get(context)
        return when {
            !options.isSettingsRow -> false
            !isCountryAllowed(context) -> false
            else -> !blocked(deviceCountry(context), options.settingsRowExcludedCountries)
        }
    }

    /** Fail-open: an unknown country or an empty list never blocks. */
    private fun blocked(iso: String?, csv: String): Boolean {
        if (iso.isNullOrBlank() || csv.isBlank()) return false
        for (token in csv.split(',')) {
            if (token.trim().equals(iso, ignoreCase = true)) return true
        }
        return false
    }

    /** Splash IP country, then the user's Home pick, then the SIM network; null if none known. */
    private fun deviceCountry(context: Context): String? {
        val book = LockerBook(context)
        val candidates = sequenceOf(
            { book.geoCountryIso },
            { book.homeCountryIso },
            {
                runCatching {
                    (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkCountryIso ?: ""
                }.getOrElse { "" }
            },
        )
        val first = candidates.map { it() }.firstOrNull { it.isNotBlank() } ?: ""
        return first.uppercase(Locale.ROOT).takeIf { it.isNotBlank() }
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
