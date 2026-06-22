filepath = "app/src/androidTest/java/com/example/gemininano/LLMValidationTestSuite.kt"
with open(filepath, 'r') as f:
    content = f.read()

import re

# Remove flow imports
content = content.replace("import kotlinx.coroutines.flow.toList\n", "")

# Add liteRT imports
if "import com.google.ai.edge.litertlm.Content" not in content:
    content = content.replace("import kotlinx.coroutines.runBlocking", "import com.google.ai.edge.litertlm.Content\nimport com.google.ai.edge.litertlm.Contents\nimport com.google.ai.edge.litertlm.Message\nimport com.google.ai.edge.litertlm.MessageCallback\nimport kotlinx.coroutines.runBlocking")

# Replace predictStreaming
replacement = """
                val finalResponse = suspendCancellableCoroutine<String> { continuation ->
                    val callback = object : MessageCallback {
                        override fun onMessage(message: Message) {
                            if (firstTokenTime == 0L) {
                                firstTokenTime = System.currentTimeMillis()
                                val ttft = firstTokenTime - startTime
                                Log.d("LLMTest", "TTFT for '${case.prompt}': ${ttft}ms")
                                totalTTFT += ttft
                                if (ttft > maxTTFT) maxTTFT = ttft
                            }
                            val chunk = message.toString()
                            responseBuilder.append(chunk)
                            
                            val currentText = responseBuilder.toString()
                            if (currentText.contains("</TOOL>")) {
                                if (continuation.isActive) {
                                    continuation.resume(currentText)
                                }
                            }
                        }
                    }
                    
                    try {
                        LLMManager.conversation!!.sendMessageAsync(
                            Contents.of(Content.Text(finalPrompt)),
                            callback,
                            emptyMap()
                        )
                        // Timeout watchdog
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            kotlinx.coroutines.delay(20000)
                            if (continuation.isActive) {
                                continuation.resume(responseBuilder.toString())
                            }
                        }
                    } catch(e: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }
                }
"""

content = re.sub(
    r'val flow = LLMManager\.conversation!!\.predictStreaming\(finalPrompt\).*?val finalResponse = responseBuilder\.toString\(\)',
    replacement.strip(),
    content,
    flags=re.DOTALL
)

with open(filepath, 'w') as f:
    f.write(content)
