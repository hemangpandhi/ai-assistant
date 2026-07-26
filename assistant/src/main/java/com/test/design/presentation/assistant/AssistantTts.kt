package com.test.design.presentation.assistant

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "AssistantTts"

/** Whether this beat should be spoken aloud. */
internal fun shouldSpeakBeat(beat: DialogueBeat): Boolean =
    beat.speaker == DialogueSpeaker.Assistant && beat.text.isNotBlank()

/**
 * Speaks [text] via TextToSpeech when an engine is available; otherwise silent lip-sync only.
 */
fun assistantUtteranceLipSync(
    context: Context,
    text: String,
    holdMs: Long,
): Flow<Float> = flow {
    val engine = acquireTts(context)
    if (engine != null) {
        val speaking = AtomicBoolean(true)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                speaking.set(true)
            }

            override fun onDone(id: String?) {
                speaking.set(false)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                speaking.set(false)
            }

            override fun onError(id: String?, errorCode: Int) {
                speaking.set(false)
            }
        })
        if (speakWithTts(engine, text)) {
            val steps = (holdMs / 70L).coerceAtLeast(4)
            repeat(steps.toInt()) { i ->
                val t = i * 0.4f
                val base = if (speaking.get()) 0.55f else 0.18f
                val envelope = base + 0.35f * sin(t.toDouble()).toFloat() +
                    Random.nextFloat() * 0.12f
                emit(envelope.coerceIn(0.05f, 1f))
                delay(70)
            }
            var wait = 0
            while (speaking.get() && wait < 60) {
                delay(50)
                wait++
            }
            emit(0f)
            return@flow
        }
    }

    simulatedLipSync(holdMs).collect { emit(it) }
}

/** Speak without lip-sync (side panel / gallery). */
internal suspend fun speakAssistantUtterance(
    context: Context,
    text: String,
    holdMs: Long,
) {
    if (text.isBlank()) {
        delay(holdMs)
        return
    }
    assistantUtteranceLipSync(context, text, holdMs).collect { }
}

/** Simulated lip-sync only (no audio) — unit tests / silent demos. */
internal fun simulatedLipSync(holdMs: Long = 2400L): Flow<Float> = flow {
    val steps = (holdMs / 70L).coerceAtLeast(4)
    repeat(steps.toInt()) { i ->
        val t = i * 0.35f
        val envelope = 0.55f + 0.35f * sin(t.toDouble()).toFloat() + Random.nextFloat() * 0.1f
        emit(envelope.coerceIn(0.1f, 1f))
        delay(70)
    }
    emit(0f)
}

private val sharedTts = AtomicReference<TextToSpeech?>(null)
private val ttsReady = AtomicBoolean(false)
private val ttsUnavailable = AtomicBoolean(false)

private suspend fun acquireTts(context: Context): TextToSpeech? {
    if (ttsUnavailable.get()) return null
    sharedTts.get()?.takeIf { ttsReady.get() }?.let { return it }
    return suspendCancellableCoroutine { cont ->
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ttsUnavailable.set(true)
                Log.w(TAG, "TextToSpeech init failed status=$status")
                if (cont.isActive) cont.resume(null)
                return@TextToSpeech
            }
            configureTts(engine)
            val langOk = engine?.isLanguageAvailable(Locale.US)?.let {
                it >= TextToSpeech.LANG_AVAILABLE
            } == true
            val hasEngine = !engine?.engines.isNullOrEmpty()
            if (!langOk && !hasEngine) {
                ttsUnavailable.set(true)
                runCatching { engine?.shutdown() }
                Log.w(TAG, "No usable TTS voices/engines")
                if (cont.isActive) cont.resume(null)
                return@TextToSpeech
            }
            ttsReady.set(true)
            sharedTts.compareAndSet(null, engine)
            if (cont.isActive) cont.resume(engine)
        }
        cont.invokeOnCancellation { }
    }
}

private fun speakWithTts(engine: TextToSpeech, text: String): Boolean {
    return try {
        val id = UUID.randomUUID().toString()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
    } catch (e: Exception) {
        Log.w(TAG, "speak failed", e)
        false
    }
}

private fun configureTts(engine: TextToSpeech?) {
    if (engine == null) return
    // USAGE_MEDIA / STREAM_MUSIC — USAGE_ASSISTANT is often silent on AAOS without a car audio patch.
    try {
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    } catch (_: Exception) {
    }
    val lang = engine.setLanguage(Locale.getDefault())
    if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
        engine.language = Locale.US
    }
    engine.setSpeechRate(1.0f)
    engine.setPitch(1.04f)
}
