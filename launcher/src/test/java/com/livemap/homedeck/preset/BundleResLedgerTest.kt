package com.livemap.homedeck.preset

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class BundleResLedgerTest {
    private val apks = System.getProperty("launcher.apks", "").split(';').filter { it.isNotBlank() && File(it).exists() }

    @Test
    fun indexesIntegerDimenBoolXmlNamesOfRealLaunchers() {
        assumeTrue(apks.isNotEmpty())
        for (apk in apks) {
            val index = BundleResLedger.read(apk)
            assertTrue("no index for $apk", index != null && !index.isEmpty)
            val integers = index!!.names("integer")
            val xmls = index.names("xml")
            println("$apk: ${integers.size} integers, ${index.names("dimen").size} dimens, ${index.names("bool").size} bools, ${xmls.size} xmls")
            assertTrue(integers.size > 20)
            assertTrue(xmls.any { it.contains("workspace") })
            assertTrue(integers.any { it.contains("cell_count_x") } || integers.any { it.contains("default_columns") })
        }
    }
}
