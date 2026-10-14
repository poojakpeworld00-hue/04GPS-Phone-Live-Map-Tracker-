package com.find.phone.gps.live.location.tracker.data

import android.content.Context
import com.find.phone.gps.live.location.tracker.adcast.data.getLocationFromIP
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.ui.lookup.Realms
import com.find.phone.gps.live.location.tracker.util.WardenRail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the user's IP-resolved country.
 *
 * The country is detected **once** — SpotBeaconActivity resolves it early via
 * [getLocationFromIP] and stores it in [LockerBook.homeCountryIso]. Every other
 * caller (Language, Home, Lookup) goes through [detectCountry], which reuses that
 * cached value and only touches the network if nothing has resolved it yet — so
 * the geo endpoint is never hit multiple times.
 *
 * Best-effort: returns null when there's no cache and the network call fails.
 * ip-api returns only the ISO code, so the dialing code is derived from [Realms].
 */
object ZoneSpotter {

    private const val TAG = "ZoneSpotter"

    /**
     * TEST ONLY — force a country in debug builds, bypassing cache and network.
     * Set to an ISO-3166 alpha-2 code (e.g. "JP" for Japan) to test that region's
     * suggestions; set back to null for normal IP detection. No effect in release.
     */
    private val DEBUG_FORCE_ISO: String? = "JP"

    /** Resolved country: ISO-3166 alpha-2 plus the dialing code (digits, no '+'). */
    data class GeoCountry(val iso: String, val dial: String)

    /**
     * The user's country, cache-first:
     *  1. [LockerBook.homeCountryIso] — user pick or SpotBeaconActivity's detection.
     *  2. [LockerBook.geoCountryIso]  — our own previously-cached IP result.
     *  3. Only if both are empty, hit the network once and cache the result.
     *
     * Returns null only when there's no cache and the IP lookup fails.
     */
    suspend fun detectCountry(context: Context): GeoCountry? {
        // Debug override wins over everything so a forced region is deterministic.
        if (BuildConfig.DEBUG) {
            validCountry(DEBUG_FORCE_ISO)?.let { forced ->
                WardenRail.log(TAG, "DEBUG force country=$forced (cache/network bypassed)")
                return GeoCountry(forced, Realms.dialOf(forced).orEmpty())
            }
        }

        val prefs = LockerBook(context)

        // Reuse a cached value only if it's a *real* ISO-2 country (present in the
        // dial table). This rejects stale/garbage codes (e.g. a language tag like
        // "JA" wrongly stored as a country), so the cache self-heals via re-detect.
        val cached = validCountry(prefs.homeCountryIso) ?: validCountry(prefs.geoCountryIso)
        if (cached != null) {
            WardenRail.log(TAG, "reuse cached country=$cached (no IP call)")
            return GeoCountry(cached, Realms.dialOf(cached).orEmpty())
        }

        val geo = detectCountryFromIp() ?: return null
        // Cache under our own key (not homeCountryIso) so it's never confused with
        // an explicit user pick — subsequent callers reuse it without a network hit.
        prefs.geoCountryIso = geo.iso
        WardenRail.log(TAG, "cached detected country=${geo.iso} for app-wide reuse")
        return geo
    }

    /** An uppercase ISO-2 code, or null when [raw] isn't a recognised country. */
    private fun validCountry(raw: String?): String? {
        val iso = raw?.takeIf { it.length == 2 }?.uppercase() ?: return null
        return iso.takeIf { Realms.dialOf(it) != null }
    }

    /** One-shot IP lookup via the app's shared [getLocationFromIP] source (ip-api.com). */
    private suspend fun detectCountryFromIp(): GeoCountry? = withContext(Dispatchers.IO) {
        WardenRail.log(TAG, "no cache → getLocationFromIP()")
        val location = getLocationFromIP()
        if (location == null) {
            WardenRail.log(TAG, "getLocationFromIP() returned null → null")
            return@withContext null
        }
        WardenRail.log(TAG, "location: country=${location.country} code=${location.countryCode}")

        val iso = location.countryCode?.takeIf { it.length == 2 }?.uppercase()
        if (iso == null) {
            WardenRail.log(TAG, "no valid countryCode → null")
            return@withContext null
        }
        val dial = Realms.dialOf(iso).orEmpty()
        WardenRail.log(TAG, "resolved country=$iso dial=$dial")
        GeoCountry(iso, dial)
    }
}
