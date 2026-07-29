package com.tcs.vehicleassistant.domain

import android.content.Context
import android.content.Intent
import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.utils.StreamingToolCallParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Schedules tool execution (eager mid-stream or onDone).
 */
class ExecuteToolUseCase(
    private val toolManager: ToolManager,
) {
    data class ScheduledTool(
        val toolCall: String,
        val requiresConfirmation: Boolean,
        val deferred: kotlinx.coroutines.Deferred<String?>?,
    )

    fun parseComplete(raw: String) = StreamingToolCallParser.extractCompleteToolCalls(raw)

    fun schedule(
        scope: CoroutineScope,
        toolCall: String,
        onIntent: (Intent) -> Unit,
        execute: suspend (String) -> String?,
    ): ScheduledTool {
        val toolDef = toolManager.getToolDefinition(toolCall)
        if (toolDef?.requiresConfirmation == true) {
            return ScheduledTool(toolCall, requiresConfirmation = true, deferred = null)
        }
        val job = scope.async(Dispatchers.IO) {
            withTimeoutOrNull(10_000L) { execute(toolCall) }
                ?: "System Error: Tool execution timed out."
        }
        return ScheduledTool(toolCall, requiresConfirmation = false, deferred = job)
    }

    suspend fun execute(
        context: Context,
        toolCall: String,
        onIntent: (Intent) -> Unit,
    ): String? = toolManager.executeToolCall(context.applicationContext, toolCall, onIntent)
}
