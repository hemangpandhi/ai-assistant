package com.tcs.vehicleassistant.core

/**
 * Pure helpers for the LLM tool loop: confirmation asks must never be narrated as
 * "I ran X", and ContextGuard Confirm / registry `requires_confirmation` must surface
 * as spoken questions before any VHAL write.
 */
object LlmToolTurnPolicy {

    data class FinalDisplay(
        val text: String,
        val asQuestion: Boolean,
    )

    fun confirmationAskMessage(toolName: String, confirmationMessage: String?): String {
        val custom = confirmationMessage?.trim().orEmpty()
        if (custom.isNotEmpty()) return custom
        return "Are you sure you want me to run $toolName?"
    }

    /**
     * Resolve what to show/speak when model prose is empty or only a placeholder.
     *
     * Priority: pending confirmation ask → useful tool feedback → actually-executed ACK →
     * [emptyFallback]. Never claims a tool ran when only a confirm was stashed.
     */
    fun resolveEmptyProseDisplay(
        confirmationAsks: List<String>,
        toolFeedbacks: List<String>,
        actuallyExecutedToolCalls: Collection<String>,
        emptyFallback: String,
    ): FinalDisplay {
        val ask = confirmationAsks.map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (ask != null) {
            return FinalDisplay(text = ask, asQuestion = true)
        }
        val useful = toolFeedbacks
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("Action completed.", ignoreCase = true) }
            .distinct()
        if (useful.isNotEmpty()) {
            val text = useful.joinToString(" ")
            return FinalDisplay(text = text, asQuestion = looksLikeQuestion(text))
        }
        if (actuallyExecutedToolCalls.isNotEmpty()) {
            val names = actuallyExecutedToolCalls.map { it.substringBefore("(").trim() }
            if (names.size == 1) {
                return FinalDisplay(text = "Okay — I ran ${names.first()}.", asQuestion = false)
            } else if (names.size == 2) {
                return FinalDisplay(text = "Okay — I ran ${names[0]} and ${names[1]}.", asQuestion = false)
            } else {
                val last = names.last()
                val rest = names.dropLast(1).joinToString(", ")
                return FinalDisplay(text = "Okay — I ran $rest, and $last.", asQuestion = false)
            }
        }
        return FinalDisplay(text = emptyFallback, asQuestion = looksLikeQuestion(emptyFallback))
    }

    fun looksLikeQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.endsWith("?")) return true
        return t.contains("would you like", ignoreCase = true) ||
            t.contains("do you want", ignoreCase = true) ||
            t.contains("shall i", ignoreCase = true) ||
            t.contains("are you sure", ignoreCase = true) ||
            t.contains("should i", ignoreCase = true) ||
            t.contains("want me to", ignoreCase = true)
    }

    /** True when confirm/error feedback must be spoken even if the model already produced prose. */
    fun shouldSpeakToolFeedback(
        pendingConfirmation: Boolean,
        confirmationAsks: List<String>,
        toolFeedbacks: List<String>,
    ): Boolean {
        if (pendingConfirmation || confirmationAsks.any { it.isNotBlank() }) return true
        return toolFeedbacks.any {
            it.contains("Error", true) || it.contains("Failed", true) ||
                it.contains("couldn't", true) || it.contains("didn't confirm", true) ||
                it.contains("won't", true) || it.contains("I couldn't verify", true)
        }
    }
}
