package com.assistant.ui.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDebugStripConfigTest {

    @Test
    fun parse_acceptsOnTokens() {
        assertEquals(true, AssistantDebugStripConfig.parse("on"))
        assertEquals(true, AssistantDebugStripConfig.parse("1"))
        assertEquals(true, AssistantDebugStripConfig.parse("true"))
        assertEquals(true, AssistantDebugStripConfig.parse("SHOW"))
        assertEquals(true, AssistantDebugStripConfig.parse(" visible "))
    }

    @Test
    fun parse_acceptsOffTokens() {
        assertEquals(false, AssistantDebugStripConfig.parse("off"))
        assertEquals(false, AssistantDebugStripConfig.parse("0"))
        assertEquals(false, AssistantDebugStripConfig.parse("false"))
        assertEquals(false, AssistantDebugStripConfig.parse("HIDE"))
        assertEquals(false, AssistantDebugStripConfig.parse("hidden"))
    }

    @Test
    fun parse_rejectsUnknown() {
        assertNull(AssistantDebugStripConfig.parse(""))
        assertNull(AssistantDebugStripConfig.parse(null))
        assertNull(AssistantDebugStripConfig.parse("maybe"))
    }

    @Test
    fun toAdbToken_roundTrips() {
        assertEquals("on", AssistantDebugStripConfig.toAdbToken(true))
        assertEquals("off", AssistantDebugStripConfig.toAdbToken(false))
        assertTrue(AssistantDebugStripConfig.parse(AssistantDebugStripConfig.toAdbToken(true))!!)
        assertFalse(AssistantDebugStripConfig.parse(AssistantDebugStripConfig.toAdbToken(false))!!)
    }

    @Test
    fun defaultIsVisible() {
        assertTrue(AssistantDebugStripConfig.DEFAULT_VISIBLE)
    }
}
