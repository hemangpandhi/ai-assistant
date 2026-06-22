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
                        CoroutineScope(Dispatchers.IO).launch {
                            runSilentPrewarmInference()
                        }
                        // Start proactive ambient monitoring now that VehicleManager is up
                        // (autoInitialize also triggers VehicleManager via the normal boot path).
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
        Log.i(TAG, "Running silent pre-warm inference to compile GPU shaders...")
        try {
            val dummyCallback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    // Discard output — this inference exists only to warm up GPU kernels.
                }

                override fun onDone() {
                    Log.i(TAG, "Pre-warm inference complete. GPU shaders compiled, KV-cache ready.")
                    // Reset so the first real user query starts with a clean conversation.
                    LLMManager.resetConversation()
                }

                override fun onError(throwable: Throwable) {
                    Log.w(TAG, "Pre-warm inference error (non-fatal, resetting conversation)", throwable)
                    LLMManager.resetConversation()
                }
            }
            // Send a short representative automotive query to exercise the same GPU shader
            // paths that real user queries will use. Output is discarded — this inference
            // exists solely to trigger GPU kernel compilation and KV-cache pre-allocation.
            conversation.sendMessageAsync(Contents.of(Content.Text("warmup")), dummyCallback, emptyMap())
        } catch (e: Exception) {
            Log.w(TAG, "Silent pre-warm inference failed (non-fatal)", e)
            LLMManager.resetConversation()
        }
    }

    companion object {
        private const val TAG = "MyApplication"
    }
}
