package com.tcs.vehicleassistant.assistant

import com.assistant.api.face.FaceCueCatalog

/**
 * LLM face-cue vocabulary for prompt / tool injection (host-neutral catalog).
 *
 * Append [promptFragment] to the system prompt (or tool preamble) so the model
 * can emit `<face …/>` tags consumed by [VehicleAgentAssistantBackend].
 */
object FaceCueLlmBridge {
    val allowedIconIds: List<String> = FaceCueCatalog.iconIds

    fun promptFragment(): String = FaceCueCatalog.llmPromptFragment()
}
