package com.tcs.vehicleassistant.assistant.agent

import android.content.Intent

sealed class AgentState {
    object Idle : AgentState()
    data class Thinking(val query: String? = null) : AgentState()
    data class Streaming(val displayMsg: String) : AgentState()
    data class Speaking(val finalMsg: String) : AgentState()
    data class Error(val message: String) : AgentState()
}

sealed class AgentIntent {
    data class HandleQuery(val query: String, val retryCount: Int = 0) : AgentIntent()
    data class HandleConfirmation(val accepted: Boolean) : AgentIntent()
    data class TriggerProactiveEvent(val prompt: String) : AgentIntent()
    object StopSpeaking : AgentIntent()
    object Destroy : AgentIntent()
}

sealed class AgentEffect {
    data class ShowToast(val message: String) : AgentEffect()
    data class SetInputEnabled(val enabled: Boolean) : AgentEffect()
    data class LaunchIntent(val intent: Intent) : AgentEffect()
    object StartListening : AgentEffect()
    object FinishSession : AgentEffect()
}
