package com.test.design.presentation.assistant.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStateTest {

    @Test
    fun statesMatchBlueprint() {
        val names = AssistantState.entries.map { it.name }.toSet()
        assertTrue(
            names.containsAll(
                listOf("IDLE", "LISTENING", "THINKING", "SPEAKING", "ERROR"),
            ),
        )
        assertEquals(5, AssistantState.entries.size)
    }

    @Test
    fun geometryMatchesBlueprint() {
        assertEquals(420f, CarAssistantGeometry.CapsuleWidth.value)
        assertEquals(180f, CarAssistantGeometry.CapsuleHeight.value)
        assertEquals(210f, CarAssistantGeometry.CenterX.value)
        assertEquals(72f, CarAssistantGeometry.EyesBaselineY.value)
        assertEquals(92.5f, CarAssistantGeometry.EyeHalfSpacing.value)
        assertEquals(117f, CarAssistantGeometry.MouthBaselineY.value)
        assertEquals(8f, CarAssistantGeometry.MouthStroke.value)
        assertEquals(32f, CarAssistantGeometry.BottomInset.value)
        assertEquals(64f, CarAssistantGeometry.BugSize.value)
    }
}
