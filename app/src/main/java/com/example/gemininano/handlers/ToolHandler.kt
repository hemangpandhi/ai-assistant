package com.example.gemininano.handlers

import android.content.Context
import android.content.Intent

interface ToolHandler {
    val handlerKey: String
    suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult
}
