package com.tcs.vehicleassistant.manager

import com.tcs.vehicleassistant.hardware.IAudioManager

interface VoiceInteractionCallback {
    fun onFinalUtteranceDone(utteranceId: String)
    fun onFinalUtteranceError(utteranceId: String)
}

class VoiceInteractionManager(
    private val audioManager: IAudioManager,
    private val callback: VoiceInteractionCallback
) {
    @Volatile var ttsSpokenLength = 0
        private set

    @Volatile var lastTtsUpdateTime = 0L
        private set

    init {
        audioManager.setUtteranceListener(
            onStart = { utteranceId ->
                if (utteranceId.startsWith("SENTENCE_")) {
                    lastTtsUpdateTime = System.currentTimeMillis()
                }
            },
            onDone = { utteranceId ->
                if (utteranceId.startsWith("SENTENCE_")) {
                    lastTtsUpdateTime = System.currentTimeMillis()
                }
                callback.onFinalUtteranceDone(utteranceId)
            },
            onError = { utteranceId ->
                callback.onFinalUtteranceError(utteranceId)
            },
            onRangeStart = { utteranceId, start, end, frame ->
                if (utteranceId.startsWith("SENTENCE_")) {
                    val sentenceStartOffset = utteranceId.substringAfter("SENTENCE_").toIntOrNull() ?: 0
                    ttsSpokenLength = Math.max(ttsSpokenLength, sentenceStartOffset + end)
                    lastTtsUpdateTime = System.currentTimeMillis()
                }
            }
        )
    }

    fun speak(text: String, utteranceId: String) {
        audioManager.speak(text, utteranceId)
    }

    fun playSilentUtterance(durationMs: Long, utteranceId: String) {
        audioManager.playSilentUtterance(durationMs, utteranceId)
    }

    fun resetState() {
        ttsSpokenLength = 0
        lastTtsUpdateTime = System.currentTimeMillis()
    }

    fun stop() {
        audioManager.stopSpeaking()
        resetState()
    }
}
