import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/AssistantSession.kt"
with open(file_path, "r") as f:
    text = f.read()

# 1. Add variables to the top of AssistantSession class
import_idx = text.find("class AssistantSession")
if import_idx != -1:
    old_class_def = "class AssistantSession(val context: Context, private val isAgenticObservation: Boolean = false) : Dialog(context, R.style.TransparentDialog), TextToSpeech.OnInitListener {"
    new_class_def = """class AssistantSession(val context: Context, private val isAgenticObservation: Boolean = false) : Dialog(context, R.style.TransparentDialog), TextToSpeech.OnInitListener {
    private var audioManager: android.media.AudioManager? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
"""
    text = text.replace(old_class_def, new_class_def)

# 2. Request focus in onCreateContentView
oncreate_idx = text.find("private fun inflateAndBindLayout()")
if oncreate_idx != -1:
    new_oncreate = """private fun inflateAndBindLayout() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { focusChange ->
                android.util.Log.d("AssistantSession", "Audio Focus Change: $focusChange")
            }
            .build()
"""
    text = text.replace("private fun inflateAndBindLayout() {", new_oncreate)

# 3. Request focus right before speaking in handleChunk
old_speak = """                                val audioBundle = android.os.Bundle().apply { 
                                    putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, 11) 
                                }
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, audioBundle, utteranceId)"""
new_speak = """                                val audioBundle = android.os.Bundle().apply { 
                                    putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, 11) 
                                }
                                audioFocusRequest?.let { audioManager?.requestAudioFocus(it) }
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, audioBundle, utteranceId)"""
text = text.replace(old_speak, new_speak)

# 4. Request focus right before fallback speaking
old_fallback = """                                val audioBundle = android.os.Bundle().apply { 
                                    putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, 11) 
                                }
                                    val utteranceId = "SENTENCE_$sentenceStartOffset"
                                    lastQueuedUtteranceId = utteranceId
                                    LatencyLogger.log("AssistantSession", "Sending fallback sentence to TTS queue: $utteranceId")
                                    tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, audioBundle, utteranceId)"""
new_fallback = """                                val audioBundle = android.os.Bundle().apply { 
                                    putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, 11) 
                                }
                                    val utteranceId = "SENTENCE_$sentenceStartOffset"
                                    lastQueuedUtteranceId = utteranceId
                                    LatencyLogger.log("AssistantSession", "Sending fallback sentence to TTS queue: $utteranceId")
                                    audioFocusRequest?.let { audioManager?.requestAudioFocus(it) }
                                    tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, audioBundle, utteranceId)"""
text = text.replace(old_fallback, new_fallback)

# 5. Abandon focus in onHide()
old_hide = """    override fun onHide() {
        super.onHide()"""
new_hide = """    override fun onHide() {
        super.onHide()
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }"""
text = text.replace(old_hide, new_hide)

with open(file_path, "w") as f:
    f.write(text)
