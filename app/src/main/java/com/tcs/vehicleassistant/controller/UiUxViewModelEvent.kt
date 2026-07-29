package com.tcs.vehicleassistant.controller

import android.content.Intent
import com.assistant.ui.assistant.api.AssistantMoodId

/**
 * UI/UX-only one-shot events. The refactor-owned [ViewModelEvent] remains unchanged.
 */
sealed class UiUxViewModelEvent {
    data class LaunchIntent(val intent: Intent) : UiUxViewModelEvent()
    data object FinishSession : UiUxViewModelEvent()
    data object StartListening : UiUxViewModelEvent()
    data class ShowToast(val message: String) : UiUxViewModelEvent()
    data class SetInputEnabled(val enabled: Boolean) : UiUxViewModelEvent()
    data class SetInputText(val text: String) : UiUxViewModelEvent()
    data class AffectiveMood(val mood: AssistantMoodId) : UiUxViewModelEvent()
}
