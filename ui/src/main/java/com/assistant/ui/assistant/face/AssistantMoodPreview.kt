package com.assistant.ui.assistant.face

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide ADB override for immersive face mood / Nomi-Mate expression.
 *
 * When non-null, the main overlay prefers this over session [StageState.mood].
 * [clear] restores pipeline / LLM mood.
 */
object AssistantMoodPreview {
    private val _mood = MutableStateFlow<AssistantMood?>(null)
    val mood: StateFlow<AssistantMood?> = _mood.asStateFlow()

    fun current(): AssistantMood? = _mood.value

    fun set(mood: AssistantMood?) {
        _mood.value = mood
    }

    fun clear() {
        _mood.value = null
    }

    fun setFromRaw(raw: String?): Boolean {
        val parsed = parseMood(raw) ?: return false
        set(parsed)
        return true
    }

    fun describe(): String = _mood.value?.name?.lowercase() ?: "off"

    fun parseMood(raw: String?): AssistantMood? {
        val key = raw?.trim()?.lowercase().orEmpty()
        if (key.isEmpty() || key == "off" || key == "clear" || key == "none") return null
        return AssistantMood.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
            ?: AssistantMood.entries.firstOrNull { it.label.equals(key, ignoreCase = true) }
    }
}
