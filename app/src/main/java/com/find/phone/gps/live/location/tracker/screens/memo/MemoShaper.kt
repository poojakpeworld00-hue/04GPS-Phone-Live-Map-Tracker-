package com.find.phone.gps.live.location.tracker.screens.memo

import android.content.Context
import android.text.format.DateFormat
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.memo.Repeat
import java.util.Calendar
import java.util.Date

object MemoShaper {
    fun time(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

    fun day(context: Context, millis: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance()
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (sameDay(target, today)) return context.getString(R.string.rem_today)
        if (sameDay(target, (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) })) {
            return context.getString(R.string.rem_tomorrow)
        }
        if (sameDay(target, (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })) {
            return context.getString(R.string.rem_yesterday)
        }
        return DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEdMMM"), target).toString()
    }

    fun dayAndTime(context: Context, millis: Long): String =
        context.getString(R.string.rem_when_fmt, day(context, millis), time(context, millis))

    fun repeatLabel(context: Context, repeat: String): String = context.getString(
        when (repeat) {
            Repeat.DAILY -> R.string.rem_repeat_daily
            Repeat.WEEKDAYS -> R.string.rem_repeat_weekdays
            else -> R.string.rem_repeat_once
        }
    )
}
