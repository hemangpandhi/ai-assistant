package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmToolTurnPolicyTest {

    @Test
    fun confirmationAsk_prefersRegistryMessage() {
        val msg = LlmToolTurnPolicy.confirmationAskMessage(
            "unlockDoors",
            "Security Warning: Are you sure you want to unlock the vehicle doors?",
        )
        assertTrue(msg.contains("unlock", ignoreCase = true))
        assertTrue(msg.contains("?"))
    }

    @Test
    fun confirmationAsk_defaultsWhenRegistryBlank() {
        val msg = LlmToolTurnPolicy.confirmationAskMessage("openTrunk", "  ")
        assertTrue(msg.contains("openTrunk"))
        assertTrue(msg.contains("sure", ignoreCase = true))
    }

    @Test
    fun emptyProse_confirmationNeverClaimsRan() {
        val display = LlmToolTurnPolicy.resolveEmptyProseDisplay(
            confirmationAsks = listOf("Security Warning: unlock the doors?"),
            toolFeedbacks = listOf("Security Warning: unlock the doors?"),
            actuallyExecutedToolCalls = emptyList(),
            emptyFallback = "I couldn't run a tool for that. Want to try again?",
        )
        assertEquals("Security Warning: unlock the doors?", display.text)
        assertTrue(display.asQuestion)
        assertFalse(display.text.contains("I ran", ignoreCase = true))
    }

    @Test
    fun emptyProse_actualExecuteMayAckRan() {
        val display = LlmToolTurnPolicy.resolveEmptyProseDisplay(
            confirmationAsks = emptyList(),
            toolFeedbacks = emptyList(),
            actuallyExecutedToolCalls = listOf("playMusic(music)"),
            emptyFallback = "",
        )
        assertEquals("Okay — I ran playMusic.", display.text)
        assertFalse(display.asQuestion)
    }

    @Test
    fun emptyProse_prefersConfirmOverExecutedAck() {
        // Deduped tool set may still list the call; confirm must win.
        val display = LlmToolTurnPolicy.resolveEmptyProseDisplay(
            confirmationAsks = listOf("Unlock anyway?"),
            toolFeedbacks = listOf("Unlock anyway?"),
            actuallyExecutedToolCalls = emptyList(),
            emptyFallback = "fallback",
        )
        assertEquals("Unlock anyway?", display.text)
        assertTrue(display.asQuestion)
    }

    @Test
    fun shouldSpeakToolFeedback_forPendingConfirmEvenWithoutError() {
        assertTrue(
            LlmToolTurnPolicy.shouldSpeakToolFeedback(
                pendingConfirmation = true,
                confirmationAsks = listOf("Increase volume anyway?"),
                toolFeedbacks = listOf("Increase volume anyway?"),
            ),
        )
        assertFalse(
            LlmToolTurnPolicy.shouldSpeakToolFeedback(
                pendingConfirmation = false,
                confirmationAsks = emptyList(),
                toolFeedbacks = listOf("Playing music."),
            ),
        )
    }
}
