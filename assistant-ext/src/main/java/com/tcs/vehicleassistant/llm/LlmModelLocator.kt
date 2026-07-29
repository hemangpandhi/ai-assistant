package com.tcs.vehicleassistant.llm

import android.content.Context
import java.io.File

/**
 * LiteRT model discovery for `/data/local/tmp/llm` + app storage (UI/UX extension).
 *
 * Keeps path precedence out of [com.tcs.vehicleassistant.LLMManager] so remote
 * refactor engine lifecycle edits rebase more cleanly.
 */
class LlmModelLocator {
    fun locate(context: Context, savedPath: String?): File? {
        val internalDir = context.filesDir
        val externalDir = context.getExternalFilesDir(null)
        val explicitModel = File("/data/local/tmp/llm/model.litertlm")
        val explicitQwen = File("/data/local/tmp/llm/Qwen2.5.litertlm")
        val explicitGemma = File("/data/local/tmp/llm/gemma-4-E2B-it.litertlm")

        val allFiles = listOfNotNull(internalDir?.listFiles(), externalDir?.listFiles())
            .flatMap { it.toList() }
            .toMutableList()

        if (explicitQwen.exists() && explicitQwen.canRead()) {
            allFiles.add(0, explicitQwen)
        }
        if (explicitModel.exists() && explicitModel.canRead()) {
            allFiles.add(explicitModel)
        }
        if (explicitGemma.exists() && explicitGemma.canRead()) {
            allFiles.add(explicitGemma)
        }

        val models = allFiles.filter {
            it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm")
        }

        var modelFile: File? = null
        if (savedPath != null && !savedPath.endsWith("model.litertlm")) {
            modelFile = File(savedPath)
        }
        if (modelFile == null || !modelFile.exists()) {
            modelFile = models.find { it.name.contains("gemma", ignoreCase = true) }
                ?: models.find { it.name.contains("qwen", ignoreCase = true) }
                ?: models.firstOrNull()
        }
        return modelFile?.takeIf { it.exists() && it.length() > 0 }
    }
}
