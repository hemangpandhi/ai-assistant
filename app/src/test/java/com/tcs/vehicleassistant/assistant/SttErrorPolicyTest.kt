package com.tcs.vehicleassistant.assistant

import com.tcs.vehicleassistant.core.AssistantConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SttErrorPolicyTest {

    @Test
    fun noMatch_withinListenWindow_retriesQuietly() {
        assertEquals(
            SttErrorPolicy.RetryQuiet,
            sttErrorPolicyFor(
                "No recognition result matched",
                missingModels = false,
                retryCount = 0,
                listenElapsedMs = 1_200L,
            ),
        )
    }

    @Test
    fun noSpeech_withinListenWindow_retriesQuietly() {
        assertEquals(
            SttErrorPolicy.RetryQuiet,
            sttErrorPolicyFor(
                "No speech input",
                missingModels = false,
                retryCount = 0,
                listenElapsedMs = 500L,
            ),
        )
    }

    @Test
    fun emptyResult_withinListenWindow_retriesQuietly() {
        assertEquals(
            SttErrorPolicy.RetryQuiet,
            sttErrorPolicyFor(
                "I didn't hear anything.",
                missingModels = false,
                retryCount = 0,
                listenElapsedMs = 2_000L,
            ),
        )
    }

    @Test
    fun noMatch_afterListenWindow_completesSession() {
        assertEquals(
            SttErrorPolicy.Complete,
            sttErrorPolicyFor(
                "No recognition result matched",
                missingModels = false,
                retryCount = 0,
                listenElapsedMs = AssistantConfig.Audio.NO_SPEECH_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun noSpeech_afterListenWindow_completesSession() {
        assertEquals(
            SttErrorPolicy.Complete,
            sttErrorPolicyFor(
                "No speech input",
                missingModels = false,
                retryCount = 0,
                listenElapsedMs = AssistantConfig.Audio.NO_SPEECH_TIMEOUT_MS + 1,
            ),
        )
    }

    @Test
    fun emptyResult_afterListenWindow_completesSession() {
        assertEquals(
            SttErrorPolicy.Complete,
            sttErrorPolicyFor(
                "I didn't hear anything.",
                missingModels = false,
                retryCount = 0,
                listenElapsedMs = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun clientError_retriesThenCompletes() {
        assertEquals(
            SttErrorPolicy.Retry,
            sttErrorPolicyFor(
                "Client side error",
                missingModels = false,
                retryCount = 0,
                listenElapsedMs = 100L,
            ),
        )
        assertEquals(
            SttErrorPolicy.Complete,
            sttErrorPolicyFor(
                "Client side error",
                missingModels = false,
                retryCount = 2,
                listenElapsedMs = 100L,
            ),
        )
    }

    @Test
    fun missingModels_holds() {
        assertEquals(
            SttErrorPolicy.Hold,
            sttErrorPolicyFor(
                "Unknown recognition error",
                missingModels = true,
                retryCount = 0,
            ),
        )
    }

    @Test
    fun friendlyCopy_forNoMatch() {
        assertEquals(
            "I didn't catch that.",
            friendlySttErrorMessage("No recognition result matched"),
        )
    }

    @Test
    fun listenWindow_matchesConfigNoSpeechTimeout() {
        assertTrue(AssistantConfig.Audio.NO_SPEECH_TIMEOUT_MS in 1_000L..5_000L)
        assertEquals(5_000L, AssistantConfig.Audio.NO_SPEECH_TIMEOUT_MS)
    }
}
