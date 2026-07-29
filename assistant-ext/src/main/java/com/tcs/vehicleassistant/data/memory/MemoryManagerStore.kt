package com.tcs.vehicleassistant.data.memory

import android.content.Context
import com.tcs.vehicleassistant.MemoryManager

/** Default [ConversationMemory] delegating to the legacy MemoryManager singleton. */
class MemoryManagerStore : ConversationMemory {
    override fun addTurn(role: String, content: String) = MemoryManager.addTurn(role, content)

    override fun isFollowUpQuery(query: String): Boolean = MemoryManager.isFollowUpQuery(query)

    override fun isAffirmative(query: String): Boolean = MemoryManager.isAffirmative(query)

    override fun captureLongTermFacts(context: Context, query: String): Boolean =
        MemoryManager.captureLongTermFacts(context, query)

    override fun getSlidingWindowContext(maxChars: Int): String =
        MemoryManager.getSlidingWindowContext(maxChars)

    override fun getLongTermMemory(context: Context): String =
        MemoryManager.getLongTermMemory(context)

    override fun clearMemory() = MemoryManager.clearMemory()
}
