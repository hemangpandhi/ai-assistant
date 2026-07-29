package com.tcs.vehicleassistant.handlers.hvac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HvacToolAliasesTest {
    @Test
    fun canonicalize_mapsRefactorAliases() {
        assertEquals("turnOnAC", HvacToolAliases.canonicalize("turnOnAc"))
        assertEquals("turnOffAC", HvacToolAliases.canonicalize("turnOffAc"))
        assertEquals("turnOnAutoClimate", HvacToolAliases.canonicalize("turnOnAutoHvac"))
        assertEquals("turnOnRecirculation", HvacToolAliases.canonicalize("turnOnAirRecirculation"))
    }

    @Test
    fun canonicalize_passthroughCanonical() {
        assertEquals("turnOnAC", HvacToolAliases.canonicalize("turnOnAC"))
    }

    @Test
    fun expand_includesAliases() {
        val expanded = HvacToolAliases.expand(setOf("turnOnAC"))
        assertTrue(expanded.contains("turnOnAc"))
        assertTrue(expanded.contains("turnOnAC"))
    }
}
