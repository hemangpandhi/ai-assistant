package com.tcs.vehicleassistant.hardware.ear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM checks for the session ear contract (no AudioRecord / Sherpa natives).
 */
class AssistantEarContractTest {

    @Test
    fun frameSamples_isTwentyMsAt16kHz() {
        assertEquals(320, EarMic.FRAME_SAMPLES)
        assertEquals(
            20,
            EarMic.FRAME_SAMPLES * 1000 / 16_000,
        )
    }

    @Test
    fun earState_coversSessionLifecycle() {
        val order = listOf(
            EarState.Closed,
            EarState.Prewarm,
            EarState.Armed,
            EarState.Capturing,
            EarState.Finalizing,
            EarState.Armed,
            EarState.Closed,
        )
        assertEquals(7, order.size)
        assertTrue(EarState.entries.containsAll(order.toSet()))
    }

    @Test
    fun earSttCallbacks_defaultsAreNoOps() {
        val cbs = EarSttCallbacks()
        cbs.onReadyForSpeech()
        cbs.onBeginningOfSpeech()
        cbs.onEndOfSpeech()
        cbs.onResult("x")
        cbs.onEmptyResult()
        cbs.onError(0)
        cbs.onPartial("p")
    }
}
