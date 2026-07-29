package com.tcs.vehicleassistant.data.memory

import android.content.Context

/**
 * Conversation + long-term memory port (replaces direct MemoryManager object access).
 */
interface ConversationMemory {
    fun addTurn(role: String, content: String)
    fun isFollowUpQuery(query: String): Boolean
    fun isAffirmative(query: String): Boolean
    fun captureLongTermFacts(context: Context, query: String): Boolean
    fun getSlidingWindowContext(maxChars: Int = 3000): String
    fun getLongTermMemory(context: Context): String
    fun clearMemory()
}
