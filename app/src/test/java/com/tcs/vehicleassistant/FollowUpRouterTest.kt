package com.tcs.vehicleassistant.utils

import com.tcs.vehicleassistant.MemoryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpRouterTest {

    @Test
    fun extractNumberedOptions_parsesCommaSeparatedList() {
        val text = "I found these options nearby: 1. Sensō-ji Temple, 2. Tokyo Skytree, 3. Meiji Shrine. Which one?"
        val options = FollowUpRouter.extractNumberedOptions(text)
        assertEquals(3, options.size)
        assertTrue(options[1].contains("Skytree"))
    }

    @Test
    fun resolveDirectTool_secondOneNavigates() {
        val last = "I found these options nearby: 1. Olive Garden, 2. Mario's, 3. Sushi Place. Which one?"
        val tool = FollowUpRouter.resolveDirectTool("the second one", last)
        assertNotNull(tool)
        assertTrue(tool!!.contains("Mario"))
    }

    @Test
    fun resolveDirectTool_gasStationAffirmative() {
        val last = "Should I find a nearby gas station?"
        val tool = FollowUpRouter.resolveDirectTool("yes", last)
        assertEquals("searchNearby(gas)", tool)
    }

    @Test
    fun resolveDirectTool_drowsyDriver() {
        val tool = FollowUpRouter.resolveDirectTool("the driver is falling asleep", "")
        assertEquals("handleDrowsyDriving()", tool)
    }

    @Test
    fun resolveDirectTool_unrelatedQueryReturnsNull() {
        assertNull(FollowUpRouter.resolveDirectTool("tell me a joke", "Hello there"))
    }

    @Test
    fun isAffirmative_stillWorksForSeatHeater() {
        val tool = FollowUpRouter.resolveDirectTool("yes", "Would you like me to turn on the seat heater?")
        assertEquals("setSeatHeater(2)", tool)
    }

    @Test
    fun resolveDirectCommand_acOn() {
        assertEquals("turnOnAC()", FollowUpRouter.resolveDirectCommand("please turn on the ac"))
    }

    @Test
    fun resolveDirectCommand_increaseTemp() {
        assertEquals("increaseTemperature(2)", FollowUpRouter.resolveDirectCommand("make it warmer"))
    }

    @Test
    fun resolveDirectCommand_setTemp() {
        assertEquals("setTemperature(70)", FollowUpRouter.resolveDirectCommand("set temperature to 70"))
    }

    @Test
    fun resolveDirectCommand_playMusic() {
        assertEquals("playMusic(music)", FollowUpRouter.resolveDirectTool("play music", ""))
    }
}
