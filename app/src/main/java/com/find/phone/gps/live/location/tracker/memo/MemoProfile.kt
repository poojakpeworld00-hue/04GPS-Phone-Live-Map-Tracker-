package com.find.phone.gps.live.location.tracker.memo

import android.content.Context
import android.telephony.TelephonyManager
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.Locale

data class MemoSproutSchema(
    @SerializedName("title") val title: String = "",
    @SerializedName("note") val note: String = "",
    @SerializedName("daysAhead") val daysAhead: Int = 1,
)

data class MemoOptions(
    @SerializedName("feature_title") val featureTitle: String = "",

    @SerializedName("feature_desc") val featureDesc: String = "",
    @SerializedName("feature_action") val featureAction: String = "",

    @SerializedName("action_delay_options") val actionDelayOptions: String = "5,10,30,60",

    @SerializedName("is_default_config") val isDefaultConfig: Boolean = false,
    @SerializedName("default_config_list") val defaultConfigList: List<MemoSproutSchema> = emptyList(),

    @SerializedName("is_feature_prompt") val isFeaturePrompt: Boolean = false,

    @SerializedName("is_enhanced_mode") val isEnhancedMode: Boolean = true,

    @SerializedName("is_intro_screen") val isIntroScreen: Boolean = false,

    @SerializedName("prompt_frequency") val promptFrequency: String = "always",
    @SerializedName("prompt_interval") val promptInterval: Int = 0,

    @SerializedName("is_country_filter") val isCountryFilter: Boolean = false,

    @SerializedName("excluded_countries") val excludedCountries: String = "",

    @SerializedName("is_settings_row") val isSettingsRow: Boolean = false,
    @SerializedName("settings_row_excluded_countries") val settingsRowExcludedCountries: String = "",

    @SerializedName("intro_title") val introTitle: String = "",
    @SerializedName("intro_desc") val introDesc: String = "",
)

private data class MemoProfileDocument(
    @SerializedName("marketing") val marketing: MemoOptions? = null,
    @SerializedName("organic") val organic: MemoOptions? = null,
)

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

    private fun blocked(iso: String?, csv: String): Boolean {
        if (iso.isNullOrBlank() || csv.isBlank()) return false
        for (token in csv.split(',')) {
            if (token.trim().equals(iso, ignoreCase = true)) return true
        }
        return false
    }

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
