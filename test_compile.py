import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/LLMManager.kt"
with open(file_path, "r") as f:
    text = f.read()

# restore old
old = "conversation = engine!!.createConversation(activeContext, conversationConfig)"
new = "conversation = engine!!.createConversation(conversationConfig)"
text = text.replace(old, new)

# add systemPrompt
old2 = "val conversationConfig = ConversationConfig()"
new2 = """val conversationConfig = ConversationConfig().apply {
            if (activeContext != null) {
                // Not sure if this exists, but I can check by compiling
                // this.systemPrompt = getSystemPrompt(activeContext)
            }
        }"""
text = text.replace(old2, new2)

with open(file_path, "w") as f:
    f.write(text)
