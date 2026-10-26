package com.find.phone.gps.live.location.tracker.store

import android.content.Context
import com.find.phone.gps.live.location.tracker.store.plus.PlusCheckout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HuntRation {
    private const val PREFS = "search_quota"
    private const val KEY_DAY = "day"
    private const val KEY_USED = "used"
    private const val KEY_COOLDOWN_END = "cooldown_end"

    fun isUnlimited(context: Context): Boolean =
        PlusCheckout.isPremium(context) || HuntProfile.read(context).freeSearchLimit <= 0

    fun limit(context: Context): Int = HuntProfile.read(context).freeSearchLimit

    fun used(context: Context): Int = usedNow(context)

    fun remaining(context: Context): Int {
        settleCooldown(context)
        return (limit(context) - usedNow(context)).coerceAtLeast(0)
    }

    fun canSearch(context: Context): Boolean = isUnlimited(context) || remaining(context) > 0

    fun consume(context: Context) {
        if (isUnlimited(context)) return
        save(context, usedNow(context) + 1)
        armCooldown(context)
    }

    fun cooldownLeftMs(context: Context): Long {
        settleCooldown(context)
        val end = prefs(context).getLong(KEY_COOLDOWN_END, 0L)
        return if (end == 0L) 0L else (end - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun coolingDown(context: Context): Boolean = cooldownLeftMs(context) > 0L

    private fun armCooldown(context: Context) {
        val minutes = HuntProfile.read(context).cooldownMinutes
        if (minutes <= 0 || usedNow(context) < limit(context)) return
        if (prefs(context).getLong(KEY_COOLDOWN_END, 0L) > System.currentTimeMillis()) return
        prefs(context).edit().putLong(KEY_COOLDOWN_END, System.currentTimeMillis() + minutes * 60_000L).apply()
    }

    private fun settleCooldown(context: Context) {
        val end = prefs(context).getLong(KEY_COOLDOWN_END, 0L)
        if (end == 0L || System.currentTimeMillis() < end) return
        prefs(context).edit().remove(KEY_COOLDOWN_END).putString(KEY_DAY, dayKey()).putInt(KEY_USED, 0).apply()
    }

    private fun usedNow(context: Context): Int {
        val p = prefs(context)
        // A running cooldown alone decides when searches come back; a midnight rollover must not reset it early.
        if (p.getLong(KEY_COOLDOWN_END, 0L) > System.currentTimeMillis()) return p.getInt(KEY_USED, 0)
        if (p.getString(KEY_DAY, null) != dayKey()) return 0
        return p.getInt(KEY_USED, 0)
    }

    private fun save(context: Context, used: Int) {
        prefs(context).edit().putString(KEY_DAY, dayKey()).putInt(KEY_USED, used).apply()
    }

    private fun dayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun formatLeft(ms: Long): String {
        val total = (ms + 999L) / 1000L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
