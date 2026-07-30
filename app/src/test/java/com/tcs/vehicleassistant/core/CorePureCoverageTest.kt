package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavSessionStateTest {
    @Test
    fun setActive_andClear() {
        NavSessionState.clear()
        assertNull(NavSessionState.activeDest)
        NavSessionState.setActive("  Tokyo Tower  ")
        assertEquals("Tokyo Tower", NavSessionState.activeDest)
        NavSessionState.setActive("\"Skytree\"")
        assertEquals("Skytree", NavSessionState.activeDest)
        NavSessionState.setActive("   ")
        assertNull(NavSessionState.activeDest)
        NavSessionState.setActive(null)
        assertNull(NavSessionState.activeDest)
        NavSessionState.clear()
    }
}

class CabinSnapshotPureTest {
    private fun snap(
        gear: String = "Park",
        fuel: Int = 50,
        window: Int = 0,
        nav: String? = null,
    ) = CabinSnapshot(
        mediaVolumePct = 40,
        mediaPlaying = true,
        fanLevel = 3,
        cabinTempF = 72,
        seatHeaterLevel = 1,
        acOn = true,
        hvacPowerOn = true,
        hvacAutoOn = true,
        defrostOn = false,
        speedMph = 0,
        gear = gear,
        fuelLevelPct = fuel,
        windowOpenPct = window,
        navActiveDest = nav,
        city = "Tokyo",
        latitude = 35.6,
        longitude = 139.7,
    )

    @Test
    fun gearLooksParked() {
        assertTrue(CabinSnapshot.gearLooksParked("Park"))
        assertTrue(CabinSnapshot.gearLooksParked("P"))
        assertTrue(CabinSnapshot.gearLooksParked("PARKING"))
        assertFalse(CabinSnapshot.gearLooksParked("Drive"))
        assertFalse(CabinSnapshot.gearLooksParked("R"))
    }

    @Test
    fun sensorMap_andInterpolate() {
        val s = snap(nav = "Tokyo Tower")
        assertEquals(40.0, s.sensor("media_volume_pct"))
        assertEquals(1.0, s.sensor("media_playing"))
        assertEquals(1.0, s.sensor("is_parked"))
        assertEquals(1.0, s.sensor("nav_active"))
        assertEquals(50.0, s.sensor("fuel"))
        assertEquals(35.6, s.sensor("lat"))
        assertNull(s.sensor("unknown_sensor"))
        assertTrue(s.navActive)
        val msg = s.interpolate("Vol {media_volume_pct} in {city} to {nav_active_dest}")
        assertEquals("Vol 40 in Tokyo to Tokyo Tower", msg)
    }

    @Test
    fun unknownFuelAndWindow_returnNullSensors() {
        val s = snap(fuel = -1, window = -1, nav = null)
        assertNull(s.sensor("fuel_level_pct"))
        assertNull(s.sensor("window_open_pct"))
        assertFalse(s.navActive)
        assertEquals("unknown", s.interpolate("{fuel_level_pct}"))
    }
}

class DebugBroadcastsTest {
    @Test
    fun actionConstantsAreStable() {
        assertEquals("com.tcs.vehicleassistant.DIAGNOSTICS_DUMP", DebugBroadcasts.ACTION_DIAGNOSTICS_DUMP)
        assertEquals("com.tcs.vehicleassistant.TEST_QUERY", DebugBroadcasts.ACTION_TEST_QUERY)
        assertEquals("com.tcs.vehicleassistant.SIDELOAD_MODEL", DebugBroadcasts.ACTION_SIDELOAD_MODEL)
        // Unit tests run on debug classpath — registration gate should be enabled.
        assertTrue(DebugBroadcasts.isEnabled)
    }
}

class AssistantConfigCoreTest {
    @Test
    fun criticalBudgetsArePositive() {
        assertTrue(AssistantConfig.Session.END_TO_END_BUDGET_MS > 0)
        assertTrue(AssistantConfig.Llm.TOOL_TIMEOUT_MS > 0)
        assertTrue(AssistantConfig.Memory.MAX_RETAINED_TURNS > 0)
        assertTrue(AssistantConfig.LARGE_SCREEN_MIN_WIDTH_DP > 0)
        assertTrue(AssistantConfig.PREFS_NAME.isNotBlank())
    }
}

class DeviceCapabilitiesPureTest {
    @Test
    fun cpuCoreCount_positive() {
        assertTrue(DeviceCapabilities.cpuCoreCount() >= 1)
    }

    @Test
    fun backendFallbackChain_delegates() {
        val chain = DeviceCapabilities.backendFallbackChain("CPU")
        assertTrue(chain.isNotEmpty())
        assertTrue(chain.any { it.contains("CPU", ignoreCase = true) || it.equals("CPU", ignoreCase = true) })
    }
}
