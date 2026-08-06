package com.tcs.vehicleassistant.controller

/**
 * Represents all user or system intents that can be dispatched to the AssistantViewModel.
 * This is the 'Intent' in the MVI architecture.
 */
sealed class AssistantIntent {
    /** Called when the user types or speaks a complete query. */
    data class ProcessQuery(val query: String) : AssistantIntent()
    
    /** Called when a proactive event needs to trigger the assistant. */
    data class ProcessProactiveEvent(val prompt: String) : AssistantIntent()

    /** Called when the user wants to start the microphone. */
    object StartListening : AssistantIntent()
    
    /** Called when the user wants to stop the microphone manually. */
    object StopListening : AssistantIntent()

    /** Called when the user barges in with speech, interrupting the assistant's speech. */
    object InterruptSpeech : AssistantIntent()

    /**
     * Clear in-flight orchestrator work for a new listen turn.
     * Does **not** dismiss the session (unlike [Cancel]).
     */
    object ResetTurn : AssistantIntent()
    
    /** Called when the user dismisses the assistant or cancels an action. */
    object Cancel : AssistantIntent()

    /** Called to clear the state without finishing the session. */
    object ClearState : AssistantIntent()

    /** Called when the assistant requests confirmation for a tool and the user responds. */
    data class ConfirmTool(val accepted: Boolean) : AssistantIntent()
}
