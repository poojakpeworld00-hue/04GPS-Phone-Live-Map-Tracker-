package com.find.my.phone.gps.location.tracker.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.data.contactsync.ContactRows

/**
 * DEBUG ONLY. Logs whether a SIM is present and which phone number the device reports for it.
 * Filter: `adb logcat -s SimNumber`
 *
 * The phone permissions are asked from the Home "My number" card; to test without it, grant them
 * from adb, then restart the app:
 *
 *     adb shell pm grant com.find.my.phone.gps.location.tracker android.permission.READ_PHONE_STATE
 *     adb shell pm grant com.find.my.phone.gps.location.tracker android.permission.READ_PHONE_NUMBERS
 *
 * Without them the SIM state, country and carrier still log; the number lines say so instead.
 * Many carriers do not write the number onto the SIM, so an empty number with a READY SIM is normal.
 */
object SimNumberLog {

    private const val TAG = "SimNumber"

    fun log(context: Context) {
        if (!BuildConfig.DEBUG) return
        runCatching { dump(context) }.onFailure { Log.w(TAG, "SIM check failed", it) }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun dump(context: Context) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null) {
            Log.d(TAG, "No TelephonyManager (no telephony on this device)")
            return
        }
        val phoneState = granted(context, Manifest.permission.READ_PHONE_STATE)
        val phoneNumbers = granted(context, Manifest.permission.READ_PHONE_NUMBERS)

        Log.d(TAG, "──── SIM check ────")
        Log.d(TAG, "READ_PHONE_STATE=$phoneState  READ_PHONE_NUMBERS=$phoneNumbers")
        Log.d(TAG, "simState=${simStateName(tm.simState)}  present=${tm.simState == TelephonyManager.SIM_STATE_READY}")
        Log.d(TAG, "simCountryIso=${tm.simCountryIso}  networkCountryIso=${tm.networkCountryIso}")
        Log.d(TAG, "simOperator=${tm.simOperatorName}  network=${tm.networkOperatorName}")
        Log.d(TAG, "deviceRegion (used for contacts)=${ContactRows.deviceRegion(context)}")

        // Default SIM's number. line1Number accepts either permission.
        if (phoneNumbers || phoneState) {
            val line1 = runCatching { tm.line1Number }
                .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
            Log.d(TAG, "line1Number=${line1.ifBlankLabel()}")
        } else {
            Log.d(TAG, "line1Number skipped: no phone permission")
        }

        // Every active SIM (dual-SIM phones). The list needs READ_PHONE_STATE.
        if (!phoneState) {
            Log.d(TAG, "Per-SIM list skipped: READ_PHONE_STATE not granted")
            return
        }
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val subs = sm?.activeSubscriptionInfoList.orEmpty()
        Log.d(TAG, "activeSims=${subs.size}")
        subs.forEach { info ->
            val number = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && phoneNumbers ->
                    runCatching { sm!!.getPhoneNumber(info.subscriptionId) }
                        .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
                    @Suppress("DEPRECATION") info.number
                else -> "skipped: READ_PHONE_NUMBERS not granted"
            }
            Log.d(
                TAG,
                "slot=${info.simSlotIndex} subId=${info.subscriptionId} carrier=${info.carrierName} " +
                    "country=${info.countryIso} number=${number.ifBlankLabel()}",
            )
        }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun String?.ifBlankLabel() = if (isNullOrBlank()) "(empty - not stored on SIM)" else this

    private fun simStateName(state: Int) = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
        TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
        else -> "UNKNOWN($state)"
    }
}
