filepath = "app/src/androidTest/java/com/example/gemininano/LLMValidationTestSuite.kt"
with open(filepath, 'r') as f:
    content = f.read()

content = content.replace("import kotlinx.coroutines.flow.toList", "import com.google.ai.edge.litertlm.Content\nimport com.google.ai.edge.litertlm.Contents\nimport com.google.ai.edge.litertlm.Message\nimport com.google.ai.edge.litertlm.MessageCallback")

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
                                assertTrue("TTFT exceeded threshold! ($ttft ms)", ttft < 15000)
                            }
                            responseBuilder.append(message.toString())
                        }
                    }
                    
                    try {
                        LLMManager.conversation!!.sendMessageAsync(
                            Contents.of(Content.Text(finalPrompt)),
                            callback,
                            emptyMap()
                        )
                        // A simple polling loop since LiteRT LM doesn't have an explicit 'onComplete' yet 
                        // in this specific async wrapper, or we wait for a specific token or timeout.
                        // Actually, sendMessageAsync might be returning something, or we can just use 
                        // predict() which is blocking! Wait, does conversation have predict?
                    } catch(e: Exception) {}
                }
"""

# Wait! Is there a predict or generate string method?
# Let's check LLMManager.kt or Conversation interface via grep.
