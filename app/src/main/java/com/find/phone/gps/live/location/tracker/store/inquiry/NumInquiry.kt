package com.find.phone.gps.live.location.tracker.store.inquiry

import android.content.Context
import androidx.annotation.Keep
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import java.io.Serializable
import java.util.Locale

/** A map point. */
@Keep
data class TerraTack(val lat: Double, val lng: Double) : Serializable

/** How precisely the number's area is known; drives the map zoom and the area circle. */
enum class SectorExactness(val zoom: Double, val radiusMeters: Double) {
    PINCODE(13.5, 3_000.0),
    CITY(12.0, 6_000.0),
    STATE(7.0, 60_000.0),
    COUNTRY(4.5, 0.0),
}

/** Everything the detail screen shows for one searched number. */
@Keep
data class NumDigest(
    val e164: String,
    val international: String,
    val iso2: String,
    val countryName: String,
    val valid: Boolean,
    val lineType: RowGenre,
    val carrier: String?,
    val timeZone: String?,
    /** Best-match name from the server; null when nobody knows it. */
    val name: String?,
    val otherNames: List<String>,
    val city: String?,
    val state: String?,
    val pincode: String?,
    val spam: Boolean,
    /** False when the server couldn't be reached (offline, timeout): only offline facts are shown. */
    val serverReached: Boolean,
    val pin: TerraTack?,
) : Serializable {

    val precision: SectorExactness
        get() = when {
            !pincode.isNullOrBlank() -> SectorExactness.PINCODE
            !city.isNullOrBlank() -> SectorExactness.CITY
            !state.isNullOrBlank() -> SectorExactness.STATE
            else -> SectorExactness.COUNTRY
        }

    /** "Junagadh, Gujarat, India": most precise first, blanks dropped. */
    val placeLabel: String
        get() = listOf(city, state, countryName).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .distinct().joinToString(", ")
}

/**
 * Runs a single lookup: libphonenumber facts, then the remote contact-saver call, then a geocode
 * of the reported area. Server trouble never fails the lookup; it only flips [NumDigest.serverReached].
 */
object NumInquiry {

    private const val TAG = "NumInquiry"
    private const val CALL_BUDGET_MS = 12_000L

    suspend fun run(context: Context, digits: String, iso2: String, dial: String): NumDigest = coroutineScope {
        val appCtx = context.applicationContext
        val offline = withContext(Dispatchers.Default) { NumNotesScanner.read(digits, iso2, dial) }

        val reply = async(Dispatchers.IO) { fetch(appCtx, offline) }.await()
        val payload = reply?.body()?.takeIf { reply.isSuccessful && it.ok }
        traceReply(reply, payload)

        val region = payload?.area
        val nation = displayCountry(payload?.countryCode) ?: displayCountry(offline.iso2) ?: offline.iso2
        val matches = payload?.names ?: emptyList()
        val town = region?.city.nonBlank()
        val province = region?.state.nonBlank() ?: offline.area
        val postal = region?.pincode.nonBlank()

        val point = SectorPlaceCoder.locate(appCtx, postal, town, province, nation)
        Log.i(TAG, "report: place=[${listOfNotNull(town, province, postal, nation).joinToString()}], pin=${point ?: "none"}")

        val reached = when {
            reply == null -> false
            reply.isSuccessful -> true
            else -> reply.code() == 404
        }

        NumDigest(
            e164 = offline.e164,
            international = offline.international,
            iso2 = offline.iso2,
            countryName = nation,
            valid = offline.valid,
            lineType = offline.lineType,
            carrier = payload?.carrier ?: offline.carrier,
            timeZone = offline.timeZone,
            name = matches.firstOrNull(),
            otherNames = ArrayList(matches.drop(1)),
            city = town,
            state = province,
            pincode = postal,
            spam = payload?.isSpam == true,
            serverReached = reached,
            pin = point,
        )
    }

    private suspend fun fetch(appCtx: Context, offline: NumNotes): Response<MobileAnswer>? =
        withTimeoutOrNull(CALL_BUDGET_MS) {
            try {
                Log.i(TAG, "request phone=${offline.e164} (region ${offline.iso2}, valid=${offline.valid})")
                InquiryCaller.api(appCtx).similarPhoneNumber(offline.e164)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

    private fun traceReply(reply: Response<MobileAnswer>?, payload: MobileAnswer?) {
        if (reply == null) {
            Log.w(TAG, "response: none (network error or timeout)")
            return
        }
        if (!reply.isSuccessful) {
            Log.w(TAG, "response: HTTP ${reply.code()} ${reply.errorBody()?.string()?.take(200).orEmpty()}")
            return
        }
        if (payload == null) {
            Log.i(TAG, "response: HTTP ${reply.code()}, success=false (no data)")
            return
        }
        Log.i(
            TAG,
            "response: HTTP ${reply.code()}, count=${payload.count}, names=${payload.names.size}, " +
                "first=${payload.names.firstOrNull()}, country=${payload.countryCode}, area=${payload.area}, " +
                "carrier=${payload.carrier}, spam=${payload.isSpam}",
        )
    }

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /** ISO alpha-2 to a localized country name; anything else is returned as given. */
    private fun displayCountry(code: String?): String? {
        val raw = code.nonBlank() ?: return null
        val isIso = raw.length == 2 && raw.all { it.isLetter() }
        if (!isIso) return raw
        val upper = raw.uppercase(Locale.ROOT)
        val shown = Locale("", upper).getDisplayCountry(Locale.getDefault())
        return if (shown.isBlank() || shown == upper) null else shown
    }
}
