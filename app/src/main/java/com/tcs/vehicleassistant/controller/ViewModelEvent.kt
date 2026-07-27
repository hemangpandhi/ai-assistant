package com.tcs.vehicleassistant.controller

import android.content.Intent

/**
 * One-shot events emitted by the ViewModel that the View must consume exactly once.
 * These are NOT state — they are transient commands.
 *
 * Note: TTS operations (speak, playSilentUtterance) are handled directly by the
 * ViewModel via IAudioManager and do NOT flow through events.
 */
sealed class ViewModelEvent {
    /** Launch an intent after TTS finishes (e.g. Google Maps navigation). */
    data class LaunchIntent(val intent: Intent) : ViewModelEvent()
    
    /** Close the VoiceInteractionSession. */
    object FinishSession : ViewModelEvent()
    
    /** Auto-trigger the microphone for follow-up questions. */
    object StartListening : ViewModelEvent()
    
    /** Display a transient toast message. */
    data class ShowToast(val message: String) : ViewModelEvent()
    
    /** Enable/disable input controls. */
    data class SetInputEnabled(val enabled: Boolean) : ViewModelEvent()
    
    /** Sets the text in the input box / live transcript. */
    data class SetInputText(val text: String) : ViewModelEvent()

    /** LLM or heuristic affective face mood (happy/sad/…). */
    data class AffectiveMood(
        val mood: com.assistant.ui.assistant.api.AssistantMoodId,
    ) : ViewModelEvent()
}
