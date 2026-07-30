package com.assistant.api.face

import org.junit.Assert.assertTrue
import org.junit.Test

class FaceCueCatalogTest {
    @Test
    fun promptFragment_listsSlotsAndIds() {
        val prompt = FaceCueCatalog.llmPromptFragment()
        assertTrue(prompt.contains("left_eye"))
        assertTrue(prompt.contains("right_accent"))
        assertTrue(FaceCueCatalog.iconIds.contains("sunny"))
        assertTrue(FaceCueCatalog.iconIds.contains("music"))
        assertTrue(prompt.contains("sunny"))
    }
}
