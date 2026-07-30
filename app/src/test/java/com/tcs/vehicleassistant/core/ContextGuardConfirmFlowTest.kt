package com.tcs.vehicleassistant.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Simulates ContextGuard confirm → user yes without re-asking (orchestrator skipGuard semantics).
 */
class ContextGuardConfirmFlowTest {

    private val loudPlaying = CabinSnapshot(
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
            ),
        )
    }

    @After
    fun tearDown() {
        ContextGuard.clearRulesForTest()
    }

    @Test
    fun loudVolumeUp_confirmsThenSecondEvalStillConfirms_untilSkipSemantics() {
        val first = ContextGuard.evaluate("setVolumeLevel(up)", loudPlaying)
        assertTrue(first is ContextGuard.Decision.Confirm)
        // Unchanged cabin → second evaluate still Confirm (why orchestrator must skipGuard on yes).
        val second = ContextGuard.evaluate("setVolumeLevel(up)", loudPlaying)
        assertTrue(
            "without skipGuard, yes would re-ask forever: $second",
            second is ContextGuard.Decision.Confirm,
        )
    }

    @Test
    fun afterUserYes_applySkipGuardSemantics_allowsExecute() {
        val pending = (ContextGuard.evaluate("setVolumeLevel(up)", loudPlaying)
            as ContextGuard.Decision.Confirm).originalToolCall
        assertEquals("setVolumeLevel(up)", pending)

        // Orchestrator skipGuard: treat Confirm as Allow; still honor Block.
        val decision = ContextGuard.evaluate(pending, loudPlaying)
        val effective = when (decision) {
            is ContextGuard.Decision.Block -> decision
            is ContextGuard.Decision.Confirm,
            is ContextGuard.Decision.Escalate,
            is ContextGuard.Decision.Allow,
            -> ContextGuard.Decision.Allow()
        }
        assertTrue(effective is ContextGuard.Decision.Allow)
        assertEquals(ConfirmationPolicy.Reply.AFFIRM, ConfirmationPolicy.classify("yes please"))
    }

    @Test
    fun skipGuardStillHonorsHardBlock() {
        val moving = loudPlaying.copy(speedMph = 30, gear = "Drive", isParked = false, mediaPlaying = false)
        val d = ContextGuard.evaluate("openTrunk()", moving)
        assertTrue(d is ContextGuard.Decision.Block)
        // Even after "yes", Block must win:
        val effective = when (val decision = ContextGuard.evaluate("openTrunk()", moving)) {
            is ContextGuard.Decision.Block -> decision
            else -> ContextGuard.Decision.Allow()
        }
        assertTrue(effective is ContextGuard.Decision.Block)
    }

    @Test
    fun declineAndSupersedeClassification() {
        assertEquals(ConfirmationPolicy.Reply.DECLINE, ConfirmationPolicy.classify("no"))
        assertEquals(ConfirmationPolicy.Reply.OTHER, ConfirmationPolicy.classify("raise temperature"))
    }
}
