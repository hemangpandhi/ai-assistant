package com.tcs.vehicleassistant

import com.tcs.vehicleassistant.llm.LLMManager

import com.tcs.vehicleassistant.core.AssistantConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the P0 contract: native engine teardown must never race an active LiteRT stream.
 */
class InferenceLifecycleContractTest {

    @Test
    fun `drain timeout is configured`() {
        assertTrue(AssistantConfig.Llm.INFERENCE_DRAIN_TIMEOUT_MS in 1_000L..60_000L)
    }

    @Test
    fun `capability reminder still forbids text-AI refusals`() {
        val text = com.tcs.vehicleassistant.assistant.SystemPromptBuilder.capabilityReminder().lowercase()
        assertTrue(text.contains("text-based"))
        assertTrue(text.contains("cannot play music"))
    }

    @Test
    fun `resetConversation is a no-op when there is no engine`() {
        // With no engine loaded in a JVM unit test, reset must succeed without throwing.
        assertTrue(LLMManager.resetConversation())
        assertFalse(LLMManager.hasActiveInference())
        assertEquals(false, LLMManager.isReady())
    }

    @Test
    fun `unload clears nativeTurnsSinceReset`() {
        LLMManager.nativeTurnsSinceReset = AssistantConfig.Llm.CONVERSATION_RESET_TURNS
        assertTrue(LLMManager.unload())
        assertEquals(0, LLMManager.nativeTurnsSinceReset)
        assertTrue(LLMManager.isFirstMessage)
    }

    @Test
    fun `resetConversation clears nativeTurnsSinceReset`() {
        LLMManager.nativeTurnsSinceReset = AssistantConfig.Llm.CONVERSATION_RESET_TURNS
        assertTrue(LLMManager.resetConversation())
        assertEquals(0, LLMManager.nativeTurnsSinceReset)
        assertTrue(LLMManager.isFirstMessage)
    }
}
