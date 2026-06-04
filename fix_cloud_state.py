import re

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "r") as f:
    content = f.read()

old_cloud = """                    val systemPrompt = LLMManager.getSystemPrompt(context, interceptedQuery)
                    if (LocalLLMActivity.currentCloudModelName.contains("Gemini")) {
                        GeminiManager.sendMessageAsync(systemPrompt, interceptedQuery, callback)
                    } else {
                        AnthropicManager.sendMessageAsync(systemPrompt, interceptedQuery, callback)
                    }"""

new_cloud = """                    var systemPrompt = LLMManager.getSystemPrompt(context, interceptedQuery)
                    systemPrompt += "\\n\\n[Current State: ${VehicleManager.getLLMContextString(context)}]"
                    
                    if (LocalLLMActivity.currentCloudModelName.contains("Gemini")) {
                        GeminiManager.sendMessageAsync(systemPrompt, interceptedQuery, callback)
                    } else {
                        AnthropicManager.sendMessageAsync(systemPrompt, interceptedQuery, callback)
                    }"""

content = content.replace(old_cloud, new_cloud)

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "w") as f:
    f.write(content)

print("Applied fix_cloud_state.py successfully!")
