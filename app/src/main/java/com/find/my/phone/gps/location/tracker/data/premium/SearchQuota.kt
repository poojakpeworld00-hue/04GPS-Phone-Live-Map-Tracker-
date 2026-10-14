package com.find.my.phone.gps.location.tracker.data.premium

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The free user's daily search allowance (design 9a / 9b): `free_search_limit` new searches a
 * day plus whatever rewarded ads have added today; it all resets at local midnight. Premium, or
 * a limit of 0 in Remote Config, means no limit. Only new lookups count — reopening a saved
 * result doesn't.
 */
object SearchQuota {

    private const val PREFS = "search_quota"
    private const val KEY_DAY = "day"
    private const val KEY_USED = "used"
    private const val KEY_BONUS = "bonus"

    fun isUnlimited(context: Context): Boolean =
        PremiumBilling.isPremium(context) || PremiumConfig.read(context).freeSearchLimit <= 0

    fun limit(context: Context): Int = PremiumConfig.read(context).freeSearchLimit

    fun used(context: Context): Int = today(context).first

    /** Searches still available today (free allowance + ad bonus − used). */
    fun remaining(context: Context): Int {
        val (used, bonus) = today(context)
        return (limit(context) + bonus - used).coerceAtLeast(0)
    }

    fun canSearch(context: Context): Boolean = isUnlimited(context) || remaining(context) > 0

    /** A new lookup started. */
    fun consume(context: Context) {
        if (isUnlimited(context)) return
        val (used, bonus) = today(context)
        save(context, used + 1, bonus)
    }

    /** A rewarded ad was watched on the limit sheet. */
    fun grantRewarded(context: Context) {
        val (used, bonus) = today(context)
        save(context, used, bonus + PremiumConfig.read(context).rewardedExtraSearches)
    }

    /** (used, bonus) for today; yesterday's counts are dropped. */
    private fun today(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        if (p.getString(KEY_DAY, null) != dayKey()) return 0 to 0
        return p.getInt(KEY_USED, 0) to p.getInt(KEY_BONUS, 0)
    }

    private fun save(context: Context, used: Int, bonus: Int) {
        prefs(context).edit().putString(KEY_DAY, dayKey()).putInt(KEY_USED, used).putInt(KEY_BONUS, bonus).apply()
    }

    private fun dayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
