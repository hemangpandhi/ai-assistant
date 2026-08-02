package com.assistant.ui.assistant.face

import com.assistant.ui.assistant.api.AssistantFaceCueIcon
import com.assistant.ui.assistant.api.AssistantFaceCues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantFaceCuePreviewTest {

    @Test
    fun set_and_clear() {
        AssistantFaceCuePreview.clear()
        assertNull(AssistantFaceCuePreview.current())

        val cues = AssistantFaceCues(
            leftEye = AssistantFaceCueIcon.Sunny,
            mouth = AssistantFaceCueIcon.Music,
        )
        AssistantFaceCuePreview.set(cues)
        assertEquals(cues, AssistantFaceCuePreview.current())
        assertEquals(
            "left_eye=sunny right_eye=none mouth=music left_accent=none right_accent=none",
            AssistantFaceCuePreview.describe(),
        )

        AssistantFaceCuePreview.set(AssistantFaceCues.Empty)
        assertNull(AssistantFaceCuePreview.current())
        assertEquals("off", AssistantFaceCuePreview.describe())
    }
}
