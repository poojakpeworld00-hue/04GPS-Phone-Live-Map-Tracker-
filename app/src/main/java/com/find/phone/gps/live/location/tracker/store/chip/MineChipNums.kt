package com.find.phone.gps.live.location.tracker.store.chip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.find.phone.gps.live.location.tracker.store.peoplesync.BuddyLines
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale

object MineChipNums {
    val PERMISSIONS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)

    data class SimLine(

        val slot: Int,
        val carrier: String,

        val e164: String?,

        val number: String?,

        val region: String?,
    )

    private val util by lazy { PhoneNumberUtil.getInstance() }
    private val geocoder by lazy { PhoneNumberOfflineGeocoder.getInstance() }

    fun hasReadySim(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        return runCatching { tm.simState == TelephonyManager.SIM_STATE_READY }.getOrDefault(false)
    }

    fun isGranted(context: Context): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun read(context: Context): List<SimLine> {
        if (!isGranted(context)) return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val subs = runCatching { sm.activeSubscriptionInfoList }.getOrNull().orEmpty()
        return subs.sortedBy { it.simSlotIndex }.map { info ->
            val parsed = parse(rawNumber(sm, info), info.countryIso, context)
            SimLine(
                slot = info.simSlotIndex + 1,
                carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    ?: info.displayName?.toString().orEmpty(),
                e164 = parsed?.let { util.format(it, PhoneNumberUtil.PhoneNumberFormat.E164) },
                number = parsed?.let { util.format(it, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL) },
                region = parsed?.let(::regionOf) ?: countryName(info.countryIso),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun rawNumber(sm: SubscriptionManager, info: SubscriptionInfo): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) sm.getPhoneNumber(info.subscriptionId)
            else @Suppress("DEPRECATION") info.number
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun parse(raw: String?, simIso: String?, context: Context): PhoneNumber? {
        if (raw == null) return null
        val region = simIso?.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: BuddyLines.deviceRegion(context)
        val candidates = listOf(raw, "+" + raw.trimStart('+', '0'))
        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching { util.parse(candidate, region) }.getOrNull()?.takeIf { util.isValidNumber(it) }
        }
    }

    private fun regionOf(number: PhoneNumber): String? {
        val country = countryName(util.getRegionCodeForNumber(number))
        val area = runCatching { geocoder.getDescriptionForNumber(number, Locale.getDefault()) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != country }
        return listOfNotNull(area, country).joinToString(", ").ifBlank { null }
    }

    private fun countryName(iso: String?): String? {
        val code = iso?.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: return null
        return Locale("", code).getDisplayCountry(Locale.getDefault()).takeIf { it.isNotBlank() && it != code }
    }
}
