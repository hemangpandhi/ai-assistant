import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/AssistantSession.kt"
with open(file_path, "r") as f:
    text = f.read()

# 1. Update onInit to use AudioAttributes
old_oninit = """            } catch (e: Exception) {
                // Ignore if getVoices() is unsupported or fails, fallback to default
            }
            
            tts?.playSilentUtterance(10, TextToSpeech.QUEUE_ADD, "PREWARM")"""
new_oninit = """            } catch (e: Exception) {
                // Ignore if getVoices() is unsupported or fails, fallback to default
            }
            
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
            
            tts?.playSilentUtterance(10, TextToSpeech.QUEUE_ADD, "PREWARM")"""
text = text.replace(old_oninit, new_oninit)

# 2. Revert the handleChunk `KEY_PARAM_STREAM` bundle hack back to `null`
old_handle_chunk_bundle = """                                val utteranceId = "SENTENCE_$sentenceStartOffset"
                                lastQueuedUtteranceId = utteranceId
                                LatencyLogger.log("AssistantSession", "Sending sentence to TTS queue: $utteranceId")
                                val audioBundle = android.os.Bundle().apply { putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, 11) }
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, audioBundle, utteranceId)"""
new_handle_chunk_bundle = """                                val utteranceId = "SENTENCE_$sentenceStartOffset"
                                lastQueuedUtteranceId = utteranceId
                                LatencyLogger.log("AssistantSession", "Sending sentence to TTS queue: $utteranceId")
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, utteranceId)"""
text = text.replace(old_handle_chunk_bundle, new_handle_chunk_bundle)

# 3. Fix the indentation of the while loop in handleChunk
old_handle_chunk_while = """                                    typewriterJob = CoroutineScope(Dispatchers.Main).launch {
                                        while (isActive && currentDisplayLength < targetDisplayMessage.length) {
                                        val timeSinceTts = System.currentTimeMillis() - lastTtsUpdateTime
                                        val isTtsActive = lastTtsUpdateTime > 0L && timeSinceTts < 2000 // Active if TTS fired recently
                                        
                                        // Throttle the visual typewriter if it gets more than 3 characters ahead of the spoken audio
                                        if (isTtsActive && currentDisplayLength > ttsSpokenLength + 3) {
                                            kotlinx.coroutines.delay(50)
                                            continue
                                        }
                                        
                                        val step = 1
                                        val dynamicDelay = typingSpeedMs
                                        
                                        currentDisplayLength = Math.min(currentDisplayLength + step, targetDisplayMessage.length)
                                        val currentSubstring = targetDisplayMessage.substring(0, currentDisplayLength)
                                        
                                        responseText.text = currentSubstring
                                        
                                        // Auto-scroll to bottom efficiently (batching to prevent message queue flooding)
                                        if (currentDisplayLength % 5 == 0) {
                                            svResponse?.post {
                                                svResponse?.fullScroll(View.FOCUS_DOWN)
                                            }
                                        }
                                        
                                        kotlinx.coroutines.delay(dynamicDelay)
                                        }
                                    }"""
new_handle_chunk_while = """                                    typewriterJob = CoroutineScope(Dispatchers.Main).launch {
                                        while (isActive && currentDisplayLength < targetDisplayMessage.length) {
                                            val timeSinceTts = System.currentTimeMillis() - lastTtsUpdateTime
                                            val isTtsActive = lastTtsUpdateTime > 0L && timeSinceTts < 2000 // Active if TTS fired recently
                                            
                                            // Throttle the visual typewriter if it gets more than 3 characters ahead of the spoken audio
                                            if (isTtsActive && currentDisplayLength > ttsSpokenLength + 3) {
                                                kotlinx.coroutines.delay(50)
                                                continue
                                            }
                                            
                                            val step = 1
                                            val dynamicDelay = typingSpeedMs
                                            
                                            currentDisplayLength = Math.min(currentDisplayLength + step, targetDisplayMessage.length)
                                            val currentSubstring = targetDisplayMessage.substring(0, currentDisplayLength)
                                            
                                            responseText.text = currentSubstring
                                            
                                            // Auto-scroll to bottom efficiently (batching to prevent message queue flooding)
                                            if (currentDisplayLength % 5 == 0) {
                                                svResponse?.post {
                                                    svResponse?.fullScroll(View.FOCUS_DOWN)
                                                }
                                            }
                                            
                                            kotlinx.coroutines.delay(dynamicDelay)
                                        }
                                    }"""
text = text.replace(old_handle_chunk_while, new_handle_chunk_while)

# 4. Fix indentation of while loop in onDone
old_ondone_while = """                                typewriterJob = CoroutineScope(Dispatchers.Main).launch {
                                    kotlinx.coroutines.delay(400) // Wait for TTS engine to initialize audio buffer
                                    while (isActive && currentDisplayLength < targetDisplayMessage.length) {
                                        val timeSinceTts = System.currentTimeMillis() - lastTtsUpdateTime
                                        val isTtsActive = lastTtsUpdateTime > 0L && timeSinceTts < 2000 // Active if TTS fired recently
                                        
                                        if (isTtsActive && currentDisplayLength > ttsSpokenLength + 3) {
                                            kotlinx.coroutines.delay(50)
                                            continue
                                        }
                                        
                                        val step = 1
                                        val dynamicDelay = typingSpeedMs
                                        currentDisplayLength = Math.min(currentDisplayLength + step, targetDisplayMessage.length)
                                        val currentSubstring = targetDisplayMessage.substring(0, currentDisplayLength)
                                        responseText.text = currentSubstring
                                        if (currentDisplayLength % 5 == 0) {
                                            svResponse?.post { svResponse?.fullScroll(View.FOCUS_DOWN) }
                                        }
                                        kotlinx.coroutines.delay(dynamicDelay)
                                        }
                                    // Apply Markdown formatting only once after typewriter finishes
                                    responseText.text = parseMarkdown(targetDisplayMessage)
                                }"""
new_ondone_while = """                                typewriterJob = CoroutineScope(Dispatchers.Main).launch {
                                    kotlinx.coroutines.delay(400) // Wait for TTS engine to initialize audio buffer
                                    while (isActive && currentDisplayLength < targetDisplayMessage.length) {
                                        val timeSinceTts = System.currentTimeMillis() - lastTtsUpdateTime
                                        val isTtsActive = lastTtsUpdateTime > 0L && timeSinceTts < 2000 // Active if TTS fired recently
                                        
                                        if (isTtsActive && currentDisplayLength > ttsSpokenLength + 3) {
                                            kotlinx.coroutines.delay(50)
                                            continue
                                        }
                                        
                                        val step = 1
                                        val dynamicDelay = typingSpeedMs
                                        currentDisplayLength = Math.min(currentDisplayLength + step, targetDisplayMessage.length)
                                        val currentSubstring = targetDisplayMessage.substring(0, currentDisplayLength)
                                        responseText.text = currentSubstring
                                        if (currentDisplayLength % 5 == 0) {
                                            svResponse?.post { svResponse?.fullScroll(View.FOCUS_DOWN) }
                                        }
                                        kotlinx.coroutines.delay(dynamicDelay)
                                    }
                                    // Apply Markdown formatting only once after typewriter finishes
                                    responseText.text = parseMarkdown(targetDisplayMessage)
                                }"""
text = text.replace(old_ondone_while, new_ondone_while)


with open(file_path, "w") as f:
    f.write(text)
