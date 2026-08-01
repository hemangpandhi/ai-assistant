package com.tcs.vehicleassistant.requirements

import com.tcs.vehicleassistant.core.CabinSnapshot
import com.tcs.vehicleassistant.core.ConfirmationPolicy
import com.tcs.vehicleassistant.core.ContextGuard
import com.tcs.vehicleassistant.core.ConversationalIntent
import com.tcs.vehicleassistant.core.DirectToolResolver
import com.tcs.vehicleassistant.core.LlmToolTurnPolicy
import com.tcs.vehicleassistant.core.SafetyCriticalTools
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Driver-seat scenario matrix (text → path). Mirrors
 * `docs/use_cases/DRIVER_SEAT_TABLET_SUITE.md` so JVM CI catches routing/safety
 * regressions before tablet soak.
 */
class DriverSeatScenarioMatrixUnitTest {

    private lateinit var specs: List<DirectToolResolver.ToolSpec>

    private val parked = CabinSnapshot(
        mediaVolumePct = 40,
        mediaPlaying = false,
        fanLevel = 3,
        cabinTempF = 72,
        seatHeaterLevel = 0,
        acOn = false,
        hvacPowerOn = true,
        defrostOn = false,
        speedMph = 0,
        gear = "Park",
        isParked = true,
        city = "Tokyo",
        fuelLevelPct = 55,
    )

    private val driving = parked.copy(
        speedMph = 45,
        gear = "Drive",
        isParked = false,
        mediaPlaying = true,
        mediaVolumePct = 70,
    )

    @Before
    fun setUp() {
        specs = RegistryFixture.toSpecs()
        val config = JSONObject(RegistryFixture.registryFile().readText()).getJSONObject("config")
        ContextGuard.loadFromConfig(config)
    }

    @After
    fun tearDown() {
        ContextGuard.clearRulesForTest()
    }

    @Test
    fun parked_cabinComfort_directTool() {
        for (phrase in listOf("turn on the ac", "increase temperature", "increase fan")) {
            val hit = DirectToolResolver.resolve(phrase, specs)
            assertTrue(
                "parked comfort should DirectTool: $phrase → $hit",
                hit is DirectToolResolver.Outcome.Execute,
            )
        }
    }

    @Test
    fun driving_playMusic_directTool() {
        val play = DirectToolResolver.resolve("play music", specs)
        assertTrue("play music → $play", play is DirectToolResolver.Outcome.Execute)
    }

    @Test
    fun driving_unlock_requiresConfirmNotSilentAllow() {
        val d = ContextGuard.evaluate("unlockDoors()", driving)
        assertTrue("expected Confirm while driving, got $d", d is ContextGuard.Decision.Confirm)
    }

    @Test
    fun driving_openTrunk_blocks() {
        val d = ContextGuard.evaluate("openTrunk()", driving)
        assertTrue("expected Block while driving, got $d", d is ContextGuard.Decision.Block)
    }

    @Test
    fun parked_unlock_allowsWhenGearKnown() {
        assertTrue(ContextGuard.evaluate("unlockDoors()", parked) is ContextGuard.Decision.Allow)
    }

    @Test
    fun unknownGear_unlock_failClosedConfirm() {
        val unknown = parked.copy(gear = "Unknown", isParked = false)
        val d = ContextGuard.evaluate("unlockDoors()", unknown)
        assertTrue(d is ContextGuard.Decision.Confirm)
        assertEquals(
            SafetyCriticalTools.GEAR_UNKNOWN_POLICY_ID,
            (d as ContextGuard.Decision.Confirm).policyId,
        )
    }

    @Test
    fun llmToolTagOnly_unlock_neverClaimsRan() {
        val display = LlmToolTurnPolicy.resolveEmptyProseDisplay(
            confirmationAsks = listOf(
                "Security Warning: Are you sure you want to unlock the vehicle doors?",
            ),
            toolFeedbacks = listOf(
                "Security Warning: Are you sure you want to unlock the vehicle doors?",
            ),
            actuallyExecutedToolCalls = emptyList(),
            emptyFallback = "I couldn't run a tool for that.",
        )
        assertFalse(display.text.contains("I ran", ignoreCase = true))
        assertTrue(display.asQuestion)
        assertTrue(display.text.contains("unlock", ignoreCase = true))
    }

    @Test
    fun wellnessWhileDriving_isOpenChatNotClimate() {
        assertTrue(ConversationalIntent.isEmotionalOrWellness("I'm not feeling good"))
        assertTrue(ConversationalIntent.isOpenChat("how are you"))
        assertFalse(ConversationalIntent.isOpenChat("I'm feeling cold"))
    }

    @Test
    fun confirmDeclineWhileDriving() {
        assertEquals(ConfirmationPolicy.Reply.AFFIRM, ConfirmationPolicy.classify("yes"))
        assertEquals(ConfirmationPolicy.Reply.DECLINE, ConfirmationPolicy.classify("no thanks"))
        assertEquals(ConfirmationPolicy.Reply.DECLINE, ConfirmationPolicy.classify("yes no"))
    }

    @Test
    fun emptyModel_actionWhileDriving_admitsToolFailure() {
        // Empty-model honesty for action queries lives in master-owned agent code
        // (StreamTextHandlingTest). Here we lock the driver-seat display path:
        // empty prose + no executed tools must admit failure, never claim success.
        val display = LlmToolTurnPolicy.resolveEmptyProseDisplay(
            confirmationAsks = emptyList(),
            toolFeedbacks = emptyList(),
            actuallyExecutedToolCalls = emptyList(),
            emptyFallback = "I couldn't run a tool for that. Want to try again?",
        )
        assertTrue(display.text.contains("couldn't run a tool", ignoreCase = true))
        assertFalse(display.text.contains("taken care", ignoreCase = true))
    }

    @Test
    fun weatherAndIdentity_softInterrogatives() {
        for (phrase in listOf("what's the weather?", "who are you", "what model is this")) {
            val hit = DirectToolResolver.resolve(phrase, specs)
            assertTrue(
                "soft info should DirectTool: $phrase → $hit",
                hit is DirectToolResolver.Outcome.Execute,
            )
        }
    }
}
