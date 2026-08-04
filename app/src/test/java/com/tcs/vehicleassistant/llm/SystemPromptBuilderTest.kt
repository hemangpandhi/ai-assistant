package com.tcs.vehicleassistant.llm

import com.tcs.vehicleassistant.assistant.SystemPromptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the prompt builder extracted from [com.tcs.vehicleassistant.LLMManager]
 * so SRP splits cannot drop anti-refusal rules.
 */
class SystemPromptBuilderTest {

    @Test
    fun `capability reminder keeps vehicle co-pilot identity`() {
        val text = SystemPromptBuilder.capabilityReminder()
        assertTrue(text.contains("vehicle", ignoreCase = true))
        assertTrue(text.contains("co-pilot", ignoreCase = true) || text.contains("copilot", ignoreCase = true))
    }

    @Test
    fun `capability reminder forbids text-AI refusal phrases`() {
        val text = SystemPromptBuilder.capabilityReminder().lowercase()
        for (phrase in listOf("text-based", "cannot play music", "cannot control playback", "never say")) {
            assertTrue("reminder must address '$phrase', was:\n$text", text.contains(phrase))
        }
    }

    @Test
    fun `capability reminder forbids music after accidents`() {
        val text = SystemPromptBuilder.capabilityReminder().lowercase()
        assertTrue(text.contains("accident") || text.contains("emergency"))
        assertTrue(text.contains("never suggest music") || text.contains("never suggest"))
    }

    @Test
    fun `capability reminder requires tool tags for clear cabin commands`() {
        val text = SystemPromptBuilder.capabilityReminder()
        assertTrue(text.contains("<TOOL>"))
        assertTrue(text.contains("clear cabin", ignoreCase = true) || text.contains("media command", ignoreCase = true))
        assertTrue(text.contains("empathy", ignoreCase = true) || text.contains("feelings", ignoreCase = true))
        assertFalse(text.contains("I cannot help", ignoreCase = true))
    }
}
