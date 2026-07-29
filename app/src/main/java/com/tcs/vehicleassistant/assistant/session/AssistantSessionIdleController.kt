package com.tcs.vehicleassistant.assistant.session

import android.content.Context
import com.assistant.api.llm.LlmSessionPort
import com.assistant.ui.assistant.api.AssistantDebugLog
import com.tcs.vehicleassistant.LocalLLMActivity
import com.tcs.vehicleassistant.assistant.AssistantIdleTimeout
import com.tcs.vehicleassistant.controller.AssistantUiState
import com.tcs.vehicleassistant.controller.UiUxAssistantViewModel
import com.tcs.vehicleassistant.controller.UiUxViewModelEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Arms and pauses the quiet-listening timeout from assistant UI state.
 *
 * The countdown does not start until STT reaches Listening at least once, so it
 * cannot race wake-word mic release and close before the user can speak.
 */
internal class AssistantSessionIdleController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isVisible: () -> Boolean,
    private val isBusy: () -> Boolean,
    private val currentUiState: () -> AssistantUiState?,
    private val viewModelProvider: () -> UiUxAssistantViewModel?,
    private val llmSession: LlmSessionPort,
    private val onTimeout: () -> Unit,
) {
    private var idleJob: Job? = null
    private var idleWatchJob: Job? = null
    private var idleArmedAfterMicReady = false

    fun start() {
        AssistantIdleTimeout.install(context)
        idleArmedAfterMicReady = false
        idleWatchJob?.cancel()
        idleWatchJob = scope.launch {
            val viewModel = viewModelProvider()
            if (viewModel != null) {
                launch {
                    viewModel.uiState.collect { state -> onUiState(state) }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is UiUxViewModelEvent.SetInputText -> {
                                if (event.text.isNotBlank()) noteActivity()
                            }
                            is UiUxViewModelEvent.StartListening -> {
                                // Request only — idle arms when uiState reaches Listening (ready).
                            }
                            else -> Unit
                        }
                    }
                }
            }
            // Do not count down until STT is ready — pause while warming / handoff.
            pause()
            AssistantDebugLog.d("Session", "idle watch started — waiting for mic ready")
            // Safety: if STT never reaches Listening, still allow close after a long grace.
            delay(15_000)
            if (isActive && isVisible() && !idleArmedAfterMicReady) {
                AssistantDebugLog.w("Session", "idle grace — mic never ready, arming anyway")
                idleArmedAfterMicReady = true
                arm("mic-ready-fallback")
            }
        }
    }

    fun stop() {
        idleJob?.cancel()
        idleJob = null
        idleWatchJob?.cancel()
        idleWatchJob = null
        idleArmedAfterMicReady = false
    }

    /** Reset the idle countdown after real user / pipeline activity. */
    fun noteActivity() {
        if (!isVisible()) return
        if (isBusy()) {
            pause()
            return
        }
        if (!idleArmedAfterMicReady) return
        arm("user-activity")
    }

    fun pause() {
        idleJob?.cancel()
        idleJob = null
    }

    fun arm(reason: String) {
        idleJob?.cancel()
        if (!isVisible()) return
        if (!idleArmedAfterMicReady) {
            AssistantDebugLog.d("Session", "idle arm skipped — mic not ready ($reason)")
            return
        }
        val timeoutMs = AssistantIdleTimeout.currentMs()
        if (timeoutMs <= 0L) {
            AssistantDebugLog.d("Session", "idle timer disabled ($reason)")
            idleJob = null
            return
        }
        idleJob = scope.launch {
            AssistantDebugLog.d("Session", "idle arm ${timeoutMs}ms ($reason)")
            delay(timeoutMs)
            if (!isActive || !isVisible()) return@launch
            // Still busy? Skip close (e.g. race with Thinking).
            val busy = when (currentUiState()) {
                is AssistantUiState.Thinking,
                is AssistantUiState.Streaming,
                is AssistantUiState.Speaking,
                -> true
                else -> isBusy()
            }
            if (busy || isModelWarmingUp()) {
                AssistantDebugLog.d("Session", "idle fire skipped — busy")
                return@launch
            }
            // Never close while STT is still starting (not yet Listening).
            if (currentUiState() !is AssistantUiState.Listening &&
                currentUiState() !is AssistantUiState.Idle &&
                currentUiState() !is AssistantUiState.Error
            ) {
                return@launch
            }
            onTimeout()
        }
    }

    fun destroy() {
        stop()
    }

    private fun onUiState(state: AssistantUiState) {
        when (state) {
            is AssistantUiState.Thinking,
            is AssistantUiState.Streaming,
            is AssistantUiState.Speaking,
            -> pause()
            is AssistantUiState.Listening -> {
                idleArmedAfterMicReady = true
                if (isModelWarmingUp()) {
                    pause()
                } else {
                    arm("ui:Listening")
                }
            }
            is AssistantUiState.Idle,
            is AssistantUiState.Error,
            -> {
                if (!idleArmedAfterMicReady || isModelWarmingUp()) {
                    pause()
                } else {
                    arm("ui:${state::class.simpleName}")
                }
            }
        }
    }

    private fun isCloudRoutingActive(): Boolean {
        return runCatching {
            org.koin.java.KoinJavaComponent.getKoin()
                .get<com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags>()
                .isCloudActive
        }.getOrElse { LocalLLMActivity.isCloudModelActive }
    }

    private fun isModelWarmingUp(): Boolean {
        if (isCloudRoutingActive()) return false
        return llmSession.isInitializing() || llmSession.isPrewarming() || !llmSession.isReady()
    }
}
