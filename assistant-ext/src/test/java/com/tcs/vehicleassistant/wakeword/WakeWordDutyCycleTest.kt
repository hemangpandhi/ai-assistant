package com.tcs.vehicleassistant.wakeword

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordDutyCycleTest {
    @Test
    fun skipsMostSilentBuffers() {
        val duty = WakeWordDutyCycle(silenceThreshold = 180, silentSkip = 4)
        val silent = ShortArray(16) { 0 }
        val decisions = (1..8).map { duty.inspect(silent, silent.size).shouldRecognize }
        // Every 4th silent buffer recognizes
        assertTrue(decisions[3])
        assertTrue(decisions[7])
        assertFalse(decisions[0])
        assertFalse(decisions[1])
        assertFalse(decisions[2])
    }

    @Test
    fun loudBufferAlwaysRecognizes() {
        val duty = WakeWordDutyCycle()
        val loud = ShortArray(16) { 1000 }
        assertTrue(duty.inspect(loud, loud.size).shouldRecognize)
    }
}

class WakePhraseMatcherTest {
    @Test
    fun matchesConfiguredWord() {
        assertTrue(WakePhraseMatcher.matches("please hey auto now", "hey auto"))
    }

    @Test
    fun matchesAlias() {
        assertTrue(WakePhraseMatcher.matches("hey nissan open the door", "hey auto"))
    }

    @Test
    fun rejectsUnrelated() {
        assertFalse(WakePhraseMatcher.matches("play some music", "hey auto"))
    }
}
