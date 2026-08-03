package com.tcs.vehicleassistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the P0 contract for the inference gate extracted from LLMManager.
 */
class LlmInferenceGateTest {

    @Test
    fun `begin returns null when resolver yields null`() {
        assertEquals(null, LlmInferenceGate.begin { null })
        assertFalse(LlmInferenceGate.hasActive())
    }

    @Test
    fun `forceReset clears stuck counter`() {
        LlmInferenceGate.forceReset()
        assertFalse(LlmInferenceGate.hasActive())
        assertEquals(0, LlmInferenceGate.activeCount())
    }

    @Test
    fun `withLock serializes critical sections`() {
        var saw = false
        LlmInferenceGate.withLock {
            assertFalse(LlmInferenceGate.hasActive())
            saw = true
        }
        assertTrue(saw)
    }

    @Test
    fun `end is a no-op when counter is already zero`() {
        LlmInferenceGate.forceReset()
        LlmInferenceGate.end()
        assertEquals(0, LlmInferenceGate.activeCount())
    }
}
