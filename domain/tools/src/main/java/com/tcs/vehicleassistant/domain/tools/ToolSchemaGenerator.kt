package com.tcs.vehicleassistant.domain.tools

import com.tcs.vehicleassistant.core.ConversationalIntent
import com.tcs.vehicleassistant.core.DirectToolResolver
import com.tcs.vehicleassistant.core.LlmToolAllowList
import com.tcs.vehicleassistant.core.ToolRetriever

class ToolSchemaGenerator(
    private val registry: ToolRegistry,
    private val directToolResolver: com.tcs.vehicleassistant.core.DirectToolResolver
) {

    @Volatile
    var lastPromptedToolKeys: Set<String> = emptySet()
        private set

    /**
     * Tool retrieval for prompt construction, in three tiers.
     */
    fun getRelevantTools(userQuery: String, conversationalContext: String = ""): List<ToolDefinition> {
        val combinedQuery = "$conversationalContext $userQuery".trim()
        if (combinedQuery.isBlank()) return registry.getAllTools().values.toList()

        val userTurn = userQuery.lowercase()
        val lexical = mutableListOf<ToolDefinition>()

        if (conversationalContext.isNotBlank() &&
            com.tcs.vehicleassistant.ConversationMemory.Companion.isFollowUpQuery(userQuery, conversationalContext)
        ) {
            lexical += matchByKeyword(conversationalContext.lowercase())
        }

        lexical += matchByKeyword(userTurn).ifEmpty { matchByKeyword(combinedQuery.lowercase()) }

        val temperatureValueRegex = Regex("""\b(5[0-9]|6[0-9]|7[0-9]|8[0-9]|90)\b""")
        val temperatureFahrenheitRegex = Regex("""\d{2}f""")

        if (temperatureValueRegex.containsMatchIn(userTurn) ||
            userTurn.contains("degrees") ||
            temperatureFahrenheitRegex.containsMatchIn(userTurn)
        ) {
            lexical += registry.getAllTools().values.filter {
                it.handlerKey?.contains("Temperature", ignoreCase = true) == true
            }
        }

        if (lexical.isEmpty() &&
            ConversationalIntent.isOpenChat(userQuery)
        ) {
            return emptyList()
        }

        val retrieved = when {
            lexical.isNotEmpty() -> lexical
            else -> ToolRetriever.rank(userQuery, registry.getRetrievalIndex(), registry.bm25TopK)
                .mapNotNull { registry.getTool(it.id) }
        }

        val coreTools = registry.getAllTools().values.filter { it.handlerKey in ToolRegistry.CORE_HANDLER_KEYS }
        return (retrieved + coreTools).distinct()
            .ifEmpty { registry.getAllTools().values.take(registry.maxPromptTools).toList() }
    }

    private fun matchByKeyword(haystack: String): List<ToolDefinition> {
        val normalized = directToolResolver.normalize(haystack)
        return registry.getAllTools().entries
            .filter { (name, _) ->
                registry.getKeywordMatchers()[name]?.any { it.containsMatchIn(normalized) } == true
            }
            .map { it.value }
    }

    fun getLlmFewShotsPrompt(): String {
        val keys = registry.getAllTools().keys.toSet()
        val filtered = com.tcs.vehicleassistant.core.LocalLlmPromptSupport.filterByAvailableTools(
            registry.llmFewShots,
            keys,
        )
        return com.tcs.vehicleassistant.core.LocalLlmPromptSupport.formatFewShots(filtered)
    }

    fun getLlmToolsPrompt(userQuery: String = "", conversationalContext: String = ""): String {
        val relevantTools = if (userQuery.isNotBlank() || conversationalContext.isNotBlank()) {
            getRelevantTools(userQuery, conversationalContext).take(registry.maxPromptTools)
        } else {
            registry.getAllTools().values.take(registry.maxPromptTools).toList()
        }
        if (relevantTools.isEmpty() && registry.getSystemInstructions().isEmpty()) return ""
        
        val sb = StringBuilder()
        val toolNames = mutableListOf<String>()
        for (tool in relevantTools) {
            sb.append("- ${tool.promptString}")
            val cleanDesc = tool.description?.substringBefore("?")?.trim()
            if (!cleanDesc.isNullOrEmpty()) {
                sb.append(": $cleanDesc")
            }
            sb.append("\n")
            
            val match = Regex("(?i)<TOOL>([a-zA-Z0-9_]+)\\(").find(tool.promptString)
            if (match != null) {
                toolNames.add(match.groupValues[1])
            }
        }
        if (toolNames.isNotEmpty()) {
            sb.append("Allowed tools: ")
            sb.append(toolNames.joinToString(", "))
            sb.append("\n")
        }
        lastPromptedToolKeys = toolNames.toSet()

        val haystack = "$conversationalContext $userQuery".lowercase()
        val matchedInstructions = registry.getSystemInstructions().filter { inst ->
            inst.keywords.isEmpty() || inst.keywords.any { kw -> haystack.contains(kw) }
        }
        if (matchedInstructions.isNotEmpty()) {
            sb.append("\n--- Tool guidance ---\n")
            for (inst in matchedInstructions) {
                sb.append(inst.instruction).append("\n")
            }
        }
        
        return sb.toString().trim()
    }

    fun getOpenApiSchemas(userQuery: String = "", conversationalContext: String = ""): List<Pair<String, String>> {
        val relevantTools = if (userQuery.isNotBlank() || conversationalContext.isNotBlank()) {
            getRelevantTools(userQuery, conversationalContext).take(registry.maxPromptTools)
        } else {
            registry.getAllTools().values.take(registry.maxPromptTools).toList()
        }
        val schemas = mutableListOf<Pair<String, String>>()
        
        val toolNames = mutableListOf<String>()
        for (tool in relevantTools) {
            val match = Regex("(?i)<TOOL>([a-zA-Z0-9_]+)\\(").find(tool.promptString)
            val name = match?.groupValues?.get(1) ?: tool.handlerKey ?: continue
            val json = buildOpenApiJson(name, tool)
            schemas.add(Pair(name, json))
            toolNames.add(name)
        }
        lastPromptedToolKeys = toolNames.toSet()
        return schemas
    }

    private fun buildOpenApiJson(name: String, def: ToolDefinition): String {
        val paramMatch = Regex("\\((.*?)\\)").find(def.promptString)
        val paramName = (paramMatch?.groupValues?.get(1)?.trim() ?: "").replace("\"", "")

        val builder = StringBuilder()
        builder.append("{\n")
        builder.append("  \"name\": \"$name\",\n")
        
        val safeDesc = (def.description ?: def.instruction ?: "Executes $name tool").replace("\"", "\\\"").replace("\n", " ")
        builder.append("  \"description\": \"$safeDesc\"")
        
        if (paramName.isNotEmpty() && paramName != "..." && !paramName.contains("|")) {
            val params = paramName.split(",").map { it.trim() }
            builder.append(",\n")
            builder.append("  \"parameters\": {\n")
            builder.append("    \"type\": \"object\",\n")
            builder.append("    \"properties\": {\n")
            for ((index, p) in params.withIndex()) {
                builder.append("      \"$p\": { \"type\": \"string\" }")
                if (index < params.size - 1) builder.append(",\n") else builder.append("\n")
            }
            builder.append("    },\n")
            val requiredArray = params.joinToString(", ") { "\"$it\"" }
            builder.append("    \"required\": [$requiredArray]\n")
            builder.append("  }\n")
        } else if (paramName.contains("|")) {
             val enums = paramName.split("|").joinToString(", ") { "\"$it\"" }
             builder.append(",\n")
             builder.append("  \"parameters\": {\n")
             builder.append("    \"type\": \"object\",\n")
             builder.append("    \"properties\": {\n")
             builder.append("      \"zone\": { \"type\": \"string\", \"enum\": [$enums] }\n")
             builder.append("    },\n")
             builder.append("    \"required\": [\"zone\"]\n")
             builder.append("  }\n")
        } else {
            builder.append("\n")
        }
        builder.append("}\n")
        return builder.toString()
    }

    fun needsToolUpdate(userQuery: String, conversationalContext: String): Boolean {
        val relevantTools = getRelevantTools(userQuery, conversationalContext).take(registry.maxPromptTools)
        val requiredNames = relevantTools.mapNotNull { tool ->
            Regex("(?i)<TOOL>([a-zA-Z0-9_]+)\\(").find(tool.promptString)?.groupValues?.get(1) ?: tool.handlerKey
        }.toSet()
        return !lastPromptedToolKeys.containsAll(requiredNames)
    }

    fun isToolAllowedForCurrentPrompt(toolName: String): Boolean {
        val def = registry.getToolDefinition(toolName)
        val canonical = def?.handlerKey ?: toolName.substringBefore("(").trim()
        return LlmToolAllowList.isAllowed(
            toolName = toolName.substringBefore("(").trim(),
            allowedKeys = lastPromptedToolKeys,
            canonicalKey = canonical,
        )
    }
}
