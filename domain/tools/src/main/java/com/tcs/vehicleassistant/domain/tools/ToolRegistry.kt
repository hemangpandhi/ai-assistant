package com.tcs.vehicleassistant.domain.tools

import android.content.Context
import android.util.Log
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.ContextGuard
import com.tcs.vehicleassistant.core.DirectToolResolver
import com.tcs.vehicleassistant.core.ToolRetriever
import org.json.JSONObject

class ToolRegistry(
    private val contextGuard: com.tcs.vehicleassistant.core.ContextGuard,
    private val directToolResolver: com.tcs.vehicleassistant.core.DirectToolResolver
) {

    companion object {
        private const val TAG = "ToolRegistry"

        /** Tools the model may need at any moment, regardless of the current utterance. */
        val CORE_HANDLER_KEYS = setOf(
            "stopMusic", "playMusic", "pauseMusic", "nextTrack",
            "increaseTemperature", "decreaseTemperature", "setSeatHeater",
            "searchNearby", "search", "startNavigationTo"
        )

        private val SPEAKABLE_SHORT_ALIASES = setOf("play", "pause")

        fun isSpeakableDirectKeyword(alias: String): Boolean {
            val a = alias.trim().lowercase()
            if (a.isEmpty()) return false
            if (a.contains(' ')) return true
            if (a in SPEAKABLE_SHORT_ALIASES) return true
            return false
        }

        private const val BM25_TOP_K = 4
        private const val MAX_PROMPT_TOOLS = 12
    }

    private val activeTools = mutableMapOf<String, ToolDefinition>()
    private var retrievalIndex: List<ToolRetriever.Document> = emptyList()
    private var keywordMatchers: Map<String, List<Regex>> = emptyMap()
    private var systemInstructions: List<SystemInstruction> = emptyList()

    var llmFewShots: List<com.tcs.vehicleassistant.core.LocalLlmPromptSupport.FewShot> = emptyList()
        private set

    var isInitialized = false
        private set

    var slidingWindowMaxChars: Int = AssistantConfig.Memory.DEFAULT_MAX_CHARS
        private set

    var bm25TopK: Int = BM25_TOP_K
        private set

    var maxPromptTools: Int = MAX_PROMPT_TOOLS
        private set

    var directExecutionPolicy: DirectToolResolver.Policy = DirectToolResolver.Policy()
        private set

    fun getTool(key: String): ToolDefinition? = activeTools[key]
    fun getAllTools(): Map<String, ToolDefinition> = activeTools
    fun getRetrievalIndex(): List<ToolRetriever.Document> = retrievalIndex
    fun getKeywordMatchers(): Map<String, List<Regex>> = keywordMatchers
    fun getSystemInstructions(): List<SystemInstruction> = systemInstructions

    fun getToolDefinition(rawToolCall: String): ToolDefinition? {
        val toolCall = rawToolCall.replace(Regex("(?i)<TOOL>|</TOOL>|<\\|tool_call>call:"), "").trim()
        val commandName = toolCall.substringBefore("(").trim()
        val directMatch = activeTools[commandName]
        if (directMatch != null) return directMatch
        
        for (tool in activeTools.values) {
            if (tool.aliases != null) {
                val aliasMatch = tool.aliases.any { alias -> commandName.lowercase() == alias.lowercase() }
                if (aliasMatch) return tool
            }
        }
        return null
    }

    fun resolveDirectHit(userQuery: String): DirectToolResolver.Hit? {
        if (!isInitialized || userQuery.isBlank()) return null
        val specs = activeTools.map { (id, tool) ->
            DirectToolResolver.ToolSpec(
                id = id,
                handlerKey = tool.handlerKey ?: id,
                promptString = tool.promptString,
                keywords = tool.keywords.orEmpty(),
                successMessage = tool.successMessage,
                requiresConfirmation = tool.requiresConfirmation,
                requiresAgenticLoop = tool.requiresAgenticLoop,
                directExecutable = tool.directExecutable,
            )
        }
        return when (val outcome = directToolResolver.resolve(userQuery, specs, directExecutionPolicy)) {
            is com.tcs.vehicleassistant.core.DirectToolResolver.Outcome.Execute -> outcome.hit
            is com.tcs.vehicleassistant.core.DirectToolResolver.Outcome.Skip -> {
                Log.d(TAG, "Direct execution skipped: ${outcome.rejection.reason} for '$userQuery'")
                null
            }
        }
    }

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val jsonStr = context.assets.open("vehicle_skills_registry.json").use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
            val jsonObject = JSONObject(jsonStr)

            if (jsonObject.has("config")) {
                val config = jsonObject.getJSONObject("config")
                if (config.has("sliding_window_max_chars")) {
                    slidingWindowMaxChars = config.getInt("sliding_window_max_chars")
                }
                if (config.has("max_relevant_tools")) {
                    bm25TopK = config.getInt("max_relevant_tools").coerceAtLeast(1)
                }
                if (config.has("max_prompt_tools")) {
                    maxPromptTools = config.getInt("max_prompt_tools").coerceAtLeast(1)
                }
                if (config.has("direct_execution")) {
                    val de = config.getJSONObject("direct_execution")
                    directExecutionPolicy = DirectToolResolver.Policy(
                        enabled = de.optBoolean("enabled", true),
                        minKeywordChars = de.optInt("min_keyword_chars", 5).coerceAtLeast(3),
                        maxQueryWords = de.optInt("max_query_words", 12).coerceAtLeast(3),
                        maxQueryChars = de.optInt("max_query_chars", 100).coerceAtLeast(20),
                        minKeywordMargin = de.optInt("min_keyword_margin", 3).coerceAtLeast(0),
                        fanMax = de.optInt("fan_max", 7).coerceAtLeast(1),
                        volumeMax = de.optInt("volume_max", 100).coerceAtLeast(1),
                        seatHeaterOnDefault = de.optInt("seat_heater_on_default", 2).coerceAtLeast(0),
                        alertLevelDefault = de.optInt("alert_level_default", 2).coerceAtLeast(0),
                        numericMinDefault = de.optInt("numeric_min_default", 1).coerceAtLeast(0),
                    )
                }
                llmFewShots = parseLlmFewShots(config)
                contextGuard.loadFromConfig(config)
            }

            systemInstructions = parseSystemInstructions(jsonObject)

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
                            if (isSpeakableDirectKeyword(alias) && !keywordsList.contains(alias)) {
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
                        requiresAgenticLoop = if (toolObj.has("requires_agentic_loop")) toolObj.getBoolean("requires_agentic_loop") else false,
                        directExecutable = toolObj.optBoolean("direct_executable", false),
                    )
                    Log.i(TAG, "Registered Tool: $commandName ($handlerType) -> $promptString")
                }

                // 2. Build retrieval index
                retrievalIndex = activeTools.mapNotNull { (key, def) ->
                    ToolRetriever.document(
                        id = key,
                        key,
                        def.keywords?.joinToString(" "),
                        def.aliases?.joinToString(" "),
                        def.description
                    )
                }

                // 3. Compile regexes for DirectToolResolver
                val matchers = mutableMapOf<String, List<Regex>>()
                for ((key, def) in activeTools) {
                    if (def.keywords.isNullOrEmpty()) continue
                    val compiledList = mutableListOf<Regex>()
                    for (kw in def.keywords) {
                        try {
                            val bounded = "\\b${Regex.escape(kw)}\\b"
                            compiledList.add(Regex(bounded, RegexOption.IGNORE_CASE))
                        } catch (e: Exception) {
                            Log.w(TAG, "Invalid keyword regex '$kw' for tool $key", e)
                        }
                    }
                    if (compiledList.isNotEmpty()) {
                        matchers[key] = compiledList
                    }
                }
                keywordMatchers = matchers
            }

            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing vehicle_skills_registry.json", e)
        }
    }

    private fun parseLlmFewShots(configObj: JSONObject): List<com.tcs.vehicleassistant.core.LocalLlmPromptSupport.FewShot> {
        val list = mutableListOf<com.tcs.vehicleassistant.core.LocalLlmPromptSupport.FewShot>()
        if (configObj.has("llm_few_shots")) {
            val arr = configObj.getJSONArray("llm_few_shots")
            for (i in 0 until arr.length()) {
                val shot = arr.getJSONObject(i)
                list.add(
                    com.tcs.vehicleassistant.core.LocalLlmPromptSupport.FewShot(
                        user = shot.getString("user"),
                        assistant = shot.getString("assistant")
                    )
                )
            }
        }
        return list
    }

    private fun parseSystemInstructions(rootObj: JSONObject): List<SystemInstruction> {
        val list = mutableListOf<SystemInstruction>()
        if (rootObj.has("system_instructions")) {
            val arr = rootObj.getJSONArray("system_instructions")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val instruction = obj.getString("instruction")
                val keywords = mutableListOf<String>()
                if (obj.has("keywords")) {
                    val kArr = obj.getJSONArray("keywords")
                    for (j in 0 until kArr.length()) keywords.add(kArr.getString(j))
                }
                list.add(SystemInstruction(instruction, keywords))
            }
        }
        return list
    }
}
