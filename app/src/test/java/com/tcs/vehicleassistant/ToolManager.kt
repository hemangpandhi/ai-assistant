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
    val registry = ToolRegistry()
    val schemaGenerator = ToolSchemaGenerator(registry)
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
        executor = AppToolExecutor(registry)
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

    suspend fun executeToolCall(context: Context, call: ParsedToolCall): String {
        return executor.executeToolCall(context, call.fullTag)
    }

    suspend fun executeToolCall(context: Context, rawToolCall: String): String {
        return executor.executeToolCall(context, rawToolCall)
    }

    fun resolveDirectHit(query: String): com.tcs.vehicleassistant.core.DirectToolResolver.Hit? {
        return registry.resolveDirectHit(query)
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
