package com.tcs.vehicleassistant.wakeword

/**
 * Wake-phrase matcher with Vosk confusion aliases (UI/UX extension).
 *
 * Kept outside [com.tcs.vehicleassistant.WakeWordService] so the alias table can
 * evolve without fighting `dev/refactor` service-shell changes.
 */
object WakePhraseMatcher {

    private val ALIASES = listOf(
        "hey nissan",
        "nissan",
        "hey nice",
        "hey me",
        "hey listen",
        "hey lisa",
        "hey mason",
        "hey nathan",
        "hey missing",
        "hey auto",
        "hey otto",
        "hey out",
        "hey miss",
        "hey reason",
        "hey recent",
        "hey decent",
        "hey sam",
        "hey sun",
        "hey son",
        "hey i have a hot",
        "haney",
        "nisa",
        "haney sir",
    )

    fun matches(hypothesis: String, configuredWakeWord: String): Boolean {
        val lower = hypothesis.lowercase()
        if (lower.contains(configuredWakeWord.lowercase())) return true
        return ALIASES.any { lower.contains(it) }
    }
}
