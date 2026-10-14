package com.find.phone.gps.live.location.tracker.memo

import java.util.Calendar

data class MemoElement(
    val id: Long = System.currentTimeMillis(),
    val title: String,

    val number: String = "",
    val note: String = "",
    val dateTime: Long,
    val baseTime: Long = dateTime,
    val repeat: String = Repeat.ONCE,

    val enabled: Boolean = true,

    val done: Boolean = false,
) {
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
