import re

file_path = "app/src/main/java/com/tcs/vehicleassistant/AssistantSession.kt"
with open(file_path, "r") as f:
    content = f.read()

# 1. State tracker variables
state_vars = """    private val typingSpeedMs: Long = 15L
    
    private val stateLock = Any()
    
    private val currentPendingTools = mutableListOf<kotlinx.coroutines.Deferred<String?>>()
    
    @Volatile private var lastQueuedUtteranceId: String = ""
    @Volatile private var lastFinishedUtteranceId: String = ""
    @Volatile private var expectedFinalUtteranceAction: String = ""
    @Volatile private var isLlmGenerationDone: Boolean = false"""
content = re.sub(r'    private val typingSpeedMs: Long = 15L\s*private val currentPendingTools = mutableListOf<kotlinx.coroutines.Deferred<String\?>>\(\)', state_vars, content)

# 2. Reset in handleQuery
reset_vars = """        ttsSpokenLength = 0
        lastTtsUpdateTime = 0L
        typewriterJob?.cancel()
        
        lastQueuedUtteranceId = ""
        lastFinishedUtteranceId = ""
        expectedFinalUtteranceAction = ""
        isLlmGenerationDone = false"""
content = re.sub(r'        ttsSpokenLength = 0\s*lastTtsUpdateTime = 0L\s*typewriterJob\?\.cancel\(\)', reset_vars, content)

# 3. Audio Bundle for vehicle assistant stream
audio_bundle = """        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
"""
content = re.sub(r'        if \(status == TextToSpeech\.SUCCESS\) \{\s*tts\?\.language = Locale\.US', audio_bundle, content)

# 4. handleChunk loop fix
handle_chunk_orig = """                                val utteranceId = "SENTENCE_$sentenceStartOffset"
                                lastQueuedUtteranceId = utteranceId
                                LatencyLogger.log("AssistantSession", "Sending sentence to TTS queue: $utteranceId")
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, utteranceId)"""
handle_chunk_new = """                                val utteranceId = "SENTENCE_$sentenceStartOffset"
                                lastQueuedUtteranceId = utteranceId
                                LatencyLogger.log("AssistantSession", "Sending sentence to TTS queue: $utteranceId")
                                val audioBundle = android.os.Bundle().apply { putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, 11 /* AudioManager.STREAM_ASSISTANT */) }
                                tts?.speak(parsedSentence, android.speech.tts.TextToSpeech.QUEUE_ADD, audioBundle, utteranceId)"""
content = content.replace('tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, "SENTENCE_$sentenceStartOffset")', handle_chunk_new)

# 5. LLM onDone TTS fix
on_done_orig = """                                    tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, "SENTENCE_$sentenceStartOffset")"""
on_done_new = """                                    val utteranceId = "SENTENCE_$sentenceStartOffset"
                                    lastQueuedUtteranceId = utteranceId
                                    val audioBundle = android.os.Bundle().apply { putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, 11) }
                                    tts?.speak(parsedSentence, android.speech.tts.TextToSpeech.QUEUE_ADD, audioBundle, utteranceId)"""
content = content.replace(on_done_orig, on_done_new)

# 6. targetDisplayMessage thread context fix
target_disp_orig = """                            val displayStr = displayMsg.toString()
                            targetDisplayMessage = displayStr
                            
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {"""
target_disp_new = """                            val displayStr = displayMsg.toString()
                            
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                targetDisplayMessage = displayStr"""
content = content.replace(target_disp_orig, target_disp_new)

# 7. Final Action evaluation logic in LLM onDone
final_action_orig = """                                val finalUtterance = if (isQuestion) "QUESTION_FINAL" else if (toolFeedbacks.isNotEmpty() || currentPendingTools.isNotEmpty()) "STATEMENT_FINAL_TOOL" else "STATEMENT_FINAL"
                                tts?.playSilentUtterance(400, TextToSpeech.QUEUE_ADD, finalUtterance)"""
final_action_new = """                                synchronized(stateLock) {
                                    expectedFinalUtteranceAction = if (isQuestion) "QUESTION_FINAL" else if (toolFeedbacks.isNotEmpty() || currentPendingTools.isNotEmpty()) "STATEMENT_FINAL_TOOL" else "STATEMENT_FINAL"
                                    isLlmGenerationDone = true
                                }
                                checkAndRunFinalAction()"""
content = content.replace(final_action_orig, final_action_new)

# 8. UtteranceProgressListener onDone modifications
tts_listener_orig = """                    lastTtsUpdateTime = System.currentTimeMillis()
                    currentHighlightStart = -1
                    currentHighlightEnd = -1
                    CoroutineScope(Dispatchers.Main).launch {
                        val spannable = responseText.text as? android.text.Spannable
                        if (spannable != null) {
                            val oldSpans = spannable.getSpans(0, spannable.length, android.text.style.BackgroundColorSpan::class.java)
                            for (span in oldSpans) spannable.removeSpan(span)
                        }
                    }
                }
                CoroutineScope(Dispatchers.Main).launch {
                    if (utteranceId == "QUESTION_FINAL") {
                        btnMic.performClick()
                    } else if (utteranceId == "STATEMENT_FINAL_TOOL") {
                        for (job in currentPendingTools) {
                            try { job.await() } catch (e: Exception) {}
                        }
                        kotlinx.coroutines.delay(50)
                        finish()
                    } else if (utteranceId == "STATEMENT_FINAL") {
                        kotlinx.coroutines.delay(50)
                        finish()
                    }
                }"""
tts_listener_new = """                    lastTtsUpdateTime = System.currentTimeMillis()
                    lastFinishedUtteranceId = utteranceId
                    currentHighlightStart = -1
                    currentHighlightEnd = -1
                    CoroutineScope(Dispatchers.Main).launch {
                        val spannable = responseText.text as? android.text.Spannable
                        if (spannable != null) {
                            val oldSpans = spannable.getSpans(0, spannable.length, android.text.style.BackgroundColorSpan::class.java)
                            for (span in oldSpans) spannable.removeSpan(span)
                        }
                    }
                    checkAndRunFinalAction()
                }"""
content = content.replace(tts_listener_orig, tts_listener_new)

# 9. onDestroy updates
ondestroy_orig = """    override fun onDestroy() {
        tts?.stop()
        speechRecognizer?.destroy()
        dotAnimatorJob?.cancel()
        
        val restartIntent = Intent(context, WakeWordService::class.java)"""
ondestroy_new = """    override fun onDestroy() {
        tts?.stop()
        speechRecognizer?.destroy()
        dotAnimatorJob?.cancel()
        typewriterJob?.cancel()
        unloadJob?.cancel()
        
        val restartIntent = Intent(context, WakeWordService::class.java)"""
content = content.replace(ondestroy_orig, ondestroy_new)

# 10. checkAndRunFinalAction method
gateway_method = """    private fun checkAndRunFinalAction() {
        var actionToRun: String? = null
        synchronized(stateLock) {
            if (isLlmGenerationDone && (lastQueuedUtteranceId.isEmpty() || lastFinishedUtteranceId == lastQueuedUtteranceId)) {
                actionToRun = expectedFinalUtteranceAction
                expectedFinalUtteranceAction = "" // Prevent duplicate triggers
                isLlmGenerationDone = false
            }
        }
        
        if (!actionToRun.isNullOrEmpty()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                if (actionToRun == "QUESTION_FINAL") {
                    btnMic.performClick()
                } else if (actionToRun == "STATEMENT_FINAL_TOOL") {
                    for (job in currentPendingTools) { try { job.await() } catch (e: Exception) {} }
                    kotlinx.coroutines.delay(50)
                    finish()
                } else if (actionToRun == "STATEMENT_FINAL") {
                    kotlinx.coroutines.delay(50)
                    finish()
                }
            }
        }
    }

    override fun onDestroy()"""
content = content.replace("    override fun onDestroy()", gateway_method)

with open(file_path, "w") as f:
    f.write(content)
