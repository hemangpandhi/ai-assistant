package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.*
class AndroidAudioManager(private val context: Context) : IAudioManager {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    
    // TTS callbacks
    private var onTtsStart: ((String) -> Unit)? = null
    private var onTtsDone: ((String) -> Unit)? = null
    private var onTtsError: ((String) -> Unit)? = null
    private var onTtsRangeStart: ((String, Int, Int, Int) -> Unit)? = null
    
    // STT callbacks (full lifecycle)
    private var onSttReadyForSpeech: (() -> Unit)? = null
    private var onSttBeginningOfSpeech: (() -> Unit)? = null
    private var onSttEndOfSpeech: (() -> Unit)? = null
    private var onSttResult: ((String) -> Unit)? = null
    private var onSttEmptyResult: (() -> Unit)? = null
    private var onSttError: ((Int) -> Unit)? = null
    private var onSttPartial: ((String) -> Unit)? = null

    override fun initialize(onSuccess: () -> Unit, onError: () -> Unit) {
        tts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                try {
                    val voices = tts?.voices
                    if (voices != null) {
                        for (voice in voices) {
                            if (voice.locale.language == Locale.US.language && !voice.isNetworkConnectionRequired) {
                                tts?.voice = voice
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore if getVoices() is unsupported or fails
                }
                
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let { onTtsStart?.invoke(it) }
                    }
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { onTtsDone?.invoke(it) }
                    }
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { onTtsError?.invoke(it) }
                    }
                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        utteranceId?.let { onTtsRangeStart?.invoke(it, start, end, frame) }
                    }
                })
                onSuccess()
            } else {
                onError()
            }
        }, "com.google.android.tts")
    }

    private var isListening = false
    private var isRecognizerReady = false
    private var customAudioRecord: android.media.AudioRecord? = null
    private var customRecognizer: org.vosk.Recognizer? = null
    private var listeningJob: Job? = null

    override fun startListening() {
        if (isListening) return
        isListening = true

        val voskModel = com.tcs.vehicleassistant.WakeWordService.sharedModel
        if (voskModel == null) {
            isListening = false
            onSttError?.invoke(0)
            return
        }

        try {
            customRecognizer = org.vosk.Recognizer(voskModel, 16000.0f)
            
            val bufferSize = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT) * 2
            customAudioRecord = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, bufferSize)

            if (customAudioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                isListening = false
                onSttError?.invoke(0)
                return
            }

            customAudioRecord?.startRecording()
            
            // Notify UI that we are ready
            isRecognizerReady = true
            onSttReadyForSpeech?.invoke()

            listeningJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ShortArray(bufferSize)
                var speechStarted = false
                
                try {
                    while (isListening && isActive) {
                        val readSize = customAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readSize > 0) {
                            if (!speechStarted) {
                                val maxAmplitude = buffer.maxOrNull() ?: 0
                                if (maxAmplitude > 300) {
                                    speechStarted = true
                                    withContext(Dispatchers.Main) { onSttBeginningOfSpeech?.invoke() }
                                }
                            }

                            if (customRecognizer?.acceptWaveForm(buffer, readSize) == true) {
                                val result = customRecognizer?.result
                                if (result != null) {
                                    val json = org.json.JSONObject(result)
                                    val text = json.optString("text", "")
                                    if (text.isNotBlank()) {
                                        withContext(Dispatchers.Main) { onSttResult?.invoke(text) }
                                        isListening = false
                                        break
                                    }
                                }
                            } else {
                                val partial = customRecognizer?.partialResult
                                if (partial != null) {
                                    val json = org.json.JSONObject(partial)
                                    val text = json.optString("partial", "")
                                    if (text.isNotBlank()) {
                                        withContext(Dispatchers.Main) { onSttPartial?.invoke(text) }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { onSttError?.invoke(0) }
                } finally {
                    withContext(Dispatchers.Main) { onSttEndOfSpeech?.invoke() }
                    withContext(Dispatchers.Main) { destroySpeechRecognizer() }
                }
            }
        } catch (e: Exception) {
            isListening = false
            onSttError?.invoke(0)
        }
    }

    override fun stopListening() {
        if (!isListening) return
        isListening = false
        
        try {
            val finalResult = customRecognizer?.finalResult
            if (finalResult != null) {
                val json = org.json.JSONObject(finalResult)
                val text = json.optString("text", "")
                if (text.isNotBlank()) {
                    CoroutineScope(Dispatchers.Main).launch { onSttResult?.invoke(text) }
                } else {
                    CoroutineScope(Dispatchers.Main).launch { onSttEmptyResult?.invoke() }
                }
            }
        } catch (e: Exception) {}
        
        destroySpeechRecognizer()
    }

    override fun destroySpeechRecognizer() {
        isListening = false
        isRecognizerReady = false
        listeningJob?.cancel()
        listeningJob = null
        try {
            customAudioRecord?.stop()
            customAudioRecord?.release()
        } catch (e: Exception) {}
        customAudioRecord = null
        
        try {
            customRecognizer?.close()
        } catch (e: Exception) {}
        customRecognizer = null
    }

    override fun speak(text: String, utteranceId: String) {
        var result = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result == TextToSpeech.ERROR || tts == null) {
            // TTS engine might have died or unbound. Try to re-initialize and speak again.
            initialize(
                onSuccess = {
                    tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
                },
                onError = {
                    onTtsError?.invoke(utteranceId)
                }
            )
        }
    }

    override fun playSilentUtterance(durationMs: Long, utteranceId: String) {
        var result = tts?.playSilentUtterance(durationMs, TextToSpeech.QUEUE_ADD, utteranceId)
        if (result == TextToSpeech.ERROR || tts == null) {
            initialize(
                onSuccess = {
                    tts?.playSilentUtterance(durationMs, TextToSpeech.QUEUE_ADD, utteranceId)
                },
                onError = {
                    onTtsError?.invoke(utteranceId)
                }
            )
        }
    }

    override fun stopSpeaking() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        
        destroySpeechRecognizer()
    }

    override fun setUtteranceListener(
        onStart: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        onRangeStart: (String, Int, Int, Int) -> Unit
    ) {
        this.onTtsStart = onStart
        this.onTtsDone = onDone
        this.onTtsError = onError
        this.onTtsRangeStart = onRangeStart
    }

    override fun setRecognitionListener(
        onReadyForSpeech: () -> Unit,
        onBeginningOfSpeech: () -> Unit,
        onEndOfSpeech: () -> Unit,
        onResult: (String) -> Unit,
        onEmptyResult: () -> Unit,
        onError: (Int) -> Unit,
        onPartial: (String) -> Unit
    ) {
        this.onSttReadyForSpeech = onReadyForSpeech
        this.onSttBeginningOfSpeech = onBeginningOfSpeech
        this.onSttEndOfSpeech = onEndOfSpeech
        this.onSttResult = onResult
        this.onSttEmptyResult = onEmptyResult
        this.onSttError = onError
        this.onSttPartial = onPartial
    }
}
