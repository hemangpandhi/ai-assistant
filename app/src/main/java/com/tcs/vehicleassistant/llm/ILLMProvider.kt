package com.tcs.vehicleassistant.llm

import android.content.Context

interface ILLMProvider {
    suspend fun initialize(context: Context, force: Boolean = false)
    suspend fun generateStream(
        context: Context, 
        prompt: String, 
        userQuery: String, 
        onToken: (String) -> Unit, 
        onDone: (String) -> Unit, 
        onError: (Exception) -> Unit
    )
    fun unload()
    fun resetConversation()
    fun isReady(): Boolean
}
