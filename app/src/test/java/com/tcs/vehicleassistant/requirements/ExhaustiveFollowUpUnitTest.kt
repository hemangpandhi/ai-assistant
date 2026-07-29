package com.tcs.vehicleassistant.requirements

import com.tcs.vehicleassistant.utils.FollowUpRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Follow-up / multi-turn matrix from use-cases.md, demo_script, and WOW docs.
 */
class ExhaustiveFollowUpUnitTest {

    @Test
    fun seatHeaterAffirmatives() {
        val last = "Would you like me to turn on the seat heater?"
        for (yes in listOf("yes", "yeah", "yep", "sure", "ok", "okay", "do it", "yes please")) {
            assertEquals("setSeatHeater(2)", FollowUpRouter.resolveDirectTool(yes, last))
        }
    }

    @Test
    fun seatHeaterDecline() {
        val last = "Would you like me to turn on the seat heater?"
        assertNull(FollowUpRouter.resolveDirectTool("no", last))
    }

    @Test
    fun gasStationAffirmative() {
        assertEquals(
            "searchNearby(gas)",
            FollowUpRouter.resolveDirectTool("yes", "Should I find a nearby gas station?"),
        )
    }

    @Test
    fun numberedListPicks() {
        val last = "I found these options nearby: 1. Sensō-ji Temple, 2. Tokyo Skytree, 3. Meiji Shrine. Which one?"
        assertTrue(FollowUpRouter.resolveDirectTool("the first one", last)!!.contains("Sens"))
        assertTrue(FollowUpRouter.resolveDirectTool("the second one", last)!!.contains("Skytree"))
        assertTrue(FollowUpRouter.resolveDirectTool("the third one", last)!!.contains("Meiji"))
        assertTrue(FollowUpRouter.resolveDirectTool("2", last)!!.contains("Skytree"))
    }

    @Test
    fun drowsyDriverPhrases() {
        for (q in listOf(
            "the driver is falling asleep",
            "the driver is asleep",
            "i am getting sleepy",
            "i feel drowsy",
            "drowsy driving",
        )) {
            val tool = FollowUpRouter.resolveDirectTool(q, "")
            assertNotNull("expected drowsy tool for '$q'", tool)
            assertEquals("handleDrowsyDriving()", tool)
        }
    }

    @Test
    fun unrelatedDoesNotFire() {
        assertNull(FollowUpRouter.resolveDirectTool("tell me a joke", "Hello there"))
        assertNull(FollowUpRouter.resolveDirectTool("what time is it", "Would you like the seat heater?"))
    }
}
