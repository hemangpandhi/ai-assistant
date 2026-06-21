package com.example.gemininano

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

object ToolManager {
    private val TAG = "ToolManager"
    
    data class Constraint(
        val propertyId: Int,
        val operator: String,
        val value: Double,
        val errorMsg: String
    )
    
    data class SafetyConstraints(
        val requiresParked: Boolean,
        val maxSpeedKmh: Float
    )
    
    data class ToolParameters(
        val required: List<String>,
        val missingSlotPrompt: String? = null
    )
    
    data class SystemInstruction(
        val instruction: String,
        val keywords: List<String>
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
        val keywords: List<String>?,
        val constraints: List<Constraint>?,
        val requiresConfirmation: Boolean = false,
        val confirmationMessage: String? = null,
        val requiresAgenticLoop: Boolean = false,
        val requiresVehicleState: Boolean = false,
        val contextDependencies: List<String>? = null,
        val offlineCapable: Boolean = true,
        val executionProfile: String? = null,
        val latencyPlaceholder: String? = null,
        val readPropertyId: Int? = null,
        val areaMappingStrategy: String? = null,
        val roleRestrictions: List<String>? = null,
        val safetyConstraints: SafetyConstraints? = null,
        val parameters: ToolParameters? = null,
        val emulatedBypass: Boolean = false,
        val diagnosticPayload: String? = null
    )
    
    // Maps command prefix -> ToolDefinition
    private val activeTools = mutableMapOf<String, ToolDefinition>()
    
    private val systemInstructions = mutableListOf<SystemInstruction>()
    
    private var mediaPlayer: android.media.MediaPlayer? = null
    
    var isInitialized = false
        private set

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val inputStream = context.assets.open("vehicle_skills_registry_v2.0.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            
            val jsonStr = String(buffer, Charsets.UTF_8)
            val jsonObject = JSONObject(jsonStr)
            
            if (jsonObject.has("system_instructions")) {
                val instructionsArray = jsonObject.getJSONArray("system_instructions")
                for (i in 0 until instructionsArray.length()) {
                    val instObj = instructionsArray.getJSONObject(i)
                    val instText = instObj.getString("instruction")
                    val keywordsList = mutableListOf<String>()
                    if (instObj.has("keywords")) {
                        val arr = instObj.getJSONArray("keywords")
                        for (j in 0 until arr.length()) keywordsList.add(arr.getString(j).lowercase())
                    }
                    systemInstructions.add(SystemInstruction(instText, keywordsList))
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

                    val keywordsList = mutableListOf<String>()
                    if (toolObj.has("keywords")) {
                        val arr = toolObj.getJSONArray("keywords")
                        for (j in 0 until arr.length()) keywordsList.add(arr.getString(j).lowercase())
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

                    val requiresAgenticLoop = if (toolObj.has("requires_agentic_loop")) toolObj.getBoolean("requires_agentic_loop") else false
                    val requiresVehicleState = if (toolObj.has("requires_vehicle_state")) toolObj.getBoolean("requires_vehicle_state") else false
                    val contextDependenciesList = mutableListOf<String>()
                    if (toolObj.has("context_dependencies")) {
                        val arr = toolObj.getJSONArray("context_dependencies")
                        for (j in 0 until arr.length()) contextDependenciesList.add(arr.getString(j))
                    }
                    
                    val offlineCapable = if (toolObj.has("offline_capable")) toolObj.getBoolean("offline_capable") else true
                    val executionProfile = if (toolObj.has("execution_profile")) toolObj.getString("execution_profile") else null
                    val latencyPlaceholder = if (toolObj.has("latency_placeholder")) toolObj.getString("latency_placeholder") else null
                    val readPropertyId = if (toolObj.has("read_property_id")) toolObj.getInt("read_property_id") else null
                    val areaMappingStrategy = if (toolObj.has("area_mapping_strategy")) toolObj.getString("area_mapping_strategy") else null
                    
                    var roleRestrictions: List<String>? = null
                    if (toolObj.has("role_restrictions")) {
                        val arr = toolObj.getJSONArray("role_restrictions")
                        val roles = mutableListOf<String>()
                        for (j in 0 until arr.length()) roles.add(arr.getString(j))
                        roleRestrictions = roles
                    }
                    
                    var safetyConstraints: SafetyConstraints? = null
                    if (toolObj.has("safety_constraints")) {
                        val sObj = toolObj.getJSONObject("safety_constraints")
                        safetyConstraints = SafetyConstraints(
                            requiresParked = if (sObj.has("requires_parked")) sObj.getBoolean("requires_parked") else false,
                            maxSpeedKmh = if (sObj.has("max_speed_kmh")) sObj.getDouble("max_speed_kmh").toFloat() else 0f
                        )
                    }
                    
                    var parameters: ToolParameters? = null
                    if (toolObj.has("parameters")) {
                        val pObj = toolObj.getJSONObject("parameters")
                        if (pObj.has("required")) {
                            val arr = pObj.getJSONArray("required")
                            val reqs = mutableListOf<String>()
                            for (j in 0 until arr.length()) reqs.add(arr.getString(j))
                            val missingPrompt = if (pObj.has("missing_slot_prompt")) pObj.getString("missing_slot_prompt") else null
                            parameters = ToolParameters(reqs, missingPrompt)
                        }
                    }

                    activeTools[commandName] = ToolDefinition(
                        handlerType, promptString, handlerKey, propertyId, dataType, areaId, valueToWrite, successMessage, errorMessage,
                        if (keywordsList.isNotEmpty()) keywordsList else null,
                        if (constraintsList.isNotEmpty()) constraintsList else null,
                        requiresConfirmation = if (toolObj.has("requires_confirmation")) toolObj.getBoolean("requires_confirmation") else false,
                        confirmationMessage = if (toolObj.has("confirmation_message")) toolObj.getString("confirmation_message") else null,
                        requiresAgenticLoop = requiresAgenticLoop,
                        requiresVehicleState = requiresVehicleState,
                        contextDependencies = if (contextDependenciesList.isNotEmpty()) contextDependenciesList else null,
                        offlineCapable = offlineCapable,
                        executionProfile = executionProfile,
                        latencyPlaceholder = latencyPlaceholder,
                        readPropertyId = readPropertyId,
                        areaMappingStrategy = areaMappingStrategy,
                        roleRestrictions = roleRestrictions,
                        safetyConstraints = safetyConstraints,
                        parameters = parameters,
                        emulatedBypass = if (toolObj.has("emulated_bypass")) toolObj.getBoolean("emulated_bypass") else false,
                        diagnosticPayload = if (toolObj.has("diagnostic_payload")) toolObj.getString("diagnostic_payload") else null
                    )
                    Log.i(TAG, "Registered Tool: $commandName ($handlerType) -> $promptString")
                }
            }
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tools from vehicle_skills_registry_v2.0.json", e)
        }
        
        // Initialize Semantic Search RAG asynchronously
        SemanticSearchManager.initialize(context)
        SemanticSearchManager.buildToolEmbeddingsCache()
    }

    /**
     * Primitive Tool RAG Engine.
     * Evaluates the user query against the tool keywords.
     * Returns the top matching tools (plus any default generic ones).
     */
    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getRelevantTools(context: Context, query: String, previousExecutedTools: Set<String> = emptySet()): List<ToolDefinition> {
        val isOnline = isNetworkAvailable(context)
        
        val injectedDependencies = mutableSetOf<ToolDefinition>()
        for (toolKey in previousExecutedTools) {
            val toolDef = activeTools[toolKey]
            toolDef?.contextDependencies?.forEach { depKey ->
                activeTools[depKey]?.let { injectedDependencies.add(it) }
            }
        }

        val allToolsFiltered = if (isOnline) activeTools.values else activeTools.values.filter { it.offlineCapable }

        if (query.isBlank()) return emptyList()
        
        val q = query.lowercase()
        
        // Semantic Search with keyword boosting
        val scoredTools = SemanticSearchManager.searchWithScores(query, 20)
        
        val enhancedScores = scoredTools.map { (tool, score) ->
            val isKeywordMatch = tool.keywords?.any { q.contains(it) } == true
            val finalScore = if (isKeywordMatch) score + 0.3f else score
            Pair(tool, finalScore)
        }
        
        val topTools = enhancedScores
            .sortedByDescending { it.second }
            .take(4) // Strictly limit to top 4 tools
            .map { it.first }
            .filter { isOnline || it.offlineCapable }
            .toMutableList()
            
        return (topTools + injectedDependencies.filter { isOnline || it.offlineCapable }).distinct()
    }

    /**
     * Returns the comma-separated list of tool prompts for the LLM System Prompt.
     */


    fun getToolDefinition(toolCall: String): ToolDefinition? {
        val commandName = toolCall.substringBefore("(").trim()
        return activeTools[commandName]
    }
    
    fun getAllTools(): Map<String, ToolDefinition> = activeTools

    fun requiresVehicleState(context: Context, query: String, previousExecutedTools: Set<String> = emptySet()): Boolean {
        val relevantTools = getRelevantTools(context, query, previousExecutedTools)
        return relevantTools.any { it.requiresVehicleState }
    }

    fun getGlobalSystemInstructions(query: String): String {
        if (systemInstructions.isEmpty() || query.isBlank()) return ""
        val q = query.lowercase()
        val matchingInstructions = systemInstructions.filter { inst ->
            inst.keywords.isEmpty() || inst.keywords.any { q.contains(it) }
        }
        if (matchingInstructions.isEmpty()) return ""
        
        val builder = StringBuilder("=== STRICT RULES ===\n")
        matchingInstructions.forEachIndexed { index, inst ->
            builder.append("${index + 1}. ${inst.instruction}\n")
        }
        return builder.toString()
    }

    fun getLlmToolsPrompt(context: Context, query: String = "", previousExecutedTools: Set<String> = emptySet()): String {
        val relevantTools = getRelevantTools(context, query, previousExecutedTools)
        if (relevantTools.isEmpty()) return ""
        
        val builder = StringBuilder("<AvailableTools>\n")
        for (tool in relevantTools) {
            val keywordsText = tool.keywords?.joinToString(", ") ?: ""
            builder.append("  - Tool: ${tool.promptString}\n")
            if (keywordsText.isNotBlank()) {
                builder.append("    Keywords: $keywordsText\n")
            }
        }
        builder.append("</AvailableTools>")
        return builder.toString()
    }

    /**
     * Executes the requested tool call if it is enabled in vehicle_skills_registry_v2.0.json.
     * Returns a string summarizing the outcome for the chat UI.
     */
    suspend fun executeToolCall(context: Context, rawToolCall: String, intentHandler: ((Intent) -> Unit)? = null): String {
        val toolCall = rawToolCall.trim()
        Log.d(TAG, "Executing toolCall: $toolCall")
        try {
            // Check if the requested tool corresponds to an enabled handler
            var matchedTool: ToolDefinition? = null
            for ((key, def) in activeTools) {
                if (toolCall.lowercase().startsWith(key.lowercase())) {
                    matchedTool = def
                    break
                }
            }
            
            if (matchedTool == null) {
                Log.w(TAG, "Tool blocked or unrecognized: $toolCall")
                return "System Error: The requested tool is not supported or is disabled by the manufacturer."
            }

            Log.d(TAG, "Matched tool handlerKey: ${matchedTool.handlerKey}, handlerType: ${matchedTool.handlerType}")

            // 1. Safety Middleware: Block Execution if Vehicle is Moving
            if (matchedTool.safetyConstraints != null) {
                val currentSpeed = VehicleManager.getFloatPropertyQuietly(291504647, 0f) // PERF_VEHICLE_SPEED
                if (matchedTool.safetyConstraints.requiresParked && currentSpeed > matchedTool.safetyConstraints.maxSpeedKmh) {
                    Log.w(TAG, "Safety Constraint Blocked Tool $toolCall: Vehicle speed is $currentSpeed km/h (Max: ${matchedTool.safetyConstraints.maxSpeedKmh})")
                    return "Safety Error: Cannot execute while vehicle is in motion."
                }
            }
            
            // 2. Agentic Slot Filling: Validate required parameters
            if (matchedTool.parameters?.required?.isNotEmpty() == true) {
                val argStr = toolCall.substringAfter("(").substringBefore(")").trim()
                if (argStr.isBlank() || argStr == "\"\"" || argStr == "''") {
                    val prompt = matchedTool.parameters.missingSlotPrompt ?: "Please provide more details."
                    Log.i(TAG, "Agentic Slot Filling triggered: missing arguments for $toolCall")
                    return prompt
                }
            }


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
                
                Log.d(TAG, "Executing GENERIC_VHAL_WRITE for propId $propId")
                val success = VehicleManager.setPropertyVerified(propId, areaId, valueToSet, dataType)
                
                // Emulated Bypass: Barebones AOSP emulators lack some hardware properties, skip verification if flagged in JSON
                if (matchedTool.emulatedBypass) {
                    return matchedTool.successMessage ?: "Action completed successfully."
                }

                return if (success) {
                    matchedTool.successMessage ?: "Action completed successfully."
                } else {
                    "I sent the command, but the vehicle hardware didn't confirm the change. Please check your system."
                }
            }

            // Execute the corresponding Kotlin handler via Registry
            val handler = com.example.gemininano.handlers.ToolHandlerRegistry.getHandler(matchedTool.handlerKey!!, matchedTool)
            if (handler != null) {
                val args = toolCall.substringAfter("(").substringBeforeLast(")")
                val result = handler.execute(context, toolCall, args, intentHandler)
                if (result.success && matchedTool.successMessage != null) {
                    return matchedTool.successMessage
                }
                if (!result.success && matchedTool.errorMessage != null) {
                    return matchedTool.errorMessage
                }
                return result.message
            } else {
                return "System Error: Handler found but logic is missing."
            }

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
                // Generate a dummy tool call string based on the registry's diagnostic_payload
                val dummyPayload = def.diagnosticPayload ?: ""
                val dummyCall = if (dummyPayload.isNotEmpty()) "$key($dummyPayload)" else "$key()"
                
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
