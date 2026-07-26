package com.test.design.presentation.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * Live speech events from [SpeechRecognizer] — hotword, partial/final transcript, RMS energy.
 */
sealed interface AssistantSpeechEvent {
    data object Hotword : AssistantSpeechEvent
    data class Partial(val text: String) : AssistantSpeechEvent
    data class Final(val text: String) : AssistantSpeechEvent
    data class Rms(val normalized: Float) : AssistantSpeechEvent
}

internal fun matchesAssistantHotword(text: String): Boolean {
    val t = text.lowercase()
        .replace(',', ' ')
        .replace('.', ' ')
        .replace('!', ' ')
        .replace('?', ' ')
        .trim()
    return t.contains("hey assistant") ||
        t.contains("hey assistent") ||
        t.contains("hi assistant") ||
        t.contains("ok assistant") ||
        t.contains("okay assistant") ||
        t == "assistant" ||
        t.startsWith("hey assist")
}

/** Continuous speech stream used for hotword + live transcript. */
internal fun assistantSpeechEvents(context: Context): Flow<AssistantSpeechEvent> = callbackFlow {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        awaitClose { }
        return@callbackFlow
    }

    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    var listening = true

    fun startListening() {
        if (!listening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            recognizer.startListening(intent)
        } catch (_: Exception) {
            // Mic / service unavailable — UI keeps tap + scripted fallback.
        }
    }

    fun emitHeard(texts: List<String>?, final: Boolean) {
        if (texts.isNullOrEmpty()) return
        val best = texts.firstOrNull { it.isNotBlank() } ?: return
        if (matchesAssistantHotword(best)) {
            trySend(AssistantSpeechEvent.Hotword)
        }
        trySend(
            if (final) AssistantSpeechEvent.Final(best) else AssistantSpeechEvent.Partial(best),
        )
    }

    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // Typical SpeechRecognizer RMS is roughly −2…10; normalize to 0..1.
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            trySend(AssistantSpeechEvent.Rms(normalized))
        }

        override fun onError(error: Int) {
            if (listening) {
                recognizer.cancel()
                startListening()
            }
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            emitHeard(texts, final = true)
            if (listening) startListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            emitHeard(texts, final = false)
        }
    })

    startListening()

    awaitClose {
        listening = false
        try {
            recognizer.cancel()
            recognizer.destroy()
        } catch (_: Exception) {
        }
    }
}

/** Back-compat hotword-only stream. */
internal fun hotwordDetections(context: Context): Flow<Unit> =
    assistantSpeechEvents(context)
        .filterIsInstance<AssistantSpeechEvent.Hotword>()
        .map { }

/** Affirmative / negative phrase helpers for nod / shake gestures. */
internal fun isAffirmativeUtterance(text: String): Boolean {
    val t = text.lowercase().trim()
    return t == "yes" ||
        t == "yes!" ||
        t.startsWith("yes ") ||
        t.startsWith("yeah") ||
        t.startsWith("yep") ||
        t.startsWith("sure") ||
        t.startsWith("ok") ||
        t.startsWith("okay") ||
        (t.contains("please") && t.length < 24)
}

internal fun isNegativeUtterance(text: String): Boolean {
    val t = text.lowercase().trim()
    return t == "no" ||
        t == "nope" ||
        t.startsWith("no ") ||
        t.startsWith("nah") ||
        t.startsWith("cancel") ||
        t.startsWith("don't") ||
        t.startsWith("do not")
}
