package com.assistant.ui.assistant.ui.immersive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPlacementTest {

    @Test
    fun parsesCanonicalKeys() {
        assertEquals(AssistantPlacement.Fullscreen, AssistantPlacement.parse("fullscreen"))
        assertEquals(AssistantPlacement.LeftCard, AssistantPlacement.parse("left"))
        assertEquals(AssistantPlacement.RightCard, AssistantPlacement.parse("right"))
        assertEquals(AssistantPlacement.BottomCard, AssistantPlacement.parse("bottom"))
    }

    @Test
    fun parsesAliases() {
        assertEquals(AssistantPlacement.Fullscreen, AssistantPlacement.parse("overlay"))
        assertEquals(AssistantPlacement.Fullscreen, AssistantPlacement.parse("full"))
        assertEquals(AssistantPlacement.Fullscreen, AssistantPlacement.parse("immersive"))
        assertEquals(AssistantPlacement.LeftCard, AssistantPlacement.parse("side_left"))
        assertEquals(AssistantPlacement.LeftCard, AssistantPlacement.parse("left_card"))
        assertEquals(AssistantPlacement.RightCard, AssistantPlacement.parse("side_right"))
        assertEquals(AssistantPlacement.RightCard, AssistantPlacement.parse("card_right"))
        assertEquals(AssistantPlacement.BottomCard, AssistantPlacement.parse("card_bottom"))
        assertEquals(AssistantPlacement.BottomCard, AssistantPlacement.parse("bottom_sheet"))
    }

    @Test
    fun ignoresCaseAndWhitespace() {
        assertEquals(AssistantPlacement.LeftCard, AssistantPlacement.parse("  LEFT "))
        assertEquals(AssistantPlacement.RightCard, AssistantPlacement.parse("Right"))
        assertEquals(AssistantPlacement.Fullscreen, AssistantPlacement.parse("FullScreen"))
    }

    @Test
    fun unknownReturnsNull() {
        assertNull(AssistantPlacement.parse(null))
        assertNull(AssistantPlacement.parse(""))
        assertNull(AssistantPlacement.parse("banana"))
    }

    @Test
    fun adbKeysAreUnique() {
        val keys = AssistantPlacement.entries.map { it.adbKey }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.contains("fullscreen"))
        assertTrue(keys.contains("left"))
        assertTrue(keys.contains("right"))
        assertTrue(keys.contains("bottom"))
    }

    @Test
    fun defaultIsFullscreen() {
        assertEquals(AssistantPlacement.Fullscreen, AssistantPlacement.Default)
        assertFalse(AssistantPlacement.Fullscreen.isCard)
        assertTrue(AssistantPlacement.LeftCard.isCard)
        assertTrue(AssistantPlacement.RightCard.isCard)
        assertTrue(AssistantPlacement.BottomCard.isCard)
    }
}
