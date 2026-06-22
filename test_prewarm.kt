suspend fun prewarm(context: Context) {
    if (engine == null || conversation == null || !isFirstMessage) return
    isInitializing = true
    try {
        val sysPrompt = getSystemPrompt(context, "")
        val prewarmPrompt = "$sysPrompt\n\n[System Initialization: Acknowledge this configuration. Do not generate a response.]"
        
        // We use a latch or just a simple callback
        val latch = kotlinx.coroutines.sync.Mutex(true)
        conversation?.sendMessageAsync(Contents.of(Content.Text(prewarmPrompt)), object : com.google.ai.edge.litertlm.ChatCallback {
            override fun onResponse(response: String) {}
            override fun onFinish() { latch.unlock() }
            override fun onError(e: Exception) { latch.unlock() }
        }, emptyMap())
        latch.lock() // wait for it to finish
        isFirstMessage = false
        Log.d("LLMManager", "Prewarm complete. KV cache populated.")
    } catch (e: Exception) {
        Log.e("LLMManager", "Prewarm failed", e)
    } finally {
        isInitializing = false
    }
}
