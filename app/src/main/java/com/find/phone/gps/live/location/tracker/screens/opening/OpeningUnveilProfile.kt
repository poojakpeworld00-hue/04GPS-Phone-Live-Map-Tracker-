package com.find.phone.gps.live.location.tracker.screens.opening

import android.content.Context
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import org.json.JSONObject

enum class CueTempo {
    ALWAYS, ONCE, EVERY_DAYS, APP_LAUNCHES, NEVER;

    companion object {
        fun from(raw: String?): CueTempo = when (raw?.trim()?.lowercase()) {
            "always" -> ALWAYS
            "once" -> ONCE
            "every_days" -> EVERY_DAYS
            "app_launches" -> APP_LAUNCHES
            "never" -> NEVER
            else -> ONCE
        }
    }
}

data class OpeningUnveil(
    val enabled: Boolean,
    val frequency: CueTempo,
    val interval: Int
)

object OpeningUnveilProfile {
    private const val RC_KEY = "intro_display"

    const val LANGUAGE = "language"
    const val TERMS = "terms"
    const val ONBOARDING = "onboarding"
    const val PERMISSION_SHEET = "permission_sheet"
    const val REMINDERS_INTRO = "reminders_intro"

    fun remindersIntro(context: Context): OpeningUnveil =
        load(context, REMINDERS_INTRO, defaultEnabled = true, defaultFrequency = CueTempo.ONCE)

    fun language(context: Context): OpeningUnveil =
        load(context, LANGUAGE, defaultEnabled = true, defaultFrequency = CueTempo.ONCE)

    fun terms(context: Context): OpeningUnveil =
        load(context, TERMS, defaultEnabled = false, defaultFrequency = CueTempo.ONCE)

    fun onboarding(context: Context): OpeningUnveil =
        load(context, ONBOARDING, defaultEnabled = false, defaultFrequency = CueTempo.ONCE)

    fun permissionSheet(context: Context): OpeningUnveil =
        load(context, PERMISSION_SHEET, defaultEnabled = true, defaultFrequency = CueTempo.ALWAYS)

    private fun load(
        context: Context,
        key: String,
        defaultEnabled: Boolean,
        defaultFrequency: CueTempo
    ): OpeningUnveil {
        val ads = SpotsLocker.getInstance(context)
        val raw = ads.getString(RC_KEY)
        val obj = if (raw.isNullOrBlank()) null else runCatching {
            val root = JSONObject(raw)
            audienceContainer(root, isMarketing = ads.getBoolean("OnMaketing")).optJSONObject(key)
        }.getOrNull()

        if (obj == null) return OpeningUnveil(defaultEnabled, defaultFrequency, 0)

        return OpeningUnveil(
            enabled = obj.optBoolean("enabled", defaultEnabled),
            frequency = CueTempo.from(
                obj.optString("prompt_frequency", defaultFrequency.name.lowercase())
            ),
            interval = obj.optInt("prompt_interval", 0)
        )
    }

    private fun audienceContainer(root: JSONObject, isMarketing: Boolean): JSONObject {
        val preferred = if (isMarketing) "marketing" else "organic"
        val fallback = if (isMarketing) "organic" else "marketing"
        root.optJSONObject(preferred)?.let { return it }
        root.optJSONObject(fallback)?.let { return it }
        return root
    }
}
