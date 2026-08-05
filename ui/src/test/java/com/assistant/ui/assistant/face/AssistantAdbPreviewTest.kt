package com.assistant.ui.assistant.face

import com.assistant.ui.assistant.api.AssistantFaceCueIcon
import com.assistant.ui.assistant.api.AssistantFaceCues
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AssistantAdbPreviewTest {

    @Before
    @After
    fun reset() {
        AssistantAdbPreview.clearAll()
    }

    @Test
    fun isHolding_falseWhenEmpty() {
        assertFalse(AssistantAdbPreview.isHolding())
    }

    @Test
    fun isHolding_trueForMoodPreview() {
        assertTrue(AssistantMoodPreview.setFromRaw("happy"))
        assertTrue(AssistantAdbPreview.isHolding())
    }

    @Test
    fun isHolding_trueForFaceCuePreview() {
        AssistantFaceCuePreview.set(
            AssistantFaceCues(leftEye = AssistantFaceCueIcon.Sunny),
        )
        assertTrue(AssistantAdbPreview.isHolding())
    }

    @Test
    fun clearAll_releasesHold() {
        AssistantMoodPreview.setFromRaw("excited")
        AssistantFaceCuePreview.set(
            AssistantFaceCues(mouth = AssistantFaceCueIcon.Music),
        )
        assertTrue(AssistantAdbPreview.isHolding())
        AssistantAdbPreview.clearAll()
        assertFalse(AssistantAdbPreview.isHolding())
    }
}
