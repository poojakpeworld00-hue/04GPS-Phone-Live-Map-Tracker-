package com.find.phone.gps.live.location.tracker.store.chip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.find.phone.gps.live.location.tracker.store.inquiry.InquiryCaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

object MineChipTitles {
    enum class Source(val key: String) { LOOKUP("lookup"), PROFILE("profile"), CONTACT("contact"), SIM("sim") }

    data class Resolved(val name: String, val source: Source)

    private const val TAG = "SimNumber"
    private const val PREFS = "my_sim_names"
    private const val CACHE_MS = 7L * 24 * 60 * 60 * 1000
    private const val API_TIMEOUT_MS = 12_000L

    private val JUNK = setOf(
        "me", "my", "my number", "mine", "self", "own", "unknown", "spam", "fraud", "scam", "no name",
        "jio", "vi", "vodafone", "idea", "airtel", "bsnl", "mtnl", "sim", "sim 1", "sim 2", "mobile", "phone",
    )

    fun cached(context: Context, e164: String): Resolved? {
        val raw = prefs(context).getString(e164, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            if (System.currentTimeMillis() - json.getLong("at") > CACHE_MS) return null
            val source = Source.entries.first { it.key == json.getString("source") }
            Resolved(json.getString("name"), source)
        }.getOrNull()
    }

    suspend fun resolve(context: Context, e164: String, simLabel: String): Resolved {
        val app = context.applicationContext
        cached(app, e164)?.let { return it }
        val found = fromLookup(app, e164)?.let { Resolved(it, Source.LOOKUP) }
            ?: withContext(Dispatchers.IO) { fromProfile(app, e164) }?.let { Resolved(it, Source.PROFILE) }
            ?: withContext(Dispatchers.IO) { fromContacts(app, e164) }?.let { Resolved(it, Source.CONTACT) }
        if (found != null) save(app, e164, found)
        return (found ?: Resolved(simLabel, Source.SIM)).also {
            Log.d(TAG, "name ${mask(e164)} = \"${it.name}\" source=${it.source.key}")
        }
    }

    private suspend fun fromLookup(context: Context, e164: String): String? = withContext(Dispatchers.IO) {
        val response = withTimeoutOrNull(API_TIMEOUT_MS) {
            try {
                InquiryCaller.api(context).similarPhoneNumber(e164)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "lookup ${mask(e164)} failed: ${e.javaClass.simpleName}")
                null
            }
        }
        val body = response?.body()?.takeIf { response.isSuccessful && it.ok }
        if (body == null) {
            Log.d(TAG, "lookup ${mask(e164)}: no data (HTTP ${response?.code() ?: "-"})")
            return@withContext null
        }
        if (body.isSpam) {
            Log.d(TAG, "lookup ${mask(e164)}: flagged spam, names ignored")
            return@withContext null
        }
        body.names.firstOrNull { isUsable(it, e164) }
            .also { Log.d(TAG, "lookup ${mask(e164)}: ${body.names.size} names, picked=${it != null}") }
    }

    private fun fromProfile(context: Context, e164: String): String? {
        if (!hasContacts(context)) return null
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Profile.CONTENT_URI,
                arrayOf(ContactsContract.Profile.DISPLAY_NAME_PRIMARY),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.trim()?.takeIf { isUsable(it, e164) }
    }

    private fun fromContacts(context: Context, e164: String): String? {
        if (!hasContacts(context)) return null
        return runCatching {
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(e164)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.trim()?.takeIf { isUsable(it, e164) }
    }

    internal fun isUsable(name: String, e164: String): Boolean {
        val clean = name.trim()
        val letters = clean.count(Char::isLetter)
        val digits = clean.count(Char::isDigit)
        return letters >= 2 &&
            digits <= letters &&
            clean.lowercase(Locale.ROOT) !in JUNK &&
            !e164.takeLast(6).let { clean.filter(Char::isDigit).contains(it) }
    }

    private fun save(context: Context, e164: String, resolved: Resolved) = prefs(context).edit {
        putString(
            e164,
            JSONObject().put("name", resolved.name).put("source", resolved.source.key)
                .put("at", System.currentTimeMillis()).toString(),
        )
    }

    private fun hasContacts(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun mask(e164: String) = e164.take(3) + "•".repeat((e164.length - 7).coerceAtLeast(0)) + e164.takeLast(4)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
