import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/AssistantSession.kt"
with open(file_path, "r") as f:
    text = f.read()

old_tts_call = """                                val utteranceId = "SENTENCE_$sentenceStartOffset"
                                lastQueuedUtteranceId = utteranceId
                                LatencyLogger.log("AssistantSession", "Sending sentence to TTS queue: $utteranceId")
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, utteranceId)"""

new_tts_call = """                                val utteranceId = "SENTENCE_$sentenceStartOffset"
                                lastQueuedUtteranceId = utteranceId
                                LatencyLogger.log("AssistantSession", "Sending sentence to TTS queue: $utteranceId")
                                val audioBundle = android.os.Bundle().apply { 
                                    putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, 11) 
                                }
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, audioBundle, utteranceId)"""
text = text.replace(old_tts_call, new_tts_call)

with open(file_path, "w") as f:
    f.write(text)
