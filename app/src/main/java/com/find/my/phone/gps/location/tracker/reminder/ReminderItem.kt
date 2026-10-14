package com.find.my.phone.gps.location.tracker.reminder

import java.util.Calendar

/**
 * One reminder. [dateTime] is when it fires next (moved by Snooze); [baseTime] is the time the
 * user picked, which a repeating reminder keeps returning to. Persisted as JSON by [ReminderStore].
 */
data class ReminderItem(
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

    /**
     * The first time after [after] that a [repeat] reminder picked for [base] falls on: the same
     * hour and minute, on the next day (daily) or next Monday–Friday (weekdays). Null for once.
     */
    fun nextAfter(repeat: String, base: Long, after: Long): Long? {
        if (repeat != DAILY && repeat != WEEKDAYS) return null
        val pick = Calendar.getInstance().apply { timeInMillis = base }
        val c = Calendar.getInstance().apply {
            timeInMillis = after
            set(Calendar.HOUR_OF_DAY, pick.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, pick.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            val weekday = c.get(Calendar.DAY_OF_WEEK) !in setOf(Calendar.SATURDAY, Calendar.SUNDAY)
            if (c.timeInMillis > after && (repeat == DAILY || weekday)) return c.timeInMillis
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }
}
