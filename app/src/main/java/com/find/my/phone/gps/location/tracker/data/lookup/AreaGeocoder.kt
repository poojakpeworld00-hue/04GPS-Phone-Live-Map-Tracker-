package com.find.my.phone.gps.location.tracker.data.lookup

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Turns a registered area (pincode / city / state / country) into a map point with the
 * platform [Geocoder]: no API key, no location permission. Tries the most precise query first,
 * then looser ones; answers are cached per query.
 */
object AreaGeocoder {

    private const val PREFS = "area_geocode_cache"
    private const val TIMEOUT_MS = 6_000L

    suspend fun locate(context: Context, pincode: String?, city: String?, state: String?, country: String?): GeoPin? {
        val app = context.applicationContext
        for (query in queries(pincode, city, state, country)) {
            cached(app, query)?.let { return it }
            val pin = geocode(app, query) ?: continue
            save(app, query, pin)
            return pin
        }
        return null
    }

    private fun queries(pincode: String?, city: String?, state: String?, country: String?): List<String> {
        fun join(vararg parts: String?) = parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(", ")
        return listOf(
            join(pincode, city, state, country),
            join(city, state, country),
            join(pincode, country),
            join(state, country),
            join(country),
        ).filter { it.isNotEmpty() }.distinct()
    }

    /**
     * One geocoder query, never throwing. The platform call itself can fail synchronously — on
     * some OnePlus / OPPO builds (ColorOS 15+, Android 16) the system geocoder service throws a
     * NullPointerException over Binder from `forwardGeocode`, which surfaces here as a plain
     * exception from [Geocoder.getFromLocationName] — so every path treats a throw as "no answer".
     */
    private suspend fun geocode(context: Context, query: String): GeoPin? {
        if (!runCatching { Geocoder.isPresent() }.getOrDefault(false)) return null
        val geocoder = runCatching { Geocoder(context, Locale.ENGLISH) }.getOrNull() ?: return null
        val address: Address? = withTimeoutOrNull(TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    runCatching {
                        geocoder.getFromLocationName(query, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (cont.isActive) cont.resume(addresses.firstOrNull())
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                    }.onFailure {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocationName(query, 1)?.firstOrNull() }.getOrNull()
                }
            }
        }
        return runCatching {
            address?.takeIf { it.hasLatitude() && it.hasLongitude() }?.let { GeoPin(it.latitude, it.longitude) }
        }.getOrNull()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cached(context: Context, query: String): GeoPin? {
        val raw = prefs(context).getString(query.lowercase(Locale.ROOT), null) ?: return null
        val parts = raw.split(",").mapNotNull { it.toDoubleOrNull() }
        return if (parts.size == 2) GeoPin(parts[0], parts[1]) else null
    }

    private fun save(context: Context, query: String, pin: GeoPin) {
        prefs(context).edit().putString(query.lowercase(Locale.ROOT), "${pin.lat},${pin.lng}").apply()
    }
}
