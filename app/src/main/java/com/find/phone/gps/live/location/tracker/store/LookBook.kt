package com.find.phone.gps.live.location.tracker.store

import androidx.appcompat.app.AppCompatDelegate

object LookBook {
    val modes = intArrayOf(
        AppCompatDelegate.MODE_NIGHT_NO,
        AppCompatDelegate.MODE_NIGHT_YES,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    )

    fun apply(mode: Int) = AppCompatDelegate.setDefaultNightMode(mode)

    fun indexOf(mode: Int): Int = modes.indexOf(mode).coerceAtLeast(0)
}
