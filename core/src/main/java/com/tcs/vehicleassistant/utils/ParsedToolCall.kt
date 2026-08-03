package com.tcs.vehicleassistant.utils

data class ParsedToolCall(
    val fullTag: String,
    val toolName: String,
    val args: String
)
