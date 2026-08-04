package com.tcs.vehicleassistant.assistant.agent

import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.ConfirmationPolicy
import com.tcs.vehicleassistant.core.ConversationSafetyPolicy
import com.tcs.vehicleassistant.core.ConversationalIntent

/**
 * Stream display / cutoff / empty-fallback policy — no TTS, no LiteRT, no tool I/O.
 *
 * Safe to unit-test and change without touching [AgentOrchestrator] execution paths.
 */
object StreamTextPolicy {

    const val WELLNESS_OFFER =
        "I'm sorry you're not feeling well. Would quiet help, or would you like me to play some music?"

    private const val EMPTY_CATCH_FALLBACK = "I didn't catch that. Could you say that again?"
    private const val EMPTY_TOOL_FALLBACK = "I couldn't run a tool for that. Want to try again?"
    private const val EMPTY_CHAT_LAST_RESORT =
        "I'm here with you. Would you like me to play some music or adjust the cabin?"

    val ROLE_PREFIXES: List<String> = listOf(
        "Assistant:", "Response:", "User:", "Assistant :", "Response :", "User :",
        "System:", "System :", "<start_of_turn>", "<end_of_turn>", "model\n", "user\n",
        "model", "user",
    )

    private val SPECIAL_TOKEN_REGEX =
        Regex("(?i)<start_of_turn>|<end_of_turn>|start_of_turn|end_of_turn|start of turn|end of turn")
    private val ROLE_TOKEN_REGEX = Regex("(?i)\\bmodel\\b\\n?|\\buser\\b\\n?")

    /** Splits streamed text into speakable sentences at terminal punctuation or newlines. */
    val SENTENCE_REGEX: Regex =
        "^(.*?)([.!?]{2,}(?:\\s+|$)|\\n|(?<=[a-zA-Z\\)\\]\\\"])[.,!?](?:\\s+|$))".toRegex()

    private val LEADING_I_REGEX = Regex("^i\\s+")
    private val BARE_I_REGEX = Regex("^i\\b")
    private val DOUBLED_I_REGEX = Regex("\\biI\\b")
    private val I_CAN_I_REGEX = Regex("\\bi can I\\b", RegexOption.IGNORE_CASE)

    private val USER_QUESTION_PREFIX = Regex(
        "(?i)^(what|why|how|when|where|who|which|can you|could you|would you|will you|" +
            "do you|did you|is |are |am i|should |tell me|explain)\\b",
    )

    private val ACTION_REQUEST_HINT = Regex(
        "(?i)\\b(" +
            "play|stop|pause|resume|skip|next|previous|mute|unmute|volume|" +
            "turn on|turn off|increase|decrease|set|open|close|lock|unlock|" +
            "navigate|navigation|directions|call|text|message|warm|cool|" +
            "heater|defrost|ac|a\\.?c\\.?|fan|music|song|track" +
            ")\\b",
    )

    fun normalizeForDisplay(text: String): String = text
        .replace(DOUBLED_I_REGEX, "I")
        .replace(I_CAN_I_REGEX, "I can")
        .replace(LEADING_I_REGEX, "")
        .replace(BARE_I_REGEX, "I")
        .trim()

    fun isRunawayGeneration(text: String): Boolean {
        if (text.length > AssistantConfig.Streaming.RUNAWAY_LENGTH) return true
        if (text.length <= AssistantConfig.Streaming.REPETITION_SCAN_MIN_LENGTH) return false
        val window = AssistantConfig.Streaming.REPETITION_WINDOW
        val lastWords = text.trim().split(Regex("\\s+")).takeLast(window)
        return lastWords.size == window && lastWords.distinct().size == 1
    }

    /** Strip role scaffolding / special tokens while leaving tool tags for later parse. */
    fun scrubStreamChunk(text: String): String {
        var currentText = text
        var stripped = true
        while (stripped) {
            stripped = false
            for (prefix in ROLE_PREFIXES) {
                if (currentText.trimStart().startsWith(prefix, ignoreCase = true)) {
                    currentText = currentText.trimStart().substring(prefix.length).trimStart()
                    stripped = true
                }
            }
        }
        return currentText
            .replace(SPECIAL_TOKEN_REGEX, "")
            .replace(ROLE_TOKEN_REGEX, "")
    }

    /**
     * Detects model echoing a new "User:" turn (hallucination). Returns truncated text + flag.
     */
    fun cutHallucinatedUserEcho(text: String): Pair<String, Boolean> {
        val userIdx = text.indexOf("\nUser:")
        if (userIdx != -1) {
            return text.substring(0, userIdx) to true
        }
        if (text.trim().endsWith("User:")) {
            return text.substringBeforeLast("User:") to true
        }
        return text to false
    }

    fun sanitizeFinalReply(userQuery: String, modelReply: String): String =
        ConversationSafetyPolicy.sanitizeAssistantReply(userQuery, modelReply)

    fun resolveEmptyModelFallback(userQuery: String): String {
        val crisis = ConversationSafetyPolicy.evaluate(userQuery)
        if (crisis.isCrisis) return crisis.spokenResponse
        if (ConversationalIntent.isEmotionalOrWellness(userQuery)) return WELLNESS_OFFER
        if (ConfirmationPolicy.isAffirmative(userQuery)) {
            return "Got it — would you like me to play some music, or something else?"
        }
        if (ConfirmationPolicy.isDecline(userQuery)) {
            return "Okay — what would you like me to do instead?"
        }
        if (ConversationalIntent.isOpenChat(userQuery)) return EMPTY_CHAT_LAST_RESORT
        if (looksLikeActionRequest(userQuery)) return EMPTY_TOOL_FALLBACK
        if (ConversationalIntent.isLikelyAsrGarbage(userQuery)) return EMPTY_CATCH_FALLBACK
        if (looksLikeUserQuestion(userQuery)) return EMPTY_CATCH_FALLBACK
        return ""
    }

    fun looksLikeUserQuestion(userQuery: String): Boolean {
        val q = userQuery.trim()
        if (q.isEmpty() || q.startsWith("[")) return false
        if (q.endsWith("?")) return true
        return USER_QUESTION_PREFIX.containsMatchIn(q)
    }

    fun looksLikeActionRequest(userQuery: String): Boolean {
        val q = userQuery.trim()
        if (q.isEmpty() || q.startsWith("[")) return false
        return ACTION_REQUEST_HINT.containsMatchIn(q)
    }
}
