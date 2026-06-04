import re

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "r") as f:
    content = f.read()

content = content.replace("private fun executeToolCall(toolCall: String): String? {", "private suspend fun executeToolCall(toolCall: String): String? {")

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "w") as f:
    f.write(content)

print("executeToolCall made suspend.")
