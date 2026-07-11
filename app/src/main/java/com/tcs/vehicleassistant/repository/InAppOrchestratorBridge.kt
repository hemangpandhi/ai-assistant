package com.tcs.vehicleassistant.repository

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.tcs.vehicleassistant.hardware.IAudioManager
import com.tcs.vehicleassistant.utils.ToolCallParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Runs [AgentOrchestrator] inside LocalLLMActivity with optional TTS and chat UI callbacks.
 */
class InAppOrchestratorBridge(
    context: Context,
    private val ttsProvider: () -> TextToSpeech?,
    private val scope: CoroutineScope
) {
    var enableTts: Boolean = false

    private val bridgeAudio = BridgeAudioManager()
    private val orchestrator = AgentOrchestrator(context.applicationContext, bridgeAudio)

    var onStreaming: ((String) -> Unit)? = null
    var onSpeaking: ((String) -> Unit)? = null
    var onThinking: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onIdle: (() -> Unit)? = null
    var onLaunchIntent: ((Intent) -> Unit)? = null

    init {
        orchestrator.state.onEach { state ->
            when (state) {
                is OrchestratorState.Thinking -> onThinking?.invoke()
                is OrchestratorState.Streaming -> onStreaming?.invoke(ToolCallParser.stripToolTags(state.displayMsg))
                is OrchestratorState.Speaking -> onSpeaking?.invoke(state.finalMsg)
                is OrchestratorState.Error -> onError?.invoke(state.message)
                is OrchestratorState.Idle -> onIdle?.invoke()
            }
        }.launchIn(scope)

        orchestrator.events.onEach { event ->
            when (event) {
                is OrchestratorEvent.LaunchIntent -> onLaunchIntent?.invoke(event.intent)
                else -> {}
            }
        }.launchIn(scope)
    }

    fun isProcessing(): Boolean = orchestrator.isProcessing()

    fun handleQuery(query: String) {
        orchestrator.handleQuery(query)
    }

    fun destroy() {
        orchestrator.destroy()
    }

    fun notifyUtteranceDone(utteranceId: String) = bridgeAudio.notifyUtteranceDone(utteranceId)

    fun notifyUtteranceError(utteranceId: String) = bridgeAudio.notifyUtteranceError(utteranceId)

    fun notifyUtteranceRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) =
        bridgeAudio.notifyUtteranceRangeStart(utteranceId, start, end, frame)

    private inner class BridgeAudioManager : IAudioManager {
        private var onTtsStart: ((String) -> Unit)? = null
        private var onTtsDone: ((String) -> Unit)? = null
        private var onTtsError: ((String) -> Unit)? = null
        private var onTtsRangeStart: ((String, Int, Int, Int) -> Unit)? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun notifyUtteranceDone(utteranceId: String) {
            onTtsDone?.invoke(utteranceId)
        }

        fun notifyUtteranceError(utteranceId: String) {
            onTtsError?.invoke(utteranceId)
        }

        fun notifyUtteranceRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            onTtsRangeStart?.invoke(utteranceId, start, end, frame)
        }

        override fun initialize(onSuccess: () -> Unit, onError: () -> Unit) = onSuccess()
        override fun startListening() {}
        override fun stopListening() {}
        override fun destroySpeechRecognizer() {}
        override fun shutdown() {}

        override fun speak(text: String, utteranceId: String) {
            val tts = ttsProvider()
            if (enableTts && tts != null) {
                onTtsStart?.invoke(utteranceId)
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            } else {
                mainHandler.post { onTtsDone?.invoke(utteranceId) }
            }
        }

        override fun playSilentUtterance(durationMs: Long, utteranceId: String) {
            val tts = ttsProvider()
            if (enableTts && tts != null) {
                tts.playSilentUtterance(durationMs, TextToSpeech.QUEUE_ADD, utteranceId)
            } else {
                mainHandler.post { onTtsDone?.invoke(utteranceId) }
            }
        }

        override fun stopSpeaking() {
            ttsProvider()?.stop()
        }

        override fun setUtteranceListener(
            onStart: (String) -> Unit,
            onDone: (String) -> Unit,
            onError: (String) -> Unit,
            onRangeStart: (String, Int, Int, Int) -> Unit
        ) {
            onTtsStart = onStart
            onTtsDone = onDone
            onTtsError = onError
            onTtsRangeStart = onRangeStart
        }

        override fun setRecognitionListener(
            onReadyForSpeech: () -> Unit,
            onBeginningOfSpeech: () -> Unit,
            onEndOfSpeech: () -> Unit,
            onResult: (String) -> Unit,
            onEmptyResult: () -> Unit,
            onError: (Int) -> Unit,
            onPartial: (String) -> Unit
        ) {}
    }
}
