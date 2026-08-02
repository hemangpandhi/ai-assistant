package com.assistant.ui.assistant.mvi

import com.assistant.ui.assistant.api.AssistantSessionEvent
import com.assistant.ui.assistant.api.AssistantMoodId
import com.assistant.ui.assistant.api.AssistantSpeaker
import com.assistant.ui.assistant.dialogue.DialogueSpeaker
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
        assertEquals("", state.transcript)
        assertEquals(DialogueSpeaker.User, state.speaker)
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

    @Test
    fun faceCuesChanged_updatesState() {
        val cues = com.assistant.ui.assistant.api.AssistantFaceCues(
            leftEye = com.assistant.ui.assistant.api.AssistantFaceCueIcon.Sunny,
            mouth = com.assistant.ui.assistant.api.AssistantFaceCueIcon.Music,
        )
        val (state, _) = reduceStage(
            StageState(visible = true),
            StageIntent.BackendEvent(AssistantSessionEvent.FaceCuesChanged(cues)),
        )
        assertEquals(cues, state.faceCues)
    }

    @Test
    fun faceCuesChanged_emptyClears() {
        val prior = StageState(
            faceCues = com.assistant.ui.assistant.api.AssistantFaceCues(
                leftEye = com.assistant.ui.assistant.api.AssistantFaceCueIcon.Rain,
            ),
        )
        val (state, _) = reduceStage(
            prior,
            StageIntent.BackendEvent(
                AssistantSessionEvent.FaceCuesChanged(
                    com.assistant.ui.assistant.api.AssistantFaceCues.Empty,
                ),
            ),
        )
        assertEquals(null, state.faceCues)
    }

    @Test
    fun sessionComplete_hidesAndEmitsFinishStop() {
        val (state, effects) = reduceStage(
            StageState(visible = true, mood = AssistantMood.Speaking),
            StageIntent.BackendEvent(AssistantSessionEvent.SessionComplete),
        )
        assertEquals(false, state.visible)
        assertEquals(AssistantMood.Idle, state.mood)
        assertTrue(effects.any { it is StageEffect.FinishSession })
        assertTrue(effects.any { it is StageEffect.StopSession })
    }
}
