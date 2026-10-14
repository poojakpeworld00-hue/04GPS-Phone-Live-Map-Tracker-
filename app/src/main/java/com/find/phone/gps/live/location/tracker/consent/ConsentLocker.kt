package com.find.phone.gps.live.location.tracker.consent

import android.content.Context

class ConsentLocker(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun wasShown(key: String): Boolean = prefs.getBoolean(shownKey(key), false)

    fun flagShown(key: String) {
        prefs.edit().putBoolean(shownKey(key), true).apply()
    }

    fun wasAsked(key: String): Boolean = prefs.getBoolean(askedKey(key), false)

    fun flagAsked(key: String) {
        prefs.edit().putBoolean(askedKey(key), true).apply()
    }

    private fun shownKey(key: String) = "shown_$key"
    private fun askedKey(key: String) = "asked_$key"

    companion object {
        private const val FILE = "permission_engine_prefs"
    }
}
