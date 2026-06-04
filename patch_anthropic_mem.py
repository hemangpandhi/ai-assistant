import re

with open("app/src/main/java/com/example/gemininano/AnthropicManager.kt", "r") as f:
    code = f.read()

# Replace conversationHistory management
code = code.replace("    private val conversationHistory = mutableListOf<JSONObject>()", "")
code = code.replace("    fun clearContext() {\n        conversationHistory.clear()\n    }", "    fun clearContext() {\n        MemoryManager.clearMemory()\n    }")

# Update sendMessageAsync payload
code = code.replace("                    for (msg in conversationHistory) {", "                    for (msg in MemoryManager.getAnthropicHistory()) {")

with open("app/src/main/java/com/example/gemininano/AnthropicManager.kt", "w") as f:
    f.write(code)

print("AnthropicManager.kt patched for MemoryManager.")
