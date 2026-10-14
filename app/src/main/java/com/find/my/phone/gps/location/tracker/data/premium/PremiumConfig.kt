package com.find.my.phone.gps.location.tracker.data.premium

import android.content.Context
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import org.json.JSONObject

/**
 * One plan on the paywall. Its price only ever comes from Google Play (localized, in the user's
 * currency): there is no fallback price, so the paywall never shows one Play won't charge.
 * [fallbackTrialDays] stands in until Play answers; then Play's actual free-trial offer (or none) wins.
 */
data class PremiumPlan(
    val productId: String,
    val kind: Kind,
    val tag: String,
    val preselected: Boolean,
    val fallbackTrialDays: Int = 0,
) {
    enum class Kind { WEEKLY, MONTHLY, LIFETIME }

    val isSubscription: Boolean get() = kind != Kind.LIFETIME
}

/**
 * Remote Config `premium` (in the GET_DATA_LIST audience block, ingested into AdsVault):
 *
 * ```
 * { "enabled": true, "show_after_intro": true, "close_delay_sec": 5, "close_button_opacity": 1.0,
 *   "free_search_limit": 3, "rewarded_extra_searches": 0,
 *   "products": [
 *     { "id": "gps_premium_weekly",   "type": "subs",  "period": "P1W", "tag": "SUGGESTED", "default": true, "trial_days": 3 },
 *     { "id": "gps_premium_monthly",  "type": "subs",  "period": "P1M" },
 *     { "id": "gps_premium_lifetime", "type": "inapp" } ] }
 * ```
 *
 * A product's `price` key, if still present, is ignored: prices come from Play only.
 *
 * Missing or malformed → the defaults below (paywall on, the design's three plans).
 */
data class PremiumConfig(
    val enabled: Boolean,
    val showAfterIntro: Boolean,
    val closeDelaySec: Int,
    val plans: List<PremiumPlan>,
    /** Free searches per day (design 9a); 0 or less turns the limit off. */
    val freeSearchLimit: Int = 3,
    /** Searches one rewarded ad adds once the limit is reached (design 9b); 0 hides the ad option. */
    val rewardedExtraSearches: Int = 0,
    /**
     * Alpha of the paywall's close control (countdown and X), `close_button_opacity`. Floored at
     * [MIN_CLOSE_OPACITY]: a close that is hard to see on a subscription screen is what Google
     * Play's subscription policy treats as deceptive.
     */
    val closeButtonOpacity: Float = 1f,
) {
    companion object {
        const val KEY = "premium"
        private const val MIN_CLOSE_OPACITY = 0.3

        private val DEFAULT_PLANS = listOf(
            PremiumPlan("gps_premium_weekly", PremiumPlan.Kind.WEEKLY, "SUGGESTED", preselected = true, fallbackTrialDays = 3),
            PremiumPlan("gps_premium_monthly", PremiumPlan.Kind.MONTHLY, "", preselected = false),
            PremiumPlan("gps_premium_lifetime", PremiumPlan.Kind.LIFETIME, "", preselected = false),
        )

        fun read(context: Context): PremiumConfig {
            val raw = AdsVault.getInstance(context).getString(KEY, null)
            val o = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return PremiumConfig(true, true, 5, DEFAULT_PLANS)
            val plans = o.optJSONArray("products")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val p = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = p.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val kind = when {
                        p.optString("type") == "inapp" -> PremiumPlan.Kind.LIFETIME
                        p.optString("period").equals("P1M", ignoreCase = true) -> PremiumPlan.Kind.MONTHLY
                        else -> PremiumPlan.Kind.WEEKLY
                    }
                    PremiumPlan(
                        id, kind, p.optString("tag"), p.optBoolean("default"),
                        fallbackTrialDays = if (kind == PremiumPlan.Kind.LIFETIME) 0 else p.optInt("trial_days", 0),
                    )
                }
            }?.takeIf { it.isNotEmpty() } ?: DEFAULT_PLANS
            return PremiumConfig(
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
