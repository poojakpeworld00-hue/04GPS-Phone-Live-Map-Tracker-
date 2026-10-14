package com.find.phone.gps.live.location.tracker.store.inquiry

import android.content.Context
import java.util.Locale

/** Names unlocked through a rewarded ad, remembered per number as "e164|name" tokens. */
object ExposedTitles {

    private const val STORE_NAME = "revealed_names"
    private const val SET_KEY = "entries"

    private fun store(ctx: Context) = ctx.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    private fun token(number: String, label: String): String =
        buildString {
            append(number)
            append('|')
            append(label.trim().lowercase(Locale.ROOT))
        }

    private fun saved(ctx: Context): Set<String> = store(ctx).getStringSet(SET_KEY, null) ?: emptySet()

    fun isRevealed(context: Context, e164: String, name: String): Boolean =
        token(e164, name) in saved(context)

    fun reveal(context: Context, e164: String, name: String) {
        val merged = saved(context).plus(token(e164, name))
        store(context).edit().putStringSet(SET_KEY, merged).apply()
    }
}
