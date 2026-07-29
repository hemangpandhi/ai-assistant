package com.tcs.vehicleassistant.repository.uiux

import android.content.Intent
import com.assistant.ui.assistant.api.AssistantMoodId

sealed class OrchestratorState {
    data object Idle : OrchestratorState()
    data object Thinking : OrchestratorState()
    data class Streaming(val displayMsg: String) : OrchestratorState()
    data class Speaking(val finalMsg: String) : OrchestratorState()
    data class Error(val message: String) : OrchestratorState()
}

sealed class OrchestratorEvent {
    data class ShowToast(val message: String) : OrchestratorEvent()
    data class SetInputEnabled(val enabled: Boolean) : OrchestratorEvent()
    data class LaunchIntent(val intent: Intent) : OrchestratorEvent()
    data object StartListening : OrchestratorEvent()
    data object FinishSession : OrchestratorEvent()

    /** Optional LLM / heuristic emotion — UI merges with harness pipeline mood. */
    data class AffectiveMood(
        val mood: AssistantMoodId,
    ) : OrchestratorEvent()
}
