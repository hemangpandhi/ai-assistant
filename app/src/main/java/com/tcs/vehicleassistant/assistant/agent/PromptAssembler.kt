package com.tcs.vehicleassistant.assistant.agent

import com.tcs.vehicleassistant.core.ConversationSafetyPolicy
import com.tcs.vehicleassistant.core.ConversationalIntent

/**
 * Pure prompt / chat-hint assembly for Gemma-style turns.
 *
 * No Android, LiteRT, or Koin — orchestrator supplies sysPrompt / tools / history strings.
 */
object PromptAssembler {

    fun chatHint(userQuery: String, emptyChatRetry: Int = 0): String = when {
        ConversationSafetyPolicy.isCrisis(userQuery) ->
            ConversationSafetyPolicy.CRISIS_CHAT_HINT
        emptyChatRetry > 0 || ConversationalIntent.isEmotionalOrWellness(userQuery) ->
            "[System: Reply with warm empathy only — no tools this turn. Acknowledge the feeling. " +
                "For mild stress or low mood you may softly offer music or climate; " +
                "never offer entertainment after accidents, injury, or emergencies.]\n"
        else -> ""
    }

    /**
     * @param isFirstMessage when true, wraps the full system prompt; otherwise re-injects
     * capability reminder + tools only (LiteRT KV already holds history).
     */
    fun buildGemmaTurn(
        isFirstMessage: Boolean,
        sysPrompt: String,
        capabilityReminder: String,
        toolsBlock: String,
        historyBlock: String,
        stateInject: String,
        chatHint: String,
        formattedQuery: String,
    ): String {
        return if (isFirstMessage) {
            buildString {
                append("<start_of_turn>system\n")
                append(sysPrompt)
                append("\n<end_of_turn>\n<start_of_turn>user\n")
                if (historyBlock.isNotBlank()) append(historyBlock)
                if (stateInject.isNotBlank()) append('\n').append(stateInject)
                if (chatHint.isNotBlank()) append('\n').append(chatHint)
                append('\n').append(formattedQuery)
                append("\n<end_of_turn>\n<start_of_turn>model\n")
            }.trim()
        } else {
            buildString {
                append("<start_of_turn>user\n")
                append(capabilityReminder)
                append('\n')
                append(toolsBlock)
                if (stateInject.isNotBlank()) append(stateInject)
                if (chatHint.isNotBlank()) append(chatHint)
                append(formattedQuery)
                append("\n<end_of_turn>\n<start_of_turn>model\n")
            }.trim()
        }
    }

    fun toolsBlock(toolsForTurn: String): String =
        if (toolsForTurn.isNotBlank()) "=== AVAILABLE TOOLS ===\n$toolsForTurn\n" else ""
}
