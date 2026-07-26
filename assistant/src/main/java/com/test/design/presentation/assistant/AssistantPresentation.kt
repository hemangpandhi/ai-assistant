package com.test.design.presentation.assistant

/**
 * Visual phase of an immersive assistant session.
 *
 * [Immersive] — full-screen overlay (default when open).
 * [Compact] — retained for host blur reset when dismissed; no longer shown as a corner bubble.
 */
enum class AssistantPresentation {
    Compact,
    Immersive,
}
