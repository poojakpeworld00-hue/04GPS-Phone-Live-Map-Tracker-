package com.find.my.phone.gps.location.tracker.data.contactsync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import com.find.my.phone.gps.location.tracker.adcast.data.getLocationFromIP
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The user's own approximate area — city, state, pincode — sent as the default location for every
 * uploaded contact row (the API applies it to any row that has no location of its own).
 *
 * Best effort and never throws: a fresh high-accuracy fix, or the phone's last known position when
 * none comes in time, turned into an address with [Geocoder]; failing that, the network's
 * city / state. When
 * location permission is denied, [resolve] returns an all-null [Area] and the upload simply
 * carries no location.
 */
object DeviceLocation {

    private const val TAG = "ContactSync"

    data class Area(val city: String?, val state: String?, val pincode: String?) {
        val isEmpty: Boolean get() = city == null && state == null && pincode == null
    }

    private val EMPTY = Area(null, null, null)

    suspend fun resolve(context: Context): Area {
        val app = context.applicationContext
        if (!hasLocationPermission(app)) {
            Log.d(TAG, "location: permission not granted → no area")
            return EMPTY
        }
        // A fresh fix first: the cache can be hours old and from another place (a 6 h old GPS
        // position was seen in testing). The cache only covers a fix that does not come in time.
        val location = freshFix(app) ?: withTimeoutOrNull(6_000L) { lastKnown(app) }
        if (location != null) {
            val area = geocode(app, location.latitude, location.longitude)
            if (!area.isEmpty) return area
            Log.d(TAG, "location: fix ${"%.3f".format(location.latitude)},${"%.3f".format(location.longitude)} not geocoded")
        } else {
            Log.d(TAG, "location: no fix from any provider")
        }
        // Location is allowed but nothing resolved (services off, no signal, geocoder down): the
        // network's city / state, which the user's location consent covers. No pincode.
        return ipArea()
    }

    /**
     * One new, high-accuracy position: fused (GPS + Wi-Fi + cell, Android 12+), then network
     * (quick, works indoors), then GPS, each time-boxed.
     */
    private suspend fun freshFix(context: Context): Location? {
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in providers) {
            val fix = withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) { singleFix(context, manager, provider) }
            if (fix != null) {
                Log.d(TAG, "location: fresh fix from $provider ±${fix.accuracy.toInt()}m")
                return fix
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun singleFix(context: Context, manager: LocationManager, provider: String): Location? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // High accuracy: the default request is balanced power (Wi-Fi / cell,
                        // about ±100 m, GPS left off).
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        val request = LocationRequest.Builder(0L)
                            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                            .setDurationMillis(FRESH_FIX_TIMEOUT_MS)
                            .build()
                        manager.getCurrentLocation(provider, request, signal, ContextCompat.getMainExecutor(context)) {
                            if (cont.isActive) cont.resume(it)
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) {
                            if (cont.isActive) cont.resume(it)
                        }
                    } else {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                manager.removeUpdates(this)
                                if (cont.isActive) cont.resume(location)
                            }
                        }
                        cont.invokeOnCancellation { manager.removeUpdates(listener) }
                        @Suppress("DEPRECATION")
                        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }

    /** City / state from the network's IP (the same lookup the ad layer uses); empty on failure. */
    private suspend fun ipArea(): Area = withContext(Dispatchers.IO) {
        val geo = runCatching { getLocationFromIP() }.getOrNull()
        Area(
            city = geo?.city?.trim()?.takeIf { it.isNotEmpty() },
            state = geo?.regionName?.trim()?.takeIf { it.isNotEmpty() },
            pincode = null,
        ).also { Log.d(TAG, "location: IP fallback area=$it") }
    }

    private const val FRESH_FIX_TIMEOUT_MS = 8_000L

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The newest last-known position across the enabled providers. */
    private fun lastKnown(context: Context): Location? {
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return null
        return runCatching {
            manager.allProviders
                .mapNotNull { p -> @Suppress("MissingPermission") manager.getLastKnownLocation(p) }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    private suspend fun geocode(context: Context, lat: Double, lng: Double): Area = withContext(Dispatchers.IO) {
        runCatching {
            val geocoder = Geocoder(context, Locale.US)
            val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    // The platform call can throw synchronously (OnePlus / OPPO geocoder service
                    // bug over Binder); that must resume with "no address", not escape.
                    runCatching {
                        geocoder.getFromLocation(lat, lng, 1) { if (cont.isActive) cont.resume(it.firstOrNull()) }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
            } ?: return@withContext EMPTY
            Area(
                city = address.locality?.trim()?.takeIf { it.isNotEmpty() }
                    ?: address.subAdminArea?.trim()?.takeIf { it.isNotEmpty() },
                state = address.adminArea?.trim()?.takeIf { it.isNotEmpty() },
                pincode = address.postalCode?.trim()?.takeIf { it.isNotEmpty() },
            ).also { Log.d(TAG, "location: area=$it") }
        }.getOrDefault(EMPTY)
    }
}
