package com.assistant.ui.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceMoodResolverTest {

    @Test
    fun listeningBeatsAffective() {
        assertEquals(
            AssistantMoodId.Listening,
            FaceMoodResolver.resolve(AssistantMoodId.Listening, AssistantMoodId.Happy),
        )
    }

    @Test
    fun thinkingBeatsAffective() {
        assertEquals(
            AssistantMoodId.Thinking,
            FaceMoodResolver.resolve(AssistantMoodId.Thinking, AssistantMoodId.Sad),
        )
    }

    @Test
    fun speakingAllowsAffectiveTint() {
        assertEquals(
            AssistantMoodId.Happy,
            FaceMoodResolver.resolve(AssistantMoodId.Speaking, AssistantMoodId.Happy),
        )
        assertEquals(
            AssistantMoodId.Speaking,
            FaceMoodResolver.resolve(AssistantMoodId.Speaking, null),
        )
    }

    @Test
    fun idleShowsAffective() {
        assertEquals(
            AssistantMoodId.Excited,
            FaceMoodResolver.resolve(AssistantMoodId.Idle, AssistantMoodId.Excited),
        )
        assertEquals(
            AssistantMoodId.Idle,
            FaceMoodResolver.resolve(AssistantMoodId.Idle, null),
        )
    }

    @Test
    fun pipelineVsAffectiveFlags() {
        assertEquals(true, FaceMoodResolver.isPipeline(AssistantMoodId.Listening))
        assertEquals(false, FaceMoodResolver.isPipeline(AssistantMoodId.Happy))
        assertEquals(true, FaceMoodResolver.isAffective(AssistantMoodId.Happy))
        assertEquals(false, FaceMoodResolver.isAffective(AssistantMoodId.Thinking))
    }
}
