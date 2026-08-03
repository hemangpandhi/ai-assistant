package com.tcs.vehicleassistant.core

/**
 * Decides whether the native LiteRT conversation should be recycled before the next prompt
 * is built, so the rebuilt prompt sees [LLMManager.isFirstMessage]=true after reset.
 */
object ConversationResetPolicy {

    /**
     * @param nativeTurnsSinceReset turns already sent since the last successful reset
     * @param resetEveryN recycle threshold from [AssistantConfig.Llm.CONVERSATION_RESET_TURNS]
     */
    fun shouldResetBeforePrompt(nativeTurnsSinceReset: Int, resetEveryN: Int): Boolean {
        if (resetEveryN <= 0) return false
        return nativeTurnsSinceReset >= resetEveryN
    }
}
