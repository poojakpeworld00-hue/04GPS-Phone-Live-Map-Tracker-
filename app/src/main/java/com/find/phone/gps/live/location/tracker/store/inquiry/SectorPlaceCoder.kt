package com.find.phone.gps.live.location.tracker.store.inquiry

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

object SectorPlaceCoder {
    private const val CACHE_FILE = "area_geocode_cache"
    private const val LOOKUP_TIMEOUT_MS = 6_000L

    suspend fun locate(context: Context, pincode: String?, city: String?, state: String?, country: String?): TerraTack? {
        val appCtx = context.applicationContext
        val candidates = candidateQueries(pincode, city, state, country)
        var idx = 0
        while (idx < candidates.size) {
            val q = candidates[idx++]
            readCache(appCtx, q)?.let { return it }
            val hit = lookup(appCtx, q)
            if (hit != null) {
                writeCache(appCtx, q, hit)
                return hit
            }
        }
        return null
    }

    private fun candidateQueries(pincode: String?, city: String?, state: String?, country: String?): List<String> {
        val combos = arrayOf(
            arrayOf(pincode, city, state, country),
            arrayOf(city, state, country),
            arrayOf(pincode, country),
            arrayOf(state, country),
            arrayOf(country),
        )
        val result = LinkedHashSet<String>()
        combos.forEach { parts ->
            val joined = parts.asSequence()
                .mapNotNull { it?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
            if (joined.isNotEmpty()) result += joined
        }
        return result.toList()
    }

    private suspend fun lookup(context: Context, query: String): TerraTack? {
        val present = runCatching { Geocoder.isPresent() }.getOrElse { false }
        if (!present) return null
        val coder = runCatching { Geocoder(context, Locale.ENGLISH) }.getOrNull() ?: return null
        val found: Address? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> asyncLookup(coder, query)
                else -> withContext(Dispatchers.IO) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        coder.getFromLocationName(query, 1)?.firstOrNull()
                    }.getOrNull()
                }
            }
        }
        return runCatching {
            if (found != null && found.hasLatitude() && found.hasLongitude()) TerraTack(found.latitude, found.longitude) else null
        }.getOrNull()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun asyncLookup(coder: Geocoder, query: String): Address? =
        suspendCancellableCoroutine { cont ->
            val finish: (Address?) -> Unit = { a -> if (cont.isActive) cont.resume(a) }
            runCatching {
                coder.getFromLocationName(query, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = finish(addresses.firstOrNull())

                    override fun onError(errorMessage: String?) = finish(null)
                })
            }.onFailure { finish(null) }
        }

    private fun cacheOf(context: Context) = context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)

    private fun readCache(context: Context, query: String): TerraTack? {
        val stored = cacheOf(context).getString(query.lowercase(Locale.ROOT), null) ?: return null
        val nums = stored.split(",").mapNotNull(String::toDoubleOrNull)
        return nums.takeIf { it.size == 2 }?.let { TerraTack(it[0], it[1]) }
    }

    private fun writeCache(context: Context, query: String, pin: TerraTack) {
        cacheOf(context).edit().putString(query.lowercase(Locale.ROOT), "${pin.lat},${pin.lng}").apply()
    }
}
