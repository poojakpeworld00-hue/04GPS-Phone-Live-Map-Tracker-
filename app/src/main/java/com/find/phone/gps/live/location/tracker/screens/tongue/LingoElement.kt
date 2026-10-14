package com.find.phone.gps.live.location.tracker.screens.tongue

import com.find.phone.gps.live.location.tracker.screens.finder.Realms

data class LingoElement(
    val tag: String,
    val code: String,
    val name: String,
    val nativeName: String,
    val flagIso: String
) {
    val flag: String get() = Realms.flag(flagIso)
}

object LiveLocales {
    val all: List<LingoElement> = listOf(
        LingoElement("en", "EN", "English", "English", "US"),
        LingoElement("hi", "HI", "Hindi", "हिन्दी", "IN"),
        LingoElement("es", "ES", "Spanish", "Español", "ES"),
        LingoElement("fr", "FR", "French", "Français", "FR"),
        LingoElement("pt", "PT", "Portuguese", "Português", "PT"),
        LingoElement("th", "TH", "Thai", "ไทย", "TH"),
        LingoElement("zh", "ZH", "Chinese", "中文", "CN"),
        LingoElement("ja", "JA", "Japanese", "日本語", "JP"),
        LingoElement("ru", "RU", "Russian", "Русский", "RU"),
        LingoElement("vi", "VI", "Vietnamese", "Tiếng Việt", "VN"),
        LingoElement("tr", "TR", "Turkish", "Türkçe", "TR")
    )

    private fun byTag(tag: String): LingoElement? = all.firstOrNull { it.tag == tag }

    private const val ENGLISH = "en"

    private val DEFAULT_SUGGESTED = listOf("hi")

    private val SUGGESTED_BY_COUNTRY: Map<String, List<String>> = mapOf(

        "US" to listOf("es", "fr"),
        "CA" to listOf("fr"),
        "MX" to listOf("es"),
        "BR" to listOf("pt"),
        "AR" to listOf("es"),
        "CO" to listOf("es"),
        "CL" to listOf("es"),
        "PE" to listOf("es"),

        "IN" to listOf("hi"),
        "PK" to listOf("hi"),
        "BD" to listOf("hi"),
        "NP" to listOf("hi"),
        "LK" to listOf("hi"),

        "GB" to listOf(),
        "IE" to listOf(),
        "ES" to listOf("es"),
        "FR" to listOf("fr"),
        "BE" to listOf("fr"),
        "CH" to listOf("fr"),
        "PT" to listOf("pt"),
        "RU" to listOf("ru"),
        "UA" to listOf("ru"),
        "TR" to listOf("tr"),

        "CN" to listOf("zh"),
        "HK" to listOf("zh"),
        "TW" to listOf("zh"),
        "SG" to listOf("zh"),
        "JP" to listOf("ja"),
        "TH" to listOf("th"),
        "VN" to listOf("vi"),
        "AU" to listOf(),
        "NZ" to listOf()
    )

    fun suggestedFor(iso2: String?): List<LingoElement> {
        val base = SUGGESTED_BY_COUNTRY[iso2?.uppercase()] ?: DEFAULT_SUGGESTED
        val tags = (base + ENGLISH).distinct()
        return tags.mapNotNull { byTag(it) }
    }
}
