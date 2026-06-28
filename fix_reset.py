import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/LLMManager.kt"
with open(file_path, "r") as f:
    text = f.read()

old_reset = """    fun resetConversation(context: Context? = null) {
        if (engine == null) return
        val activeContext = context ?: appContext
        
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w("LLMManager", "Error closing previous conversation", e)
        }
        
        isFirstMessage = true
        
        val conversationConfig = ConversationConfig().apply {
            if (activeContext != null) {
                // Not sure if this exists, but I can check by compiling
                // this.systemPrompt = getSystemPrompt(activeContext)
            }
        }
        
        try {
            conversation = engine!!.createConversation(conversationConfig)
            Log.d("LLMManager", "Conversation reset. isFirstMessage=true.")
        } catch (e: Exception) {
            Log.e("LLMManager", "Error creating conversation", e)
        }
    }"""

new_reset = """    fun resetConversation(context: Context? = null) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            resetConversationSuspend(context)
        }
    }

    suspend fun resetConversationSuspend(context: Context? = null) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        initMutex.withLock {
            if (engine == null) return@withContext
            val activeContext = context ?: appContext
            
            try {
                conversation?.close()
            } catch (e: Exception) {
                Log.w("LLMManager", "Error closing previous conversation", e)
            }
            
            isFirstMessage = true
            
            val conversationConfig = ConversationConfig()
            
            try {
                conversation = engine!!.createConversation(conversationConfig)
                Log.d("LLMManager", "Conversation reset. isFirstMessage=true.")
            } catch (e: Exception) {
                Log.e("LLMManager", "Error creating conversation", e)
            }
        }
    }"""
text = text.replace(old_reset, new_reset)

with open(file_path, "w") as f:
    f.write(text)
