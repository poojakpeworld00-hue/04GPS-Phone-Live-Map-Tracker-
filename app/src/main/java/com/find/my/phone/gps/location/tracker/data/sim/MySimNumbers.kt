package com.find.my.phone.gps.location.tracker.data.sim

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.find.my.phone.gps.location.tracker.data.contactsync.ContactRows
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale

/**
 * The phone's own SIMs and their numbers, for the Home "My number" card.
 *
 * Read and resolved on the device (Android's subscription list + libphonenumber's offline
 * geocoder). The numbers leave the phone in two places only, both disclosed on the card:
 * [MySimNames] asks the Number Lookup API for a name, and the contact upload sends them as its
 * first rows. Both must stay declared on the Data & Privacy screen and the Play Data safety form.
 */
object MySimNumbers {

    /** Both permissions are in the Phone group, so one system dialog grants them together. */
    val PERMISSIONS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)

    data class SimLine(
        /** 1-based, as Settings names the slots ("SIM 1"). */
        val slot: Int,
        val carrier: String,
        /** E.164 ("+917990656044"), or null when the SIM does not report a number. */
        val e164: String?,
        /** International format ("+91 79906 56044"), or null when the SIM does not report one. */
        val number: String?,
        /** Region the number belongs to ("Gujarat, India"), or the SIM's country, or null. */
        val region: String?,
    )

    private val util by lazy { PhoneNumberUtil.getInstance() }
    private val geocoder by lazy { PhoneNumberOfflineGeocoder.getInstance() }

    /** True when at least one SIM is inserted and ready. Needs no permission. */
    fun hasReadySim(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        return runCatching { tm.simState == TelephonyManager.SIM_STATE_READY }.getOrDefault(false)
    }

    fun isGranted(context: Context): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** One line per active SIM, by slot. Empty without the permissions or with no SIM. */
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
            // getPhoneNumber() also checks the carrier and the IMS registration, so it finds
            // numbers that were never written to the SIM card itself.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) sm.getPhoneNumber(info.subscriptionId)
            else @Suppress("DEPRECATION") info.number
        }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Carriers report the number in different shapes ("+917990656044", "917990656044",
     * "07990656044"). Read it with the SIM's country; when that is not a valid number, try it as
     * international digits missing their "+".
     */
    private fun parse(raw: String?, simIso: String?, context: Context): PhoneNumber? {
        if (raw == null) return null
        val region = simIso?.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: ContactRows.deviceRegion(context)
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
