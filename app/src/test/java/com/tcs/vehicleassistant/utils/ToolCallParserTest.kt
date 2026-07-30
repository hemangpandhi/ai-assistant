package com.tcs.vehicleassistant.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers extraction of tool calls from raw model output and the stripping that produces the text
 * actually spoken to the driver.
 *
 * Both run on every streamed token, and a leaked partial tag is audible: the TTS engine reads
 * "less than tool greater than" out loud.
 */
class ToolCallParserTest {

    @Test
    fun `a well-formed call is extracted with its name and arguments`() {
        val calls = ToolCallParser.extractToolCalls("Warming things up. <TOOL>increaseTemperature(all)</TOOL>")
        assertEquals(1, calls.size)
        assertEquals("increaseTemperature", calls.single().toolName)
        assertEquals("all", calls.single().args)
    }

    @Test
    fun `a call without arguments yields empty arguments`() {
        val calls = ToolCallParser.extractToolCalls("<TOOL>stopMusic()</TOOL>")
        assertEquals("stopMusic", calls.single().toolName)
        assertEquals("", calls.single().args)
    }

    @Test
    fun `a call with no parentheses at all is still recognised`() {
        val calls = ToolCallParser.extractToolCalls("<TOOL>stopMusic</TOOL>")
        assertEquals("stopMusic", calls.single().toolName)
        assertEquals("", calls.single().args)
    }

    @Test
    fun `tag matching is case insensitive`() {
        val calls = ToolCallParser.extractToolCalls("<tool>stopMusic()</tool>")
        assertEquals("stopMusic", calls.single().toolName)
    }

    @Test
    fun `several calls in one response are all extracted in order`() {
        val calls = ToolCallParser.extractToolCalls(
            "On it. <TOOL>increaseTemperature(all)</TOOL><TOOL>playMusic(jazz)</TOOL>"
        )
        assertEquals(listOf("increaseTemperature", "playMusic"), calls.map { it.toolName })
        assertEquals(listOf("all", "jazz"), calls.map { it.args })
    }

    @Test
    fun `quoted arguments survive extraction`() {
        val calls = ToolCallParser.extractToolCalls("""<TOOL>startNavigationTo("Tokyo Skytree")</TOOL>""")
        assertEquals("startNavigationTo", calls.single().toolName)
        assertEquals(""""Tokyo Skytree"""", calls.single().args)
    }

    @Test
    fun `a truncated closing tag is still extracted`() {
        // Generation can be cut off mid-tag by the runaway guard or a token limit.
        val calls = ToolCallParser.extractToolCalls("Cooling down. <TOOL>decreaseTemperature(all)</TOOL")
        assertEquals("decreaseTemperature", calls.single().toolName)
    }

    @Test
    fun `the native JSON tool-call format is extracted`() {
        val calls = ToolCallParser.extractToolCalls(
            """<tool_call>{"name": "setSeatHeater", "arguments": {"level": 2}}</tool_call>"""
        )
        assertEquals("setSeatHeater", calls.single().toolName)
        assertTrue("expected the level in the args, got '${calls.single().args}'", calls.single().args.contains("2"))
    }

    @Test
    fun `plain conversational text produces no calls`() {
        assertTrue(ToolCallParser.extractToolCalls("The cabin is already at 72 degrees.").isEmpty())
    }

    @Test
    fun `an unrecognised bare function call is not executed without a registry`() {
        // The bare-function fallback only accepts names the registry knows. With no Koin container
        // in a unit test, nothing resolves, so nothing is executed -- which is the safe direction.
        assertTrue(ToolCallParser.extractToolCalls("launchRocket(now)").isEmpty())
    }

    @Test
    fun `stripping removes a complete tag and leaves the spoken text`() {
        assertEquals(
            "Warming things up for you.",
            ToolCallParser.stripToolTags("Warming things up for you. <TOOL>increaseTemperature(all)</TOOL>")
        )
    }

    @Test
    fun `stripping removes several tags`() {
        assertEquals(
            "On it.",
            ToolCallParser.stripToolTags("On it. <TOOL>increaseTemperature(all)</TOOL> <TOOL>playMusic()</TOOL>")
        )
    }

    @Test
    fun `stripping removes a JSON tool call`() {
        assertEquals(
            "Turning the seat heater on.",
            ToolCallParser.stripToolTags(
                """Turning the seat heater on. <tool_call>{"name": "setSeatHeater"}</tool_call>"""
            )
        )
    }

    @Test
    fun `stripping removes a tag that is still being streamed`() {
        // Mid-stream the model has emitted only part of the tag; none of it may reach TTS.
        for (partial in listOf("<", "<T", "<TO", "<TOOL", "<TOOL>", "<TOOL>increaseTem")) {
            assertEquals(
                "stripping '$partial' must leave only the prose",
                "Warming up.",
                ToolCallParser.stripToolTags("Warming up. $partial")
            )
        }
    }

    @Test
    fun `stripping leaves plain text untouched`() {
        val text = "The cabin is already at 72 degrees."
        assertEquals(text, ToolCallParser.stripToolTags(text))
    }

    @Test
    fun `stripping a response that is only a tool call yields empty text`() {
        assertEquals("", ToolCallParser.stripToolTags("<TOOL>stopMusic()</TOOL>"))
    }

    @Test
    fun `stripping trims the surrounding whitespace a removed tag leaves behind`() {
        assertEquals("Done.", ToolCallParser.stripToolTags("  Done.  <TOOL>stopMusic()</TOOL>  "))
    }

    @Test
    fun `extraction and stripping agree on the same response`() {
        val response = "Cooling the cabin. <TOOL>decreaseTemperature(all)</TOOL>"
        assertEquals("decreaseTemperature", ToolCallParser.extractToolCalls(response).single().toolName)
        assertEquals("Cooling the cabin.", ToolCallParser.stripToolTags(response))
    }
}
