import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/AssistantSession.kt"
with open(file_path, "r") as f:
    text = f.read()

old_fallback = """                            if (finalMsg.isNotBlank()) {
                                val safeIndex = Math.min(spokenTextLength[0], finalMsg.length)
                                val remainingSentence = finalMsg.substring(safeIndex).trim()
                                if (remainingSentence.isNotEmpty()) {
                                    android.util.Log.d("AssistantSession", "Remaining unprocessed text: $remainingSentence")
                                }"""
new_fallback = """                            if (finalMsg.isNotBlank()) {
                                val safeIndex = Math.min(spokenTextLength[0], finalMsg.length)
                                val remainingSentence = finalMsg.substring(safeIndex).trim()
                                if (remainingSentence.isNotEmpty()) {
                                    val parsedSentence = parseMarkdown(remainingSentence).toString()
                                    val sentenceStartOffset = parsedSpokenLength[0]
                                    parsedSpokenLength[0] += parsedSentence.length
                                    
                                    val utteranceId = "SENTENCE_$sentenceStartOffset"
                                    lastQueuedUtteranceId = utteranceId
                                    LatencyLogger.log("AssistantSession", "Sending fallback sentence to TTS queue: $utteranceId")
                                    tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
                                }"""
text = text.replace(old_fallback, new_fallback)

with open(file_path, "w") as f:
    f.write(text)
