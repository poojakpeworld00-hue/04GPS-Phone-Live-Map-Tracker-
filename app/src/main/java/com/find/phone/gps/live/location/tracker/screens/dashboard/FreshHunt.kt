package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.content.Context
import com.find.phone.gps.live.location.tracker.store.inquiry.NumDigest
import com.find.phone.gps.live.location.tracker.store.plus.PlusCheckout
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject

data class FreshHunt(
    val digits: String,
    val iso2: String,
    val dial: String,
    val searchedAt: Long,
    val report: NumDigest? = null,
    val unlocked: Boolean = false,
) {
    val display: String
        get() {
            val groups = listOf(digits.take(3), digits.drop(3).take(3), digits.drop(6)).filter { it.isNotEmpty() }
            return "+$dial " + groups.joinToString(" ")
        }
}

class FreshHuntShelf(context: Context) {
    private val app = context.applicationContext
    private val gson = Gson()
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<FreshHunt> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                FreshHunt(
                    digits = o.getString("digits"),
                    iso2 = o.getString("iso2"),
                    dial = o.getString("dial"),
                    searchedAt = o.getLong("at"),
                    report = o.optString("report").takeIf { it.isNotEmpty() }?.let {
                        runCatching { gson.fromJson(it, NumDigest::class.java) }.getOrNull()
                    },

                    unlocked = o.optBoolean("unlocked", o.has("report")),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(search: FreshHunt) {
        val next = listOf(search) + all().filterNot { it.digits == search.digits && it.dial == search.dial }

        save(next.take(if (PlusCheckout.isPremium(app)) MAX_PREMIUM else MAX))
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    fun remove(search: FreshHunt) =
        save(all().filterNot { it.digits == search.digits && it.dial == search.dial })

    fun find(digits: String, dial: String): FreshHunt? =
        all().firstOrNull { it.digits == digits && it.dial == dial }

    private fun save(list: List<FreshHunt>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("digits", it.digits)
                    .put("iso2", it.iso2)
                    .put("dial", it.dial)
                    .put("at", it.searchedAt)
                    .put("unlocked", it.unlocked)
                    .apply { it.report?.let { r -> put("report", gson.toJson(r)) } }
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "recent_searches"
        const val KEY = "items"
        const val MAX = 20
        const val MAX_PREMIUM = 500
    }
}
