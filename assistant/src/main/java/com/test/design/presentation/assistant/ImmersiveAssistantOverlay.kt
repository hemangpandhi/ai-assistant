package com.test.design.presentation.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.test.design.assistant.api.AssistantRuntime
import com.test.design.assistant.api.AssistantSessionConfig
import com.test.design.assistant.api.AssistantSessionEvent
import com.test.design.assistant.api.AssistantSpeechInput
import com.test.design.assistant.api.AssistantStartReason
import com.test.design.assistant.api.AssistantContextGlyph
import com.test.design.presentation.assistant.backend.toUiGesture
import com.test.design.presentation.assistant.backend.toUiMood
import com.test.design.presentation.assistant.backend.toUiSpeaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Immersive assistant: opens directly as a full-screen translucent stage
 * (bottom-band face + transcript).
 *
 * Features: gaze-to-speaker, STT streaming, TTS lip-sync, drive-context prompts,
 * cluster hand-off, OEM brand tint, high-contrast eyes, wake haptic/chime, nod/shake.
 */
@Composable
fun ImmersiveAssistantOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialMood: AssistantMood = AssistantMood.Idle,
    awaitHotword: Boolean = true,
    onRequestHotwordListen: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER")
    script: List<DialogueBeat> = ImmersiveDialogueScript,
    enableLiveSpeech: Boolean = true,
    enableTts: Boolean = true,
    @Suppress("UNUSED_PARAMETER")
    onFeedback: (Boolean) -> Unit = {},
    onPresentationChanged: (AssistantPresentation) -> Unit = {},
    @Suppress("UNUSED_PARAMETER")
    onBubbleBoundsInRoot: ((left: Int, top: Int, right: Int, bottom: Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val host = AssistantRuntime.requireHost()
    val backend = AssistantRuntime.requireBackend()
    val wake = rememberAssistantWakeFeedback()
    val highContrast = LocalAssistantHighContrast.current || host.highContrastEyes()
    val faceKind by AssistantFaceConfig.kind.collectAsStateWithLifecycle()
    val brandAccent = MaterialTheme.colorScheme.primary

    LaunchedEffect(Unit) {
        AssistantFaceConfig.install(context)
    }

    var visible by remember { mutableStateOf(!awaitHotword) }
    var session by remember { mutableIntStateOf(if (!awaitHotword) 1 else 0) }
    var presentation by remember { mutableStateOf(AssistantPresentation.Immersive) }
    var mood by remember {
        mutableStateOf(if (!awaitHotword) AssistantMood.Listening else initialMood)
    }
    var transcript by remember { mutableStateOf("") }
    var speaker by remember { mutableStateOf(DialogueSpeaker.System) }
    var gazeX by remember { mutableStateOf<Float?>(-0.42f) }
    var gazeY by remember { mutableStateOf<Float?>(0.05f) }
    var mouthAmplitude by remember { mutableStateOf<Float?>(null) }
    var gesture by remember { mutableStateOf(FaceGesture.None) }
    var showThumbs by remember { mutableStateOf(false) }
    var thumbsTick by remember { mutableIntStateOf(0) }
    var contextGlyph by remember { mutableStateOf<AssistantContextGlyph?>(null) }
    var glyphGazeActive by remember { mutableStateOf(false) }

    fun summon() {
        session += 1
        presentation = AssistantPresentation.Immersive
        mood = AssistantMood.Listening
        transcript = ""
        speaker = DialogueSpeaker.System
        gesture = FaceGesture.None
        mouthAmplitude = null
        showThumbs = false
        contextGlyph = null
        glyphGazeActive = false
        gazeX = -0.42f
        gazeY = 0.05f
        visible = true
    }

    LaunchedEffect(presentation, visible) {
        if (visible) {
            onPresentationChanged(presentation)
        }
    }

    ImmersiveHotwordBridge(onSummon = { summon() })

    // Forward device STT into the backend (UI stays dumb).
    LaunchedEffect(visible, session, enableLiveSpeech) {
        if (!visible || !enableLiveSpeech) return@LaunchedEffect
        assistantSpeechEvents(context).collectLatest { event ->
            if (!visible) return@collectLatest
            when (event) {
                AssistantSpeechEvent.Hotword -> {
                    if (!visible) summon()
                }
                is AssistantSpeechEvent.Partial ->
                    backend.onSpeechInput(AssistantSpeechInput.Partial(event.text))
                is AssistantSpeechEvent.Final ->
                    backend.onSpeechInput(AssistantSpeechInput.Final(event.text))
                is AssistantSpeechEvent.Rms ->
                    backend.onSpeechInput(AssistantSpeechInput.Rms(event.normalized))
            }
        }
    }

    // Collect backend events, then start session (avoids dropping early emits).
    LaunchedEffect(visible, session) {
        if (!visible) {
            backend.stopSession()
            return@LaunchedEffect
        }
        launch {
            backend.events.collect { event ->
                when (event) {
                    is AssistantSessionEvent.MoodChanged -> mood = event.mood.toUiMood()
                    is AssistantSessionEvent.Transcript -> {
                        transcript = event.text
                        speaker = event.speaker.toUiSpeaker()
                    }
                    is AssistantSessionEvent.Gaze -> {
                        gazeX = event.x
                        gazeY = event.y
                    }
                    is AssistantSessionEvent.GestureChanged ->
                        gesture = event.gesture.toUiGesture()
                    is AssistantSessionEvent.MouthAmplitude -> mouthAmplitude = event.value
                    is AssistantSessionEvent.ThumbsVisible -> {
                        showThumbs = event.visible
                        if (event.visible) thumbsTick += 1
                    }
                    is AssistantSessionEvent.ContextGlyph -> {
                        contextGlyph = event.glyph
                        glyphGazeActive = event.glyph != null
                    }
                    is AssistantSessionEvent.PresentationHint -> Unit
                    AssistantSessionEvent.RequestClusterHandOff -> host.openClusterHandOff()
                    AssistantSessionEvent.SessionComplete -> {
                        if (visible) visible = false
                    }
                }
            }
        }
        backend.startSession(
            reason = if (awaitHotword) AssistantStartReason.Hotword else AssistantStartReason.Dock,
            cabin = host.cabinContext(),
            config = AssistantSessionConfig(
                enableTts = enableTts,
                enableLiveSpeech = enableLiveSpeech,
            ),
        )
    }

    LaunchedEffect(showThumbs, thumbsTick, mood) {
        if (!showThumbs) return@LaunchedEffect
        if (mood == AssistantMood.Listening) {
            showThumbs = false
            return@LaunchedEffect
        }
        delay(4_000)
        showThumbs = false
    }

    LaunchedEffect(gesture) {
        if (gesture == FaceGesture.Nod || gesture == FaceGesture.Shake) {
            delay(700)
            if (gesture == FaceGesture.Nod || gesture == FaceGesture.Shake) {
                gesture = FaceGesture.None
            }
        }
    }

    LaunchedEffect(contextGlyph, glyphGazeActive) {
        if (!glyphGazeActive || contextGlyph == null) return@LaunchedEffect
        delay(800)
        glyphGazeActive = false
    }

    val backdropAlpha = remember { Animatable(0f) }
    val faceRise = remember { Animatable(1f) } // 1 = below screen, 0 = settled
    val faceScale = remember { Animatable(0.88f) }
    val faceAlpha = remember { Animatable(0f) }
    val transcriptAlpha = remember { Animatable(0f) }
    // Avoid calling onDismiss on first composition when awaitHotword keeps us hidden.
    var hasPresented by remember { mutableStateOf(false) }
    var immersiveEnteredSession by remember { mutableIntStateOf(-1) }

    // Enter: blur first → face slides up. Exit: face slides down → blur hides.
    LaunchedEffect(visible, session) {
        if (visible) {
            hasPresented = true
            if (immersiveEnteredSession != session) {
                immersiveEnteredSession = session
                faceRise.snapTo(1f)
                faceScale.snapTo(0.86f)
                faceAlpha.snapTo(0f)
                transcriptAlpha.snapTo(0f)
                backdropAlpha.snapTo(0f)

                backdropAlpha.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
                delay(60)
                wake.play() // soft chime as the face starts sliding up
                launch {
                    faceAlpha.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
                }
                launch {
                    faceScale.animateTo(
                        1f,
                        spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
                    )
                }
                faceRise.animateTo(
                    0f,
                    spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
                )
                delay(80)
                transcriptAlpha.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
            }
        } else if (hasPresented) {
            wake.playDismiss() // soft chime as the face starts sliding down
            transcriptAlpha.animateTo(0f, tween(160))
            launch {
                faceAlpha.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
            }
            faceRise.animateTo(
                1f,
                tween(380, easing = FastOutSlowInEasing),
            )
            delay(40)
            backdropAlpha.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
            faceRise.snapTo(1f)
            faceScale.snapTo(0.88f)
            faceAlpha.snapTo(0f)
            transcriptAlpha.snapTo(0f)
            immersiveEnteredSession = -1
            onPresentationChanged(AssistantPresentation.Compact)
            // Collapse host (clears Modifier.blur) after face + blur exit.
            onDismiss()
        }
    }

    val brandGlow = rememberAssistantBrandGlow(mood, brandAccent).copy(alpha = 0.65f)
    val showOverlay = visible ||
        backdropAlpha.value > 0.02f ||
        faceAlpha.value > 0.02f

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (!visible) {
                    // Hidden / awaiting hotword — tap anywhere to summon.
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onRequestHotwordListen?.invoke()
                            summon()
                        },
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { visible = false },
                    )
                },
            ),
    ) {
        if (showOverlay) {
            // Blur / dark stage — independent of face chrome.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = backdropAlpha.value.coerceIn(0f, 1f) },
            ) {
                ImmersiveBackdrop()
                ImmersiveBorderGlow(glowColor = brandGlow)
            }

            val glyphGaze = contextGlyphGaze()
            val effectiveGazeX =
                if (faceKind == AssistantFaceKind.FusionEyes && glyphGazeActive && contextGlyph != null) {
                    glyphGaze.first
                } else {
                    gazeX
                }
            val effectiveGazeY =
                if (faceKind == AssistantFaceKind.FusionEyes && glyphGazeActive && contextGlyph != null) {
                    glyphGaze.second
                } else {
                    gazeY
                }

            ImmersiveAssistantBottomChrome(
                mood = mood,
                faceKind = faceKind,
                transcript = transcript,
                speaker = speaker,
                gazeX = effectiveGazeX,
                gazeY = effectiveGazeY,
                mouthAmplitude = mouthAmplitude,
                brandGlow = brandGlow,
                highContrast = highContrast,
                gesture = gesture,
                contextGlyph = contextGlyph,
                showFace = faceKind != AssistantFaceKind.None,
                faceRise = faceRise.value,
                faceScale = faceScale.value,
                faceAlpha = faceAlpha.value,
                transcriptAlpha = transcriptAlpha.value,
                faceSizeScale = 1.10f,
            )
        }
    }
}

/**
 * Center-band stage: darken/blur only the middle ~40% width (soft 30–40–30),
 * with the same gradual falloff used vertically — side gutters stay clear.
 */
@Composable
fun ImmersiveBackdrop(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0x00000000),
                            0.52f to Color(0x00000000),
                            0.70f to Color(0x33101820),
                            0.86f to Color(0x6610141C),
                            1.0f to Color(0x990A0C10),
                        ),
                    ),
                )
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colorStops = AssistantCenterBandHorizontalStops,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )
        // Light bottom-center pool — confined to the center band.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val w = size.width
            val h = size.height
            drawRect(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0x99000000),
                        0.42f to Color(0x55000000),
                        0.78f to Color(0x22000000),
                        1.0f to Color.Transparent,
                    ),
                    center = Offset(w * 0.5f, h * 0.88f),
                    radius = minOf(w * 0.22f, h * 0.36f),
                ),
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = AssistantCenterBandHorizontalStops,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/**
 * Subtle bluish bottom-border glow as a true 2D gradient:
 * horizontal soft center band (30–40–30), soft fade upward from the edge.
 */
@Composable
fun ImmersiveBorderGlow(
    modifier: Modifier = Modifier,
    glowColor: Color = Color(0xFF8AB4F8),
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Offscreen layer so DstIn can mask the horizontal wash into a vertical fade.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val w = size.width
        val h = size.height
        val blue = glowColor
        val bloomH = 64.dp.toPx()
        val top = h - bloomH

        // Horizontal wash: transparent gutters → center 40% glow.
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.22f to Color.Transparent,
                    0.30f to blue.copy(alpha = 0.10f),
                    0.36f to blue.copy(alpha = 0.28f),
                    0.50f to blue.copy(alpha = 0.40f),
                    0.64f to blue.copy(alpha = 0.28f),
                    0.70f to blue.copy(alpha = 0.10f),
                    0.78f to Color.Transparent,
                    1.00f to Color.Transparent,
                ),
            ),
            topLeft = Offset(0f, top),
            size = Size(w, bloomH),
        )

        // Vertical mask: invisible at the top of the bloom → strongest on the bottom edge.
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.40f to Color.White.copy(alpha = 0.18f),
                    0.72f to Color.White.copy(alpha = 0.55f),
                    1.00f to Color.White,
                ),
                startY = top,
                endY = h,
            ),
            topLeft = Offset(0f, top),
            size = Size(w, bloomH),
            blendMode = BlendMode.DstIn,
        )
    }
}

@Composable
fun ImmersiveTranscript(
    text: String,
    speaker: DialogueSpeaker,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    // Crossfade only when the speaker role changes; word motion lives in LiveInputText.
    AnimatedContent(
        targetState = speaker,
        transitionSpec = {
            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
        },
        label = "immersive_transcript_speaker",
        modifier = modifier,
    ) { who ->
        val bodyColor = when (who) {
            DialogueSpeaker.User -> Color(0xFFD2E3FC)
            DialogueSpeaker.Assistant -> Color(0xFFF8F9FA)
            DialogueSpeaker.System -> Color(0xFFBDC1C6)
        }
        LiveInputText(
            text = text,
            color = bodyColor,
            live = live && who == DialogueSpeaker.User,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
    }
}

private val immersiveSummonHandlers = mutableListOf<() -> Unit>()

@Composable
private fun ImmersiveHotwordBridge(onSummon: () -> Unit) {
    DisposableEffect(onSummon) {
        immersiveSummonHandlers += onSummon
        onDispose { immersiveSummonHandlers -= onSummon }
    }
}

/** Called when hotword is detected; also notifies the legacy NOMI overlay handlers. */
fun notifyImmersiveAssistantHotword() {
    immersiveSummonHandlers.toList().forEach { it.invoke() }
    notifyAssistantHotword()
}

/**
 * Full emotion walk — listening → think → read → search → speak,
 * happy / sad / excited / bored / drowsy / tired.
 */
val ImmersiveDialogueScript: List<DialogueBeat> = listOf(
    DialogueBeat(
        speaker = DialogueSpeaker.System,
        text = "Listening…",
        mood = AssistantMood.Listening,
        holdMs = 1600,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.User,
        text = "Hey — find a coffee stop nearby",
        mood = AssistantMood.Listening,
        holdMs = 2600,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "On it — thinking through nearby options…",
        mood = AssistantMood.Thinking,
        holdMs = 2400,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Searching cafés along your route…",
        mood = AssistantMood.Searching,
        holdMs = 2600,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Reading reviews for Bluebird Roasters…",
        mood = AssistantMood.Reading,
        holdMs = 2400,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Bluebird Roasters is 6 minutes away. Want that stop?",
        mood = AssistantMood.Speaking,
        holdMs = 3000,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.User,
        text = "Yes!",
        mood = AssistantMood.Listening,
        holdMs = 1400,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Done — stop added. You're going to love their cold brew!",
        mood = AssistantMood.Happy,
        holdMs = 2800,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Oh wait — they close in ten minutes. Sorry about that.",
        mood = AssistantMood.Sad,
        holdMs = 2800,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Harbor Light is open late — I've got a better option!",
        mood = AssistantMood.Excited,
        holdMs = 2800,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.User,
        text = "Will it snow tonight?",
        mood = AssistantMood.Listening,
        holdMs = 2200,
        contextGlyph = AssistantContextGlyph.WeatherSnow,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Thinking through the overnight forecast…",
        mood = AssistantMood.Thinking,
        holdMs = 2000,
        contextGlyph = AssistantContextGlyph.WeatherCloudy,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Reading the radar along your route…",
        mood = AssistantMood.Reading,
        holdMs = 2200,
        contextGlyph = AssistantContextGlyph.WeatherCloudy,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Light snow after midnight — roads should stay clear until then.",
        mood = AssistantMood.Speaking,
        holdMs = 3200,
        contextGlyph = AssistantContextGlyph.WeatherSnow,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.User,
        text = "And will it rain tomorrow?",
        mood = AssistantMood.Listening,
        holdMs = 2000,
        contextGlyph = AssistantContextGlyph.WeatherLightRain,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "A soft drizzle around midday — nothing heavy.",
        mood = AssistantMood.Speaking,
        holdMs = 2800,
        contextGlyph = AssistantContextGlyph.WeatherLightRain,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.User,
        text = "Make the cabin a bit cooler",
        mood = AssistantMood.Listening,
        holdMs = 2000,
        contextGlyph = AssistantContextGlyph.ClimateThermostat,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Dropping to 20° — AC on, gentle airflow.",
        mood = AssistantMood.Speaking,
        holdMs = 2800,
        contextGlyph = AssistantContextGlyph.ClimateAc,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.User,
        text = "And clear the windshield",
        mood = AssistantMood.Listening,
        holdMs = 1800,
        contextGlyph = AssistantContextGlyph.ClimateDefrost,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Front defrost is on — glass should clear in a minute.",
        mood = AssistantMood.Speaking,
        holdMs = 2800,
        contextGlyph = AssistantContextGlyph.ClimateDefrost,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.System,
        text = "Quiet stretch ahead…",
        mood = AssistantMood.Bored,
        holdMs = 2000,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.User,
        text = "I'm feeling a bit tired",
        mood = AssistantMood.Listening,
        holdMs = 2000,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.System,
        text = "Late night mode",
        mood = AssistantMood.Drowsy,
        holdMs = 2000,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "I'll keep watch while you drive. Rest when you can.",
        mood = AssistantMood.Tired,
        holdMs = 2800,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Route updated. Safe travels.",
        mood = AssistantMood.Speaking,
        holdMs = 2400,
    ),
)
