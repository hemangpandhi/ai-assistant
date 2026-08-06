package com.tcs.vehicleassistant.assistant

import com.tcs.vehicleassistant.core.AssistantConfig

/**
 * Strict wake-phrase gate for UiUx.
 *
 * Only `(hey|hi|hello|ok|okay) + (iris|car|sora)` opens the assistant.
 * Bare greetings (`hi`, `hello`) and bare names (`iris`) never match.
 */
object WakeWordPhrasePolicy {

    private val PREFIXES = listOf("hey", "hi", "hello", "ok", "okay")
    private val NAMES = listOf("iris", "car", "sora")

    /** Full two-word phrases accepted as a wake. */
    val ALLOWED_PHRASES: Set<String> =
        PREFIXES.flatMap { prefix -> NAMES.map { name -> "$prefix $name" } }.toSet()

    /**
     * Bare-name alias — always null. Kept for call-site / test compatibility; bare names
     * false-trigger too often with a constrained Vosk grammar.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shortAlias(configuredWakeWord: String): String? = null

    /** Phrases to put in the Vosk grammar (allowlist + `[unk]`). */
    @Suppress("UNUSED_PARAMETER")
    fun grammarPhrases(configuredWakeWord: String): Set<String> =
        LinkedHashSet<String>(ALLOWED_PHRASES.size + 1).apply {
            addAll(ALLOWED_PHRASES)
            add(AssistantConfig.WakeWord.UNKNOWN_TOKEN)
        }

    /**
     * True when [transcript] contains exactly one allowed wake phrase.
     * [configuredWakeWord] is ignored — the allowlist is fixed.
     */
    @Suppress("UNUSED_PARAMETER")
    fun matches(transcript: String, configuredWakeWord: String): Boolean {
        val text = normalizeTranscript(transcript)
        if (text.isEmpty()) return false
        for (phrase in ALLOWED_PHRASES) {
            if (matchesPhrase(text, phrase)) return true
        }
        return false
    }

    /**
     * True when [transcript] contains [phrase] as whole words once (same rematch guards
     * as before: reject repeated lead tokens / duplicate phrases).
     */
    fun matchesPhrase(transcript: String, phrase: String): Boolean {
        val configured = phrase.lowercase().trim()
        if (configured.isEmpty()) return false
        val text = normalizeTranscript(transcript)
        if (text.isEmpty()) return false

        // Whole-phrase boundary: "hi" must not match inside "hi car" checks for other phrases,
        // and "hi" alone must not contain "hi car".
        val phraseRegex = Regex("""\b${Regex.escape(configured)}\b""")
        val phraseHits = phraseRegex.findAll(text).count()
        if (phraseHits != 1) return false

        val lead = configured.substringBefore(' ')
        if (lead.isNotEmpty()) {
            val leadRegex = Regex("""\b${Regex.escape(lead)}\b""")
            val inConfigured = leadRegex.findAll(configured).count()
            val inText = leadRegex.findAll(text).count()
            if (inText > inConfigured) return false
        }

        return true
    }

    fun normalizeTranscript(transcript: String): String =
        transcript.lowercase()
            .replace(AssistantConfig.WakeWord.UNKNOWN_TOKEN, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Normalize a Sherpa KWS keyword label (often phoneme-spaced, e.g. `H EY I R I S`)
     * into a lowercase phrase for [matches].
     */
    fun normalizeKwsKeyword(raw: String): String {
        val lower = raw.lowercase().trim()
        if (lower.isEmpty()) return ""
        val compact = lower.replace(" ", "")
        for (phrase in ALLOWED_PHRASES) {
            if (compact == phrase.replace(" ", "")) return phrase
        }
        return normalizeTranscript(lower)
    }
}
