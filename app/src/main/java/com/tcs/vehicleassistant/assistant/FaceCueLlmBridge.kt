package com.tcs.vehicleassistant.assistant

import com.assistant.api.face.FaceCueCatalog
import com.assistant.api.face.MoodCatalog

/**
 * LLM face-cue + affective mood vocabulary.
 *
 * Mood examples are taught via `vehicle_skills_registry.json` → `config.llm_few_shots`
 * (silent `<mood>…</mood>` tags). Tags are consumed by [VehicleAgentAssistantBackend].
 *
 * Do not mutate master-owned [com.tcs.vehicleassistant.ToolManager] fields from here.
 */
object FaceCueLlmBridge {
    val allowedIconIds: List<String> = FaceCueCatalog.iconIds
    val allowedMoodIds: List<String> = MoodCatalog.moodIds

    fun promptFragment(): String = buildString {
        append(MoodCatalog.llmPromptFragment())
        appendLine()
        append(FaceCueCatalog.llmPromptFragment())
    }

    /** No-op kept for call sites; hints live in registry few-shots. */
    fun installPromptHints() = Unit
}