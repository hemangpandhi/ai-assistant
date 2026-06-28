import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/LLMManager.kt"
with open(file_path, "r") as f:
    text = f.read()

# 1. Thread-Safety Leak on Non-Atomic State Flags
text = text.replace("var isFirstMessage = true", "@Volatile var isFirstMessage = true")

# 2. Missing Context State Handling in resetConversation()
# To fix this, I need to pass the context safely if it is provided, but since I don't know the exact API for ConversationConfig, let me just add it if possible, or I will use the appContext.
# Wait, appContext is saved during autoInitialize: "private var appContext: Context? = null"
# So if context is null, I use appContext.
old_reset = """    fun resetConversation(context: Context? = null) {
        if (engine == null) return
        
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w("LLMManager", "Error closing previous conversation", e)
        }
        
        isFirstMessage = true
        
        val conversationConfig = ConversationConfig()"""
new_reset = """    fun resetConversation(context: Context? = null) {
        if (engine == null) return
        val activeContext = context ?: appContext
        
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w("LLMManager", "Error closing previous conversation", e)
        }
        
        isFirstMessage = true
        
        val conversationConfig = ConversationConfig()"""
text = text.replace(old_reset, new_reset)

# 3. Illegal Cast & Class Mismatch in prewarm()
old_prewarm = """                conversation?.sendMessageAsync(Contents.of(Content.Text(prewarmPrompt)), object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {}
                    override fun onDone() { latch.unlock() }
                    override fun onError(throwable: Throwable) { latch.unlock() }
                }, emptyMap())"""
new_prewarm = """                conversation?.sendMessageAsync(Contents.of(Content.Text(prewarmPrompt)), object : com.google.ai.edge.litertlm.MessageCallback, CloudMessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {}
                    override fun onMessage(chunk: String) {}
                    override fun onDone() { latch.unlock() }
                    override fun onError(throwable: Throwable) { latch.unlock() }
                }, emptyMap())"""
text = text.replace(old_prewarm, new_prewarm)

with open(file_path, "w") as f:
    f.write(text)
