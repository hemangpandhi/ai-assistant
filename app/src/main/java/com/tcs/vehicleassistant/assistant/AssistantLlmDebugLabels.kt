package com.tcs.vehicleassistant.assistant

import com.assistant.api.llm.LlmSessionPort
import com.tcs.vehicleassistant.LocalLLMActivity
import java.io.File

/**
 * Shared model / backend label strings for XML Polestar tags and Compose debug chrome.
 */
object AssistantLlmDebugLabels {
    fun modelLabel(llmSession: LlmSessionPort): String =
        if (LocalLLMActivity.isCloudModelActive) {
            "${LocalLLMActivity.currentCloudModelName} ☁️"
        } else {
            val modelName = File(llmSession.currentModelPath).nameWithoutExtension
            modelName.ifEmpty { "Gemma 4 E2B" }
        }

    fun backendLabel(llmSession: LlmSessionPort): String =
        if (LocalLLMActivity.isCloudModelActive) {
            "Backend: Cloud"
        } else {
            "Backend: ${llmSession.activeBackendString}"
        }
}
