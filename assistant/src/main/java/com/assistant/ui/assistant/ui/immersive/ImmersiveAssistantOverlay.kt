package com.assistant.ui.assistant.ui.immersive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.ui.assistant.api.AssistantDebugInfo
import com.assistant.ui.assistant.api.AssistantDebugLog
import com.assistant.ui.assistant.api.AssistantDebugStripConfig
import com.assistant.ui.assistant.api.AssistantRuntime
import com.assistant.ui.assistant.api.AssistantSessionConfig
import com.assistant.ui.assistant.api.AssistantSessionEvent
import com.assistant.ui.assistant.api.AssistantSpeechInput
import com.assistant.ui.assistant.api.AssistantStartReason
import com.assistant.ui.assistant.api.AssistantContextGlyph
import com.assistant.ui.assistant.backend.toUiGesture
import com.assistant.ui.assistant.backend.toUiMood
import com.assistant.ui.assistant.backend.toUiSpeaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.assistant.ui.assistant.face.AssistantFaceConfig
import com.assistant.ui.assistant.face.AssistantFaceKind
import com.assistant.ui.assistant.face.AssistantMood
import com.assistant.ui.assistant.ui.chrome.AssistantPresentation
import com.assistant.ui.assistant.audio.AssistantSpeechEvent
import com.assistant.ui.assistant.ui.theme.AssistantTokens
import com.assistant.ui.assistant.ui.theme.LocalAssistantIdleMotion
import com.assistant.ui.assistant.dialogue.DialogueBeat
import com.assistant.ui.assistant.dialogue.DialogueSpeaker
import com.assistant.ui.assistant.ui.chrome.FaceGesture
import com.assistant.ui.assistant.dialogue.LiveInputText
import com.assistant.ui.assistant.ui.theme.LocalAssistantHighContrast
import com.assistant.ui.assistant.audio.assistantSpeechEvents
import com.assistant.ui.assistant.face.contextGlyphGaze
import com.assistant.ui.assistant.entry.notifyAssistantHotword
import com.assistant.ui.assistant.ui.theme.rememberAssistantBrandGlow
import com.assistant.ui.assistant.audio.rememberAssistantWakeFeedback

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
        AssistantDebugStripConfig.install(context)
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
    var lastError by remember { mutableStateOf<String?>(null) }

    fun summon() {
        if (visible) {
            // Already on stage — refresh listening mood only.
            // Do not wipe transcript: onShow re-summons and would erase user/assistant text
            // (and the greeting emitted by startSession).
            mood = AssistantMood.Listening
            gesture = FaceGesture.None
            mouthAmplitude = null
            showThumbs = false
            lastError = null
            gazeX = -0.42f
            gazeY = 0.05f
            return
        }
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
        lastError = null
        gazeX = -0.42f
        gazeY = 0.05f
        visible = true
    }

    LaunchedEffect(presentation, visible) {
        if (visible) {
            onPresentationChanged(presentation)
        }
    }

    ImmersiveHotwordBridge(
        onSummon = { summon() },
        onDismiss = {
            if (visible) visible = false
        },
    )

    // Forward device STT into the backend (UI stays dumb).
    // Wait for wake-word AudioRecord to fully release before binding SpeechRecognizer.
    LaunchedEffect(visible, session, enableLiveSpeech) {
        if (!visible || !enableLiveSpeech) return@LaunchedEffect
        delay(if (!awaitHotword) 700 else 150)
        if (!visible) return@LaunchedEffect
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
                    is AssistantSessionEvent.MoodChanged -> {
                        mood = event.mood.toUiMood()
                        if (mood == AssistantMood.Listening) lastError = null
                    }
                    is AssistantSessionEvent.Transcript -> {
                        transcript = event.text
                        speaker = event.speaker.toUiSpeaker()
                    }
                    is AssistantSessionEvent.Error -> {
                        lastError = event.message
                        transcript = event.message
                        speaker = DialogueSpeaker.System
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
                        if (awaitHotword) {
                            // Hotword mode collapses back to tap-to-summon.
                            if (visible) visible = false
                        } else {
                            // Dock / system-bar launches must keep the face on stage.
                            // Collapsing here left only a dim overlay until the next tap.
                            mood = AssistantMood.Listening
                            transcript = ""
                            speaker = DialogueSpeaker.System
                            mouthAmplitude = null
                            gesture = FaceGesture.None
                            showThumbs = false
                            contextGlyph = null
                            glyphGazeActive = false
                            lastError = null
                        }
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

    val backdropAlpha = remember { Animatable(if (!awaitHotword) 1f else 0f) }
    val faceRise = remember { Animatable(if (!awaitHotword) 0.12f else 1f) } // 1 = below screen, 0 = settled
    val faceScale = remember { Animatable(if (!awaitHotword) 0.96f else 0.88f) }
    val faceAlpha = remember { Animatable(if (!awaitHotword) 1f else 0f) }
    val transcriptAlpha = remember { Animatable(0f) }
    // Avoid calling onDismiss on first composition when awaitHotword keeps us hidden.
    var hasPresented by remember { mutableStateOf(!awaitHotword) }
    var immersiveEnteredSession by remember { mutableIntStateOf(if (!awaitHotword) 0 else -1) }
    // Two-phase paint: first frame = lite scrim + face (no Offscreen / idle loops).
    // Rich effects (glow, blur blooms, infinite motion) enable after first vsync.
    var richEffects by remember { mutableStateOf(false) }

    LaunchedEffect(visible, session) {
        if (!visible) {
            richEffects = false
            return@LaunchedEffect
        }
        // Wait one frame so the lite stage can draw before we add GPU-heavy layers.
        withFrameNanos { }
        AssistantUiLatency.mark("first compose frame")
        richEffects = true
        AssistantUiLatency.mark("rich effects enabled")
    }

    // Enter: dim stage + face rise. Exit: face slides down → dim hides.
    // Dock / system-bar (!awaitHotword) snaps presence onto the first frame so
    // time-to-visible stays under the 100ms target (no empty alpha=0 wait).
    LaunchedEffect(visible, session) {
        if (visible) {
            hasPresented = true
            if (immersiveEnteredSession != session) {
                immersiveEnteredSession = session
                transcriptAlpha.snapTo(0f)
                if (!awaitHotword) {
                    // Already snapped for first paint — finish the last ~12% rise.
                    if (backdropAlpha.value < 0.99f) backdropAlpha.snapTo(1f)
                    if (faceAlpha.value < 0.99f) faceAlpha.snapTo(1f)
                    launch {
                        faceScale.animateTo(
                            1f,
                            spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium),
                        )
                    }
                    faceRise.animateTo(
                        0f,
                        spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMedium),
                    )
                } else {
                    faceRise.snapTo(1f)
                    faceScale.snapTo(0.86f)
                    faceAlpha.snapTo(0f)
                    backdropAlpha.snapTo(0f)
                    launch {
                        backdropAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
                    }
                    launch {
                        faceAlpha.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
                    }
                    launch {
                        faceScale.animateTo(
                            1f,
                            spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium),
                        )
                    }
                    faceRise.animateTo(
                        0f,
                        spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMedium),
                    )
                }
                // Chime / haptic after first paint so AudioTrack init never blocks TTFF.
                launch {
                    withFrameNanos { }
                    wake.play()
                }
                delay(40)
                transcriptAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
            }
        } else if (hasPresented) {
            richEffects = false
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
    val debugStripVisible by AssistantDebugStripConfig.visible.collectAsStateWithLifecycle()

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        if (showOverlay) {
            // Blur / dark stage — independent of face chrome.
            // Tap empty backdrop to dismiss; face/transcript consume their own input
            // so a tap on the assistant no longer kills the session mid-listen.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = backdropAlpha.value.coerceIn(0f, 1f) }
                    .then(
                        if (visible) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { visible = false },
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                ImmersiveBackdrop(rich = richEffects)
                if (richEffects) {
                    ImmersiveBorderGlow(glowColor = brandGlow)
                }
            }

            if (!visible) {
                // Awaiting hotword — tap anywhere to summon.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                onRequestHotwordListen?.invoke()
                                summon()
                            },
                        ),
                )
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

            CompositionLocalProvider(LocalAssistantIdleMotion provides richEffects) {
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
                    faceSizeScale = 1.32f, // 1.10 baseline × 1.20
                )
            }

            if (richEffects && debugStripVisible) {
                ImmersiveAssistantDebugStrip(
                    debugInfo = host.debugInfo(),
                    errorMessage = lastError,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .graphicsLayer { alpha = backdropAlpha.value.coerceIn(0f, 1f) },
                )
            }
        }
    }
}

/** Polestar-style model / backend tags + live debug log + error (debug builds). */
@Composable
private fun ImmersiveAssistantDebugStrip(
    debugInfo: AssistantDebugInfo?,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    if (debugInfo == null) return
    val logLines by AssistantDebugLog.lines.collectAsStateWithLifecycle()
    val tagColor = Color(0xFF8AB4F8).copy(alpha = 0.85f)
    val logColor = Color(0xFFB0BEC5).copy(alpha = 0.9f)
    val errorColor = Color(0xFFFF8A80).copy(alpha = 0.95f)
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Color(0xCC0A0E14))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = debugInfo.backendLabel,
                color = tagColor,
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 0.1.sp,
            )
            Text(
                text = debugInfo.modelLabel,
                color = tagColor,
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 0.1.sp,
            )
        }
        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = "ERR: $errorMessage",
                color = errorColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        logLines.takeLast(8).forEach { line ->
            Text(
                text = line,
                color = if (line.contains(" E/") || line.contains("ERR")) errorColor else logColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Full-stage dim for the immersive assistant: light empty areas, stronger
 * bottom-center pool behind the face / transcript so chrome stays readable.
 *
 * @param rich when false, skips Offscreen compositing / DstIn masks so the first
 * frame is a single scrim + cheap gradient (target &lt; 100ms TTFF).
 */
@Composable
fun ImmersiveBackdrop(
    modifier: Modifier = Modifier,
    rich: Boolean = true,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Soft full-screen base — mostly transparent so maps / launcher show through.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AssistantTokens.Scrim),
        )
        if (!rich) {
            // Single-pass vertical darken — no Offscreen layer, no blend mask.
            // Keep the lite path soft to match the transparent stage.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color(0x1410141C),
                                0.55f to Color(0x440E1218),
                                1.0f to Color(0x99050608),
                            ),
                        ),
                    ),
            )
        } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0x1410141C),
                            0.45f to Color(0x28101820),
                            0.70f to Color(0x660E1218),
                            0.86f to Color(0x990A0C10),
                            1.0f to Color(0xCC050608),
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
        // Readable pool behind face + transcript — confined to the center band.
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
                        0.0f to Color(0xCC000000),
                        0.38f to Color(0x88000000),
                        0.70f to Color(0x33000000),
                        1.0f to Color.Transparent,
                    ),
                    center = Offset(w * 0.5f, h * 0.88f),
                    radius = minOf(w * 0.28f, h * 0.42f),
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
}

/**
 * Soft cockpit-edge glow: cool teal → ice-blue → steel spectrum bloom that
 * eases inward to full transparency. Colors drift slowly when idle motion is on.
 *
 * [windowInsets] inset the glow from system bars (e.g. bottom nav) so the rim
 * stays visible; pass [WindowInsets] with zeros for true edge-to-edge.
 */
@Composable
fun ImmersiveBorderGlow(
    modifier: Modifier = Modifier,
    glowColor: Color = Color(0xFF8AB4F8),
    windowInsets: WindowInsets = WindowInsets.systemBars,
) {
    val idleMotion = LocalAssistantIdleMotion.current
    val sweepAngle = remember { Animatable(0f) }
    LaunchedEffect(idleMotion) {
        if (!idleMotion) {
            sweepAngle.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            sweepAngle.snapTo(0f)
            sweepAngle.animateTo(
                targetValue = 360f,
                animationSpec = tween(durationMillis = 18_000, easing = LinearEasing),
            )
        }
    }
    val paint = remember { Paint().asFrameworkPaint().apply { isAntiAlias = true } }
    val shaderMatrix = remember { Matrix() }
    val angle = sweepAngle.value
    val spectrum = remember(glowColor) {
        fun tint(c: Color): Color = Color(
            red = c.red * 0.72f + glowColor.red * 0.28f,
            green = c.green * 0.72f + glowColor.green * 0.28f,
            blue = c.blue * 0.72f + glowColor.blue * 0.28f,
            alpha = 1f,
        )
        // Cool HUD spectrum — teal / cyan / ice / steel (no Siri rainbow).
        intArrayOf(
            tint(Color(0xFF0E6B78)).toArgb(), // deep teal
            tint(Color(0xFF1AA8C4)).toArgb(), // cyan
            tint(Color(0xFF6EC8FF)).toArgb(), // ice blue
            tint(Color(0xFF8AB4F8)).toArgb(), // soft panel blue
            tint(Color(0xFF4A90D9)).toArgb(), // steel blue
            tint(Color(0xFF3DDBC8)).toArgb(), // aqua
            tint(Color(0xFF2A7F9E)).toArgb(), // blue-teal
            tint(Color(0xFF0E6B78)).toArgb(), // close the loop
        )
    }
    val colorStops = remember {
        floatArrayOf(0.00f, 0.14f, 0.28f, 0.42f, 0.57f, 0.71f, 0.86f, 1.00f)
    }
    val fadeAlphas = remember {
        intArrayOf(
            0x99FFFFFF.toInt(),
            0x66FFFFFF.toInt(),
            0x33FFFFFF.toInt(),
            0x14FFFFFF.toInt(),
            0x05FFFFFF.toInt(),
            0x00FFFFFF,
        )
    }
    val fadeStops = remember {
        floatArrayOf(0.00f, 0.14f, 0.34f, 0.58f, 0.82f, 1.00f)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(windowInsets),
    ) {
        val w = size.width
        val h = size.height
        // Moderate bloom — softer than the prior neon rim.
        val thickness = 48.dp.toPx()
        val cx = w * 0.5f
        val cy = h * 0.5f

        fun drawEdge(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            ax0: Float,
            ay0: Float,
            ax1: Float,
            ay1: Float,
        ) {
            val sweep = SweepGradient(cx, cy, spectrum, colorStops)
            shaderMatrix.reset()
            // Android sweep starts at 3 o'clock; -90° puts the first stop at the top.
            shaderMatrix.postRotate(angle - 90f, cx, cy)
            sweep.setLocalMatrix(shaderMatrix)
            val fade = LinearGradient(
                ax0, ay0, ax1, ay1,
                fadeAlphas,
                fadeStops,
                Shader.TileMode.CLAMP,
            )
            paint.shader = ComposeShader(sweep, fade, PorterDuff.Mode.DST_IN)
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRect(left, top, right, bottom, paint)
            }
        }

        // Top — fades downward (inward).
        drawEdge(0f, 0f, w, thickness, 0f, 0f, 0f, thickness)
        // Bottom — fades upward (inward).
        drawEdge(0f, h - thickness, w, h, 0f, h, 0f, h - thickness)
        // Left — fades rightward (inward).
        drawEdge(0f, 0f, thickness, h, 0f, 0f, thickness, 0f)
        // Right — fades leftward (inward).
        drawEdge(w - thickness, 0f, w, h, w, 0f, w - thickness, 0f)
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
private val immersiveDismissHandlers = mutableListOf<() -> Unit>()

@Composable
private fun ImmersiveHotwordBridge(
    onSummon: () -> Unit,
    onDismiss: () -> Unit = {},
) {
    DisposableEffect(onSummon, onDismiss) {
        immersiveSummonHandlers += onSummon
        immersiveDismissHandlers += onDismiss
        onDispose {
            immersiveSummonHandlers -= onSummon
            immersiveDismissHandlers -= onDismiss
        }
    }
}

/** Called when hotword is detected; also notifies the legacy NOMI overlay handlers. */
fun notifyImmersiveAssistantHotword() {
    immersiveSummonHandlers.toList().forEach { it.invoke() }
    notifyAssistantHotword()
}

/** Release Compose STT / stage when the VoiceInteractionSession hides. */
fun notifyImmersiveAssistantDismiss() {
    immersiveDismissHandlers.toList().forEach { it.invoke() }
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
