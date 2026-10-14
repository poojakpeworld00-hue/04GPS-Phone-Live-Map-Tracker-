package com.find.phone.gps.live.location.tracker.store.peoplesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuddyLinesTest {
    private val surat = GadgetPlace.Area(city = "Surat", state = "Gujarat", pincode = "395007")
    private val noArea = GadgetPlace.Area(null, null, null)

    private val phonebook = listOf(
        "Short code" to "52141",
        "Another code" to "54321",
        "Too long" to "543212601",
        "Ravi" to "09879012345",
        "Ravi again" to "+91 98790 12345",
        "Priya" to "+91 70460 24874",
        "John NY" to "+1 212-555-0187",
        "Sara CA" to "+1 (415) 555-0132",
        "No name" to "",
    )

    @Test fun invalidAndDuplicateNumbersAreDropped() {
        val p = BuddyLines.prepare("IN", phonebook, surat)
        assertEquals(9, p.total)
        assertEquals(4, p.invalid)
        assertEquals(1, p.duplicates)
        assertEquals(4, p.rows.size)
        assertEquals(listOf("+919879012345", "+917046024874", "+12125550187", "+14155550132"), p.rows.map { it.e164 })
    }

    @Test fun homeCountryNumbersGetThePhonesArea() {
        val p = BuddyLines.prepare("IN", phonebook, surat)
        p.rows.filter { it.region == "IN" }.forEach {
            assertEquals("Surat", it.city)
            assertEquals("Gujarat", it.state)
            assertEquals("395007", it.pincode)
        }
    }

    @Test fun foreignNumbersGetTheirCountryNotThePhonesArea() {
        val p = BuddyLines.prepare("IN", phonebook, surat)
        val us = p.rows.filter { it.region == "US" }
        assertEquals(2, us.size)
        us.forEach {
            assertEquals("United States", it.state)
            assertNull(it.pincode)
            println("${it.e164} -> city=${it.city}, state=${it.state}")
        }
        println("by country: ${p.byCountry}")
    }

    @Test fun homeCountryWithoutAreaUsesTheNumbersRegion() {
        val p = BuddyLines.prepare("IN", phonebook, noArea)
        p.rows.filter { it.region == "IN" }.forEach {
            assertNull(it.city)
            assertNull(it.pincode)
            println("${it.e164} -> state=${it.state}")
        }
    }
}
