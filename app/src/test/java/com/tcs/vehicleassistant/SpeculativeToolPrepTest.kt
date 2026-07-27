package com.tcs.vehicleassistant

import com.tcs.vehicleassistant.domain.SpeculativeToolPrep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpeculativeToolPrepTest {

    @Before
    fun setUp() {
        SpeculativeToolPrep.clear()
        LLMManager.lastAiResponse = ""
    }

    @After
    fun tearDown() {
        SpeculativeToolPrep.clear()
    }

    @Test
    fun onPartial_resolvesAcCommand() {
        SpeculativeToolPrep.onPartial("turn on the ac")
        assertEquals("turnOnAC()", SpeculativeToolPrep.resolveForFinal("turn on the ac please"))
    }

    @Test
    fun resolveForFinal_freshWinsWithoutPartial() {
        assertEquals("turnOffAC()", SpeculativeToolPrep.resolveForFinal("turn off the ac"))
    }

    @Test
    fun resolveForFinal_clearsCandidate() {
        SpeculativeToolPrep.onPartial("turn on the ac")
        SpeculativeToolPrep.resolveForFinal("turn on the ac")
        assertNull(SpeculativeToolPrep.resolveForFinal("hello there friend"))
    }

    @Test
    fun looksLikeCommand_detectsCabinPhrases() {
        assertTrue(SpeculativeToolPrep.looksLikeCommand("turn on the ac"))
        assertTrue(SpeculativeToolPrep.looksLikeCommand("set temperature to 72"))
        assertFalse(SpeculativeToolPrep.looksLikeCommand("hi"))
        assertFalse(SpeculativeToolPrep.looksLikeCommand("what is the weather like today"))
    }

    @Test
    fun shortPartial_clearsCandidate() {
        SpeculativeToolPrep.onPartial("turn on the ac")
        SpeculativeToolPrep.onPartial("hi")
        assertNull(SpeculativeToolPrep.resolveForFinal("hello friend how are you"))
    }
}
