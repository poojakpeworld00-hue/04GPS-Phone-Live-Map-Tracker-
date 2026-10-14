package com.find.my.phone.gps.location.tracker.data.lookup

import android.content.Context
import java.util.Locale

/**
 * "Also known as" names the user has revealed with a rewarded ad, per number, so a reopened
 * result keeps them readable. Stored as "e164|name" entries in one string set.
 */
object RevealedNames {

    private const val PREFS = "revealed_names"
    private const val KEY = "entries"

    fun isRevealed(context: Context, e164: String, name: String): Boolean =
        entries(context).contains(entry(e164, name))

    fun reveal(context: Context, e164: String, name: String) {
        val next = entries(context) + entry(e164, name)
        prefs(context).edit().putStringSet(KEY, next).apply()
    }

    private fun entries(context: Context): Set<String> = prefs(context).getStringSet(KEY, null).orEmpty()

    private fun entry(e164: String, name: String) = "$e164|${name.trim().lowercase(Locale.ROOT)}"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
