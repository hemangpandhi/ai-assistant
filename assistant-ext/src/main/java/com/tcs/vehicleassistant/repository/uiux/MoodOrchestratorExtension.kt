package com.tcs.vehicleassistant.repository.uiux

import com.assistant.ui.assistant.api.AssistantMoodId
import com.tcs.vehicleassistant.utils.MoodTagParser

/**
 * Mood / face-emotion plugin — wraps [MoodTagParser] behind [OrchestratorExtension].
 */
class MoodOrchestratorExtension : OrchestratorExtension {

    override fun onToken(accumulatedText: String, emitMood: (AssistantMoodId) -> Unit) {
        MoodTagParser.extractAffectiveMood(accumulatedText)?.let(emitMood)
    }

    override fun onDone(rawResponse: String, emitMood: (AssistantMoodId) -> Unit) {
        MoodTagParser.extractAffectiveMood(rawResponse)?.let(emitMood)
    }

    override fun stripDecorations(rawResponse: String): String =
        MoodTagParser.stripMoodTags(rawResponse)

    override fun moodForDirectTool(toolCall: String, userQuery: String): AssistantMoodId? =
        MoodTagParser.heuristicForTool(toolCall, userQuery)
}
