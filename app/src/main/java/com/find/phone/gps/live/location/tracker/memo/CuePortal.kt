package com.find.phone.gps.live.location.tracker.memo

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import java.util.concurrent.TimeUnit

object CuePortal {
    private const val FILE = "reminder_prompt_gate"
    private const val K_ONCE = "shown_once"
    private const val K_LAST_AT = "last_shown_at"
    private const val K_LAUNCHES = "launch_count"
    private const val K_LAUNCHES_MARK = "launch_at_last_shown"
    private const val K_CREATED = "created_count"
    private const val K_CREATED_MARK = "created_at_last_shown"

    private fun store(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun bump(ctx: Context, counterKey: String) {
        val sp = store(ctx)
        val next = sp.getInt(counterKey, 0) + 1
        sp.edit().putInt(counterKey, next).apply()
    }

    private fun SharedPreferences.sinceMark(counterKey: String, markKey: String): Int =
        getInt(counterKey, 0) - getInt(markKey, 0)

    fun onAppLaunch(c: Context) = bump(c, K_LAUNCHES)

    fun onReminderCreated(c: Context) = bump(c, K_CREATED)

    fun mustShow(c: Context): Boolean {
        val options = MemoProfile.get(c)
        val step = maxOf(options.promptInterval, 1)
        val sp = store(c)
        val mode = options.promptFrequency.trim().lowercase(Locale.ROOT)
        if (mode == "never") return false
        if (mode == "once") return !sp.getBoolean(K_ONCE, false)
        if (mode == "every_days") {
            val lastAt = sp.getLong(K_LAST_AT, 0L)
            if (lastAt == 0L) return true
            return System.currentTimeMillis() - lastAt >= TimeUnit.DAYS.toMillis(step.toLong())
        }
        if (mode == "app_launches") return sp.sinceMark(K_LAUNCHES, K_LAUNCHES_MARK) >= step
        if (mode == "reminder_creations") return sp.sinceMark(K_CREATED, K_CREATED_MARK) >= step
        return true
    }

    fun flagShown(c: Context) {
        val sp = store(c)
        val launches = sp.getInt(K_LAUNCHES, 0)
        val created = sp.getInt(K_CREATED, 0)
        sp.edit().apply {
            putBoolean(K_ONCE, true)
            putLong(K_LAST_AT, System.currentTimeMillis())
            putInt(K_LAUNCHES_MARK, launches)
            putInt(K_CREATED_MARK, created)
        }.apply()
    }
}
