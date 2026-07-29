package com.tcs.vehicleassistant.utils

import com.tcs.vehicleassistant.domain.FollowUpUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectCabinCommandRouterTest {

    @Test
    fun resolve_acOn() {
        assertEquals("turnOnAC()", DirectCabinCommandRouter.resolve("please turn on the ac"))
    }

    @Test
    fun resolve_increaseTemp() {
        assertEquals("increaseTemperature(2)", DirectCabinCommandRouter.resolve("make it warmer"))
    }

    @Test
    fun resolve_setTemp() {
        assertEquals("setTemperature(70)", DirectCabinCommandRouter.resolve("set temperature to 70"))
    }

    @Test
    fun resolve_playMusic_viaUseCase() {
        assertEquals("playMusic(music)", FollowUpUseCase().resolve("play music", ""))
    }

    @Test
    fun resolve_unknownReturnsNull() {
        assertEquals(null, DirectCabinCommandRouter.resolve("tell me a joke"))
    }
}

class StreamingToolCallParserTest {

    @Test
    fun extractComplete_parsesClosedTags() {
        val calls = StreamingToolCallParser.extractCompleteToolCalls(
            "Sure. <TOOL>setTemperature(72)</TOOL> Done."
        )
        assertEquals(1, calls.size)
        assertEquals("setTemperature(72)", calls[0].invocation)
    }

    @Test
    fun extractComplete_ignoresIncomplete() {
        assertTrue(
            StreamingToolCallParser.extractCompleteToolCalls("Working. <TOOL>playMusic(").isEmpty()
        )
    }

    @Test
    fun parsedCall_exposesInvocation() {
        val call = StreamingToolCallParser.extractCompleteToolCalls("<TOOL>nextTrack()</TOOL>").single()
        assertEquals("nextTrack()", call.invocation)
    }
}
