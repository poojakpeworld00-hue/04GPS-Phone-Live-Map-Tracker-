package com.find.phone.gps.live.location.tracker.store.inquiry

import androidx.annotation.Keep
import java.util.Locale

@Keep
data class MobileAnswer(
    val success: Boolean? = null,
    val count: Int? = null,
    val country: String? = null,
    val spam: Boolean? = null,
    val location: MobilePlace? = null,
    val data: List<MobilePayload?>? = null,
) {
    val ok: Boolean get() = success ?: false

    val names: List<String>
        get() {
            val seen = HashSet<String>()
            val out = ArrayList<String>()
            for (item in data ?: emptyList()) {
                val n = item?.name?.trim()
                if (n.isNullOrEmpty()) continue
                if (seen.add(n.lowercase(Locale.ROOT))) out.add(n)
            }
            return out
        }

    val area: MobilePlace?
        get() {
            if (location != null && location.hasAny) return location
            return data?.asSequence()?.mapNotNull { it?.location }?.firstOrNull { it.hasAny }
        }

    val countryCode: String?
        get() = country.cleaned() ?: data?.firstNotNullOfOrNull { it?.country.cleaned() }

    val carrier: String?
        get() = data?.firstNotNullOfOrNull { it?.carrier.cleaned() }

    val isSpam: Boolean
        get() = when {
            spam == true -> true
            else -> data?.any { p -> p != null && (p.is_spam == true || p.is_user_spam == true) } ?: false
        }

    private fun String?.cleaned(): String? = this?.trim()?.ifEmpty { null }
}

@Keep
data class MobilePayload(
    val is_spam: Boolean? = null,
    val is_user_spam: Boolean? = null,
    val name: String? = null,
    val country: String? = null,
    val carrier: String? = null,
    val location: MobilePlace? = null,
)

@Keep
data class MobilePlace(
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
) {
    val hasAny: Boolean get() = listOf(city, state, pincode).any { !it.isNullOrBlank() }
}
