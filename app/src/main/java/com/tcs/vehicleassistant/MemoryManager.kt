package com.tcs.vehicleassistant

import android.util.Log

object MemoryManager {
    private const val TAG = "MemoryManager"
    private const val DEFAULT_MAX_CHARS = 3000

    data class Turn(val role: String, val content: String)

    private val conversationHistory = mutableListOf<Turn>()

    private val followUpPatterns = listOf(
        "yes", "yeah", "yep", "sure", "ok", "okay", "do it", "go ahead",
        "no", "nope", "cancel", "never mind", "nevermind",
        "the first", "the second", "the third", "first one", "second one", "third one",
        "that one", "this one", "the one", "number one", "number two", "number three",
        "take me there", "navigate there", "let's go", "lets go"
    )

    fun addTurn(role: String, content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        conversationHistory.add(Turn(role, trimmed))
        Log.d(TAG, "Added turn: $role. Total turns: ${conversationHistory.size}")
    }

    fun isAffirmative(query: String): Boolean {
        val q = query.lowercase().trim()
        return q in setOf("yes", "yeah", "yep", "sure", "ok", "okay", "do it", "go ahead", "please", "yup")
    }

    fun isFollowUpQuery(query: String): Boolean {
        val q = query.lowercase().trim()
        if (q.length > 40) return false
        return followUpPatterns.any { q == it || q.contains(it) }
    }

    fun turnCount(): Int = conversationHistory.size

    /**
     * Returns the most recent conversation turns without mutating stored history.
     */
    fun getSlidingWindowContext(maxChars: Int = DEFAULT_MAX_CHARS): String {
        val sb = StringBuilder()
        var currentLength = 0
        val reversedContext = mutableListOf<String>()

        for (i in conversationHistory.indices.reversed()) {
            val turn = conversationHistory[i]
            val turnString = "${turn.role}: ${turn.content}\n"
            if (currentLength + turnString.length > maxChars) break
            reversedContext.add(turnString)
            currentLength += turnString.length
        }

        for (i in reversedContext.indices.reversed()) {
            sb.append(reversedContext[i])
        }

        return sb.toString().trim()
    }

    fun getAnthropicHistory(): List<org.json.JSONObject> {
        return conversationHistory.map { turn ->
            org.json.JSONObject().apply {
                val apiRole = if (turn.role.equals("User", true)) "user" else "assistant"
                put("role", apiRole)
                put("content", turn.content)
            }
        }
    }

    fun getGeminiHistory(): List<org.json.JSONObject> {
        return conversationHistory.map { turn ->
            org.json.JSONObject().apply {
                val apiRole = if (turn.role.equals("User", true)) "user" else "model"
                put("role", apiRole)
                put("parts", org.json.JSONArray().put(org.json.JSONObject().put("text", turn.content)))
            }
        }
    }

    fun clearMemory() {
        conversationHistory.clear()
        Log.i(TAG, "Conversation memory cleared.")
    }
}
