package com.tcs.vehicleassistant

import android.content.Context
import com.tcs.vehicleassistant.domain.tools.IToolExecutor
import com.tcs.vehicleassistant.domain.tools.ToolDefinition
import com.tcs.vehicleassistant.domain.tools.ToolRegistry
import com.tcs.vehicleassistant.domain.tools.ToolSchemaGenerator
import com.tcs.vehicleassistant.executor.AppToolExecutor
import com.tcs.vehicleassistant.utils.ParsedToolCall

/**
 * Test wrapper for backward compatibility with tests that haven't been updated 
 * to use the modularized tool components (ToolRegistry, ToolSchemaGenerator, IToolExecutor).
 */
class ToolManager {
    val contextGuard = com.tcs.vehicleassistant.core.ContextGuard()
    val directToolResolver = com.tcs.vehicleassistant.core.DirectToolResolver()
    val registry = ToolRegistry(contextGuard, directToolResolver)
    val schemaGenerator = ToolSchemaGenerator(registry, directToolResolver)
    lateinit var executor: IToolExecutor

    val isInitialized: Boolean
        get() = registry.isInitialized

    val slidingWindowMaxChars: Int
        get() = registry.slidingWindowMaxChars

    val maxPromptTools: Int
        get() = registry.maxPromptTools

    val bm25TopK = 5
    val directExecutionPolicy = "default"

    fun initialize(context: Context) {
        registry.initialize(context)
        executor = AppToolExecutor(registry, com.tcs.vehicleassistant.handlers.DefaultToolHandlerRegistry())
    }

    fun getAllTools(): Map<String, ToolDefinition> {
        return registry.getAllTools()
    }

    fun getToolDefinition(name: String): ToolDefinition? {
        return registry.getToolDefinition(name)
    }

    fun getRelevantTools(query: String, context: String = ""): List<ToolDefinition> {
        if (query.isBlank()) return registry.getAllTools().values.toList()
        return schemaGenerator.getOpenApiSchemas(query).map { registry.getToolDefinition(it.first)!! }
    }

    fun getLlmToolsPrompt(query: String, lastAiResponse: String = ""): String {
        return schemaGenerator.getLlmToolsPrompt(query, lastAiResponse)
    }

    suspend fun executeToolCall(context: Context, call: ParsedToolCall, onIntercept: () -> Unit = {}): String {
        return executor.executeToolCall(context, call.fullTag)
    }

    suspend fun executeToolCall(context: Context, rawToolCall: String, onIntercept: () -> Unit = {}): String {
        return executor.executeToolCall(context, rawToolCall)
    }

    fun resolveDirectHit(query: String): com.tcs.vehicleassistant.core.DirectToolResolver.Hit? {
        val policy = com.tcs.vehicleassistant.core.DirectToolResolver.Policy()
        val hit = directToolResolver.resolve(query, registry.getAllTools().map {
            com.tcs.vehicleassistant.core.DirectToolResolver.ToolSpec(
                id = it.key,
                handlerKey = it.value.handlerKey ?: it.key,
                promptString = it.value.promptString,
                keywords = it.value.keywords ?: emptyList(),
                successMessage = it.value.successMessage,
                requiresConfirmation = it.value.requiresConfirmation,
                requiresAgenticLoop = it.value.requiresAgenticLoop,
                directExecutable = it.value.directExecutable
            )
        }, policy)
        return (hit as? com.tcs.vehicleassistant.core.DirectToolResolver.Outcome.Execute)?.hit
    }

    suspend fun runSystemDiagnostics(context: Context): String {
        return executor.runSystemDiagnostics(context)
    }

    companion object {
        fun isSpeakableDirectKeyword(keyword: String): Boolean {
            val a = keyword.trim().lowercase()
            if (a.isEmpty()) return false
            if (a.contains(' ')) return true
            if (a in setOf("play", "pause")) return true
            return false
        }
    }
}
