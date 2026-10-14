package com.find.phone.gps.live.location.tracker.store.inquiry

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

data class NumNotes(
    val e164: String,
    val international: String,
    val iso2: String,
    val valid: Boolean,
    val lineType: RowGenre,
    val carrier: String?,

    val area: String?,
    val timeZone: String?,
)

@Keep
enum class RowGenre { MOBILE, FIXED_LINE, FIXED_OR_MOBILE, TOLL_FREE, PREMIUM, VOIP, OTHER }

object NumNotesScanner {
    private const val UNKNOWN_REGION = "ZZ"
    private const val UNKNOWN_ZONE = "Etc/Unknown"

    private val lib: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    private val genreByType: Map<PhoneNumberType, RowGenre> = mapOf(
        PhoneNumberType.MOBILE to RowGenre.MOBILE,
        PhoneNumberType.FIXED_LINE to RowGenre.FIXED_LINE,
        PhoneNumberType.FIXED_LINE_OR_MOBILE to RowGenre.FIXED_OR_MOBILE,
        PhoneNumberType.TOLL_FREE to RowGenre.TOLL_FREE,
        PhoneNumberType.PREMIUM_RATE to RowGenre.PREMIUM,
        PhoneNumberType.VOIP to RowGenre.VOIP,
    )

    fun parse(digits: String, iso2: String): PhoneNumber? =
        runCatchingParse { lib.parse(digits, iso2.uppercase(Locale.ROOT)) }

    private inline fun runCatchingParse(block: () -> PhoneNumber): PhoneNumber? =
        try {
            block()
        } catch (_: NumberParseException) {
            null
        }

    fun split(raw: String, fallbackIso: String): Pair<String, String> {
        val number = parse(raw, fallbackIso) ?: return Pair(fallbackIso, raw.filter { it.isDigit() })
        val regionCode = lib.getRegionCodeForNumber(number)
        val region = if (regionCode == null || regionCode == UNKNOWN_REGION) fallbackIso else regionCode
        return Pair(region, lib.getNationalSignificantNumber(number))
    }

    fun isValid(digits: String, iso2: String): Boolean {
        val number = parse(digits, iso2) ?: return false
        return lib.isValidNumber(number)
    }

    fun e164(digits: String, iso2: String, dial: String): String {
        val number = parse(digits, iso2) ?: return "+$dial$digits"
        return lib.format(number, PhoneNumberFormat.E164)
    }

    fun read(digits: String, iso2: String, dial: String): NumNotes {
        val upperIso = iso2.uppercase(Locale.ROOT)
        val number = parse(digits, iso2)
            ?: return NumNotes("+$dial$digits", "+$dial $digits", upperIso, false, RowGenre.OTHER, null, null, null)

        val region = lib.getRegionCodeForNumber(number) ?: upperIso
        return NumNotes(
            e164 = lib.format(number, PhoneNumberFormat.E164),
            international = lib.format(number, PhoneNumberFormat.INTERNATIONAL),
            iso2 = region,
            valid = lib.isValidNumber(number),
            lineType = genreByType[lib.getNumberType(number)] ?: RowGenre.OTHER,
            carrier = carrierOf(number),
            area = areaOf(number, region),
            timeZone = zoneOf(number),
        )
    }

    private fun areaOf(number: PhoneNumber, region: String): String? {
        val nationName = Locale("", region).getDisplayCountry(Locale.ENGLISH)
        val described = PhoneNumberOfflineGeocoder.getInstance().getDescriptionForNumber(number, Locale.ENGLISH).trim()
        return when {
            described.isEmpty() -> null
            described.equals(nationName, ignoreCase = true) -> null
            else -> described
        }
    }

    private fun carrierOf(number: PhoneNumber): String? =
        PhoneNumberToCarrierMapper.getInstance().getNameForNumber(number, Locale.ENGLISH).trim().ifEmpty { null }

    private fun zoneOf(number: PhoneNumber): String? =
        PhoneNumberToTimeZonesMapper.getInstance().getTimeZonesForNumber(number).firstOrNull { it != UNKNOWN_ZONE }
}
