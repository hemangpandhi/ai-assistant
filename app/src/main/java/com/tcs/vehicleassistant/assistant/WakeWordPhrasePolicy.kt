package com.tcs.vehicleassistant.assistant

import com.tcs.vehicleassistant.core.AssistantConfig

/**
 * Wake-phrase matching and Vosk grammar helpers for UiUx.
 *
 * For a configured phrase like `hey iris`, also accepts the bare name `iris`
 * (and includes it in the constrained grammar so Vosk can emit it).
 */
object WakeWordPhrasePolicy {

    private val NAME_PREFIXES = setOf("hey", "hi", "ok", "okay")

    /**
     * Common assistant nouns that must not bare-match (too many false wakes in cabin speech).
     * Proper names like `iris` are allowed.
     */
    private val BARE_ALIAS_BLOCKLIST = setOf(
        "assistant",
        "google",
        "siri",
        "alexa",
        "copilot",
        "gemini",
        "nissan",
    )

    /**
     * Bare-name alias for `hey|hi|ok|okay <name>` (e.g. `hey iris` → `iris`).
     * Null when there is no safe single-token alias.
     */
    fun shortAlias(configuredWakeWord: String): String? {
        val parts = configuredWakeWord.lowercase().trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        if (parts[0] !in NAME_PREFIXES) return null
        val name = parts[1]
        if (name == AssistantConfig.WakeWord.UNKNOWN_TOKEN) return null
        if (name in BARE_ALIAS_BLOCKLIST) return null
        return name
    }

    /** Phrases to put in the Vosk grammar (configured + optional bare name + `[unk]`). */
    fun grammarPhrases(configuredWakeWord: String): Set<String> {
        val configured = configuredWakeWord.lowercase().trim()
            .ifEmpty { AssistantConfig.WakeWord.DEFAULT_WAKE_WORD }
        val phrases = linkedSetOf(configured, AssistantConfig.WakeWord.UNKNOWN_TOKEN)
        shortAlias(configured)?.let { phrases.add(it) }
        return phrases
    }

    fun matches(transcript: String, configuredWakeWord: String): Boolean {
        val configured = configuredWakeWord.lowercase().trim()
        if (configured.isEmpty()) return false
        if (matchesPhrase(transcript, configured)) return true
        val alias = shortAlias(configured) ?: return false
        return matchesPhrase(transcript, alias)
    }

    /**
     * True when [transcript] contains [phrase] as a fresh match (same rules as master’s
     * single-phrase gate: strip `[unk]`, reject repeated lead tokens / duplicate phrases).
     */
    fun matchesPhrase(transcript: String, phrase: String): Boolean {
        val configured = phrase.lowercase().trim()
        if (configured.isEmpty()) return false
        val text = normalizeTranscript(transcript)
        if (text.isEmpty()) return false
        if (!text.contains(configured)) return false

        val lead = configured.substringBefore(' ')
        if (lead.isNotEmpty()) {
            val leadRegex = Regex("""\b${Regex.escape(lead)}\b""")
            val inConfigured = leadRegex.findAll(configured).count()
            val inText = leadRegex.findAll(text).count()
            if (inText > inConfigured) return false
        }

        val first = text.indexOf(configured)
        val second = text.indexOf(configured, first + configured.length)
        return second < 0
    }

    fun normalizeTranscript(transcript: String): String =
        transcript.lowercase()
            .replace(AssistantConfig.WakeWord.UNKNOWN_TOKEN, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
