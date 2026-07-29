package com.tcs.vehicleassistant.domain

import android.content.Context
import android.content.Intent
import com.tcs.vehicleassistant.LatencyLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

/**
 * Eager mid-stream + onDone tool scheduling.
 * Coordinates confirmation gates and pending deferred executions.
 */
class ToolLoop(
    private val executeToolUseCase: ExecuteToolUseCase,
) {
    val pendingTools: MutableList<Deferred<String?>> = mutableListOf()
    var pendingConfirmationTool: String? = null
        private set

    fun clearPending() {
        pendingTools.clear()
    }

    fun clearConfirmation() {
        pendingConfirmationTool = null
    }

    fun gateConfirmation(toolCall: String) {
        pendingConfirmationTool = toolCall
        LatencyLogger.log("ToolLoop", "Confirmation gated: $toolCall")
    }

    fun takeConfirmation(): String? {
        val t = pendingConfirmationTool
        pendingConfirmationTool = null
        return t
    }

    /**
     * Schedule a complete `<TOOL>…</TOOL>` invocation if not already seen.
     * @return true if newly scheduled or gated for confirmation.
     */
    fun scheduleIfNew(
        scope: CoroutineScope,
        toolCall: String,
        executedTools: MutableSet<String>,
        execute: suspend (String) -> String?,
        onIntent: (Intent) -> Unit = {},
    ): Boolean {
        if (!executedTools.add(toolCall)) return false
        val scheduled = executeToolUseCase.schedule(
            scope = scope,
            toolCall = toolCall,
            onIntent = onIntent,
            execute = execute,
        )
        if (scheduled.requiresConfirmation) {
            gateConfirmation(toolCall)
            return true
        }
        scheduled.deferred?.let { pendingTools.add(it) }
        LatencyLogger.log("ToolLoop", "Eager tool scheduled: $toolCall")
        return true
    }

    suspend fun executeNow(
        context: Context,
        toolCall: String,
        onIntent: (Intent) -> Unit,
    ): String? = executeToolUseCase.execute(context, toolCall, onIntent)
}
