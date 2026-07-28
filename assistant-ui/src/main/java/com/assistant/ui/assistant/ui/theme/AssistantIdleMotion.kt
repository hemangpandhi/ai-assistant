package com.assistant.ui.assistant.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * When false, faces skip infinite idle loops and soft blur blooms so the first
 * Compose frame can paint under ~100ms. The immersive overlay flips this to true
 * after the first vsync.
 */
val LocalAssistantIdleMotion = staticCompositionLocalOf { true }
