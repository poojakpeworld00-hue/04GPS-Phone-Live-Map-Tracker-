package com.find.phone.gps.live.location.tracker.store

import android.content.Context
import com.find.phone.gps.live.location.tracker.monetize.model.getLocationFromIP
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.screens.finder.Realms
import com.find.phone.gps.live.location.tracker.helpers.WardenRail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ZoneSpotter {
    private const val TAG = "ZoneSpotter"

    private val DEBUG_FORCE_ISO: String? = "JP"

    data class GeoCountry(val iso: String, val dial: String)

    suspend fun senseCountry(context: Context): GeoCountry? {
        if (BuildConfig.DEBUG) {
            validCountry(DEBUG_FORCE_ISO)?.let { forced ->
                WardenRail.log(TAG, "DEBUG force country=$forced (cache/network bypassed)")
                return GeoCountry(forced, Realms.dialOf(forced).orEmpty())
            }
        }

        val prefs = LockerBook(context)

        val cached = validCountry(prefs.homeCountryIso) ?: validCountry(prefs.geoCountryIso)
        if (cached != null) {
            WardenRail.log(TAG, "reuse cached country=$cached (no IP call)")
            return GeoCountry(cached, Realms.dialOf(cached).orEmpty())
        }

        val geo = senseCountryFromIp() ?: return null

        prefs.geoCountryIso = geo.iso
        WardenRail.log(TAG, "cached detected country=${geo.iso} for app-wide reuse")
        return geo
    }

    private fun validCountry(raw: String?): String? {
        val iso = raw?.takeIf { it.length == 2 }?.uppercase() ?: return null
        return iso.takeIf { Realms.dialOf(it) != null }
    }

    private suspend fun senseCountryFromIp(): GeoCountry? = withContext(Dispatchers.IO) {
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
