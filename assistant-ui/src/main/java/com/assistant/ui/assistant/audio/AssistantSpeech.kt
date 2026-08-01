package com.assistant.ui.assistant.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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

private const val TAG = "AssistantSpeech"

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

/**
 * Continuous speech stream used for hotword + live transcript.
 *
 * Important binder hygiene:
 * - Never call [SpeechRecognizer.cancel] from [RecognitionListener.onError] — a dead
 *   recognition service makes cancel() log "cancel() failed" / DeadObjectException and
 *   can re-enter onError via ERROR_CLIENT.
 * - Never call cancel() then destroy() — [SpeechRecognizer.destroy] already cancels.
 * - Back off before restarting after errors to avoid binder thrash.
 */
internal fun assistantSpeechEvents(context: Context): Flow<AssistantSpeechEvent> = callbackFlow {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        awaitClose { }
        return@callbackFlow
    }

    val mainHandler = Handler(Looper.getMainLooper())
    var listening = true
    var recognizer: SpeechRecognizer? = null
    var restarting = false
    var consecutiveErrors = 0

    fun safeDestroy(target: SpeechRecognizer?) {
        if (target == null) return
        try {
            // destroy() cancels internally — do NOT call cancel() first or the
            // framework logs "cancel() failed" with DeadObjectException.
            target.setRecognitionListener(null)
            target.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "SpeechRecognizer.destroy failed", t)
        }
    }

    fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    fun emitHeard(texts: List<String>?, final: Boolean) {
        if (texts.isNullOrEmpty()) return
        val best = texts.firstOrNull { it.isNotBlank() } ?: return
        consecutiveErrors = 0
        if (matchesAssistantHotword(best)) {
            trySend(AssistantSpeechEvent.Hotword)
        }
        trySend(
            if (final) AssistantSpeechEvent.Final(best) else AssistantSpeechEvent.Partial(best),
        )
    }

    lateinit var ensureRecognizerAndListen: () -> Unit

    fun scheduleRestart(recreate: Boolean, delayMs: Long) {
        if (!listening || restarting) return
        restarting = true
        mainHandler.postDelayed({
            restarting = false
            if (!listening) return@postDelayed
            if (recreate) {
                safeDestroy(recognizer)
                recognizer = null
                ensureRecognizerAndListen()
            } else {
                try {
                    recognizer?.startListening(buildIntent())
                } catch (t: Throwable) {
                    Log.w(TAG, "startListening after error failed; recreating", t)
                    safeDestroy(recognizer)
                    recognizer = null
                    ensureRecognizerAndListen()
                }
            }
        }, delayMs)
    }

    ensureRecognizerAndListen = {
        if (listening) {
            try {
                if (recognizer == null) {
                    val created = SpeechRecognizer.createSpeechRecognizer(context)
                    recognizer = created
                    created.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit

                        override fun onRmsChanged(rmsdB: Float) {
                            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                            trySend(AssistantSpeechEvent.Rms(normalized))
                        }

                        override fun onError(error: Int) {
                            if (!listening) return
                            consecutiveErrors += 1
                            // Do NOT cancel() here — dead binders log
                            // "cancel() failed" / DeadObjectException and can loop via ERROR_CLIENT.
                            val recreate = error == SpeechRecognizer.ERROR_CLIENT ||
                                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                error == 11 /* ERROR_SERVER_DISCONNECTED on newer APIs */ ||
                                consecutiveErrors >= 3
                            val delayMs = (300L * consecutiveErrors.coerceAtMost(5))
                            scheduleRestart(recreate = recreate, delayMs = delayMs)
                        }

                        override fun onResults(results: Bundle?) {
                            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            emitHeard(texts, final = true)
                            scheduleRestart(recreate = false, delayMs = 150L)
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val texts =
                                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            emitHeard(texts, final = false)
                        }
                    })
                }
                recognizer?.startListening(buildIntent())
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start SpeechRecognizer", t)
                safeDestroy(recognizer)
                recognizer = null
                scheduleRestart(recreate = true, delayMs = 500L)
            }
        }
    }

    ensureRecognizerAndListen()

    awaitClose {
        listening = false
        restarting = false
        mainHandler.removeCallbacksAndMessages(null)
        safeDestroy(recognizer)
        recognizer = null
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
