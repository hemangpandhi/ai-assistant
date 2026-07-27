package com.tcs.vehicleassistant.llm

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class LlmRequest(
    val prompt: String,
    val userQuery: String = "",
)

data class TokenChunk(
    val text: String,
)

/**
 * JetPacker-style engine port. Implementations wrap LiteRT or cloud adapters.
 */
interface LlmEngine {
    val status: StateFlow<EngineStatus>
    suspend fun ensureReady(context: Context, force: Boolean = false)
    fun generateStream(request: LlmRequest): Flow<TokenChunk>
    suspend fun unload()
    fun resetConversation()
    fun isReady(): Boolean
}
