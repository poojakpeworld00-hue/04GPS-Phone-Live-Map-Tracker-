package com.find.phone.gps.live.location.tracker.store.inquiry

import com.find.phone.gps.live.location.tracker.store.HuntRation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoneAnchorsTest {

    @Test
    fun indiaCityWinsOverState() {
        val (pin, precision) = ZoneAnchors.fallback("in", "Surat City", "Gujarat")!!
        assertEquals(SectorExactness.CITY, precision)
        assertEquals(21.1702, pin.lat, 1e-4)
    }

    @Test
    fun unknownCityFallsBackToState() {
        val (_, precision) = ZoneAnchors.fallback("IN", "Nowhere", "State of Gujarat")!!
        assertEquals(SectorExactness.STATE, precision)
    }

    @Test
    fun otherCountriesUseCountryCentroid() {
        assertEquals(SectorExactness.COUNTRY, ZoneAnchors.fallback("US", "Boston", null)!!.second)
        assertNull(ZoneAnchors.fallback("XX", null, null))
    }

    @Test
    fun cooldownFormat() {
        assertEquals("00:01", HuntRation.formatLeft(1))
        assertEquals("59:59", HuntRation.formatLeft(3_599_000))
        assertEquals("1:00:00", HuntRation.formatLeft(3_600_000))
    }
}
