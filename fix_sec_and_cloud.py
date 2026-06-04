import re

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "r") as f:
    content = f.read()

# 1. Add pendingTools
old_vars = """        val executedTools = mutableSetOf<String>()
        val toolFeedbacks = mutableListOf<String>()"""
new_vars = """        val executedTools = mutableSetOf<String>()
        val toolFeedbacks = mutableListOf<String>()
        val pendingTools = mutableListOf<kotlinx.coroutines.Deferred<String?>>()"""
content = content.replace(old_vars, new_vars)

# 2. Add Guardrails to handleChunk
old_tool_intercept = """                                val toolCall = match.groups[1]?.value?.trim() ?: continue
                                if (executedTools.add(toolCall)) {
                                    android.util.Log.d("AssistantSession", "Executing tool from LLM: $toolCall")
                                    val feedback = executeToolCall(toolCall)
                                    if (feedback != null) toolFeedbacks.add(feedback)
                                }"""
new_tool_intercept = """                                val toolCall = match.groups[1]?.value?.trim() ?: continue
                                if (executedTools.add(toolCall)) {
                                    android.util.Log.d("AssistantSession", "Executing tool from LLM: $toolCall")
                                    val toolDef = ToolManager.getToolDefinition(toolCall)
                                    if (toolDef?.requiresConfirmation == true) {
                                        pendingConfirmationTool = toolCall
                                        val confirmMsg = toolDef.confirmationMessage ?: "Warning: Are you sure you want to do this?"
                                        lastResponseBuilder.clear()
                                        lastResponseBuilder.append(confirmMsg)
                                        isHallucinating = true // Force stop further output processing
                                    } else {
                                        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                                            executeToolCall(toolCall)
                                        }
                                        pendingTools.add(job)
                                    }
                                }"""
content = content.replace(old_tool_intercept, new_tool_intercept)

# 3. Add awaitAll to onDone
old_ondone = """                        CoroutineScope(Dispatchers.Main).launch {
                            timeoutJob?.cancel()
                            var finalMsg = lastResponseBuilder.toString()"""
new_ondone = """                        CoroutineScope(Dispatchers.Main).launch {
                            timeoutJob?.cancel()
                            
                            if (pendingTools.isNotEmpty()) {
                                stopThinkingAnimation()
                                voiceAnimation.state = VoiceAnimationView.State.LISTENING
                                val feedbacks = kotlinx.coroutines.awaitAll(*pendingTools.toTypedArray()).filterNotNull()
                                toolFeedbacks.addAll(feedbacks)
                            }
                            
                            var finalMsg = lastResponseBuilder.toString()"""
content = content.replace(old_ondone, new_ondone)

# 4. Fix Cloud Model query ignoring interceptedQuery
old_cloud = """                    val systemPrompt = LLMManager.getSystemPrompt(context, query)
                    if (LocalLLMActivity.currentCloudModelName.contains("Gemini")) {
                        GeminiManager.sendMessageAsync(systemPrompt, query, callback)
                    } else {
                        AnthropicManager.sendMessageAsync(systemPrompt, query, callback)
                    }"""
new_cloud = """                    val systemPrompt = LLMManager.getSystemPrompt(context, interceptedQuery)
                    if (LocalLLMActivity.currentCloudModelName.contains("Gemini")) {
                        GeminiManager.sendMessageAsync(systemPrompt, interceptedQuery, callback)
                    } else {
                        AnthropicManager.sendMessageAsync(systemPrompt, interceptedQuery, callback)
                    }"""
content = content.replace(old_cloud, new_cloud)

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "w") as f:
    f.write(content)

print("Applied fix_sec_and_cloud.py successfully!")
