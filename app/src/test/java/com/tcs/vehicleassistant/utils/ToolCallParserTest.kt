package com.tcs.vehicleassistant.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    @Test
    fun extractCompleteToolCalls_parsesClosedTags() {
        val text = "Sure. <TOOL>setTemperature(72)</TOOL> Done."
        val calls = StreamingToolCallParser.extractCompleteToolCalls(text)
        assertEquals(1, calls.size)
        assertEquals("setTemperature", calls[0].toolName)
        assertEquals("72", calls[0].args)
        assertEquals("setTemperature(72)", calls[0].invocation)
    }

    @Test
    fun extractCompleteToolCalls_ignoresIncompleteTag() {
        val text = "Working on it. <TOOL>playMusic("
        val calls = StreamingToolCallParser.extractCompleteToolCalls(text)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun extractCompleteToolCalls_multipleEagerTags() {
        val text = "<TOOL>setHvacPower(true)</TOOL> and <TOOL>playMusic()</TOOL>"
        val calls = StreamingToolCallParser.extractCompleteToolCalls(text)
        assertEquals(2, calls.size)
        assertEquals("setHvacPower(true)", calls[0].invocation)
        assertEquals("playMusic()", calls[1].invocation)
    }

    @Test
    fun stripToolTags_removesCompleteAndTrailingIncomplete() {
        val cleaned = ToolCallParser.stripToolTags("OK <TOOL>openWindows()</TOOL> wait <TOOL>close")
        assertTrue(cleaned.startsWith("OK"))
        assertTrue(!cleaned.contains("<TOOL>"))
    }
}
