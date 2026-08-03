package com.assistant.ui.assistant.api

/**
 * Two-layer face mood:
 * - **Pipeline** (harness): Idle / Listening / Thinking / Speaking / Searching / Reading
 * - **Affective** (LLM or heuristics): Happy / Sad / Excited / Bored / Drowsy / Tired
 *
 * Precedence: active pipeline phases win; affective tints Speaking and Idle.
 */
object FaceMoodResolver {

    fun isPipeline(mood: AssistantMoodId): Boolean = when (mood) {
        AssistantMoodId.Idle,
        AssistantMoodId.Listening,
        AssistantMoodId.Thinking,
        AssistantMoodId.Speaking,
        AssistantMoodId.Searching,
        AssistantMoodId.Reading,
        -> true
        else -> false
    }

    fun isAffective(mood: AssistantMoodId): Boolean = when (mood) {
        AssistantMoodId.Idle,
        AssistantMoodId.Listening,
        AssistantMoodId.Thinking,
        AssistantMoodId.Speaking,
        AssistantMoodId.Searching,
        AssistantMoodId.Reading,
        -> false
        else -> true
    }

    /**
     * @param pipeline authoritative turn-taking mood from the harness
     * @param affective optional emotion from LLM `<MOOD>` or FollowUp heuristics
     */
    fun resolve(
        pipeline: AssistantMoodId,
        affective: AssistantMoodId?,
    ): AssistantMoodId {
        val affect = affective?.takeIf { isAffective(it) }
        return when (pipeline) {
            AssistantMoodId.Listening,
            AssistantMoodId.Thinking,
            AssistantMoodId.Searching,
            AssistantMoodId.Reading,
            -> pipeline

            // Keep lip-sync amplitude separate; allow warm/empathetic face while speaking.
            AssistantMoodId.Speaking -> affect ?: AssistantMoodId.Speaking

            AssistantMoodId.Idle -> affect ?: AssistantMoodId.Idle

            // Harness error / explicit sad base stays sad unless LLM upgrades to softer empathy.
            AssistantMoodId.Sad -> affect ?: AssistantMoodId.Sad

            else -> affect ?: pipeline
        }
    }
}
