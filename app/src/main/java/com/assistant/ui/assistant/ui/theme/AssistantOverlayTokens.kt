package com.assistant.ui.assistant.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Layout + motion tokens for the immersive overlay chrome.
 *
 * Kept small and overlay-scoped — face geometry and panel glass stay elsewhere.
 */
internal object AssistantOverlayTokens {
    // Card chrome
    val CardEdgePadding = 12.dp
    val CardSideWidthFraction = 0.36f
    val CardSideWidthMin = 280.dp
    val CardSideWidthMax = 380.dp
    val CardBottomWidthFraction = 0.72f
    val CardBottomWidthMin = 320.dp
    val CardBottomWidthMax = 720.dp
    val CardBottomHeightFraction = 0.38f
    val CardBottomHeightMin = 220.dp
    val CardBottomHeightMax = 360.dp
    val CardBottomFillWidth = 0.92f
    val CardSideFaceSize = 140.dp
    val CardBottomFaceSize = 120.dp
    val CardCorner = 28.dp

    // Bottom fullscreen chrome
    val BottomChromePaddingStart = 32.dp
    val BottomChromePaddingTop = 16.dp
    val BottomChromePaddingEnd = 32.dp
    val BottomChromeDockPadding = 4.dp
    // FaceStageDock Column bottom pad — keeps transcript near the map base.
    val DockContentPaddingBottom = 8.dp
    // Pull face toward transcript; canvas chin/glow otherwise reads as a large gap.
    val FaceTowardTranscriptNudge = 76.dp
    // ~32% of stage height (~15% smaller than prior 37.5%).
    val FaceStageHeightFraction = 0.32f
    val FaceSizeMin = 88.dp
    val FaceSizeMax = 480.dp
    val GlyphSizeFraction = 0.38f
    val GlyphSizeMin = 40.dp
    val GlyphSizeMax = 96.dp
    val FaceBelowTravelFraction = 0.38f
    val DockWidthFaceMul = 2.5f
    val DockWidthStackMul = 2.2f
    val DockWidthMin = 320.dp
    val EstimatedStackExtra = 100.dp

    // Session timing
    const val LiveSpeechDelayHotwordMs = 80L
    const val LiveSpeechDelayDockMs = 120L
    const val ThumbsHideMs = 4_000L
    const val GestureClearMs = 700L
    const val GlyphGazeClearMs = 800L
    const val TranscriptFadeInDelayMs = 80L

    // Entrance / exit (fullscreen path uses M3 expressive springs; these size snaps)
    const val CardBackdropMs = 280
    const val CardRevealMs = 380
    const val CardStartScale = 0.96f
    const val FaceStartScale = 0.88f
    const val FaceHiddenScale = 0.94f

    // Border glow
    const val BorderSweepMs = 18_000
    const val BorderBreathIdleHalfMs = 2_600
    const val BorderBreathSpeechHalfMs = 1_500
    const val BorderBreathIdlePeak = 1.65f
    const val BorderBreathSpeechPeak = 1.85f
    val BorderThickness = 52.dp
    const val SpeechEnergyKick = 0.22f

    // Linear bottom veil (transparent → blackish); stops are fractions of height from top.
    const val BackdropFadeStart = 0.55f
    const val BackdropMid = 0.78f
    const val BackdropBottomAlpha = 0.94f
    const val BackdropRichBloomAlpha = 0.10f

    // Sharp Gemini bottom-edge bar (scaled by ImmersiveGlowBreath.scale).
    val BottomEdgeCoreDp = 2.5.dp
    val BottomEdgeBloomDp = 14.dp
}

