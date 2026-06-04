package com.example.gemininano

import android.util.Log

object MemoryManager {
    private const val TAG = "MemoryManager"
    
    data class Turn(val role: String, val content: String)
    
    private val conversationHistory = mutableListOf<Turn>()
    
    fun addTurn(role: String, content: String) {
        conversationHistory.add(Turn(role, content))
        Log.d(TAG, "Added turn: $role. Total turns: ${conversationHistory.size}")
    }
    
    fun getSlidingWindowContext(maxChars: Int): String {
        val sb = java.lang.StringBuilder()
        var currentLength = 0
        
        // We iterate backwards to keep the most recent messages
        val reversedContext = mutableListOf<String>()
        for (i in conversationHistory.indices.reversed()) {
            val turn = conversationHistory[i]
            val turnString = "${turn.role}: ${turn.content}\n"
            
            if (currentLength + turnString.length > maxChars) {
                Log.w(TAG, "Sliding window threshold reached. Truncating ${i + 1} oldest turns.")
                // Remove the truncated turns from history to prevent memory leak
                conversationHistory.subList(0, i + 1).clear()
                break
            }
            reversedContext.add(turnString)
            currentLength += turnString.length
        }
        
        // Reverse back and append
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
