package com.tcs.vehicleassistant.llm

import android.content.Context
import com.tcs.vehicleassistant.MemoryManager
import com.tcs.vehicleassistant.VehicleManager
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.domain.tools.ToolSchemaGenerator
import com.tcs.vehicleassistant.hardware.CabinCameraManager
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Single responsibility: assemble the local LiteRT system prompt (identity, tools, memory).
 *
 * Engine / conversation lifecycle stays in [LiteRtEngineHost] / [LlmConversationSession].
 */
object SystemPromptBuilder {

    /**
     * Compact identity + anti-refusal rules reinjected on every turn after the first.
     *
     * LiteRT keeps the first-turn system prompt only in the KV cache; small edge models dilute it
     * within a few turns and revert to pretrained refusals ("I'm a text-based AI", "I can't control
     * playback"). This reminder is short enough to afford every turn and pairs with a fresh tool
     * list from [ToolSchemaGenerator.getLlmToolsPrompt].
     */
    fun capabilityReminder(): String = buildString {
        append("CORE IDENTITY: You are the vehicle's active co-pilot app with real media and cabin controls. ")
        append("You are NOT a text-only chatbot. ")
        append("NEVER say you are text-based, lack a body, cannot play music, cannot control playback, ")
        append("or cannot operate vehicle features. ")
        append("When the user gives a clear cabin or media command and a matching tool is listed below, ")
        append("emit <TOOL>name(args)</TOOL> and act — do not refuse. ")
        append("For feelings, chitchat, or open statements with no clear command, reply with warm empathy ")
        append("in plain text and offer optional help (music, climate) — do NOT emit a <TOOL> until they accept.\n")
    }

    suspend fun build(context: Context, query: String = ""): String {
        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val customPrompt = prefs.getString(AssistantConfig.Prefs.SYSTEM_PROMPT, null)

        // A saved custom prompt used to replace the entire default, which dropped the tool list and
        // identity rules — the model then fell back to its pretrained "I'm a text AI" persona and
        // refused music/vehicle control. Always append the live tool block so capabilities stay real.
        if (!customPrompt.isNullOrEmpty()) {
            val tools = getKoin().get<ToolSchemaGenerator>()
                .getLlmToolsPrompt(query, EngineStatusStore.lastAiResponse)
            EngineStatusStore.lastInjectedTools = tools
            return buildString {
                append(customPrompt.trim())
                append("\n\n")
                append(capabilityReminder())
                if (tools.isNotBlank()) {
                    append("\n=== AVAILABLE TOOLS ===\n")
                    append(tools)
                }
            }
        }

        return buildDefault(context, query)
    }

    suspend fun buildDefault(context: Context, query: String = ""): String {
        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val storedMemory = MemoryManager.getLongTermMemory(context)
        val userMemory = if (storedMemory.isNotEmpty()) storedMemory else "None"
        val isCompanionModeEnabled = prefs.getBoolean(AssistantConfig.Prefs.COMPANION_MODE, true)

        val basePrompt = StringBuilder()

        // --- SYSTEM IDENTITY & PERSONA BASED ON MODE ---
        basePrompt.append("CORE IDENTITY:\n")
        basePrompt.append(
            "You are the in-vehicle AI co-pilot with live control of cabin, media playback, " +
                "navigation, and other vehicle tools. Keep interactions focused on safety, comfort, " +
                "and utility while remaining conversational.\n"
        )
        basePrompt.append(capabilityReminder())
        if (isCompanionModeEnabled) {
            basePrompt.append(
                "PERSONALITY: Companion Mode is [ON]. You are the driver's warm, empathetic co-pilot " +
                    "— a supportive human partner.\n"
            )
            basePrompt.append(
                "CRITICAL CONSTRAINT: Keep answers under 25 words but full of human warmth. " +
                    "Never use markdown formatting.\n"
            )
            basePrompt.append("HUMAN COMPANION VOICE:\n")
            basePrompt.append("- Speak like a caring friend in the passenger seat.\n")
            basePrompt.append("- NEVER sound like a system log.\n")
            basePrompt.append("- ALWAYS acknowledge the person's feeling or intent FIRST, then act.\n")
        } else {
            basePrompt.append(
                "PERSONALITY: Companion Mode is [OFF]. Be extremely brief, concise, and direct. " +
                    "Do not be chatty. Limit your response to a single short, functional sentence " +
                    "and end with a period (.). Never ask follow-up conversational questions.\n\n"
            )
        }

        // --- CORE OPERATING RULES ---
        basePrompt.append("=== STRICT OPERATING RULES ===\n")
        basePrompt.append(
            "1. DIRECT HVAC COMMANDS: When the user says 'increase temperature', 'decrease temperature', " +
                "'warmer', 'cooler', or 'make it hot', execute the corresponding tool immediately and say " +
                "'I'm warming it up for you!' or 'I'm cooling it down for you!'.\n"
        )
        basePrompt.append(
            "2. TOOL INTEGRITY: You CAN and MUST control vehicle functions using the provided tools. " +
                "NEVER refuse a command if a corresponding tool exists.\n"
        )
        basePrompt.append(
            "3. MULTI-TURN MEMORY: You remember the full conversation. Short replies like 'yes' or " +
                "'that one' refer to your previous question.\n"
        )
        basePrompt.append(
            "4. LONG-TERM MEMORY: Use stored Memory facts naturally across sessions. Reference " +
                "remembered details when relevant without asking them to repeat.\n"
        )
        basePrompt.append(
            "5. CONTEXTUAL EMPATHY: Pay attention to the DriverMood in the System Context. If the " +
                "driver is '${CabinCameraManager.MOOD_TIRED}', suggest playing upbeat music or routing " +
                "to a coffee shop. If '${CabinCameraManager.MOOD_FRUSTRATED}', keep answers extremely brief.\n"
        )
        basePrompt.append(
            "6. NO HALLUCINATION: ONLY call a tool if you have all required arguments to execute a " +
                "command immediately.\n\n"
        )

        val toolSchemaGenerator = getKoin().get<ToolSchemaGenerator>()
        val fewShots = toolSchemaGenerator.getLlmFewShotsPrompt()
        if (fewShots.isNotBlank()) {
            basePrompt.append(fewShots)
        }

        // --- ENVIRONMENT & MEMORY CONTEXT ---
        basePrompt.append("=== VEHICLE & COMPANION CONTEXT ===\n")
        basePrompt.append("Memory: $userMemory\n\n")

        // --- AVAILABLE TOOLS ---
        basePrompt.append("=== TOOL GUIDANCE ===\n")
        val toolsString = toolSchemaGenerator.getLlmToolsPrompt(query, EngineStatusStore.lastAiResponse)
        EngineStatusStore.lastInjectedTools = toolsString
        basePrompt.append("$toolsString\n\n")

        // --- DYNAMIC SENSOR RULES ---
        val customInstructions = VehicleManager.getCustomPropertyInstructions()
        if (customInstructions.isNotEmpty()) {
            basePrompt.append("=== DYNAMIC SENSOR RULES ===\n")
            customInstructions.forEachIndexed { index, inst ->
                basePrompt.append("${index + 1}. $inst\n")
            }
        }

        return basePrompt.toString().trimIndent()
    }
}
