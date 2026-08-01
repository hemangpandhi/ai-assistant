package com.assistant.ui.assistant.mvi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Holds immersive stage MVI state for Compose to collect.
 */
class AssistantStageStore(
    initial: StageState = StageState(),
) {
    var state by mutableStateOf(initial)
        private set

    private val _effects = Channel<StageEffect>(Channel.BUFFERED)
    val effects: Flow<StageEffect> = _effects.receiveAsFlow()

    fun dispatch(intent: StageIntent) {
        val (next, effects) = reduceStage(state, intent)
        state = next
        effects.forEach { _effects.trySend(it) }
    }

    /** Local presentation tweaks (timeouts, animation-only) without new intents. */
    fun update(transform: (StageState) -> StageState) {
        state = transform(state)
    }
}
