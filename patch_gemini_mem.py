import re

with open("app/src/main/java/com/example/gemininano/GeminiManager.kt", "r") as f:
    code = f.read()

# If GeminiManager has a conversationHistory similar to Anthropic, we need to update it.
if "conversationHistory" in code:
    code = code.replace("    private val conversationHistory = mutableListOf<JSONObject>()", "")
    code = code.replace("    fun clearContext() {\n        conversationHistory.clear()\n    }", "    fun clearContext() {\n        MemoryManager.clearMemory()\n    }")
    code = code.replace("                    for (msg in conversationHistory) {", "                    for (msg in MemoryManager.getAnthropicHistory()) {")

with open("app/src/main/java/com/example/gemininano/GeminiManager.kt", "w") as f:
    f.write(code)

print("GeminiManager.kt patched for MemoryManager.")
