package com.tcs.vehicleassistant.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeLevelResolverTest {

    @Test
    fun upIsRelativeStep_notAbsoluteFivePercent() {
        // max=20 → index 1 is 5%. "up" must raise to 2 (10%), not stay/set 5%.
        val plan = VolumeLevelResolver.plan("up", currentIndex = 1, maxIndex = 20)
        assertTrue(plan.relative)
        assertEquals(true, plan.increasing)
        assertEquals(2, plan.targetIndex)
        assertEquals(10, plan.targetPct)
        assertEquals(
            "I've increased the volume to 10%.",
            VolumeLevelResolver.feedback(plan, appliedIndex = 2),
        )
    }

    @Test
    fun increaseVolumeArgBlankUsesToolCallHint() {
        val plan = VolumeLevelResolver.plan("", currentIndex = 1, maxIndex = 20, toolCall = "setVolumeLevel()")
        assertEquals(2, plan.targetIndex)
    }

    @Test
    fun downIsRelativeStep() {
        val plan = VolumeLevelResolver.plan("down", currentIndex = 4, maxIndex = 20)
        assertEquals(3, plan.targetIndex)
        assertEquals(
            "I've decreased the volume to 15%.",
            VolumeLevelResolver.feedback(plan, appliedIndex = 3),
        )
    }

    @Test
    fun alreadyAtMaxReportsHonestly() {
        val plan = VolumeLevelResolver.plan("up", currentIndex = 20, maxIndex = 20)
        assertEquals(20, plan.targetIndex)
        assertEquals(
            "Volume is already at maximum (100%).",
            VolumeLevelResolver.feedback(plan, appliedIndex = 20),
        )
    }

    @Test
    fun applyFailureDoesNotClaimNewLevel() {
        val plan = VolumeLevelResolver.plan("up", currentIndex = 1, maxIndex = 20)
        assertEquals(
            "I couldn't change the volume; it's still at 5%.",
            VolumeLevelResolver.feedback(plan, appliedIndex = 1),
        )
    }

    @Test
    fun absolutePercentStillWorks() {
        val plan = VolumeLevelResolver.plan("50%", currentIndex = 1, maxIndex = 20)
        assertFalse(plan.relative)
        assertEquals(10, plan.targetIndex)
        assertEquals(
            "I've set the volume to 50%.",
            VolumeLevelResolver.feedback(plan, appliedIndex = 10),
        )
    }

    @Test
    fun plusPercentIsRelative() {
        val plan = VolumeLevelResolver.plan("+10%", currentIndex = 2, maxIndex = 20)
        assertTrue(plan.relative)
        assertEquals(4, plan.targetIndex)
    }

    @Test
    fun relativeStepIsAtLeastOne() {
        assertEquals(1, VolumeLevelResolver.relativeStep(10))
        assertEquals(1, VolumeLevelResolver.relativeStep(20))
        assertEquals(5, VolumeLevelResolver.relativeStep(100))
    }
}
