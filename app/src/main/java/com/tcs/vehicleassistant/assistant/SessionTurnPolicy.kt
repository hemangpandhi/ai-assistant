package com.tcs.vehicleassistant.assistant

import com.tcs.vehicleassistant.controller.ViewModelEvent

/**
 * Agent turn-control → UI session policy.
 *
 * The UI must not infer continue/close from user text ("play music" vs "I'm sad");
 * the orchestrator already encodes that as [ViewModelEvent.StartListening] vs
 * [ViewModelEvent.FinishSession].
 */
enum class SessionTurnPolicy {
    /** Keep overlay open and re-arm mic (question / soft music offer). */
    Continue,

    /** Turn is done — emit [com.assistant.ui.assistant.api.AssistantSessionEvent.SessionComplete]. */
    Complete,
}

fun sessionTurnPolicyFor(event: ViewModelEvent): SessionTurnPolicy? = when (event) {
    ViewModelEvent.StartListening -> SessionTurnPolicy.Continue
    ViewModelEvent.FinishSession -> SessionTurnPolicy.Complete
    else -> null
}
