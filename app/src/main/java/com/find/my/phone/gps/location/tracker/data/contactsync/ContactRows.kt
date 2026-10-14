package com.find.my.phone.gps.location.tracker.data.contactsync

import android.content.Context
import android.telephony.TelephonyManager
import com.find.my.phone.gps.location.tracker.ui.contacts.ContactNumber
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale

/**
 * Turns the raw phonebook into the rows that are worth uploading, each with its own location.
 *
 * - Every number is parsed with the phone's own country as the default ([deviceRegion]); one that
 *   is not a real number (short codes such as "52141", too short, too long) is dropped, and the
 *   rest are sent in E.164 so the server gets one format. The same number saved twice is sent once.
 * - Location is per row, never one default for the whole upload (that put a +1 contact in Surat):
 *   a number from the phone's own country gets the phone's area; a number from another country
 *   gets that country in `state` and, when the number itself names one, its region in `city`
 *   (the API has no country column, so `state` carries the country for foreign numbers).
 */
object ContactRows {

    data class Row(
        val e164: String,
        val name: String,
        val city: String?,
        val state: String?,
        val pincode: String?,
        val region: String,
    )

    data class Prepared(
        val rows: List<Row>,
        val total: Int,
        val invalid: Int,
        val duplicates: Int,
        val deviceRegion: String,
    ) {
        /** "IN 590, US 8, AE 2": rows per number country, most first. */
        val byCountry: String
            get() = rows.groupingBy { it.region }.eachCount().entries
                .sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }
    }

    private val util = PhoneNumberUtil.getInstance()
    private val geocoder = PhoneNumberOfflineGeocoder.getInstance()

    fun prepare(context: Context, contacts: List<ContactNumber>, deviceArea: DeviceLocation.Area): Prepared =
        prepare(deviceRegion(context), contacts.map { it.name to it.number }, deviceArea)

    /** [prepare] for a known [region] and (name, number) pairs; no Android needed, so testable. */
    internal fun prepare(region: String, contacts: List<Pair<String, String>>, deviceArea: DeviceLocation.Area): Prepared {
        val seen = HashSet<String>()
        val rows = ArrayList<Row>(contacts.size)
        var invalid = 0
        var duplicates = 0

        for ((rawName, rawNumber) in contacts) {
            val name = rawName.trim()
            val raw = rawNumber.trim()
            if (name.isEmpty() || raw.isEmpty()) {
                invalid++
                continue
            }
            val number = parse(raw, region)
            if (number == null || !util.isValidNumber(number)) {
                invalid++
                continue
            }
            val e164 = util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
            if (!seen.add(e164)) {
                duplicates++
                continue
            }
            val numberRegion = util.getRegionCodeForNumber(number) ?: region
            rows += locate(number, numberRegion, region, deviceArea).let { (city, state, pincode) ->
                Row(e164, name, city, state, pincode, numberRegion)
            }
        }
        return Prepared(rows, contacts.size, invalid, duplicates, region)
    }

    /** (city, state, pincode) for one number. */
    private fun locate(
        number: PhoneNumber,
        numberRegion: String,
        deviceRegion: String,
        deviceArea: DeviceLocation.Area,
    ): Triple<String?, String?, String?> {
        val countryName = countryName(numberRegion)
        val numberArea = numberArea(number, countryName)
        return when {
            // Home country, and the phone's own area is known: the uploader's area.
            numberRegion == deviceRegion && !deviceArea.isEmpty ->
                Triple(deviceArea.city, deviceArea.state, deviceArea.pincode)
            // Home country, area unknown: the region the number belongs to (India: its circle).
            numberRegion == deviceRegion -> Triple(null, numberArea, null)
            // Another country: that country, plus the number's region when it has one.
            else -> Triple(numberArea, countryName, null)
        }
    }

    /** The region libphonenumber knows for [number] ("New York, NY", "Gujarat"), or null. */
    private fun numberArea(number: PhoneNumber, countryName: String?): String? =
        runCatching { geocoder.getDescriptionForNumber(number, Locale.ENGLISH) }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() && !it.equals(countryName, ignoreCase = true) }

    private fun countryName(region: String): String? =
        Locale("", region).getDisplayCountry(Locale.ENGLISH).takeIf { it.isNotBlank() && it != region }

    private fun parse(raw: String, region: String): PhoneNumber? =
        runCatching { util.parse(raw, region) }.getOrNull()

    /**
     * The phone's country, for reading numbers saved without a "+": the SIM, then the network it
     * is on, then the phone's region setting. Uppercase ISO ("IN").
     */
    fun deviceRegion(context: Context): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return listOf(
            runCatching { tm?.simCountryIso }.getOrNull(),
            runCatching { tm?.networkCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).firstOrNull { !it.isNullOrBlank() && it.length == 2 }?.uppercase(Locale.US) ?: "IN"
    }
}
