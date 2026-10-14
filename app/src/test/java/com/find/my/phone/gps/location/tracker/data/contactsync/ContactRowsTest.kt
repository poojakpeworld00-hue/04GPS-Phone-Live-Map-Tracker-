package com.find.my.phone.gps.location.tracker.data.contactsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactRowsTest {

    private val surat = DeviceLocation.Area(city = "Surat", state = "Gujarat", pincode = "395007")
    private val noArea = DeviceLocation.Area(null, null, null)

    private val phonebook = listOf(
        "Short code" to "52141",          // from the real upload log: invalid
        "Another code" to "54321",        // invalid
        "Too long" to "543212601",        // invalid
        "Ravi" to "09879012345",          // Indian mobile, saved with a leading 0
        "Ravi again" to "+91 98790 12345", // same number, other format: duplicate
        "Priya" to "+91 70460 24874",     // Indian mobile
        "John NY" to "+1 212-555-0187",    // US, New York area code
        "Sara CA" to "+1 (415) 555-0132",  // US, California area code
        "No name" to "",                  // blank number
    )

    @Test fun invalidAndDuplicateNumbersAreDropped() {
        val p = ContactRows.prepare("IN", phonebook, surat)
        assertEquals(9, p.total)
        assertEquals(4, p.invalid)   // 52141, 54321, 543212601, blank
        assertEquals(1, p.duplicates)
        assertEquals(4, p.rows.size)
        assertEquals(listOf("+919879012345", "+917046024874", "+12125550187", "+14155550132"), p.rows.map { it.e164 })
    }

    @Test fun homeCountryNumbersGetThePhonesArea() {
        val p = ContactRows.prepare("IN", phonebook, surat)
        p.rows.filter { it.region == "IN" }.forEach {
            assertEquals("Surat", it.city)
            assertEquals("Gujarat", it.state)
            assertEquals("395007", it.pincode)
        }
    }

    @Test fun foreignNumbersGetTheirCountryNotThePhonesArea() {
        val p = ContactRows.prepare("IN", phonebook, surat)
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
        val p = ContactRows.prepare("IN", phonebook, noArea)
        p.rows.filter { it.region == "IN" }.forEach {
            assertNull(it.city)
            assertNull(it.pincode)
            println("${it.e164} -> state=${it.state}")
        }
    }
}
