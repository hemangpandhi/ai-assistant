package com.tcs.vehicleassistant.assistant

import com.tcs.vehicleassistant.llm.LLMManager

import com.tcs.vehicleassistant.LocalLLMActivity
import java.io.File

/**
 * Shared model / backend label strings for XML Polestar tags and Compose debug chrome.
 */
object AssistantLlmDebugLabels {
    fun modelLabel(): String =
        if (LocalLLMActivity.isCloudModelActive) {
            "${LocalLLMActivity.currentCloudModelName} ☁️"
        } else {
            val modelName = File(LLMManager.currentModelPath).nameWithoutExtension
            modelName.ifEmpty { "Gemma 4 E2B" }
        }

    fun backendLabel(): String =
        if (LocalLLMActivity.isCloudModelActive) {
            "Backend: Cloud"
        } else {
            "Backend: ${LLMManager.activeBackendString}"
        }
}
