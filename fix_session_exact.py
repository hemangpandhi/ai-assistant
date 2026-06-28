import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/AssistantSession.kt"
with open(file_path, "r") as f:
    text = f.read()

# 1. Variables
old_vars = """    private val typingSpeedMs: Long = 15L
    
    private val currentPendingTools = mutableListOf<kotlinx.coroutines.Deferred<String?>>()"""
new_vars = """    private val typingSpeedMs: Long = 15L
    
    private val stateLock = Any()
    
    private val currentPendingTools = mutableListOf<kotlinx.coroutines.Deferred<String?>>()
    
    @Volatile private var lastQueuedUtteranceId: String = ""
    @Volatile private var lastFinishedUtteranceId: String = ""
    @Volatile private var expectedFinalUtteranceAction: String = ""
    @Volatile private var isLlmGenerationDone: Boolean = false"""
text = text.replace(old_vars, new_vars)

# 2. handleQuery reset
old_reset = """        ttsSpokenLength = 0
        lastTtsUpdateTime = 0L
        typewriterJob?.cancel()"""
new_reset = """        ttsSpokenLength = 0
        lastTtsUpdateTime = 0L
        typewriterJob?.cancel()
        
        lastQueuedUtteranceId = ""
        lastFinishedUtteranceId = ""
        expectedFinalUtteranceAction = ""
        isLlmGenerationDone = false"""
text = text.replace(old_reset, new_reset)

# 3. handleChunk TTS Queue (adds utterance ID and STREAM_ASSISTANT)
old_handle_chunk = """                                LatencyLogger.log("AssistantSession", "Sending sentence to TTS queue: SENTENCE_$sentenceStartOffset")
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, "SENTENCE_$sentenceStartOffset")"""
new_handle_chunk = """                                val utteranceId = "SENTENCE_$sentenceStartOffset"
                                lastQueuedUtteranceId = utteranceId
                                LatencyLogger.log("AssistantSession", "Sending sentence to TTS queue: $utteranceId")
                                val audioBundle = android.os.Bundle().apply { putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, 11) }
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, audioBundle, utteranceId)"""
text = text.replace(old_handle_chunk, new_handle_chunk)

# 4. handleChunk thread violation fix
old_thread_fix_1 = """                            val displayStr = displayMsg.toString()
                            targetDisplayMessage = displayStr
                            
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {"""
new_thread_fix_1 = """                            val displayStr = displayMsg.toString()
                            
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                targetDisplayMessage = displayStr"""
text = text.replace(old_thread_fix_1, new_thread_fix_1)

# 5. LLM onDone TTS Queue Duplication REMOVAL
old_ondone_tts = """                                val safeIndex = Math.min(spokenTextLength[0], finalMsg.length)
                                val remainingSentence = finalMsg.substring(safeIndex).trim()
                                if (remainingSentence.isNotEmpty()) {
                                    val parsedSentence = parseMarkdown(remainingSentence).toString()
                                    val sentenceStartOffset = parsedSpokenLength[0]
                                    parsedSpokenLength[0] += parsedSentence.length
                                    tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, "SENTENCE_$sentenceStartOffset")
                                }"""
new_ondone_tts = """                                // Duplicated TTS code removed. handleChunk accurately catches the streaming sentences.
                                // If any remaining text exists, we simply log it or let the UI handle it.
                                val safeIndex = Math.min(spokenTextLength[0], finalMsg.length)
                                val remainingSentence = finalMsg.substring(safeIndex).trim()
                                if (remainingSentence.isNotEmpty()) {
                                    android.util.Log.d("AssistantSession", "Remaining unprocessed text: $remainingSentence")
                                }"""
text = text.replace(old_ondone_tts, new_ondone_tts)

# 6. LLM onDone thread violation fix
old_thread_fix_2 = """                            targetDisplayMessage = finalMsg
                            if (typewriterJob == null || typewriterJob?.isActive != true) {"""
new_thread_fix_2 = """                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                targetDisplayMessage = finalMsg
                            }
                            if (typewriterJob == null || typewriterJob?.isActive != true) {"""
text = text.replace(old_thread_fix_2, new_thread_fix_2)

# 7. LLM onDone Final Utterance Gateway
old_final_utt = """                                val finalUtterance = if (isQuestion) "QUESTION_FINAL" else if (toolFeedbacks.isNotEmpty() || currentPendingTools.isNotEmpty()) "STATEMENT_FINAL_TOOL" else "STATEMENT_FINAL"
                                tts?.playSilentUtterance(10, TextToSpeech.QUEUE_ADD, finalUtterance)"""
new_final_utt = """                                synchronized(stateLock) {
                                    expectedFinalUtteranceAction = if (isQuestion) "QUESTION_FINAL" else if (toolFeedbacks.isNotEmpty() || currentPendingTools.isNotEmpty()) "STATEMENT_FINAL_TOOL" else "STATEMENT_FINAL"
                                    isLlmGenerationDone = true
                                }
                                checkAndRunFinalAction()"""
text = text.replace(old_final_utt, new_final_utt)

# 8. TTS Listener onDone Updates
old_tts_listener = """                    lastTtsUpdateTime = System.currentTimeMillis()
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
                        kotlinx.coroutines.delay(500)
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
new_tts_listener = """                    lastTtsUpdateTime = System.currentTimeMillis()
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
text = text.replace(old_tts_listener, new_tts_listener)

# 9. Gateway Helper Method & onDestroy memory leak fixes
old_bottom = """    override fun onDestroy() {
        tts?.stop()
        speechRecognizer?.destroy()
        dotAnimatorJob?.cancel()
        
        val restartIntent = Intent(context, WakeWordService::class.java)"""
new_bottom = """    private fun checkAndRunFinalAction() {
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

    override fun onDestroy() {
        tts?.stop()
        speechRecognizer?.destroy()
        dotAnimatorJob?.cancel()
        typewriterJob?.cancel()
        unloadJob?.cancel()
        
        val restartIntent = Intent(context, WakeWordService::class.java)"""
text = text.replace(old_bottom, new_bottom)

with open(file_path, "w") as f:
    f.write(text)
