package com.find.my.phone.gps.location.tracker.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.find.my.phone.gps.location.tracker.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * DEBUG ONLY. Logs where the phone is: the permission level, which location sources are on, the
 * cached positions (with their age), then a fresh fix with its accuracy and the street address it
 * resolves to. Filter: `adb logcat -s LocationLog`
 *
 * On Infinix / Transsion phones debug logs are hidden by default; first run
 *
 *     adb shell setprop log.tag.LocationLog D
 *
 * A cached position can be hours old and from another place; the fresh fix is the one to trust.
 * Approximate permission (Android 12+) blurs every position to about 2-3 km on purpose.
 */
object LocationLog {

    private const val TAG = "LocationLog"

    /** Per source; GPS needs open sky and can take a while on a cold start. */
    private const val FIX_TIMEOUT_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun log(context: Context) {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext
        scope.launch { runCatching { dump(app) }.onFailure { Log.w(TAG, "location check failed", it) } }
    }

    private suspend fun dump(context: Context) {
        val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        Log.d(TAG, "──── Location check ────")
        Log.d(TAG, "permission=${if (fine) "PRECISE" else if (coarse) "APPROXIMATE (~2-3 km)" else "NONE"}")
        if (!fine && !coarse) {
            Log.d(TAG, "Skipped: location permission not granted")
            return
        }

        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
        if (manager == null) {
            Log.d(TAG, "No LocationManager on this device")
            return
        }
        Log.d(TAG, "locationServicesOn=${LocationManagerCompat.isLocationEnabled(manager)}")
        val enabled = manager.allProviders.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        Log.d(TAG, "providers all=${manager.allProviders} enabled=$enabled")

        // Cached positions: instant, but possibly stale.
        enabled.forEach { provider ->
            @SuppressLint("MissingPermission")
            val last = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            Log.d(TAG, "lastKnown[$provider] = ${last?.let(::describe) ?: "none"}")
        }

        // A fresh, high-accuracy position: fused (GPS + Wi-Fi + cell, Android 12+), then GPS, then
        // network.
        val order = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { it in enabled }
        var fresh: Location? = null
        for (provider in order) {
            Log.d(TAG, "fresh fix: asking $provider (up to ${FIX_TIMEOUT_MS / 1000}s)…")
            val started = SystemClock.elapsedRealtime()
            fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) { currentFix(context, manager, provider) }
            val took = SystemClock.elapsedRealtime() - started
            if (fresh != null) {
                Log.d(TAG, "fresh fix [$provider] in ${took}ms = ${describe(fresh)}")
                break
            }
            Log.d(TAG, "fresh fix [$provider]: none after ${took}ms")
        }
        if (fresh == null) {
            Log.d(TAG, "No fresh fix from any source (location off, indoors without Wi-Fi, or no signal)")
            return
        }
        Log.d(TAG, "map: https://maps.google.com/?q=${fresh.latitude},${fresh.longitude}")
        val address = reverseGeocode(context, fresh)
        if (address == null) {
            Log.d(TAG, "address: not resolved (geocoder unavailable or offline)")
        } else {
            Log.d(TAG, "address: ${address.getAddressLine(0)}")
            Log.d(
                TAG,
                "area: subLocality=${address.subLocality} locality=${address.locality} " +
                    "district=${address.subAdminArea} state=${address.adminArea} " +
                    "pincode=${address.postalCode} country=${address.countryName} (${address.countryCode})",
            )
        }
    }

    private fun describe(location: Location): String {
        val ageSec = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000L
        val accuracy = if (location.hasAccuracy()) "±${location.accuracy.toInt()}m" else "±?"
        return "%.6f,%.6f %s age=%s provider=%s".format(
            Locale.US, location.latitude, location.longitude, accuracy, age(ageSec), location.provider,
        )
    }

    private fun age(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        else -> "${seconds / 3600}h${(seconds % 3600) / 60}m"
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFix(context: Context, manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // High accuracy: the default request is balanced power, which answers from
                    // Wi-Fi / cell at about ±100 m without turning GPS on.
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    val request = LocationRequest.Builder(0L)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setDurationMillis(FIX_TIMEOUT_MS)
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

    private suspend fun reverseGeocode(context: Context, location: Location): Address? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            runCatching {
                val geocoder = Geocoder(context, Locale.ENGLISH)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    withTimeoutOrNull(10_000L) {
                        suspendCancellableCoroutine { cont ->
                            runCatching {
                                geocoder.getFromLocation(location.latitude, location.longitude, 1) {
                                    if (cont.isActive) cont.resume(it.firstOrNull())
                                }
                            }.onFailure { if (cont.isActive) cont.resume(null) }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                }
            }.getOrNull()
        }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
