package com.tcs.vehicleassistant

import com.tcs.vehicleassistant.llm.LLMManager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the anti-refusal reminder reinjected on every conversation turn.
 *
 * Small edge models dilute the first-turn system prompt in the KV cache and revert to pretrained
 * refusals ("I'm a text-based AI", "I can't control playback"). The reminder must keep naming those
 * exact failure modes so a future edit cannot drop them silently.
 */
class CapabilityReminderTest {

    @Test
    fun `reminder asserts vehicle co-pilot identity`() {
        val text = com.tcs.vehicleassistant.assistant.SystemPromptBuilder.capabilityReminder()
        assertTrue(text.contains("vehicle", ignoreCase = true))
        assertTrue(text.contains("co-pilot", ignoreCase = true) || text.contains("copilot", ignoreCase = true))
    }

    @Test
    fun `reminder forbids the common text-AI refusal phrases`() {
        val text = com.tcs.vehicleassistant.assistant.SystemPromptBuilder.capabilityReminder().lowercase()
        for (phrase in listOf("text-based", "cannot play music", "cannot control playback", "never say")) {
            assertTrue("reminder must address '$phrase', was:\n$text", text.contains(phrase))
        }
    }

    @Test
    fun `reminder requires emitting a tool tag for clear cabin commands`() {
        val text = com.tcs.vehicleassistant.assistant.SystemPromptBuilder.capabilityReminder()
        assertTrue(text.contains("<TOOL>"))
        assertTrue(text.contains("clear cabin", ignoreCase = true) || text.contains("media command", ignoreCase = true))
        assertTrue(text.contains("empathy", ignoreCase = true) || text.contains("feelings", ignoreCase = true))
        assertTrue(text.contains("accident", ignoreCase = true) || text.contains("emergency", ignoreCase = true))
        assertFalse(text.contains("I cannot help", ignoreCase = true))
    }
}
