package com.example.gemininano

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    var isStreaming: Boolean = false
)
