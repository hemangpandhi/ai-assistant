package com.tcs.vehicleassistant

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    var isStreaming: Boolean = false
)
