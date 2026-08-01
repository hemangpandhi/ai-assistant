package com.tcs.vehicleassistant.core

/**
 * Lightweight utterance classifiers for chat / wellness / ASR-noise routing.
 *
 * Shared by [com.tcs.vehicleassistant.repository.AgentOrchestrator] and
 * [com.tcs.vehicleassistant.ToolManager] so "emotional / open chat" vs cabin
 * command vs mic garbage use one definition.
 *
 * Climate phrases like "I'm feeling hot/cold" are intentionally excluded —
 * those remain DirectTool / BM25 cabin intents.
 */
object ConversationalIntent {

    private val CLIMATE_FEELING = Regex(
        """(?i)\b(feeling|feel|i'?m|i am)\s+(very\s+)?(too\s+)?(hot|cold|warm|chilly|freezing)\b""" +
            """|\b(too\s+hot|too\s+cold|i'?m\s+hot|i'?m\s+cold|i am\s+hot|i am\s+cold)\b""",
    )

    private val EMOTIONAL_WELLNESS = Regex(
        """(?i)\b(""" +
            """not feeling (good|well|great|ok|okay)|feeling (sad|down|bad|low|lonely|stressed|anxious|upset|depressed|blue|miserable)|""" +
            """i'?m (sad|depressed|stressed|lonely|upset|anxious|miserable)|""" +
            """i am (sad|depressed|stressed|lonely|upset|anxious|miserable)|""" +
            """feeling (very\s+)?(sad|depressed|stressed|lonely|upset|anxious)|""" +
            """(sad|depressed|stressed|lonely|upset|anxious|miserable)\b""" +
            """)""",
    )

    private val OPEN_CHAT = Regex(
        """(?i)\b(""" +
            """how are you|how'?s it going|how is it going|what'?s up|whats up|""" +
            """i'?m bored|i am bored|talk to me|keep me company|are you there|can we (talk|chat)""" +
            """)""",
    )

    private val CABIN_ACTION_HINT = Regex(
        """(?i)\b(""" +
            """play|stop|pause|volume|navigate|temperature|ac|a/?c|fan|heater|""" +
            """window|door|defrost|music|song|seat|climate""" +
            """)\b""",
    )

    fun isEmotionalOrWellness(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty() || q.startsWith("[")) return false
        if (CLIMATE_FEELING.containsMatchIn(q)) return false
        return EMOTIONAL_WELLNESS.containsMatchIn(q)
    }

    /**
     * Open-ended chat (including emotional wellness) where we should prefer empathy
     * over tool catalogs. Cabin commands return false.
     */
    fun isOpenChat(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty() || q.startsWith("[")) return false
        if (CLIMATE_FEELING.containsMatchIn(q)) return false
        if (isEmotionalOrWellness(q)) return true
        if (CABIN_ACTION_HINT.containsMatchIn(q) && !OPEN_CHAT.containsMatchIn(q)) return false
        return OPEN_CHAT.containsMatchIn(q)
    }

    /**
     * Garbled / near-empty ASR that should get an honest "didn't catch that"
     * (as opposed to mid-phrase echo fragments, which stay silent).
     */
    fun isLikelyAsrGarbage(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty() || q.startsWith("[")) return false
        if (isEmotionalOrWellness(q) || isOpenChat(q)) return false
        if (CABIN_ACTION_HINT.containsMatchIn(q)) return false
        // Single token with almost no vowels — classic STT hash.
        val tokens = q.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (tokens.size == 1) {
            val t = tokens[0]
            val vowels = t.count { it in "aeiou" }
            if (t.length >= 4 && vowels == 0) return true
        }
        return false
    }
}
