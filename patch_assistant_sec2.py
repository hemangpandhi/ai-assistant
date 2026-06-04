import re

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "r") as f:
    code = f.read()

old_tool_intercept = """                                val toolCall = match.groups[1]?.value?.trim() ?: continue
                                if (executedTools.add(toolCall)) {
                                    android.util.Log.d("AssistantSession", "Executing tool from LLM: $toolCall")
                                    val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                                        executeToolCall(toolCall)
                                    }
                                    pendingTools.add(job)
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

code = code.replace(old_tool_intercept, new_tool_intercept)

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "w") as f:
    f.write(code)

print("AssistantSession Execution Interception patched.")
