package com.find.my.phone.gps.location.tracker.ui.sim

import java.util.Locale

/**
 * USSD / short codes for the SIM Tools "Carrier codes" screen (design 1c), grouped by section and
 * picked from the detected carrier name. Codes are set by the carrier and can change, so the screen
 * shows a disclaimer and only opens the dialer (ACTION_DIAL) — it never dials automatically.
 */
object CarrierCodes {

    data class Code(val title: String, val subtitle: String, val code: String)
    data class Section(val title: String, val codes: List<Code>)

    fun forCarrier(carrier: String?): List<Section> {
        val c = carrier?.lowercase(Locale.ROOT).orEmpty()
        return when {
            c.contains("jio") -> JIO
            c.contains("airtel") -> AIRTEL
            c.contains("vodafone") || c.contains("idea") || c.contains("vi india") || c == "vi" -> VI
            c.contains("bsnl") -> BSNL
            else -> GENERIC
        }
    }

    private val JIO = listOf(
        Section("Account", listOf(
            Code("Main balance", "Talk-time and validity", "*333#"),
            Code("Data balance", "Remaining daily data", "*333*1#"),
            Code("My number", "Show your SIM number", "*1#"),
        )),
        Section("Help", listOf(
            Code("Customer care", "Talk to support", "198"),
            Code("Current plan", "Active pack details", "*333*3#"),
        )),
    )

    private val AIRTEL = listOf(
        Section("Account", listOf(
            Code("Main balance", "Talk-time and validity", "*121#"),
            Code("Data balance", "Remaining data", "*121*51#"),
            Code("My number", "Show your SIM number", "*121*9#"),
        )),
        Section("Help", listOf(
            Code("Customer care", "Talk to support", "198"),
            Code("Current plan", "Active pack details", "*121#"),
        )),
    )

    private val VI = listOf(
        Section("Account", listOf(
            Code("Main balance", "Talk-time and validity", "*199#"),
            Code("Data balance", "Remaining data", "*199*2#"),
            Code("My number", "Show your SIM number", "*199#"),
        )),
        Section("Help", listOf(
            Code("Customer care", "Talk to support", "199"),
            Code("Current plan", "Active pack details", "*199#"),
        )),
    )

    private val BSNL = listOf(
        Section("Account", listOf(
            Code("Main balance", "Talk-time and validity", "*123#"),
            Code("Data balance", "Remaining data", "*123*6#"),
            Code("My number", "Show your SIM number", "*222#"),
        )),
        Section("Help", listOf(
            Code("Customer care", "Talk to support", "1503"),
            Code("Current plan", "Active pack details", "*123#"),
        )),
    )

    private val GENERIC = listOf(
        Section("Account", listOf(
            Code("My number", "Show your SIM number", "*1#"),
        )),
        Section("Help", listOf(
            Code("Customer care", "Talk to support", "198"),
        )),
    )
}
