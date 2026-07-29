package com.tcs.vehicleassistant.data.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class UiUxToolDispatcherTest {
    @Test
    fun canonicalize_rewritesHvacAliasCommandName() {
        assertEquals(
            "turnOnAC()",
            UiUxToolDispatcher.canonicalizeRawToolCall("turnOnAc()"),
        )
        assertEquals(
            "turnOnAC(fan=2)",
            UiUxToolDispatcher.canonicalizeRawToolCall("turnOnAc(fan=2)"),
        )
    }

    @Test
    fun canonicalize_passthroughCanonicalAndUnknown() {
        assertEquals("turnOnAC()", UiUxToolDispatcher.canonicalizeRawToolCall("turnOnAC()"))
        assertEquals("playMusic()", UiUxToolDispatcher.canonicalizeRawToolCall("playMusic()"))
    }

    @Test
    fun canonicalize_preservesToolTagsWhenPresent() {
        assertEquals(
            "<TOOL>turnOnAC()</TOOL>",
            UiUxToolDispatcher.canonicalizeRawToolCall("<TOOL>turnOnAc()</TOOL>"),
        )
    }
}
