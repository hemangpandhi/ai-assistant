package com.tcs.vehicleassistant.domain.tools

import android.content.Context
import android.content.Intent

interface IToolExecutor {
    suspend fun executeToolCall(
        context: Context,
        rawToolCall: String,
        enforcePromptAllowList: Boolean = false,
        intentHandler: ((Intent) -> Unit)? = null
    ): String

    suspend fun runSystemDiagnostics(context: Context): String
}
