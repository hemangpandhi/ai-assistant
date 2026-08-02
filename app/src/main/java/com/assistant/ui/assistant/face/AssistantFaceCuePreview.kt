package com.assistant.ui.assistant.face

import com.assistant.ui.assistant.api.AssistantFaceCues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide ADB / debug override for immersive face anatomy cues.
 *
 * When non-null, the main overlay prefers this over LLM-driven [StageState.faceCues].
 * [clear] restores LLM / geometric default.
 */
object AssistantFaceCuePreview {
    private val _cues = MutableStateFlow<AssistantFaceCues?>(null)
    val cues: StateFlow<AssistantFaceCues?> = _cues.asStateFlow()

    fun current(): AssistantFaceCues? = _cues.value

    fun set(cues: AssistantFaceCues?) {
        _cues.value = cues?.takeUnless { it.isEmpty }
    }

    fun clear() {
        _cues.value = null
    }

    fun describe(): String {
        val c = _cues.value
        if (c == null) return "off"
        return buildString {
            append("left_eye=${c.leftEye?.key ?: "none"}")
            append(" right_eye=${c.rightEye?.key ?: "none"}")
            append(" mouth=${c.mouth?.key ?: "none"}")
            append(" left_accent=${c.leftAccent?.key ?: "none"}")
            append(" right_accent=${c.rightAccent?.key ?: "none"}")
        }
    }
}
