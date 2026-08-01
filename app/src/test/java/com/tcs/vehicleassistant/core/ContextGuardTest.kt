package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class ContextGuardTest {

    private val loudPlaying = CabinSnapshot(
        mediaVolumePct = 90,
        mediaPlaying = true,
        fanLevel = 3,
        cabinTempF = 72,
        seatHeaterLevel = 0,
        acOn = true,
        hvacPowerOn = true,
        defrostOn = false,
        speedMph = 0,
        gear = "Park",
        isParked = true,
        city = "Tokyo",
    )

    private val quietPlaying = loudPlaying.copy(mediaVolumePct = 40)

    private val maxFan = loudPlaying.copy(
        fanLevel = 7,
        fanMax = 7,
        mediaVolumePct = 20,
        mediaPlaying = false,
    )

    private val seatOn = loudPlaying.copy(
        mediaVolumePct = 20,
        mediaPlaying = false,
        seatHeaterLevel = 2,
    )

    private val highway = loudPlaying.copy(
        mediaVolumePct = 20,
        mediaPlaying = false,
        speedMph = 55,
        gear = "Drive",
        isParked = false,
    )

    @Before
    fun setUp() {
        ContextGuard.replaceRulesForTest(
            listOf(
                ContextGuard.PolicyRule(
                    id = "volume_already_loud",
                    appliesTo = listOf("setVolumeLevel"),
                    argMatches = listOf("up", "increase", "+", "louder", "max"),
                    sensors = listOf(
                        ContextGuard.SensorCondition("media_volume_pct", ">=", 85.0),
                    ),
                    requireMediaPlaying = true,
                    action = ContextGuard.Action.CONFIRM,
                    message = "It's already quite loud ({media_volume_pct}%). Increase anyway?",
                    priority = 10,
                ),
                ContextGuard.PolicyRule(
                    id = "volume_already_max",
                    appliesTo = listOf("setVolumeLevel"),
                    argMatches = listOf("up", "increase", "+", "louder", "max"),
                    sensors = listOf(
                        ContextGuard.SensorCondition("media_volume_pct", ">=", 100.0),
                    ),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.BLOCK,
                    message = "Volume is already at maximum ({media_volume_pct}%).",
                    priority = 5,
                ),
                ContextGuard.PolicyRule(
                    id = "fan_already_max",
                    appliesTo = listOf("increaseFanSpeed"),
                    argMatches = emptyList(),
                    sensors = listOf(
                        ContextGuard.SensorCondition(
                            source = "fan_level",
                            op = ">=",
                            value = null,
                            compareTo = "fan_max",
                        ),
                    ),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.BLOCK,
                    message = "The fan is already at maximum (level {fan_level}).",
                    priority = 10,
                ),
                ContextGuard.PolicyRule(
                    id = "seat_heater_already_on",
                    appliesTo = listOf("setSeatHeater"),
                    argMatches = listOf("1", "2", "3", "on"),
                    sensors = listOf(
                        ContextGuard.SensorCondition("seat_heater_level", ">=", 2.0),
                    ),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.BLOCK,
                    message = "The seat heater is already on (level {seat_heater_level}).",
                    priority = 10,
                ),
                ContextGuard.PolicyRule(
                    id = "open_windows_while_moving",
                    appliesTo = listOf("openWindowsSlightly", "openWindows"),
                    argMatches = emptyList(),
                    sensors = listOf(
                        ContextGuard.SensorCondition("speed_mph", ">=", 40.0),
                    ),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.CONFIRM,
                    message = "You're going about {speed_mph} mph — open the windows anyway?",
                    priority = 10,
                ),
                ContextGuard.PolicyRule(
                    id = "windows_already_open",
                    appliesTo = listOf("openWindowsSlightly"),
                    argMatches = emptyList(),
                    sensors = listOf(
                        ContextGuard.SensorCondition("window_open_pct", ">=", 20.0),
                    ),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.BLOCK,
                    message = "Windows are already open ({window_open_pct}%).",
                    priority = 20,
                ),
                ContextGuard.PolicyRule(
                    id = "auto_climate_already_on",
                    appliesTo = listOf("turnOnAutoClimate"),
                    argMatches = emptyList(),
                    sensors = listOf(
                        ContextGuard.SensorCondition("hvac_auto_on", "==", 1.0),
                    ),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.BLOCK,
                    message = "Auto climate is already on.",
                    priority = 15,
                ),
                ContextGuard.PolicyRule(
                    id = "unlock_doors_while_moving",
                    appliesTo = listOf("unlockDoors"),
                    argMatches = emptyList(),
                    sensors = listOf(
                        ContextGuard.SensorCondition("is_parked", "==", 0.0),
                        ContextGuard.SensorCondition("speed_mph", ">=", 5.0),
                    ),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.CONFIRM,
                    message = "You're still moving ({speed_mph} mph). Unlock the doors anyway?",
                    priority = 8,
                ),
                ContextGuard.PolicyRule(
                    id = "open_trunk_while_moving",
                    appliesTo = listOf("openTrunk"),
                    argMatches = emptyList(),
                    sensors = listOf(
                        ContextGuard.SensorCondition("speed_mph", ">=", 5.0),
                    ),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.BLOCK,
                    message = "I won't open the trunk while you're moving ({speed_mph} mph).",
                    priority = 5,
                ),
                ContextGuard.PolicyRule(
                    id = "low_fuel_navigation",
                    appliesTo = listOf("startNavigationTo"),
                    argMatches = emptyList(),
                    sensors = listOf(
                        ContextGuard.SensorCondition("fuel_level_pct", "<=", 15.0),
                    ),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.CONFIRM,
                    message = "Fuel is low ({fuel_level_pct}%) near {city}. Start navigation anyway?",
                    priority = 12,
                ),
                ContextGuard.PolicyRule(
                    id = "already_navigating_same_dest",
                    appliesTo = listOf("startNavigationTo"),
                    argMatches = emptyList(),
                    sensors = emptyList(),
                    requireMediaPlaying = null,
                    requireNavActive = true,
                    requireNavDestMatchesArg = true,
                    action = ContextGuard.Action.CONFIRM,
                    message = "You're already navigating to {nav_active_dest}. Restart that route?",
                    priority = 9,
                ),
                ContextGuard.PolicyRule(
                    id = "reroute_while_navigating",
                    appliesTo = listOf("startNavigationTo"),
                    argMatches = emptyList(),
                    sensors = emptyList(),
                    requireMediaPlaying = null,
                    requireNavActive = true,
                    requireNavDestDiffersArg = true,
                    action = ContextGuard.Action.CONFIRM,
                    message = "You're headed to {nav_active_dest}. Switch destination?",
                    priority = 11,
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        ContextGuard.clearRulesForTest()
    }

    @Test
    fun volumeUpWhenLoudAndPlaying_confirms() {
        val d = ContextGuard.evaluate("setVolumeLevel(up)", loudPlaying)
        assertTrue(d is ContextGuard.Decision.Confirm)
        val c = d as ContextGuard.Decision.Confirm
        assertEquals("volume_already_loud", c.policyId)
        assertTrue(c.message.contains("90"))
        assertTrue(c.message.contains("Increase anyway"))
        assertEquals("setVolumeLevel(up)", c.originalToolCall)
    }

    @Test
    fun volumeUpWhenQuiet_allows() {
        assertTrue(ContextGuard.evaluate("setVolumeLevel(up)", quietPlaying) is ContextGuard.Decision.Allow)
    }

    @Test
    fun volumeUpAtMax_blocksBeforeLoudConfirm() {
        val max = loudPlaying.copy(mediaVolumePct = 100)
        val d = ContextGuard.evaluate("setVolumeLevel(up)", max)
        assertTrue("expected Block (priority 5), got $d", d is ContextGuard.Decision.Block)
        assertEquals("volume_already_max", (d as ContextGuard.Decision.Block).policyId)
    }

    @Test
    fun volumeDownWhenLoud_allows() {
        assertTrue(ContextGuard.evaluate("setVolumeLevel(down)", loudPlaying) is ContextGuard.Decision.Allow)
    }

    @Test
    fun increaseFanAtMax_blocks() {
        val d = ContextGuard.evaluate("increaseFanSpeed()", maxFan)
        assertTrue(d is ContextGuard.Decision.Block)
        assertTrue((d as ContextGuard.Decision.Block).message.contains("7"))
    }

    @Test
    fun setSeatHeaterWhenAlreadyOn_blocks() {
        val d = ContextGuard.evaluate("setSeatHeater(2)", seatOn)
        assertTrue(d is ContextGuard.Decision.Block)
    }

    @Test
    fun openWindowsOnHighway_confirms() {
        val d = ContextGuard.evaluate("openWindowsSlightly()", highway)
        assertTrue(d is ContextGuard.Decision.Confirm)
        assertTrue((d as ContextGuard.Decision.Confirm).message.contains("55"))
    }

    @Test
    fun windowsAlreadyOpen_blocks() {
        val snap = loudPlaying.copy(windowOpenPct = 40, speedMph = 0)
        val d = ContextGuard.evaluate("openWindowsSlightly()", snap)
        assertTrue(d is ContextGuard.Decision.Block)
        assertEquals("windows_already_open", (d as ContextGuard.Decision.Block).policyId)
    }

    @Test
    fun autoClimateAlreadyOn_blocks() {
        val snap = loudPlaying.copy(hvacAutoOn = true)
        val d = ContextGuard.evaluate("turnOnAutoClimate()", snap)
        assertTrue(d is ContextGuard.Decision.Block)
    }

    @Test
    fun unlockWhileMoving_confirms() {
        val d = ContextGuard.evaluate("unlockDoors()", highway.copy(speedMph = 25))
        assertTrue(d is ContextGuard.Decision.Confirm)
        assertEquals("unlock_doors_while_moving", (d as ContextGuard.Decision.Confirm).policyId)
    }

    @Test
    fun openTrunkWhileMoving_blocks() {
        val d = ContextGuard.evaluate("openTrunk()", highway.copy(speedMph = 20))
        assertTrue(d is ContextGuard.Decision.Block)
        assertEquals("open_trunk_while_moving", (d as ContextGuard.Decision.Block).policyId)
    }

    @Test
    fun lowFuelNavigation_confirmsWithCity() {
        val snap = loudPlaying.copy(fuelLevelPct = 10, city = "Yokohama")
        val d = ContextGuard.evaluate("startNavigationTo(\"gas station\")", snap)
        assertTrue(d is ContextGuard.Decision.Confirm)
        val c = d as ContextGuard.Decision.Confirm
        assertEquals("low_fuel_navigation", c.policyId)
        assertTrue(c.message.contains("10"))
        assertTrue(c.message.contains("Yokohama"))
    }

    @Test
    fun sameNavDest_confirmsRestart() {
        val snap = loudPlaying.copy(navActiveDest = "Tokyo Tower")
        val d = ContextGuard.evaluate("startNavigationTo(\"tokyo tower\")", snap)
        assertTrue(d is ContextGuard.Decision.Confirm)
        assertEquals("already_navigating_same_dest", (d as ContextGuard.Decision.Confirm).policyId)
    }

    @Test
    fun differentNavDest_confirmsReroute() {
        val snap = loudPlaying.copy(navActiveDest = "Tokyo Tower", fuelLevelPct = 80)
        val d = ContextGuard.evaluate("startNavigationTo(\"Tokyo Skytree\")", snap)
        assertTrue(d is ContextGuard.Decision.Confirm)
        assertEquals("reroute_while_navigating", (d as ContextGuard.Decision.Confirm).policyId)
    }

    @Test
    fun unrelatedTool_allows() {
        assertTrue(ContextGuard.evaluate("increaseTemperature()", loudPlaying) is ContextGuard.Decision.Allow)
    }

    @Test
    fun unlockWhenParkedKnownGear_allows() {
        val parked = loudPlaying.copy(speedMph = 0, gear = "Park", isParked = true)
        assertTrue(ContextGuard.evaluate("unlockDoors()", parked) is ContextGuard.Decision.Allow)
    }

    @Test
    fun unlockWithUnknownGear_failClosedConfirm() {
        val unknown = loudPlaying.copy(speedMph = 0, gear = "Unknown", isParked = false)
        val d = ContextGuard.evaluate("unlockDoors()", unknown)
        assertTrue("expected Confirm for unknown gear, got $d", d is ContextGuard.Decision.Confirm)
        val c = d as ContextGuard.Decision.Confirm
        assertEquals(SafetyCriticalTools.GEAR_UNKNOWN_POLICY_ID, c.policyId)
        assertTrue(c.message.contains("parked", ignoreCase = true))
    }

    @Test
    fun openTrunkWithUnknownGear_failClosedConfirm() {
        val unknown = loudPlaying.copy(speedMph = 0, gear = "Unknown", isParked = false)
        val d = ContextGuard.evaluate("openTrunk()", unknown)
        assertTrue(d is ContextGuard.Decision.Confirm)
        assertEquals(
            SafetyCriticalTools.GEAR_UNKNOWN_POLICY_ID,
            (d as ContextGuard.Decision.Confirm).policyId,
        )
    }

    @Test
    fun nonSafetyToolWithUnknownGear_stillAllows() {
        val unknown = loudPlaying.copy(gear = "Unknown", isParked = false, mediaVolumePct = 20, mediaPlaying = false)
        assertTrue(ContextGuard.evaluate("increaseTemperature()", unknown) is ContextGuard.Decision.Allow)
    }

    @Test
    fun snapshotInterpolatesGeoAndFuel() {
        val snap = loudPlaying.copy(
            fuelLevelPct = 12,
            city = "Osaka",
            latitude = 34.69,
            longitude = 135.50,
            navActiveDest = "Home",
        )
        val msg = snap.interpolate("Near {city} fuel {fuel_level_pct}% nav {nav_active_dest}")
        assertEquals("Near Osaka fuel 12% nav Home", msg)
        assertEquals(1.0, snap.sensor("is_parked"))
        assertEquals(12.0, snap.sensor("fuel_level_pct"))
        assertEquals(1.0, snap.sensor("nav_active"))
    }
}
