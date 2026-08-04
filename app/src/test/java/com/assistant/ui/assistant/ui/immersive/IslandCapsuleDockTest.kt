package com.assistant.ui.assistant.ui.immersive

import androidx.compose.ui.unit.dp
import com.assistant.ui.assistant.face.AssistantMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandCapsuleDockTest {

    @Test
    fun compactWhenNoTranscript() {
        assertEquals(
            IslandSizeClass.Compact,
            resolveIslandSizeClass(AssistantMood.Idle, hasTranscript = false),
        )
        assertEquals(
            IslandSizeClass.Compact,
            resolveIslandSizeClass(AssistantMood.Listening, hasTranscript = false),
        )
        assertEquals(
            IslandSizeClass.Compact,
            resolveIslandSizeClass(AssistantMood.Thinking, hasTranscript = false),
        )
        assertEquals(
            IslandSizeClass.Compact,
            resolveIslandSizeClass(AssistantMood.Speaking, hasTranscript = false),
        )
    }

    @Test
    fun transcriptForcesExpanded() {
        assertEquals(
            IslandSizeClass.Expanded,
            resolveIslandSizeClass(AssistantMood.Listening, hasTranscript = true),
        )
        assertEquals(
            IslandSizeClass.Expanded,
            resolveIslandSizeClass(AssistantMood.Speaking, hasTranscript = true),
        )
        assertEquals(
            IslandSizeClass.Expanded,
            resolveIslandSizeClass(AssistantMood.Idle, hasTranscript = true),
        )
    }

    @Test
    fun shortTranscriptWidensButStaysUnderCap() {
        val face = 88.dp
        val short = estimateIslandExpandedWidth(
            charCount = "i didn't catch that".length,
            maxWidth = 800.dp,
            faceSlot = face,
        )
        val longer = estimateIslandExpandedWidth(
            charCount = 48,
            maxWidth = 800.dp,
            faceSlot = face,
        )
        assertTrue(short > 168.dp)
        assertTrue(longer > short)
        assertTrue(longer <= 720.dp)
    }
}
