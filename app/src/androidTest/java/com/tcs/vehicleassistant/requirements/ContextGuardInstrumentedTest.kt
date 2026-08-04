package com.tcs.vehicleassistant.requirements

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tcs.vehicleassistant.core.CabinSnapshot
import com.tcs.vehicleassistant.hardware.CabinSnapshotReader
import com.tcs.vehicleassistant.core.ContextGuard
import com.tcs.vehicleassistant.core.NavSessionState
import com.tcs.vehicleassistant.support.RegistryTestSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ContextGuard scenarios: registry policies + live snapshot shape on device.
 */
@RunWith(AndroidJUnit4::class)
class ContextGuardInstrumentedTest {
    private val tm = RegistryTestSupport.initializedToolManager()


    @Before
    fun setUp() {
        // Ensures config.context_policies are loaded from assets.
        RegistryTestSupport.initializedToolManager()
        NavSessionState.clear()
    }

    @Test
    fun registryLoadsContextPolicies() {
        assertTrue("context policies should be enabled after ToolManager init", tm.contextGuard.enabled)
        val loud = CabinSnapshot(
            mediaVolumePct = 92,
            mediaPlaying = true,
            fanLevel = 2,
            cabinTempF = 70,
            seatHeaterLevel = 0,
            acOn = true,
            hvacPowerOn = true,
            defrostOn = false,
            speedMph = 0,
            gear = "Park",
            isParked = true,
            city = "Tokyo",
        )
        val decision = tm.contextGuard.evaluate("setVolumeLevel(up)", loud)
        assertTrue(
            "expected Confirm for loud volume-up, got $decision",
            decision is ContextGuard.Decision.Confirm,
        )
        val msg = (decision as ContextGuard.Decision.Confirm).message
        assertTrue(msg.contains("92") || msg.contains("loud", ignoreCase = true))
    }

    @Test
    fun fanMaxPolicyBlocksIncrease() {
        val snap = CabinSnapshot(
            mediaVolumePct = 10,
            mediaPlaying = false,
            fanLevel = 7,
            cabinTempF = 72,
            seatHeaterLevel = 0,
            acOn = true,
            hvacPowerOn = true,
            defrostOn = false,
            speedMph = 0,
            gear = "Park",
        )
        val d = tm.contextGuard.evaluate("increaseFanSpeed()", snap)
        assertTrue(d is ContextGuard.Decision.Block)
    }

    @Test
    fun lowFuelAndReroutePolicies_fromRegistry() {
        val lowFuel = CabinSnapshot(
            mediaVolumePct = 10,
            mediaPlaying = false,
            fanLevel = 2,
            cabinTempF = 70,
            seatHeaterLevel = 0,
            acOn = true,
            hvacPowerOn = true,
            defrostOn = false,
            speedMph = 0,
            gear = "Park",
            fuelLevelPct = 8,
            city = "Tokyo",
        )
        val low = tm.contextGuard.evaluate("startNavigationTo(\"airport\")", lowFuel)
        assertTrue("expected low-fuel confirm, got $low", low is ContextGuard.Decision.Confirm)

        val rerouteSnap = lowFuel.copy(fuelLevelPct = 80, navActiveDest = "Tokyo Tower")
        val reroute = tm.contextGuard.evaluate("startNavigationTo(\"Tokyo Skytree\")", rerouteSnap)
        assertTrue("expected reroute confirm, got $reroute", reroute is ContextGuard.Decision.Confirm)
    }

    @Test
    fun openTrunkWhileMoving_blocksFromRegistry() {
        val moving = CabinSnapshot(
            mediaVolumePct = 10,
            mediaPlaying = false,
            fanLevel = 2,
            cabinTempF = 70,
            seatHeaterLevel = 0,
            acOn = true,
            hvacPowerOn = true,
            defrostOn = false,
            speedMph = 30,
            gear = "Drive",
            isParked = false,
        )
        val d = tm.contextGuard.evaluate("openTrunk()", moving)
        assertTrue(d is ContextGuard.Decision.Block)
    }

    @Test
    fun confirmDecision_messageIsQuestionAndReEvalStillConfirms() {
        // Orchestrator must skipGuard on user "yes"; this locks that re-eval alone would loop.
        val loud = CabinSnapshot(
            mediaVolumePct = 92,
            mediaPlaying = true,
            fanLevel = 2,
            cabinTempF = 70,
            seatHeaterLevel = 0,
            acOn = true,
            hvacPowerOn = true,
            defrostOn = false,
            speedMph = 0,
            gear = "Park",
            isParked = true,
            city = "Tokyo",
        )
        val first = tm.contextGuard.evaluate("setVolumeLevel(up)", loud)
        assertTrue(first is ContextGuard.Decision.Confirm)
        val second = tm.contextGuard.evaluate("setVolumeLevel(up)", loud)
        assertTrue(
            "unchanged cabin must still Confirm — orchestrator skipGuard is required",
            second is ContextGuard.Decision.Confirm,
        )
        val msg = (first as ContextGuard.Decision.Confirm).message
        assertTrue(msg.contains("?") || msg.contains("loud", ignoreCase = true) || msg.contains("92"))
        assertTrue(com.tcs.vehicleassistant.core.ConfirmationPolicy.isAffirmative("yes please"))
        assertFalse(com.tcs.vehicleassistant.core.ConfirmationPolicy.isAffirmative("please"))
    }

    @Test
    fun liveSnapshotReadable() {
        val snap = CabinSnapshotReader.capture(RegistryTestSupport.appContext())
        assertNotNull(snap)
        assertTrue(snap.mediaVolumePct in 0..100)
        assertTrue(snap.fanLevel >= 0)
        assertTrue(snap.city == null || snap.city!!.isNotBlank())
        assertTrue(snap.fuelLevelPct == -1 || snap.fuelLevelPct in 0..100)
        assertTrue(snap.windowOpenPct == -1 || snap.windowOpenPct in 0..100)
    }
}
