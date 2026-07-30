package com.tcs.vehicleassistant.data.tools

import android.content.Context
import android.content.Intent
import com.assistant.api.tools.ToolCatalog
import com.tcs.vehicleassistant.ToolManager

/**
 * Adapts refactor [ToolManager] to [ToolCatalog] without modifying the singleton.
 */
class ToolManagerCatalog(
    private val toolManager: ToolManager,
) : ToolCatalog {

    override val slidingWindowMaxChars: Int
        get() = toolManager.slidingWindowMaxChars

    override fun find(rawToolCall: String): ToolCatalog.ToolInfo? {
        val def = toolManager.getToolDefinition(rawToolCall) ?: return null
        return ToolCatalog.ToolInfo(
            invocationKey = rawToolCall,
            requiresConfirmation = def.requiresConfirmation,
            requiresAgenticLoop = def.requiresAgenticLoop,
        )
    }

    override fun llmToolsPrompt(userQuery: String, conversationalContext: String): String =
        toolManager.getLlmToolsPrompt(userQuery, conversationalContext)

    override suspend fun execute(
        context: Context,
        rawToolCall: String,
        intentHandler: ((Intent) -> Unit)?,
        enforcePromptAllowList: Boolean,
    ): String = toolManager.executeToolCall(
        context = context,
        rawToolCall = rawToolCall,
        enforcePromptAllowList = enforcePromptAllowList,
        intentHandler = intentHandler,
    )
}
