package com.tcs.vehicleassistant.repository.uiux

import com.assistant.ui.assistant.api.AssistantMoodId

/**
 * Plugin seam for [com.tcs.vehicleassistant.repository.UiUxAgentOrchestrator].
 *
 * Keep mood / telemetry / response post-processing out of the orchestrator core so
 * new UI/UX behavior lands as additive extensions instead of editing the host loop.
 */
interface OrchestratorExtension {
    /** Optionally rewrite the user query before understand/act. */
    fun beforeQuery(query: String): String = query

    /** Called for each streaming accumulation; may emit affective mood. */
    fun onToken(accumulatedText: String, emitMood: (AssistantMoodId) -> Unit) = Unit

    /** Called once when the model finishes a turn; may emit affective mood. */
    fun onDone(rawResponse: String, emitMood: (AssistantMoodId) -> Unit) = Unit

    /** Strip extension-owned decorations (e.g. `<MOOD>` tags) for agentic / display checks. */
    fun stripDecorations(rawResponse: String): String = rawResponse

    /** Heuristic mood for zero-LLM / direct tool paths. */
    fun moodForDirectTool(toolCall: String, userQuery: String): AssistantMoodId? = null

    fun onReset() = Unit
}
