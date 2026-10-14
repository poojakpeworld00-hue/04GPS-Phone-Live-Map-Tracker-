package com.find.phone.gps.live.location.tracker.store.plus

import android.content.Context
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import org.json.JSONObject

data class PlusOffer(
    val productId: String,
    val kind: Kind,
    val tag: String,
    val preselected: Boolean,
    val fallbackTrialDays: Int = 0,
) {
    enum class Kind { WEEKLY, MONTHLY, LIFETIME }

    val isSubscription: Boolean get() = kind != Kind.LIFETIME
}

data class PlusProfile(
    val enabled: Boolean,
    val showAfterIntro: Boolean,
    val closeDelaySec: Int,
    val plans: List<PlusOffer>,

    val freeSearchLimit: Int = 3,

    val rewardedExtraSearches: Int = 0,

    val closeButtonOpacity: Float = 1f,
) {
    companion object {
        const val KEY = "premium"
        private const val MIN_CLOSE_OPACITY = 0.3

        private val DEFAULT_PLANS = listOf(
            PlusOffer("gps_premium_weekly", PlusOffer.Kind.WEEKLY, "SUGGESTED", preselected = true, fallbackTrialDays = 3),
            PlusOffer("gps_premium_monthly", PlusOffer.Kind.MONTHLY, "", preselected = false),
            PlusOffer("gps_premium_lifetime", PlusOffer.Kind.LIFETIME, "", preselected = false),
        )

        fun read(context: Context): PlusProfile {
            val raw = SpotsLocker.getInstance(context).getString(KEY, null)
            val o = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return PlusProfile(true, true, 5, DEFAULT_PLANS)
            val plans = o.optJSONArray("products")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val p = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = p.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val kind = when {
                        p.optString("type") == "inapp" -> PlusOffer.Kind.LIFETIME
                        p.optString("period").equals("P1M", ignoreCase = true) -> PlusOffer.Kind.MONTHLY
                        else -> PlusOffer.Kind.WEEKLY
                    }
                    PlusOffer(
                        id, kind, p.optString("tag"), p.optBoolean("default"),
                        fallbackTrialDays = if (kind == PlusOffer.Kind.LIFETIME) 0 else p.optInt("trial_days", 0),
                    )
                }
            }?.takeIf { it.isNotEmpty() } ?: DEFAULT_PLANS
            return PlusProfile(
                enabled = o.optBoolean("enabled", true),
                showAfterIntro = o.optBoolean("show_after_intro", true),
                closeDelaySec = o.optInt("close_delay_sec", 5).coerceIn(0, 30),
                plans = plans,
                freeSearchLimit = o.optInt("free_search_limit", 3),
                rewardedExtraSearches = o.optInt("rewarded_extra_searches", 0).coerceAtLeast(0),
                closeButtonOpacity = o.optDouble("close_button_opacity", 1.0)
                    .let { if (it.isNaN()) 1.0 else it }.coerceIn(MIN_CLOSE_OPACITY, 1.0).toFloat(),
            )
        }
    }
}
