package com.find.my.phone.gps.location.tracker.data.lookup

import android.content.Context
import androidx.annotation.Keep
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Serializable
import java.util.Locale

/** A map point. */
@Keep
data class GeoPin(val lat: Double, val lng: Double) : Serializable

/** How precisely the number's area is known; drives the map zoom and the area circle. */
enum class AreaPrecision(val zoom: Double, val radiusMeters: Double) {
    PINCODE(13.5, 3_000.0),
    CITY(12.0, 6_000.0),
    STATE(7.0, 60_000.0),
    COUNTRY(4.5, 0.0),
}

/** Everything the detail screen shows for one searched number. */
@Keep
data class NumberReport(
    val e164: String,
    val international: String,
    val iso2: String,
    val countryName: String,
    val valid: Boolean,
    val lineType: LineType,
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
    val pin: GeoPin?,
) : Serializable {

    val precision: AreaPrecision
        get() = when {
            !pincode.isNullOrBlank() -> AreaPrecision.PINCODE
            !city.isNullOrBlank() -> AreaPrecision.CITY
            !state.isNullOrBlank() -> AreaPrecision.STATE
            else -> AreaPrecision.COUNTRY
        }

    /** "Junagadh, Gujarat, India": most precise first, blanks dropped. */
    val placeLabel: String
        get() = listOf(city, state, countryName).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .distinct().joinToString(", ")
}

/**
 * One search: offline facts from libphonenumber, the contact-saver lookup, then the area
 * geocoded to a map point. A failed or slow server call never fails the search; the report
 * then carries only what libphonenumber knows ([NumberReport.serverReached] = false).
 */
object NumberLookup {

    private const val TAG = "NumberLookup"
    private const val API_TIMEOUT_MS = 12_000L

    suspend fun run(context: Context, digits: String, iso2: String, dial: String): NumberReport = coroutineScope {
        val app = context.applicationContext
        val facts = withContext(Dispatchers.Default) { NumberFactsReader.read(digits, iso2, dial) }

        val call = async(Dispatchers.IO) {
            withTimeoutOrNull(API_TIMEOUT_MS) {
                try {
                    Log.i(TAG, "request phone=${facts.e164} (region ${facts.iso2}, valid=${facts.valid})")
                    LookupClient.api(app).similarPhoneNumber(facts.e164)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "request failed: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
        }
        val response = call.await()
        val body = response?.body()?.takeIf { response.isSuccessful && it.ok }
        when {
            response == null -> Log.w(TAG, "response: none (network error or timeout)")
            !response.isSuccessful -> Log.w(TAG, "response: HTTP ${response.code()} ${response.errorBody()?.string()?.take(200).orEmpty()}")
            body == null -> Log.i(TAG, "response: HTTP ${response.code()}, success=false (no data)")
            else -> Log.i(
                TAG,
                "response: HTTP ${response.code()}, count=${body.count}, names=${body.names.size}, " +
                    "first=${body.names.firstOrNull()}, country=${body.countryCode}, area=${body.area}, " +
                    "carrier=${body.carrier}, spam=${body.isSpam}",
            )
        }

        val area = body?.area
        val countryName = countryName(body?.countryCode) ?: countryName(facts.iso2) ?: facts.iso2
        val names = body?.names.orEmpty()
        val city = area?.city?.trim()?.takeIf(String::isNotEmpty)
        val state = area?.state?.trim()?.takeIf(String::isNotEmpty) ?: facts.area
        val pincode = area?.pincode?.trim()?.takeIf(String::isNotEmpty)

        val pin = AreaGeocoder.locate(app, pincode, city, state, countryName)
        Log.i(TAG, "report: place=[${listOfNotNull(city, state, pincode, countryName).joinToString()}], pin=${pin ?: "none"}")

        NumberReport(
            e164 = facts.e164,
            international = facts.international,
            iso2 = facts.iso2,
            countryName = countryName,
            valid = facts.valid,
            lineType = facts.lineType,
            carrier = body?.carrier ?: facts.carrier,
            timeZone = facts.timeZone,
            name = names.firstOrNull(),
            otherNames = ArrayList(names.drop(1)),
            city = city,
            state = state,
            pincode = pincode,
            spam = body?.isSpam == true,
            // 404 = the server answered "no record"; auth / server errors count as unreachable.
            serverReached = response != null && (response.isSuccessful || response.code() == 404),
            pin = pin,
        )
    }

    /** "IN" → "India"; a legacy full name passes through. */
    private fun countryName(code: String?): String? {
        val c = code?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (c.length != 2 || !c.all(Char::isLetter)) return c
        return Locale("", c.uppercase(Locale.ROOT)).getDisplayCountry(Locale.getDefault()).takeIf { it.isNotBlank() && it != c.uppercase(Locale.ROOT) }
    }
}
