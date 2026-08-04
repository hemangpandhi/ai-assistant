package com.tcs.vehicleassistant.llm

import org.json.JSONArray
import org.json.JSONObject
import com.tcs.vehicleassistant.MemoryManager

/**
 * Builds the message arrays for the cloud LLM APIs from [MemoryManager] history.
 *
 * Both cloud managers used to read history directly and ignore their `userMessage` argument
 * entirely, which silently depended on the orchestrator having already recorded the user turn. If
 * that ordering ever changed the request would go out without the actual question. These builders
 * append the current message when history does not already end with it.
 */
object CloudRequestBuilder {

    private const val ROLE_USER = "user"
    private const val ROLE_MODEL = "model"
    private const val ROLE_ASSISTANT = "assistant"

    /** Gemini `contents` array: `[{role, parts:[{text}]}]`. */
    fun geminiContents(userMessage: String): List<JSONObject> =
        withUserMessage(MemoryManager.snapshot(), userMessage).map { turn ->
            JSONObject().apply {
                put("role", if (isUser(turn.role)) ROLE_USER else ROLE_MODEL)
                put("parts", JSONArray().put(JSONObject().put("text", turn.content)))
            }
        }

    /** Anthropic `messages` array: `[{role, content}]`. */
    fun anthropicMessages(userMessage: String): List<JSONObject> =
        withUserMessage(MemoryManager.snapshot(), userMessage).map { turn ->
            JSONObject().apply {
                put("role", if (isUser(turn.role)) ROLE_USER else ROLE_ASSISTANT)
                put("content", turn.content)
            }
        }

    /**
     * Guarantees the request ends with [userMessage] as a user turn. Both APIs reject a request
     * whose final message is from the assistant, and an empty history is rejected outright.
     */
    internal fun withUserMessage(
        history: List<MemoryManager.Turn>,
        userMessage: String
    ): List<MemoryManager.Turn> {
        val trimmed = userMessage.trim()
        if (trimmed.isEmpty()) return history

        val last = history.lastOrNull()
        if (last != null && isUser(last.role) && last.content.trim() == trimmed) return history

        return history + MemoryManager.Turn("User", trimmed)
    }

    private fun isUser(role: String) = role.equals("User", ignoreCase = true)
}
