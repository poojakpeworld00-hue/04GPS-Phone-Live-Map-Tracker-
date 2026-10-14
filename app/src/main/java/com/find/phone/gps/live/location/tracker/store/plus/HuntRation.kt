package com.find.phone.gps.live.location.tracker.store.plus

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HuntRation {
    private const val PREFS = "search_quota"
    private const val KEY_DAY = "day"
    private const val KEY_USED = "used"
    private const val KEY_BONUS = "bonus"

    fun isUnlimited(context: Context): Boolean =
        PlusCheckout.isPremium(context) || PlusProfile.read(context).freeSearchLimit <= 0

    fun limit(context: Context): Int = PlusProfile.read(context).freeSearchLimit

    fun used(context: Context): Int = today(context).first

    fun remaining(context: Context): Int {
        val (used, bonus) = today(context)
        return (limit(context) + bonus - used).coerceAtLeast(0)
    }

    fun canSearch(context: Context): Boolean = isUnlimited(context) || remaining(context) > 0

    fun consume(context: Context) {
        if (isUnlimited(context)) return
        val (used, bonus) = today(context)
        save(context, used + 1, bonus)
    }

    fun grantRewarded(context: Context) {
        val (used, bonus) = today(context)
        save(context, used, bonus + PlusProfile.read(context).rewardedExtraSearches)
    }

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
