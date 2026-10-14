package com.find.phone.gps.live.location.tracker.memo

import java.util.Calendar

/**
 * One reminder. [dateTime] is when it fires next (moved by Snooze); [baseTime] is the time the
 * user picked, which a repeating reminder keeps returning to. Persisted as JSON by [MemoShelf].
 */
data class MemoElement(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    /** Optional number: the alert's main button calls it. */
    val number: String = "",
    val note: String = "",
    val dateTime: Long,
    val baseTime: Long = dateTime,
    val repeat: String = Repeat.ONCE,
    /** The list's switch: off keeps the reminder but nothing is scheduled. */
    val enabled: Boolean = true,
    /** A one-off reminder that has fired (or was marked Done). */
    val done: Boolean = false,
) {
    /** Gson leaves fields absent from older JSON as null even when Kotlin says otherwise. */
    val repeatOrOnce: String get() = repeat.ifBlank { Repeat.ONCE }

    val isActive: Boolean get() = enabled && !done
}

object Repeat {
    const val ONCE = "once"
    const val DAILY = "daily"
    const val WEEKDAYS = "weekdays"

    private const val LOOKAHEAD_DAYS = 8

    private fun isWorkday(cal: Calendar): Boolean = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SATURDAY, Calendar.SUNDAY -> false
        else -> true
    }

    /**
     * Next fire moment strictly after [after] at the hour/minute chosen in [base]: any day for
     * daily, Monday to Friday for weekdays. One-off reminders yield null.
     */
    fun nextAfter(repeat: String, base: Long, after: Long): Long? {
        val everyDay = repeat == DAILY
        if (!everyDay && repeat != WEEKDAYS) return null
        val chosen = Calendar.getInstance()
        chosen.timeInMillis = base
        val probe = Calendar.getInstance()
        probe.timeInMillis = after
        probe.set(Calendar.HOUR_OF_DAY, chosen.get(Calendar.HOUR_OF_DAY))
        probe.set(Calendar.MINUTE, chosen.get(Calendar.MINUTE))
        probe.set(Calendar.SECOND, 0)
        probe.set(Calendar.MILLISECOND, 0)
        for (step in 0 until LOOKAHEAD_DAYS) {
            val t = probe.timeInMillis
            if (t > after && (everyDay || isWorkday(probe))) return t
            probe.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }
}
