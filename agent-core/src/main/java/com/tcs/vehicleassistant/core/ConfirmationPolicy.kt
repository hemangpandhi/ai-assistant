package com.tcs.vehicleassistant.core

/**
 * Pure classification of short replies while a ContextGuard / OEM confirmation is pending.
 * Kept out of [com.tcs.vehicleassistant.repository.AgentOrchestrator] so JVM tests can lock behavior.
 */
object ConfirmationPolicy {

    enum class Reply {
        /** User accepted the pending tool. */
        AFFIRM,
        /** User explicitly rejected the pending tool. */
        DECLINE,
        /** Anything else — pending should be cleared and the new utterance handled normally. */
        OTHER,
    }

    fun classify(query: String): Reply {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("[")) return Reply.OTHER
        // Decline before affirm so ambiguous "yes… no…" never writes.
        if (isDecline(trimmed)) return Reply.DECLINE
        if (isAffirmative(trimmed)) return Reply.AFFIRM
        return Reply.OTHER
    }

    fun isAffirmative(query: String): Boolean {
        if (isDecline(query)) return false
        val q = normalize(query)
        if (q in AFFIRM_EXACT) return true
        // ASR politeness: "yes please", "yeah sure", "ok go ahead" — not bare "please".
        return AFFIRM_PREFIXES.any { q.startsWith(it) } && q.split(' ').size <= 4
    }

    fun isDecline(query: String): Boolean {
        val q = normalize(query)
        if (q in DECLINE_EXACT) return true
        if (q.startsWith("no ") || q.startsWith("nope ") || q.startsWith("nah ")) return true
        // Ambiguous "yes … no …" → decline for cabin safety.
        if (Regex("""\byes\b""").containsMatchIn(q) &&
            Regex("""\b(no|nope|nah)\b""").containsMatchIn(q)
        ) {
            return true
        }
        // Whole-utterance / word-boundary — avoid "I don't want X, do Y" false declines via loose contains.
        val declinePhrase = Regex(
            """^(?:no|nope|nah|cancel|never\s*mind)(?:\s+.*)?$|^(?:don'?t|do\s+not)\s+(?:do\s+that|change|increase|open|unlock).*$""",
            RegexOption.IGNORE_CASE,
        )
        return declinePhrase.containsMatchIn(q)
    }

    private fun normalize(query: String): String =
        query.lowercase().trim()
            .trimEnd('.', '!', ',', '?')
            .trim()

    private val AFFIRM_EXACT = setOf(
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "do it", "go ahead",
    )

    private val AFFIRM_PREFIXES = listOf(
        "yes ", "yeah ", "yep ", "yup ", "sure ", "ok ", "okay ",
    )

    private val DECLINE_EXACT = setOf(
        "no", "nope", "nah", "cancel", "never mind", "nevermind", "don't", "do not",
        "no thanks", "no thank you", "not now", "not really", "stop",
    )
}
