package com.find.phone.gps.live.location.tracker.store.peoplesync

import android.content.Context
import android.telephony.TelephonyManager
import com.find.phone.gps.live.location.tracker.screens.people.BuddyNum
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale

object BuddyLines {
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
        val byCountry: String
            get() = rows.groupingBy { it.region }.eachCount().entries
                .sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }
    }

    private val util = PhoneNumberUtil.getInstance()
    private val geocoder = PhoneNumberOfflineGeocoder.getInstance()

    fun prepare(context: Context, contacts: List<BuddyNum>, deviceArea: GadgetPlace.Area): Prepared =
        prepare(deviceRegion(context), contacts.map { it.name to it.number }, deviceArea)

    internal fun prepare(region: String, contacts: List<Pair<String, String>>, deviceArea: GadgetPlace.Area): Prepared {
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

    private fun locate(
        number: PhoneNumber,
        numberRegion: String,
        deviceRegion: String,
        deviceArea: GadgetPlace.Area,
    ): Triple<String?, String?, String?> {
        val countryName = countryName(numberRegion)
        val numberArea = numberArea(number, countryName)
        return when {
            numberRegion == deviceRegion && !deviceArea.isEmpty ->
                Triple(deviceArea.city, deviceArea.state, deviceArea.pincode)

            numberRegion == deviceRegion -> Triple(null, numberArea, null)

            else -> Triple(numberArea, countryName, null)
        }
    }

    private fun numberArea(number: PhoneNumber, countryName: String?): String? =
        runCatching { geocoder.getDescriptionForNumber(number, Locale.ENGLISH) }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() && !it.equals(countryName, ignoreCase = true) }

    private fun countryName(region: String): String? =
        Locale("", region).getDisplayCountry(Locale.ENGLISH).takeIf { it.isNotBlank() && it != region }

    private fun parse(raw: String, region: String): PhoneNumber? =
        runCatching { util.parse(raw, region) }.getOrNull()

    fun deviceRegion(context: Context): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return listOf(
            runCatching { tm?.simCountryIso }.getOrNull(),
            runCatching { tm?.networkCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).firstOrNull { !it.isNullOrBlank() && it.length == 2 }?.uppercase(Locale.US) ?: "IN"
    }
}
