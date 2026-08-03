package com.tcs.vehicleassistant.core

import java.io.File

/**
 * Resolves which on-device LiteRT model file to load.
 * Prefers the user/OEM selected path; falls back to the default edge model, then any candidate.
 */
object LocalModelResolver {

    fun resolve(
        savedPath: String?,
        defaultPath: String = AssistantConfig.Llm.DEFAULT_MODEL_PATH,
        defaultFilename: String = AssistantConfig.Llm.DEFAULT_MODEL_FILENAME,
        candidates: List<File> = emptyList(),
    ): File {
        readableModel(savedPath)?.let { return it }

        val explicitDefault = File(defaultPath)
        if (isReadableModel(explicitDefault)) return explicitDefault

        candidates.firstOrNull {
            it.name.equals(defaultFilename, ignoreCase = true) && isReadableModel(it)
        }?.let { return it }


        candidates.firstOrNull { isReadableModel(it) }?.let { return it }

        return explicitDefault
    }

    private fun readableModel(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        return file.takeIf { isReadableModel(it) }
    }

    private fun isReadableModel(file: File): Boolean =
        file.exists() && file.canRead() && file.length() > 0L
}
