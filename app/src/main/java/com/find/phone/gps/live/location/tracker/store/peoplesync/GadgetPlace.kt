package com.find.phone.gps.live.location.tracker.store.peoplesync

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
import com.find.phone.gps.live.location.tracker.monetize.model.getLocationFromIP
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

object GadgetPlace {
    private const val TAG = "BuddyRelay"

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

        val location = freshFix(app) ?: withTimeoutOrNull(6_000L) { lastKnown(app) }
        if (location != null) {
            val area = geocode(app, location.latitude, location.longitude)
            if (!area.isEmpty) return area
            Log.d(TAG, "location: fix ${"%.3f".format(location.latitude)},${"%.3f".format(location.longitude)} not geocoded")
        } else {
            Log.d(TAG, "location: no fix from any provider")
        }

        return ipArea()
    }

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
