package com.assistant.api.tools

import android.content.Context
import android.content.Intent

/**
 * Tool catalog / execution port — adapters wrap host ToolManager.
 */
interface ToolCatalog {
    data class ToolInfo(
        val invocationKey: String,
        val requiresConfirmation: Boolean,
        val requiresAgenticLoop: Boolean,
    )

    /** Sliding prompt-budget window used by QueryPipeline. */
    val slidingWindowMaxChars: Int

    fun find(rawToolCall: String): ToolInfo?

    fun llmToolsPrompt(userQuery: String = "", conversationalContext: String = ""): String

    suspend fun execute(
        context: Context,
        rawToolCall: String,
        intentHandler: ((Intent) -> Unit)? = null,
        enforcePromptAllowList: Boolean = false,
    ): String
}
