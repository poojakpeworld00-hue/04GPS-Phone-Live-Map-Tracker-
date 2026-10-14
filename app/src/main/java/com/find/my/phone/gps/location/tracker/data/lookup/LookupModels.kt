package com.find.my.phone.gps.location.tracker.data.lookup

import androidx.annotation.Keep
import java.util.Locale

/**
 * contact-saver `similar-phone-number` response:
 *
 * ```
 * { "success": true, "count": 8, "country": "IN", "spam": false,
 *   "location": { "city": "Junagadh", "state": "Gujarat", "pincode": "362720" },
 *   "data": [ { "name": "…" }, … ] }
 * ```
 *
 * Older servers put `country` / `carrier` / `location` / spam flags on each `data` item, so
 * those are read as a fallback. Everything is nullable: Gson ignores Kotlin defaults.
 */
@Keep
data class PhoneResponse(
    val success: Boolean? = null,
    val count: Int? = null,
    val country: String? = null,
    val spam: Boolean? = null,
    val location: PhoneLocation? = null,
    val data: List<PhoneData?>? = null,
) {
    val ok: Boolean get() = success == true

    /** Non-blank names in the server's order (first = best match), without repeats. */
    val names: List<String>
        get() = data.orEmpty().mapNotNull { it?.name?.trim()?.takeIf(String::isNotEmpty) }
            .distinctBy { it.lowercase(Locale.ROOT) }

    /** Registered area: top-level `location`, else the first item that has one. */
    val area: PhoneLocation?
        get() = location?.takeIf { it.hasAny }
            ?: data.orEmpty().firstNotNullOfOrNull { d -> d?.location?.takeIf { it.hasAny } }

    /** ISO code ("IN") or a legacy country name. */
    val countryCode: String?
        get() = country?.trim()?.takeIf { it.isNotEmpty() }
            ?: data.orEmpty().firstNotNullOfOrNull { it?.country?.trim()?.takeIf(String::isNotEmpty) }

    val carrier: String?
        get() = data.orEmpty().firstNotNullOfOrNull { it?.carrier?.trim()?.takeIf(String::isNotEmpty) }

    val isSpam: Boolean
        get() = spam == true || data.orEmpty().any { it?.is_spam == true || it?.is_user_spam == true }
}

@Keep
data class PhoneData(
    val is_spam: Boolean? = null,
    val is_user_spam: Boolean? = null,
    val name: String? = null,
    val country: String? = null,
    val carrier: String? = null,
    val location: PhoneLocation? = null,
)

/** Where the number is registered: an area, not a live position. */
@Keep
data class PhoneLocation(
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
) {
    val hasAny: Boolean get() = !city.isNullOrBlank() || !state.isNullOrBlank() || !pincode.isNullOrBlank()
}
