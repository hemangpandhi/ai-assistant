package com.test.design.presentation.assistant.gallery

/**
 * Catalog of voice-assistant chrome styles (opaque gallery stage).
 */
enum class AssistantUiStyle(
    val title: String,
    val blurb: String,
) {
    VoicePlate(
        title = "Voice plate",
        blurb = "Bottom plate with face + prompt",
    ),
    FaceOnly(
        title = "Face only",
        blurb = "No plate — floating persona",
    ),
    WaveformCenter(
        title = "Waveform",
        blurb = "Centered Siri-style ribbons",
    ),
    OrbGlow(
        title = "Orb",
        blurb = "Floating orb with ambient bloom",
    ),
    CapsuleFace(
        title = "Capsule face",
        blurb = "Wide geometric glyph capsule",
    ),
    SideRail(
        title = "Side rail",
        blurb = "Trailing translucent rail",
    ),
    EqualizerBars(
        title = "Equalizer",
        blurb = "Vertical bar equalizer",
    ),
    ListeningRings(
        title = "Listening rings",
        blurb = "Concentric attention rings",
    ),
    CornerBubble(
        title = "Corner bubble",
        blurb = "Compact bottom-end chip",
    ),
    WaveFaceCombo(
        title = "Wave + face",
        blurb = "Persona over waveform",
    ),
    AmbientPill(
        title = "Ambient pill",
        blurb = "Minimal floating pill HUD",
    ),
    ImmersiveEyes(
        title = "Immersive eyes",
        blurb = "Full-screen gradient · eyes · transcript",
    ),
    ImmersiveGlow(
        title = "Immersive glow",
        blurb = "Immersive face · EPORO purple glow rings",
    ),
    ImmersiveHybrid(
        title = "Immersive hybrid",
        blurb = "Smooth pale↔purple morph · glow on excited/happy/search/think",
    ),
    DroidFace(
        title = "Droid face",
        blurb = "Single Bugdroid · morphs through all glyphs",
    ),
    EporoFace(
        title = "EPORO",
        blurb = "SemiCircle shell · Bézier visor · glow eyes",
    ),
    FusionFace(
        title = "Fusion",
        blurb = "EPORO glow eyes · Immersive mouth & moods",
    ),
    FusionGlowFace(
        title = "Fusion glow",
        blurb = "Fusion shell · Immersive-glow capsule eyes",
    ),
    FusionEyesFace(
        title = "Fusion eyes",
        blurb = "Fusion shell · Immersive pale capsule eyes",
    ),
    WeatherSink(
        title = "Weather sink",
        blurb = "Eye↔weather icon swap · thinking cloud · Fusion eyes",
    ),
}
