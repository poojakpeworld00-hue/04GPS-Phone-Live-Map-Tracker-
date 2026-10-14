package com.find.my.phone.gps.location.tracker.data.lookup

import androidx.annotation.Keep
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import com.google.i18n.phonenumbers.PhoneNumberToTimeZonesMapper
import java.util.Locale

/** What libphonenumber knows about a number without going online. */
data class NumberFacts(
    val e164: String,
    val international: String,
    val iso2: String,
    val valid: Boolean,
    val lineType: LineType,
    val carrier: String?,
    /** Offline geocoder area (state / telecom circle); null when it only knows the country. */
    val area: String?,
    val timeZone: String?,
)

@Keep
enum class LineType { MOBILE, FIXED_LINE, FIXED_OR_MOBILE, TOLL_FREE, PREMIUM, VOIP, OTHER }

object NumberFactsReader {

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /** Parses national [digits] for [iso2]; null when libphonenumber can't parse them at all. */
    fun parse(digits: String, iso2: String): PhoneNumber? = try {
        util.parse(digits, iso2.uppercase(Locale.ROOT))
    } catch (_: NumberParseException) {
        null
    }

    /**
     * Splits a number as stored in the phone book ("+91 70460 24874", "070460 24874") into its
     * region and national digits; [fallbackIso] is the region for numbers without a "+".
     * Unparseable input comes back as its bare digits under [fallbackIso].
     */
    fun split(raw: String, fallbackIso: String): Pair<String, String> {
        val parsed = parse(raw, fallbackIso)
            ?: return fallbackIso to raw.filter(Char::isDigit)
        val region = util.getRegionCodeForNumber(parsed)?.takeIf { it != "ZZ" } ?: fallbackIso
        return region to util.getNationalSignificantNumber(parsed)
    }

    /** True when the number is a real, dialable number for its region. */
    fun isValid(digits: String, iso2: String): Boolean = parse(digits, iso2)?.let(util::isValidNumber) == true

    /** E.164 for the search, or the naive "+dial digits" when parsing fails. */
    fun e164(digits: String, iso2: String, dial: String): String =
        parse(digits, iso2)?.let { util.format(it, PhoneNumberFormat.E164) } ?: "+$dial$digits"

    fun read(digits: String, iso2: String, dial: String): NumberFacts {
        val parsed = parse(digits, iso2)
            ?: return NumberFacts("+$dial$digits", "+$dial $digits", iso2.uppercase(Locale.ROOT), false, LineType.OTHER, null, null, null)

        val region = util.getRegionCodeForNumber(parsed) ?: iso2.uppercase(Locale.ROOT)
        val countryName = Locale("", region).getDisplayCountry(Locale.ENGLISH)
        val area = PhoneNumberOfflineGeocoder.getInstance()
            .getDescriptionForNumber(parsed, Locale.ENGLISH).trim()
            .takeIf { it.isNotEmpty() && !it.equals(countryName, ignoreCase = true) }
        val carrier = PhoneNumberToCarrierMapper.getInstance()
            .getNameForNumber(parsed, Locale.ENGLISH).trim().takeIf { it.isNotEmpty() }
        val zone = PhoneNumberToTimeZonesMapper.getInstance().getTimeZonesForNumber(parsed)
            .firstOrNull { it != "Etc/Unknown" }

        return NumberFacts(
            e164 = util.format(parsed, PhoneNumberFormat.E164),
            international = util.format(parsed, PhoneNumberFormat.INTERNATIONAL),
            iso2 = region,
            valid = util.isValidNumber(parsed),
            lineType = when (util.getNumberType(parsed)) {
                PhoneNumberType.MOBILE -> LineType.MOBILE
                PhoneNumberType.FIXED_LINE -> LineType.FIXED_LINE
                PhoneNumberType.FIXED_LINE_OR_MOBILE -> LineType.FIXED_OR_MOBILE
                PhoneNumberType.TOLL_FREE -> LineType.TOLL_FREE
                PhoneNumberType.PREMIUM_RATE -> LineType.PREMIUM
                PhoneNumberType.VOIP -> LineType.VOIP
                else -> LineType.OTHER
            },
            carrier = carrier,
            area = area,
            timeZone = zone,
        )
    }
}
