package com.find.my.phone.gps.location.tracker.data.sim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.find.my.phone.gps.location.tracker.data.lookup.LookupClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

/**
 * A display name for one of the phone's own SIM numbers, first source that has one:
 *
 * 1. [Source.LOOKUP]: the Number Lookup API (`similar-phone-number`), best usable name.
 * 2. [Source.PROFILE]: the phone's own "Me" card in Contacts.
 * 3. [Source.CONTACT]: a saved contact with exactly this number.
 * 4. [Source.SIM]: "SIM 1 · Jio True5G", always available.
 *
 * API names are what other people saved the number as, so obvious junk is skipped (digits,
 * carrier / label words, one-letter names, anything on a number flagged as spam). The call goes
 * straight to [LookupClient], so it never uses the user's free searches. Results are cached on
 * the device for [CACHE_MS]; the SIM fallback is never cached, so a later lookup can still win.
 *
 * Asking the API sends the user's own number to the lookup service; the My number card says so.
 * Log tag: `SimNumber` (with [com.find.my.phone.gps.location.tracker.util.SimNumberLog]).
 */
object MySimNames {

    enum class Source(val key: String) { LOOKUP("lookup"), PROFILE("profile"), CONTACT("contact"), SIM("sim") }

    data class Resolved(val name: String, val source: Source)

    private const val TAG = "SimNumber"
    private const val PREFS = "my_sim_names"
    private const val CACHE_MS = 7L * 24 * 60 * 60 * 1000
    private const val API_TIMEOUT_MS = 12_000L

    /** Words that are a carrier or a placeholder, never a person's name. */
    private val JUNK = setOf(
        "me", "my", "my number", "mine", "self", "own", "unknown", "spam", "fraud", "scam", "no name",
        "jio", "vi", "vodafone", "idea", "airtel", "bsnl", "mtnl", "sim", "sim 1", "sim 2", "mobile", "phone",
    )

    /** Cached result, or null when there is none (or it expired). Cheap; safe on the main thread. */
    fun cached(context: Context, e164: String): Resolved? {
        val raw = prefs(context).getString(e164, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            if (System.currentTimeMillis() - json.getLong("at") > CACHE_MS) return null
            val source = Source.entries.first { it.key == json.getString("source") }
            Resolved(json.getString("name"), source)
        }.getOrNull()
    }

    /** Resolves [e164] (the SIM line's E.164 number); [simLabel] is the last-resort name. */
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
                LookupClient.api(context).similarPhoneNumber(e164)
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

    /** The phone owner's "Me" card. Read with the contacts permission the app already asks for. */
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

    /** A contact saved with this number; the phone matches formats ("+91…", "0…", spaces). */
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

    /** "+91•••••6044": enough to tell the SIMs apart in a log without printing the number. */
    private fun mask(e164: String) = e164.take(3) + "•".repeat((e164.length - 7).coerceAtLeast(0)) + e164.takeLast(4)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
