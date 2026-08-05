package com.assistant.ui.assistant.ui.theme

import androidx.compose.ui.graphics.Color
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
    val CardSideFaceSize = 112.dp
    val CardBottomFaceSize = 96.dp
    val CardCorner = 28.dp

    // Bottom fullscreen chrome
    val BottomChromePaddingStart = 32.dp
    val BottomChromePaddingTop = 16.dp
    val BottomChromePaddingEnd = 32.dp
    val BottomChromeDockPadding = 4.dp
    // Island capsule content pad (status sits outside below).
    val DockContentPaddingBottom = 8.dp
    val FaceTranscriptGap = 6.dp
    val GlyphSizeFraction = 0.38f
    val GlyphSizeMin = 40.dp
    val GlyphSizeMax = 96.dp
    val FaceBelowTravelFraction = 0.38f

    // Dynamic Island — idle pill wider than Figma 154×103 for face breathing room.
    val IslandCompactWidth = 192.dp
    val IslandCompactHeight = 103.dp
    val IslandCornerRadius = 51.5.dp
    val IslandEyesTop = 33.dp
    val IslandListeningWidth = 192.dp
    val IslandListeningHeight = 103.dp
    /** Expanded pill grows with text up to this fraction of stage width, then transcript autoscrolls. */
    val IslandExpandedWidthFraction = 0.60f
    val IslandExpandedWidthMin = 192.dp
    /** Expanded island — a touch taller than idle for breathing room; still widens for text. */
    val IslandExpandedHeight = 120.dp
    val IslandExpandedHeightWithText = 120.dp
    val IslandBottomInset = 28.dp
    val IslandFrameStroke = 2.5.dp
    val IslandStatusGap = 10.dp
    val IslandContentPadH = 16.dp
    /** Inset from the pill’s rounded ends so face/text aren’t flush to the curve. */
    val IslandExpandedPadStart = 40.dp
    val IslandExpandedPadEnd = 36.dp
    /** Gap between left face slot and transcript in the expanded row. */
    val IslandFaceTextGap = 20.dp
    val IslandContentPadV = 0.dp
    val IslandCompactPadH = 0.dp
    val IslandCompactPadV = 0.dp
    val IslandFaceCompact = 88.dp
    val IslandFaceListening = 88.dp
    val IslandFaceExpanded = 72.dp
    /** Idle eye glyph outer size (full width × height). */
    val IslandEyeWidth = 20.dp
    val IslandEyeHeight = 33.dp
    /**
     * Half-distance between eye centers.
     * Figma: eye1 left 32, eye2 left 91 → center gap 59 → half 29.5.
     */
    val IslandEyeHalfGap = 29.5.dp
    val IslandListeningGlowBlur = 18.dp
    /** Near-black capsule fill. */
    val IslandFill = Color(0xFF121418)
    /** Dark gray outer frame (idle / speaking / default). */
    val IslandFrame = Color(0xFF3A3F46)
    /** Listening cyan-tinted frame. */
    val IslandListeningFrame = Color(0xFF40C4FF)
    /** Soft listening outer glow. */
    val IslandListeningGlow = Color(0x6640C4FF)
    val IslandStatus = Color(0xFF9CA3AF)
    val IslandListeningStatus = Color(0xFF40C4FF)

    // Legacy face-stage sizing retained for card chrome / weather sink callers.
    const val FaceTowardTranscriptNudgeFraction = 0.13f
    val FaceStageHeightFraction = 0.256f
    val FaceSizeMin = 70.dp
    val FaceSizeMax = 384.dp
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

    // Border glow — keep the rim atmospheric, not a bright frame.
    const val BorderSweepMs = 18_000
    const val BorderBreathIdleHalfMs = 2_600
    const val BorderBreathSpeechHalfMs = 1_500
    const val BorderBreathIdlePeak = 1.65f
    const val BorderBreathSpeechPeak = 1.85f
    val BorderThickness = 40.dp
    const val SpeechEnergyKick = 0.22f
    /** Extra opacity scale on the outer rim only (dock / bottom bar unchanged). */
    const val BorderRimAlpha = 0.55f

    // Linear bottom veil (transparent → blackish); stops are fractions of height from top.
    const val BackdropFadeStart = 0.55f
    const val BackdropMid = 0.78f
    const val BackdropBottomAlpha = 0.94f
    const val BackdropRichBloomAlpha = 0.10f

    // Sharp Gemini bottom-edge bar (scaled by ImmersiveGlowBreath.scale).
    val BottomEdgeCoreDp = 2.5.dp
    val BottomEdgeBloomDp = 14.dp
}

