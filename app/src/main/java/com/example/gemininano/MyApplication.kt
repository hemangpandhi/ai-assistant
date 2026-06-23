package com.example.gemininano

import android.app.Application
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        prewarmLlm() 
    }

    /**
     * Eagerly initializes the LLM and runs a silent dummy inference to trigger GPU shader
     * compilation and KV-cache allocation before any user interaction. This eliminates the
     * 3–23 s cold-start penalty that would otherwise hit the first voice-path query.
     *
     * WakeWordService starts before any UI, so this Application hook is the earliest possible
     * place to kick off initialization.
     */
    private fun prewarmLlm() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                LLMManager.autoInitialize(applicationContext, callback = object : LLMManager.InitCallback {
                    override fun onSuccess() {
                        // Prime the KV cache with the real system prompt BEFORE the user speaks.
                        // After this completes, isFirstMessage=false and all user queries
                        // hit the cheap per-turn path (~80 tokens) → TTFT < 2s.
                        runSilentPrewarmInference()

                        // Start proactive ambient monitoring now that VehicleManager is up.
                        ProactiveAmbientEngine.start()
                    }

                    override fun onError(e: Exception) {
                        Log.w(TAG, "LLM auto-init failed during pre-warm (non-fatal): ${e.message}")
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Pre-warm setup failed (non-fatal)", e)
            }
        }
    }

    private fun runSilentPrewarmInference() {
        val conversation = LLMManager.conversation ?: return
        Log.i(TAG, "[Prewarm] Starting system-prompt prewarm to prime KV cache...")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, "AI Engine: Prewarming KV cache (10-20s)...", android.widget.Toast.LENGTH_LONG).show()
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // Build the real system prompt using the prewarm query from the registry.
                // This drives RAG/semantic tool selection entirely from vehicle_skills_registry_v2.0.json.
                // To change the prewarm scope, edit config.prewarm_query in the registry — no code changes needed.
                val warmupQuery = ToolManager.prewarmQuery
                val sysPrompt = LLMManager.getSystemPrompt(applicationContext, warmupQuery)
                val prewarmPrompt = "<start_of_turn>user\n$sysPrompt\n(Reminder: Use exact <TOOL> XML tags for car actions.)\n\n[PREWARM] Ready.<end_of_turn>\n<start_of_turn>model\n"

                val dummyCallback = object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                        // Discard — prewarm output is not shown to the user.
                    }

                    override fun onDone() {
                        // IMPORTANT: Do NOT call resetConversation() here.
                        // The KV cache now contains the system prompt. Setting isFirstMessage=false
                        // ensures all future user queries use the cheap per-turn prompt path.
                        LLMManager.isFirstMessage = false
                        Log.i(TAG, "[Prewarm] Complete. KV cache primed. isFirstMessage=false. TTFT will be <2s.")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(applicationContext, "AI Engine: Prewarm complete. Ready for instant replies.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        Log.w(TAG, "[Prewarm] Inference failed (non-fatal). Resetting to clean state.", throwable)
                        // Only reset on failure so the first real user query gets a safe clean start.
                        LLMManager.resetConversation(applicationContext)
                    }
                }

                conversation.sendMessageAsync(
                    com.google.ai.edge.litertlm.Contents.of(com.google.ai.edge.litertlm.Content.Text(prewarmPrompt)),
                    dummyCallback,
                    emptyMap()
                )
            } catch (e: Exception) {
                Log.w(TAG, "[Prewarm] Setup failed (non-fatal). Resetting to clean state.", e)
                LLMManager.resetConversation(applicationContext)
            }
        }
    }

    companion object {
        private const val TAG = "MyApplication"
    }
}
