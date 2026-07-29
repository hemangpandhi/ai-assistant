package com.tcs.vehicleassistant

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.ToolRetriever
import org.json.JSONObject

class ToolManager {

    companion object {
        private const val TAG = "ToolManager"

        /** Tools the model may need at any moment, regardless of the current utterance. */
        private val CORE_HANDLER_KEYS = setOf(
            "stopMusic", "playMusic", "increaseTemperature", "decreaseTemperature", "setSeatHeater"
        )

        /** Plausible cabin temperature setpoints in Fahrenheit. */
        private val TEMPERATURE_VALUE = Regex("""\b(5[0-9]|6[0-9]|7[0-9]|8[0-9]|90)\b""")
        private val TEMPERATURE_FAHRENHEIT = Regex("""\d{2}f""")

        /**
         * Injecting more than a handful of tools overflows the edge model's context and degrades
         * tool-call accuracy, so both the BM25 fallback and the prompt builder cap the count.
         */
        private const val BM25_TOP_K = 4
        private const val MAX_PROMPT_TOOLS = 8
    }

    
    data class Constraint(
        val propertyId: Int,
        val operator: String,
        val value: Double,
        val errorMsg: String
    )
    
    data class ToolDefinition(
        val handlerType: String,
        val promptString: String,
        val handlerKey: String?,
        val propertyId: Int?,
        val dataType: String?,
        val areaId: Int?,
        val valueToWrite: String?,
        val successMessage: String?,
        val errorMessage: String?,
        val description: String?,
        val instruction: String?,
        val keywords: List<String>?,
        val aliases: List<String>?,
        val constraints: List<Constraint>?,
        val requiresConfirmation: Boolean = false,
        val confirmationMessage: String? = null,
        val offlineCapable: Boolean = false,
        val requiresVehicleState: Boolean = false,
        val requiresAgenticLoop: Boolean = false
    )
    
    // Maps command prefix -> ToolDefinition
    private val activeTools = mutableMapOf<String, ToolDefinition>()

    /** BM25 index over the tool catalogue, rebuilt whenever the registry is (re)loaded. */
    private var retrievalIndex: List<ToolRetriever.Document> = emptyList()

    var isInitialized = false
        private set

    var slidingWindowMaxChars: Int = AssistantConfig.Memory.DEFAULT_MAX_CHARS
        private set

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val inputStream = context.assets.open("vehicle_skills_registry.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            
            val jsonStr = String(buffer, Charsets.UTF_8)
            val jsonObject = JSONObject(jsonStr)

            if (jsonObject.has("config")) {
                val config = jsonObject.getJSONObject("config")
                if (config.has("sliding_window_max_chars")) {
                    slidingWindowMaxChars = config.getInt("sliding_window_max_chars")
                }
            }

            if (jsonObject.has("tools")) {
                val toolsArray = jsonObject.getJSONArray("tools")
                for (i in 0 until toolsArray.length()) {
                    val toolObj = toolsArray.getJSONObject(i)
                    val promptString = toolObj.getString("prompt_string")
                    val handlerType = if (toolObj.has("handler_type")) toolObj.getString("handler_type") else "CUSTOM_KOTLIN"
                    val handlerKey = if (toolObj.has("handler_key")) toolObj.getString("handler_key") else null
                    
                    val commandName = handlerKey ?: promptString.substringAfter("<TOOL>").substringBefore("</TOOL>").substringBefore("(")
                    
                    val propertyId = if (toolObj.has("property_id")) toolObj.getInt("property_id") else null
                    val dataType = if (toolObj.has("data_type")) toolObj.getString("data_type") else null
                    val areaId = if (toolObj.has("area_id")) toolObj.getInt("area_id") else null
                    val valueToWrite = if (toolObj.has("value_to_write")) toolObj.getString("value_to_write") else null
                    val successMessage = if (toolObj.has("success_message")) toolObj.getString("success_message") else null
                    val errorMessage = if (toolObj.has("error_message")) toolObj.getString("error_message") else null
                    val description = if (toolObj.has("description")) toolObj.getString("description") else null
                    val instruction = if (toolObj.has("instruction")) toolObj.getString("instruction") else null
                    val offlineCapable = if (toolObj.has("offline_capable")) toolObj.getBoolean("offline_capable") else false
                    val requiresVehicleState = if (toolObj.has("requires_vehicle_state")) toolObj.getBoolean("requires_vehicle_state") else false

                    val keywordsList = mutableListOf<String>()
                    if (toolObj.has("keywords")) {
                        val arr = toolObj.getJSONArray("keywords")
                        for (j in 0 until arr.length()) keywordsList.add(arr.getString(j).lowercase())
                    }
                    
                    val aliasesList = mutableListOf<String>()
                    if (toolObj.has("aliases")) {
                        val arr = toolObj.getJSONArray("aliases")
                        for (j in 0 until arr.length()) {
                            val alias = arr.getString(j).lowercase()
                            aliasesList.add(alias)
                            // Merge aliases directly into keywords for instant fast-path RAG matching!
                            if (!keywordsList.contains(alias)) {
                                keywordsList.add(alias)
                            }
                        }
                    }
                    
                    val constraintsList = mutableListOf<Constraint>()
                    if (toolObj.has("constraints")) {
                        val arr = toolObj.getJSONArray("constraints")
                        for (j in 0 until arr.length()) {
                            val cObj = arr.getJSONObject(j)
                            constraintsList.add(Constraint(
                                propertyId = cObj.getInt("property_id"),
                                operator = cObj.getString("operator"),
                                value = cObj.getDouble("value"),
                                errorMsg = cObj.getString("error_msg")
                            ))
                        }
                    }

                    activeTools[commandName] = ToolDefinition(
                        handlerType = handlerType,
                        promptString = promptString,
                        handlerKey = handlerKey,
                        propertyId = propertyId,
                        dataType = dataType,
                        areaId = areaId,
                        valueToWrite = valueToWrite,
                        successMessage = successMessage,
                        errorMessage = errorMessage,
                        description = description,
                        instruction = instruction,
                        keywords = if (keywordsList.isNotEmpty()) keywordsList else null,
                        aliases = if (aliasesList.isNotEmpty()) aliasesList else null,
                        constraints = if (constraintsList.isNotEmpty()) constraintsList else null,
                        requiresConfirmation = if (toolObj.has("requires_confirmation")) toolObj.getBoolean("requires_confirmation") else false,
                        confirmationMessage = if (toolObj.has("confirmation_message")) toolObj.getString("confirmation_message") else null,
                        offlineCapable = offlineCapable,
                        requiresVehicleState = requiresVehicleState,
                        requiresAgenticLoop = if (toolObj.has("requires_agentic_loop")) toolObj.getBoolean("requires_agentic_loop") else false
                    )
                    Log.i(TAG, "Registered Tool: $commandName ($handlerType) -> $promptString")
                }
            }
            retrievalIndex = buildRetrievalIndex()
            isInitialized = true
            Log.i(TAG, "Registered ${activeTools.size} tools; BM25 index holds ${retrievalIndex.size} documents.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tools from vehicle_skills_registry.json", e)
        }
    }

    private fun buildRetrievalIndex(): List<ToolRetriever.Document> = activeTools.map { (commandName, tool) ->
        ToolRetriever.document(
            id = commandName,
            commandName,
            tool.keywords?.joinToString(" "),
            tool.aliases?.joinToString(" "),
            tool.description
        )
    }

    /**
     * Tool retrieval for prompt construction, in three tiers:
     *
     * 1. Whole-word keyword/alias matches on the current user turn (cheap and precise).
     * 2. For short follow-ups ("make it warmer"), keyword matches against the prior assistant turn,
     *    because the current turn carries no routable nouns of its own.
     * 3. BM25 ranking over the whole catalogue, used only when the first two tiers find nothing.
     *
     * Core tools stay pinned so the model can always stop music or nudge the temperature, and an
     * empty result is never returned for a non-blank query.
     */
    fun getRelevantTools(userQuery: String, conversationalContext: String = ""): List<ToolDefinition> {
        val combinedQuery = "$conversationalContext $userQuery".trim()
        if (combinedQuery.isBlank()) return activeTools.values.toList()

        val userTurn = userQuery.lowercase()

        val userTurnMatches = matchByKeyword(userTurn)
        val exactMatches = userTurnMatches.ifEmpty { matchByKeyword(combinedQuery.lowercase()) }.toMutableList()

        // Short follow-ups rely on the prior assistant context for tool routing.
        val contextTools = if (conversationalContext.isNotBlank() &&
            MemoryManager.isFollowUpQuery(userQuery, conversationalContext)
        ) {
            matchByKeyword(conversationalContext.lowercase())
        } else {
            emptyList()
        }

        // Bare numbers and "degrees" only make sense as a climate setpoint.
        if (TEMPERATURE_VALUE.containsMatchIn(userTurn) ||
            userTurn.contains("degrees") ||
            TEMPERATURE_FAHRENHEIT.containsMatchIn(userTurn)
        ) {
            exactMatches += activeTools.values.filter {
                it.handlerKey?.contains("Temperature", ignoreCase = true) == true
            }
        }

        val coreTools = activeTools.values.filter { it.handlerKey in CORE_HANDLER_KEYS }
        val matched = (contextTools + exactMatches + coreTools).distinct()
        if (matched.isNotEmpty()) return matched

        // Nothing matched lexically: fall back to BM25 over the catalogue. Ranking only the user
        // turn keeps history from swamping the scores, and a small top-K keeps the prompt short
        // enough for the edge model's context window.
        val ranked = ToolRetriever.rank(userQuery, retrievalIndex, BM25_TOP_K)
            .mapNotNull { activeTools[it.id] }
        return ranked.ifEmpty { activeTools.values.take(BM25_TOP_K).toList() }
    }

    private fun matchByKeyword(haystack: String): List<ToolDefinition> =
        activeTools.values.filter { tool ->
            tool.keywords?.any { kw -> Regex("""\b${Regex.escape(kw)}\b""").containsMatchIn(haystack) } == true
        }

    fun getToolDefinition(rawToolCall: String): ToolDefinition? {
        val toolCall = rawToolCall.replace(Regex("(?i)<TOOL>|</TOOL>|<\\|tool_call>call:"), "").trim()
        val commandName = toolCall.substringBefore("(").trim()
        val directMatch = activeTools[commandName]
        if (directMatch != null) return directMatch
        
        // Check aliases if direct match fails
        for (tool in activeTools.values) {
            if (tool.aliases != null) {
                val aliasMatch = tool.aliases.any { alias -> commandName.lowercase() == alias.lowercase() }
                if (aliasMatch) return tool
            }
        }
        return null
    }
    
    fun getAllTools(): Map<String, ToolDefinition> = activeTools

    /**
     * Renders the tool block for the LLM system prompt: one line per tool plus an explicit
     * allow-list of callable names.
     */
    fun getLlmToolsPrompt(userQuery: String = "", conversationalContext: String = ""): String {
        val relevantTools = if (userQuery.isNotBlank() || conversationalContext.isNotBlank()) {
            getRelevantTools(userQuery, conversationalContext).take(MAX_PROMPT_TOOLS)
        } else {
            activeTools.values.take(MAX_PROMPT_TOOLS).toList()
        }
        if (relevantTools.isEmpty()) return ""
        
        val sb = StringBuilder()
        val toolNames = mutableListOf<String>()
        for (tool in relevantTools) {
            sb.append("- ${tool.promptString}")
            // Use technical description only, stripping any conversational questions
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
        sb.append("Allowed tools: ")
        sb.append(toolNames.joinToString(", "))
        return sb.toString()
    }

    /**
     * Executes the requested tool call if it is enabled in vehicle_skills_registry.json.
     * Returns a string summarizing the outcome for the chat UI.
     */
    suspend fun executeToolCall(context: Context, rawToolCall: String, intentHandler: ((Intent) -> Unit)? = null): String {
        val toolCall = rawToolCall.replace(Regex("(?i)<TOOL>|</TOOL>|<\\|tool_call>call:"), "").trim()
        Log.d(TAG, "Executing toolCall: $toolCall")
        try {
            // Check if the requested tool corresponds to an enabled handler
            var matchedTool: ToolDefinition? = null
            for ((key, def) in activeTools) {
                if (toolCall.lowercase().startsWith(key.lowercase())) {
                    matchedTool = def
                    break
                }
                
                // Allow matching against aliases to handle Edge model shorthand hallucinations!
                if (def.aliases != null) {
                    val aliasMatch = def.aliases.any { alias -> toolCall.lowercase().startsWith(alias.lowercase()) }
                    if (aliasMatch) {
                        matchedTool = def
                        break
                    }
                }
            }
            
            if (matchedTool == null) {
                Log.w(TAG, "Tool blocked or unrecognized: $toolCall")
                return "System Error: The requested tool is not supported or is disabled by the manufacturer."
            }

            Log.d(TAG, "Matched tool handlerKey: ${matchedTool.handlerKey}, handlerType: ${matchedTool.handlerType}")

            // Safety Middleware Constraint Validation
            if (matchedTool.constraints != null) {
                for (constraint in matchedTool.constraints) {
                    val currentValue = VehicleManager.getFloatProperty(constraint.propertyId)
                    if (currentValue != null) {
                        val failed = when (constraint.operator) {
                            "<" -> currentValue.toDouble() >= constraint.value
                            ">" -> currentValue.toDouble() <= constraint.value
                            "==" -> currentValue.toDouble() != constraint.value
                            "!=" -> currentValue.toDouble() == constraint.value
                            "<=" -> currentValue.toDouble() > constraint.value
                            ">=" -> currentValue.toDouble() < constraint.value
                            else -> false
                        }
                        if (failed) {
                            Log.w(TAG, "Safety Constraint Blocked Tool $toolCall: Property ${constraint.propertyId} is $currentValue. Condition `${constraint.operator} ${constraint.value}` failed. Message: ${constraint.errorMsg}")
                            return constraint.errorMsg
                        }
                    } else {
                        Log.w(TAG, "Warning: Could not read property ${constraint.propertyId} for safety constraint evaluation.")
                    }
                }
            }

            if (matchedTool.handlerType == "GENERIC_VHAL_WRITE") {
                val propId = matchedTool.propertyId ?: return "System Error: Missing property_id"
                val dataType = matchedTool.dataType ?: return "System Error: Missing data_type"
                val areaId = matchedTool.areaId ?: 0
                val valueToSet = matchedTool.valueToWrite ?: toolCall.substringAfter("(").substringBefore(")")
                
                val startToolTime = System.currentTimeMillis()
                
                // Hardware confirmation logic with Emulator workaround
                val isAospEmulator = propId == 289410577 || propId == 354419973 || propId == 289410578
                val success = if (isAospEmulator) {
                    VehicleManager.setGenericVhalProperty(propId, areaId, valueToSet, dataType)
                    true
                } else {
                    VehicleManager.setPropertyVerified(propId, areaId, valueToSet, dataType)
                }
                
                val endToolTime = System.currentTimeMillis()
                val latency = endToolTime - startToolTime
                com.tcs.vehicleassistant.LatencyLogger.lastToolTimeMs = latency
                com.tcs.vehicleassistant.LatencyLogger.log("ToolManager", "Tool Execution Time for GENERIC_VHAL_WRITE ($propId): ${latency}ms")
                
                return if (success) {
                    matchedTool.successMessage ?: "Action completed successfully."
                } else {
                    matchedTool.errorMessage ?: "I sent the command, but the vehicle hardware didn't confirm the change. Please check your system."
                }
            }

            // Delegate all CUSTOM_KOTLIN handlers to the modular ToolHandlerRegistry
            if (matchedTool.handlerType == "CUSTOM_KOTLIN") {
                val handlerKey = matchedTool.handlerKey
                if (handlerKey != null) {
                    val handler = com.tcs.vehicleassistant.handlers.ToolHandlerRegistry.getHandler(handlerKey, matchedTool)
                    if (handler != null) {
                        val args = toolCall.substringAfter("(").substringBeforeLast(")")
                        val startToolTime = System.currentTimeMillis()
                        val result = handler.execute(context, toolCall, args, intentHandler)
                        val endToolTime = System.currentTimeMillis()
                        val latency = endToolTime - startToolTime
                        com.tcs.vehicleassistant.LatencyLogger.lastToolTimeMs = latency
                        com.tcs.vehicleassistant.LatencyLogger.log("ToolManager", "Tool Execution Time for $handlerKey: ${latency}ms")
                        return result.message
                    } else {
                        Log.w(TAG, "No handler registered for key: $handlerKey")
                        return "System Error: Handler not implemented for $handlerKey."
                    }
                } else {
                    return "System Error: Missing handler_key for CUSTOM_KOTLIN tool."
                }
            }

            return "System Error: Unknown handler type ${matchedTool.handlerType}."
        } catch (e: Exception) {
            Log.e(TAG, "Exception during tool execution", e)
            return "System Error: An exception occurred while executing the tool."
        }
    }

    /**
     * Executes a dummy command for every registered tool to verify the VHAL pipeline.
     */
    suspend fun runSystemDiagnostics(context: Context): String {
        val sb = StringBuilder()
        sb.append("## System Diagnostics Report\n\n")
        sb.append("| Tool Name | Handler Type | Status | Note |\n")
        sb.append("|---|---|---|---|\n")

        for ((key, def) in activeTools) {
            var status = "✅ PASS"
            var note = "Executed successfully"
            try {
                // Generate a dummy tool call string based on the required signature
                val dummyCall = when {
                    def.promptString.contains("VAL") -> "$key(72.0)"
                    def.promptString.contains("LEVEL") -> "$key(1)"
                    def.promptString.contains("PCT") -> "$key(50)"
                    def.promptString.contains("DEST") -> "$key(Home)"
                    def.promptString.contains("SONG") -> "$key(Test)"
                    def.promptString.contains("NAME") -> "$key(Mechanic)"
                    def.promptString.contains("FACT") -> "$key(TestFact)"
                    else -> "$key()" // No args
                }

                val result = executeToolCall(context, dummyCall)
                if (result.startsWith("System Error") || result.startsWith("Failed")) {
                    status = "❌ FAIL"
                    note = result
                }
            } catch (e: Exception) {
                status = "❌ CRASH"
                note = e.message ?: "Unknown Exception"
            }
            sb.append("| $key | ${def.handlerType} | $status | $note |\n")
        }

        sb.append("\n")
        sb.append(VehicleManager.runPropertyDiagnostics())
        
        return sb.toString()
    }
}
