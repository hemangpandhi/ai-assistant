package com.tcs.vehicleassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SttErrorPolicyTest {

    @Test
    fun noMatch_completesSession() {
        assertEquals(
            SttErrorPolicy.Complete,
            sttErrorPolicyFor(
                "No recognition result matched",
                missingModels = false,
                retryCount = 0,
            ),
        )
    }

    @Test
    fun noSpeech_completesSession() {
        assertEquals(
            SttErrorPolicy.Complete,
            sttErrorPolicyFor(
                "No speech input",
                missingModels = false,
                retryCount = 0,
            ),
        )
    }

    @Test
    fun emptyResult_completesSession() {
        assertEquals(
            SttErrorPolicy.Complete,
            sttErrorPolicyFor(
                "I didn't hear anything.",
                missingModels = false,
                retryCount = 0,
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
            ),
        )
        assertEquals(
            SttErrorPolicy.Complete,
            sttErrorPolicyFor(
                "Client side error",
                missingModels = false,
                retryCount = 2,
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
}
