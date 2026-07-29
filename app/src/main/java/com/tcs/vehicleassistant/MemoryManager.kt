package com.tcs.vehicleassistant

import android.content.Context
import android.util.Log

object MemoryManager {
    private const val TAG = "MemoryManager"
    private const val DEFAULT_MAX_CHARS = 3000

    data class Turn(val role: String, val content: String)

    private val conversationHistory = java.util.Collections.synchronizedList(mutableListOf<Turn>())

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

    fun isFollowUpQuery(query: String, previousResponse: String = ""): Boolean {
        val q = query.lowercase().trim()
        if (q.length > 60) return false
        
        if (previousResponse.trim().endsWith("?")) return true
        
        if (followUpPatterns.any { Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(q) }) {
            return true
        }
        
        val words = q.split(Regex("\\s+"))
        if (words.size <= 3) return true
        
        return false
    }

    private val longTermCapturePatterns = listOf(
        Regex("""remember(?:\s+that)?\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""don't forget(?:\s+that)?\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""do not forget(?:\s+that)?\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""my name is\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""call me\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""i prefer\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""i like\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""i usually\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""my (?:wife|husband|partner|mom|dad|mother|father)(?:'s)? name is\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""keep in mind(?:\s+that)?\s+(.+)""", RegexOption.IGNORE_CASE)
    )

    fun getLongTermMemory(context: Context): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("user_memory", "")?.trim().orEmpty()
    }

    /**
     * Auto-captures durable user facts from natural speech (e.g. "remember I prefer 72 degrees").
     * Returns true when a new fact was stored.
     */
    fun captureLongTermFacts(context: Context, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.length < 8) return false

        for (pattern in longTermCapturePatterns) {
            val match = pattern.find(trimmed) ?: continue
            val fact = sanitizeFact(match.groupValues[1]) ?: continue
            if (appendLongTermFact(context, fact)) {
                Log.i(TAG, "Captured long-term memory: $fact")
                return true
            }
        }
        return false
    }

    private fun sanitizeFact(raw: String): String? {
        val fact = raw.trim().trimEnd('.', '!', '?', ',')
        if (fact.length < 3 || fact.length > 200) return null
        if (fact.equals("that", ignoreCase = true)) return null
        return fact
    }

    private fun appendLongTermFact(context: Context, fact: String): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val current = prefs.getString("user_memory", "")?.trim().orEmpty()
        if (current.contains(fact, ignoreCase = true)) return false
        val newMemory = if (current.isEmpty()) fact else "$current. $fact"
        prefs.edit().putString("user_memory", newMemory).apply()
        return true
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
