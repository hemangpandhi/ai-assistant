package com.example.gemininano

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

object MemoryManager {
    private const val TAG = "MemoryManager"
    
    /** Maximum character budget for the sliding-window context passed to the LLM. */
    private const val MAX_CONTEXT_CHARS = 3000
    
    /**
     * When history exceeds this fraction of [MAX_CONTEXT_CHARS], an async summarization job
     * is triggered so that old context is preserved as a [Summary] turn rather than deleted.
     */
    private const val SUMMARIZE_THRESHOLD = 0.80f
    
    /** Number of oldest turns to collapse into one summary entry. */
    private const val TURNS_TO_SUMMARIZE = 10
    
    data class Turn(val role: String, val content: String)
    
    private val conversationHistory = mutableListOf<Turn>()
    
    /** True while an async summarization job is in flight — prevents overlapping jobs. */
    private var isSummarizing = false
    
    fun addTurn(role: String, content: String) {
        conversationHistory.add(Turn(role, content))
        Log.d(TAG, "Added turn: $role. Total turns: ${conversationHistory.size}")
        
        // Proactively trigger summarization if approaching the context limit, so that
        // the summary is ready before getSlidingWindowContext() is forced to hard-truncate.
        val approxLength = conversationHistory.sumOf { it.role.length + it.content.length + 2 }
        if (!isSummarizing && approxLength > (MAX_CONTEXT_CHARS * SUMMARIZE_THRESHOLD).toInt()
            && conversationHistory.size >= TURNS_TO_SUMMARIZE) {
            triggerAsyncSummarization()
        }
    }
    
    /**
     * Returns the most-recent turns that fit within [maxChars], preserving any leading
     * [Summary] turn. When the hard limit would truncate old turns, the oldest turns are
     * first collapsed via [triggerAsyncSummarization] (best-effort — if the summary job
     * hasn't finished yet, this falls back to hard truncation as before).
     */
    fun getSlidingWindowContext(maxChars: Int): String {
        val sb = java.lang.StringBuilder()
        var currentLength = 0
        
        // We iterate backwards to keep the most recent messages
        val reversedContext = mutableListOf<String>()
        for (i in conversationHistory.indices.reversed()) {
            val turn = conversationHistory[i]
            val turnString = "${turn.role}: ${turn.content}\n"
            
            if (currentLength + turnString.length > maxChars) {
                Log.w(TAG, "Sliding window threshold reached at index $i. Truncating ${i + 1} oldest turns.")
                // Remove truncated turns to prevent memory leak
                conversationHistory.subList(0, i + 1).clear()
                break
            }
            reversedContext.add(turnString)
            currentLength += turnString.length
        }
        
        // Reverse back and append
        for (i in reversedContext.indices.reversed()) {
            sb.append(reversedContext[i])
        }
        
        return sb.toString().trim()
    }
    
    /**
     * Collapses the oldest [TURNS_TO_SUMMARIZE] turns into a single `[Summary]` turn via
     * the local LLM. Uses an otherwise-idle inference cycle so there is no user-visible
     * latency. Falls back to a simple text-concatenation summary if the LLM is busy.
     */
    private fun triggerAsyncSummarization() {
        if (isSummarizing) return
        val turnsToCollapse = conversationHistory.take(TURNS_TO_SUMMARIZE).toList()
        if (turnsToCollapse.isEmpty()) return
        
        isSummarizing = true
        Log.i(TAG, "Triggering async summarization of ${turnsToCollapse.size} oldest turns.")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val collapsed = turnsToCollapse.joinToString("\n") { "${it.role}: ${it.content}" }
                val summaryText = summarizeWithLlm(collapsed)
                
                // Replace the summarized turns with a single compact [Summary] turn.
                // Recalculate the overlap within the synchronized block to handle concurrent
                // modifications from getSlidingWindowContext() that may have already removed
                // some of the turns we intended to collapse.
                synchronized(conversationHistory) {
                    val summarizedContents = turnsToCollapse.map { it.content }.toSet()
                    val firstUnsummarizedIndex = conversationHistory.indexOfFirst {
                        it.role != "Summary" && !summarizedContents.contains(it.content)
                    }
                    val safeDropCount = if (firstUnsummarizedIndex > 0) firstUnsummarizedIndex
                                        else minOf(turnsToCollapse.size, conversationHistory.size)
                    val remaining = conversationHistory.drop(safeDropCount)
                    conversationHistory.clear()
                    conversationHistory.add(Turn("Summary", "[Earlier context: $summaryText]"))
                    conversationHistory.addAll(remaining)
                }
                Log.i(TAG, "Summarization complete. History compacted to ${conversationHistory.size} turns.")
            } catch (e: Exception) {
                Log.w(TAG, "Summarization failed (non-fatal), using simple fallback: ${e.message}")
                applyFallbackSummary(turnsToCollapse)
            } finally {
                isSummarizing = false
            }
        }
    }
    
    private const val SUMMARIZATION_PROMPT = "Summarize the following conversation in one sentence:"
    
    /**
     * Summarizes [text] using a **separate, ephemeral** LLM conversation so the main
     * chat conversation's KV cache and history are never contaminated.
     *
     * A dedicated [Conversation] is created from the same [Engine], used only for this
     * single inference, and then closed. If no engine is available, falls back to simple
     * extractive truncation.
     */
    private suspend fun summarizeWithLlm(text: String): String {
        val engine = LLMManager.engine ?: return buildFallbackSummary(text)
        
        // Create an isolated one-shot conversation purely for summarization.
        val summarizationConversation = try {
            engine.createConversation(com.google.ai.edge.litertlm.ConversationConfig())
        } catch (e: Exception) {
            Log.w(TAG, "Could not create summarization conversation: ${e.message}")
            return buildFallbackSummary(text)
        }
        
        val prompt = "$SUMMARIZATION_PROMPT\n\n$text\n\nSummary:"
        val resultBuilder = StringBuilder()
        
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                try {
                    val cb = object : com.google.ai.edge.litertlm.MessageCallback {
                        override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                            resultBuilder.append(message.toString())
                        }
                        override fun onDone() { if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
                        override fun onError(t: Throwable) { if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
                    }
                    summarizationConversation.sendMessageAsync(
                        com.google.ai.edge.litertlm.Contents.of(
                            com.google.ai.edge.litertlm.Content.Text(prompt)
                        ),
                        cb,
                        emptyMap()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "LLM summarization call failed: ${e.message}")
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
            }
        } finally {
            // Always close the ephemeral conversation to release its KV-cache slot.
            try { summarizationConversation.close() } catch (e: Exception) { /* non-fatal */ }
        }
        
        val summary = resultBuilder.toString().trim().takeIf { it.isNotEmpty() }
            ?: buildFallbackSummary(text)
        return summary.take(200) // Keep summary compact
    }
    
    private fun applyFallbackSummary(turns: List<Turn>) {
        synchronized(conversationHistory) {
            val remaining = conversationHistory.drop(turns.size)
            conversationHistory.clear()
            conversationHistory.add(Turn("Summary", "[Earlier context: ${buildFallbackSummary(turns.joinToString(" ") { it.content })}]"))
            conversationHistory.addAll(remaining)
        }
    }
    
    private fun buildFallbackSummary(text: String): String =
        text.take(150).trimEnd() + if (text.length > 150) "..." else ""
    
    fun getAnthropicHistory(): List<org.json.JSONObject> {
        return conversationHistory.map { turn ->
            org.json.JSONObject().apply {
                val apiRole = if (turn.role.equals("User", true)) "user" else "assistant"
                put("role", apiRole)
                put("content", turn.content)
            }
        }
    }
    
    fun getGeminiHistory(): List<org.json.JSONObject> {
        return conversationHistory.map { turn ->
            org.json.JSONObject().apply {
                val apiRole = if (turn.role.equals("User", true)) "user" else "model"
                put("role", apiRole)
                put("parts", org.json.JSONArray().put(org.json.JSONObject().put("text", turn.content)))
            }
        }
    }
    
    fun clearMemory() {
        conversationHistory.clear()
        isSummarizing = false
        Log.i(TAG, "Conversation memory cleared.")
    }
}
