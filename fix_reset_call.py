import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/AssistantSession.kt"
with open(file_path, "r") as f:
    text = f.read()

old_call = """                                android.util.Log.w("AssistantSession", "Suspiciously short response. KV Cache full. Graceful Sliding Window Reset initiated...")
                                LLMManager.resetConversation()
                                handleQuery(query, retryCount + 1)
                                return@launch"""

new_call = """                                android.util.Log.w("AssistantSession", "Suspiciously short response. KV Cache full. Graceful Sliding Window Reset initiated...")
                                LLMManager.resetConversationSuspend()
                                handleQuery(query, retryCount + 1)
                                return@launch"""
text = text.replace(old_call, new_call)

with open(file_path, "w") as f:
    f.write(text)
