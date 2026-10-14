package com.find.my.phone.gps.location.tracker.reminder

import android.content.Context
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * How often the "Turn on reminders" prompt may appear, from `feature_config`'s
 * `prompt_frequency` + `prompt_interval`:
 *
 * | prompt_frequency     | Behaviour                                         | prompt_interval |
 * |----------------------|---------------------------------------------------|-----------------|
 * | `always`             | every time it is reachable                        | ignored         |
 * | `once`               | once, ever                                        | ignored         |
 * | `every_days`         | then rest X days                                  | days            |
 * | `app_launches`       | once every X app opens                            | launches        |
 * | `reminder_creations` | once X more reminders have been saved             | count           |
 * | `never`              | never                                             | ignored         |
 *
 * Unknown / blank → `always`, so a typo never hides the prompt for good. The host feeds two
 * counters: [onAppLaunch] (splash) and [onReminderCreated] (after a new reminder is saved).
 */
object PromptGate {

    private const val PREFS = "reminder_prompt_gate"
    private const val KEY_SHOWN_ONCE = "shown_once"
    private const val KEY_LAST_SHOWN_AT = "last_shown_at"
    private const val KEY_LAUNCH_COUNT = "launch_count"
    private const val KEY_LAUNCH_AT_LAST_SHOWN = "launch_at_last_shown"
    private const val KEY_CREATED_COUNT = "created_count"
    private const val KEY_CREATED_AT_LAST_SHOWN = "created_at_last_shown"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun onAppLaunch(c: Context) {
        val p = prefs(c)
        p.edit().putInt(KEY_LAUNCH_COUNT, p.getInt(KEY_LAUNCH_COUNT, 0) + 1).apply()
    }

    fun onReminderCreated(c: Context) {
        val p = prefs(c)
        p.edit().putInt(KEY_CREATED_COUNT, p.getInt(KEY_CREATED_COUNT, 0) + 1).apply()
    }

    fun shouldShow(c: Context): Boolean {
        val cfg = ReminderConfig.get(c)
        val x = cfg.promptInterval.coerceAtLeast(1)
        val p = prefs(c)
        return when (cfg.promptFrequency.trim().lowercase(Locale.ROOT)) {
            "never" -> false
            "once" -> !p.getBoolean(KEY_SHOWN_ONCE, false)
            "every_days" -> {
                val last = p.getLong(KEY_LAST_SHOWN_AT, 0L)
                last == 0L || System.currentTimeMillis() - last >= TimeUnit.DAYS.toMillis(x.toLong())
            }
            "app_launches" -> p.getInt(KEY_LAUNCH_COUNT, 0) - p.getInt(KEY_LAUNCH_AT_LAST_SHOWN, 0) >= x
            "reminder_creations" -> p.getInt(KEY_CREATED_COUNT, 0) - p.getInt(KEY_CREATED_AT_LAST_SHOWN, 0) >= x
            else -> true
        }
    }

    /** Stamps every counter the moment the prompt is actually on screen. */
    fun markShown(c: Context) {
        val p = prefs(c)
        p.edit()
            .putBoolean(KEY_SHOWN_ONCE, true)
            .putLong(KEY_LAST_SHOWN_AT, System.currentTimeMillis())
            .putInt(KEY_LAUNCH_AT_LAST_SHOWN, p.getInt(KEY_LAUNCH_COUNT, 0))
            .putInt(KEY_CREATED_AT_LAST_SHOWN, p.getInt(KEY_CREATED_COUNT, 0))
            .apply()
    }
}
