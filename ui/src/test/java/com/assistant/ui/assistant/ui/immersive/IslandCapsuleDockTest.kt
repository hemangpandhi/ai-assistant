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
        // Cap = 60% of an 800dp stage.
        val cap = 480.dp
        val short = estimateIslandExpandedWidth(
            charCount = "i didn't catch that".length,
            maxWidth = cap,
        )
        val longer = estimateIslandExpandedWidth(
            charCount = 48,
            maxWidth = cap,
        )
        val overflow = estimateIslandExpandedWidth(
            charCount = 120,
            maxWidth = cap,
        )
        assertTrue(short > 168.dp)
        assertTrue(longer > short)
        assertTrue(longer <= cap)
        assertEquals(cap, overflow)
    }
}
