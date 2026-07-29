package com.tcs.vehicleassistant

import com.tcs.vehicleassistant.core.AssistantConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the relationships between tunables in [AssistantConfig]. Each value is individually
 * plausible, but several only work in combination -- a silence timeout shorter than the trailing
 * silence window, or a session wait shorter than engine initialization, produces a hang or a
 * truncated utterance that no single-value review would catch.
 *
 * This replaces a suite that re-declared the same thresholds inside the test body and asserted the
 * copies against each other.
 */
class ConfigCoherenceTest {

    @Test
    fun `the no-speech timeout outlasts the trailing-silence window`() {
        // Otherwise capture gives up before it can detect the end of a real utterance.
        assertTrue(
            "NO_SPEECH_TIMEOUT_FRAMES must exceed TRAILING_SILENCE_FRAMES",
            AssistantConfig.Audio.NO_SPEECH_TIMEOUT_FRAMES > AssistantConfig.Audio.TRAILING_SILENCE_FRAMES
        )
    }

    @Test
    fun `silence thresholds are positive so the capture loop always terminates`() {
        assertTrue(AssistantConfig.Audio.TRAILING_SILENCE_FRAMES > 0)
        assertTrue(AssistantConfig.Audio.NO_SPEECH_TIMEOUT_FRAMES > 0)
    }

    @Test
    fun `microphone acquisition retries cover the wake-word handoff`() {
        // The wake-word process needs time to release AudioRecord; total retry budget must exceed
        // the handoff grace period or the first attempt after a wake word fails.
        val retryBudgetMs =
            AssistantConfig.Audio.AUDIO_RECORD_MAX_ATTEMPTS * AssistantConfig.Audio.AUDIO_RECORD_RETRY_DELAY_MS
        assertTrue(
            "retry budget ${retryBudgetMs}ms must exceed handoff delay ${AssistantConfig.Audio.MIC_HANDOFF_DELAY_MS}ms",
            retryBudgetMs > AssistantConfig.Audio.MIC_HANDOFF_DELAY_MS
        )
    }

    @Test
    fun `speech drain wait is bounded and covers a typical utterance`() {
        // Tool execution waits for speech to drain; an unbounded wait wedged the turn on barge-in.
        // A few seconds is too short for a multi-sentence reply; a minute is too long for a hang.
        assertTrue(
            "SPEECH_DRAIN_TIMEOUT_MS must be at least 5s",
            AssistantConfig.Audio.SPEECH_DRAIN_TIMEOUT_MS >= 5_000L
        )
        assertTrue(
            "SPEECH_DRAIN_TIMEOUT_MS must stay under a minute",
            AssistantConfig.Audio.SPEECH_DRAIN_TIMEOUT_MS <= 60_000L
        )
    }

    @Test
    fun `audio runs at the sample rate the speech models were trained on`() {
        // Vosk, sherpa-onnx and Silero VAD are all 16 kHz; a mismatch silently degrades accuracy
        // rather than failing.
        assertEquals(16_000, AssistantConfig.Audio.SAMPLE_RATE_HZ)
    }

    @Test
    fun `the session wait covers a full engine initialization`() {
        // AssistantSession polls for readiness; a shorter budget reports failure while the engine
        // is still compiling kernels on a cold start.
        assertTrue(
            "LLM_READY_TIMEOUT_MS must cover INIT_TIMEOUT_MS",
            AssistantConfig.Session.LLM_READY_TIMEOUT_MS >= AssistantConfig.Llm.INIT_TIMEOUT_MS
        )
    }

    @Test
    fun `the readiness poll is short enough to be responsive`() {
        assertTrue(AssistantConfig.Session.LLM_READY_POLL_MS in 1..1_000)
        assertTrue(
            AssistantConfig.Session.LLM_READY_POLL_MS < AssistantConfig.Session.LLM_READY_TIMEOUT_MS
        )
    }

    @Test
    fun `the first inference gets a larger budget than later ones`() {
        // The first turn pays prompt prefill and, on GPU, kernel compilation.
        assertTrue(
            "FIRST_INFERENCE_TIMEOUT_MS must exceed INFERENCE_TIMEOUT_MS",
            AssistantConfig.Llm.FIRST_INFERENCE_TIMEOUT_MS > AssistantConfig.Llm.INFERENCE_TIMEOUT_MS
        )
    }

    @Test
    fun `a tool cannot outlast the inference that requested it`() {
        assertTrue(
            "TOOL_TIMEOUT_MS must be shorter than INFERENCE_TIMEOUT_MS",
            AssistantConfig.Llm.TOOL_TIMEOUT_MS < AssistantConfig.Llm.INFERENCE_TIMEOUT_MS
        )
    }

    @Test
    fun `inference drain timeout is positive and shorter than init`() {
        assertTrue(AssistantConfig.Llm.INFERENCE_DRAIN_TIMEOUT_MS > 0)
        assertTrue(
            "drain wait must finish well before a full engine init budget",
            AssistantConfig.Llm.INFERENCE_DRAIN_TIMEOUT_MS < AssistantConfig.Llm.INIT_TIMEOUT_MS
        )
    }

    @Test
    fun `the conversation is recycled before the retention cap evicts turns`() {
        assertTrue(
            "CONVERSATION_RESET_TURNS must stay under MAX_RETAINED_TURNS",
            AssistantConfig.Llm.CONVERSATION_RESET_TURNS < AssistantConfig.Memory.MAX_RETAINED_TURNS
        )
    }

    @Test
    fun `repetition scanning starts before the runaway ceiling`() {
        // If the scan threshold were above the ceiling, repetition detection would never run.
        assertTrue(
            "REPETITION_SCAN_MIN_LENGTH must be below RUNAWAY_LENGTH",
            AssistantConfig.Streaming.REPETITION_SCAN_MIN_LENGTH < AssistantConfig.Streaming.RUNAWAY_LENGTH
        )
        assertTrue(AssistantConfig.Streaming.REPETITION_WINDOW >= 2)
    }

    @Test
    fun `the sliding window can hold more than a single turn`() {
        assertTrue(AssistantConfig.Memory.DEFAULT_MAX_CHARS > 500)
        assertTrue(AssistantConfig.Memory.MAX_RETAINED_TURNS > 1)
    }

    @Test
    fun `preference keys are unique so settings cannot overwrite each other`() {
        val keys = listOf(
            AssistantConfig.Prefs.BACKEND_CHOICE,
            AssistantConfig.Prefs.SELECTED_MODEL,
            AssistantConfig.Prefs.MAX_TOKENS,
            AssistantConfig.Prefs.WAKE_WORD,
            AssistantConfig.Prefs.WAKE_WORD_ENABLED,
            AssistantConfig.Prefs.SYSTEM_PROMPT,
            AssistantConfig.Prefs.USER_MEMORY,
            AssistantConfig.Prefs.UI_LAYOUT,
            AssistantConfig.Prefs.COMPANION_MODE,
            AssistantConfig.Prefs.AGENTIC_LOOP,
            AssistantConfig.Prefs.CLOUD_FALLBACK,
            AssistantConfig.Prefs.RESOLVED_BACKEND,
            AssistantConfig.Prefs.KERNEL_CACHE_MODEL
        )
        assertEquals("duplicate preference keys", keys.size, keys.distinct().size)
    }

    @Test
    fun `the wake word default is a two-word phrase`() {
        // A single-word wake word false-triggers constantly against ordinary speech.
        val words = AssistantConfig.WakeWord.DEFAULT_WAKE_WORD.trim().split(" ")
        assertTrue("default wake word must have at least two words, was $words", words.size >= 2)
    }

    @Test
    fun `the backend fallback chain ends on CPU`() {
        assertEquals(AssistantConfig.Backend.CPU, AssistantConfig.Backend.FALLBACK_CHAIN.last())
    }

    @Test
    fun `the large-screen breakpoint matches the Android tablet convention`() {
        assertEquals(600, AssistantConfig.LARGE_SCREEN_MIN_WIDTH_DP)
    }
}
