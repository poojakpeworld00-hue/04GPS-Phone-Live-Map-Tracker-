package com.find.phone.gps.live.location.tracker.monetize.logic

import android.content.Context
import android.util.Log
import org.json.JSONObject

data class BonusProfile(
    val mode: Mode = Mode.FREE,
    val cooldownSec: Int = 120,
    val backoff: Boolean = true,
    val cooldownMaxSec: Int = 900,
    val maxRetries: Int = 2,
) {
    enum class Mode { FREE, WAIT }

    companion object {
        const val KEY = "rewarded"

        fun read(context: Context): BonusProfile {
            val raw = SpotsLocker.getInstance(context).getString(KEY, null)
            val o = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return BonusProfile()
            val defaults = BonusProfile()
            val cooldown = o.optInt("nofill_cooldown_sec", defaults.cooldownSec).coerceAtLeast(0)
            return BonusProfile(
                mode = if (o.optString("nofill_mode").equals("wait", ignoreCase = true)) Mode.WAIT else Mode.FREE,
                cooldownSec = cooldown,
                backoff = o.optBoolean("nofill_backoff", defaults.backoff),
                cooldownMaxSec = o.optInt("nofill_cooldown_max_sec", defaults.cooldownMaxSec).coerceAtLeast(cooldown),
                maxRetries = o.optInt("max_retries", defaults.maxRetries),
            )
        }
    }
}

object BonusPause {
    private const val TAG = "BonusTeaser"
    private const val PREFS = "rewarded_nofill"
    private const val KEY_UNTIL = "until"
    private const val KEY_STREAK = "streak"
    private const val KEY_TOTAL = "total"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun remainingMs(context: Context): Long {
        val config = BonusProfile.read(context)
        if (config.cooldownSec <= 0) return 0
        val left = prefs(context).getLong(KEY_UNTIL, 0L) - System.currentTimeMillis()

        return left.coerceIn(0L, config.cooldownMaxSec * 1000L)
    }

    fun isActive(context: Context): Boolean = remainingMs(context) > 0

    fun totalMs(context: Context): Long =
        prefs(context).getLong(KEY_TOTAL, 0L).coerceAtLeast(remainingMs(context)).coerceAtLeast(1L)

    fun start(context: Context) {
        val config = BonusProfile.read(context)
        if (config.cooldownSec <= 0) return
        val p = prefs(context)
        val streak = p.getInt(KEY_STREAK, 0)
        val seconds = if (config.backoff) {
            (config.cooldownSec.toLong() shl streak.coerceAtMost(20)).coerceAtMost(config.cooldownMaxSec.toLong())
        } else {
            config.cooldownSec.toLong()
        }
        p.edit()
            .putLong(KEY_UNTIL, System.currentTimeMillis() + seconds * 1000L)
            .putLong(KEY_TOTAL, seconds * 1000L)
            .putInt(KEY_STREAK, streak + 1)
            .apply()
        Log.d(TAG, "no-fill → cooldown ${seconds}s (streak ${streak + 1})")
    }

    fun reset(context: Context) {
        prefs(context).edit().putLong(KEY_UNTIL, 0L).putInt(KEY_STREAK, 0).apply()
    }
}
