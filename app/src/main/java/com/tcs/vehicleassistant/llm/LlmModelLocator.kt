package com.tcs.vehicleassistant.llm

import android.content.Context
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.LocalModelResolver
import java.io.File

/**
 * Single responsibility: locate the on-device LiteRT model file from prefs + known directories.
 */
object LlmModelLocator {

    fun collectCandidates(context: Context): List<File> {
        val internalFiles = context.filesDir?.listFiles()?.toList() ?: emptyList()
        val externalFiles = context.getExternalFilesDir(null)?.listFiles()?.toList() ?: emptyList()
        val tmpFiles = File("/data/local/tmp/llm/").listFiles()?.toList() ?: emptyList()
        return (internalFiles + externalFiles + tmpFiles).filter {
            it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm")
        }
    }

    fun resolveSelectedModel(context: Context): File {
        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val savedModelPath = prefs.getString(AssistantConfig.Prefs.SELECTED_MODEL, null)
        return LocalModelResolver.resolve(
            savedPath = savedModelPath,
            candidates = collectCandidates(context),
        )
    }

    fun resolveBackendChoice(context: Context, requested: String): String {
        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(AssistantConfig.Prefs.BACKEND_CHOICE, AssistantConfig.Backend.AUTO)
            ?: AssistantConfig.Backend.AUTO
        return if (requested != AssistantConfig.Backend.AUTO) requested else saved
    }
}
