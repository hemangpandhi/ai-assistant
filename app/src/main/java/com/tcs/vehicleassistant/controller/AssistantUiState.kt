package com.tcs.vehicleassistant.controller

/**
 * Represents the pure UI state of the Assistant.
 * The AssistantSession (View layer) observes this state to render animations and text.
 */
sealed class AssistantUiState {
    object Idle : AssistantUiState()
    data class Listening(val partialText: String = "") : AssistantUiState()
    data class Thinking(val userQuery: String? = null) : AssistantUiState()
    
    /**
     * Represents the real-time streaming state where text chunks are arriving from the LLM.
     */
    data class Streaming(val displayText: String) : AssistantUiState()
    
    /**
     * Represents the state where the assistant has finished processing and is actively speaking the final output.
     */
    data class Speaking(val finalMessage: String) : AssistantUiState()
    
    /**
     * Represents a timeout or API error state.
     */
    data class Error(val errorMessage: String) : AssistantUiState()
}
