import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/AssistantSession.kt"
with open(file_path, "r") as f:
    text = f.read()

bundle_code = """
                                val audioBundle = android.os.Bundle().apply { 
                                    putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, 11) 
                                }
"""

# Replace in handleChunk
old_handlechunk = """                                LatencyLogger.log("AssistantSession", "Sending sentence to TTS queue: SENTENCE_$sentenceStartOffset")
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, "SENTENCE_$sentenceStartOffset")"""
new_handlechunk = bundle_code + """                                LatencyLogger.log("AssistantSession", "Sending sentence to TTS queue: SENTENCE_$sentenceStartOffset")
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, audioBundle, "SENTENCE_$sentenceStartOffset")"""
text = text.replace(old_handlechunk, new_handlechunk)

# Replace in onDone
old_ondone = """                                    val utteranceId = "SENTENCE_$sentenceStartOffset"
                                    lastQueuedUtteranceId = utteranceId
                                    LatencyLogger.log("AssistantSession", "Sending fallback sentence to TTS queue: $utteranceId")
                                    tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, utteranceId)"""
new_ondone = bundle_code + """                                    val utteranceId = "SENTENCE_$sentenceStartOffset"
                                    lastQueuedUtteranceId = utteranceId
                                    LatencyLogger.log("AssistantSession", "Sending fallback sentence to TTS queue: $utteranceId")
                                    tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, audioBundle, utteranceId)"""
text = text.replace(old_ondone, new_ondone)

with open(file_path, "w") as f:
    f.write(text)
