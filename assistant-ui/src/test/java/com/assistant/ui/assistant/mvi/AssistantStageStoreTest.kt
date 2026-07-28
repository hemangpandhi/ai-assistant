package com.assistant.ui.assistant.mvi

import com.assistant.ui.assistant.api.AssistantSessionEvent
import com.assistant.ui.assistant.api.AssistantMoodId
import com.assistant.ui.assistant.api.AssistantSpeaker
import com.assistant.ui.assistant.face.AssistantMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStageStoreTest {

    @Test
    fun summon_setsListeningVisible() {
        val (state, effects) = reduceStage(StageState(), StageIntent.Summon)
        assertTrue(state.visible)
        assertEquals(AssistantMood.Listening, state.mood)
        assertTrue(effects.any { it is StageEffect.RequestListen })
    }

    @Test
    fun transcript_updatesState() {
        val (state, _) = reduceStage(
            StageState(visible = true),
            StageIntent.BackendEvent(
                AssistantSessionEvent.Transcript("Hello", AssistantSpeaker.Assistant),
            ),
        )
        assertEquals("Hello", state.transcript)
    }

    @Test
    fun moodChanged_mapsToUiMood() {
        val (state, _) = reduceStage(
            StageState(),
            StageIntent.BackendEvent(AssistantSessionEvent.MoodChanged(AssistantMoodId.Thinking)),
        )
        assertEquals(AssistantMood.Thinking, state.mood)
    }
}
