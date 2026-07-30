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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.TransformOrigin
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
import com.assistant.ui.assistant.mvi.AssistantStageStore
import com.assistant.ui.assistant.mvi.StageIntent
import com.assistant.ui.assistant.mvi.StageState
import com.assistant.ui.assistant.mvi.StageEffect
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
    /**
     * When true (and [awaitHotword] is false), present once on first frame as an
     * icon-style emerge. Voice sessions pass false and wait for [notifyImmersiveAssistantSummon].
     */
    autoPresent: Boolean = !awaitHotword,
    onRequestHotwordListen: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER")
    script: List<DialogueBeat> = ImmersiveDialogueScript,
    // Live Compose STT — OFF by default. Production VoiceInteractionSession sets this
    // false and uses VehicleAgentAssistantBackend + IAudioManager as the sole owner.
    // Enabling this starts a second SpeechRecognizer and causes ERROR_RECOGNIZER_BUSY.
    enableLiveSpeech: Boolean = false,
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

    val stageStore = remember {
        AssistantStageStore(
            StageState(
                // Always start hidden so enter animation runs when the session/icon actually shows.
                visible = false,
                session = 0,
                mood = if (!awaitHotword) AssistantMood.Listening else initialMood,
            )
        )
    }
    var summonOrigin by remember { mutableStateOf(ImmersiveSummonOrigin.Icon) }
    val stage = stageStore.state
    val visible = stage.visible
    val session = stage.session
    val presentation = stage.presentation
    val mood = stage.mood
    val transcript = stage.transcript
    val speaker = stage.speaker
    val gazeX = stage.gazeX
    val gazeY = stage.gazeY
    val mouthAmplitude = stage.mouthAmplitude
    val gesture = stage.gesture
    val showThumbs = stage.showThumbs
    val thumbsTick = stage.thumbsTick
    val contextGlyph = stage.contextGlyph
    val glyphGazeActive = stage.glyphGazeActive
    val faceCues = stage.faceCues
    val lastError = stage.lastError

    fun summon(origin: ImmersiveSummonOrigin) {
        summonOrigin = origin
        if (visible) {
            // Already on stage — refresh listening mood only; keep transcript.
            stageStore.update {
                it.copy(
                    mood = AssistantMood.Listening,
                    gesture = FaceGesture.None,
                    mouthAmplitude = null,
                    showThumbs = false,
                    lastError = null,
                    gazeX = -0.42f,
                    gazeY = 0.05f,
                )
            }
            return
        }
        stageStore.dispatch(StageIntent.Summon)
        stageStore.update {
            it.copy(transcript = "", presentation = AssistantPresentation.Immersive)
        }
    }

    LaunchedEffect(autoPresent) {
        if (autoPresent) {
            delay(1)
            if (!visible) summon(ImmersiveSummonOrigin.Icon)
        }
    }

    LaunchedEffect(presentation, visible) {
        if (visible) {
            onPresentationChanged(presentation)
        }
    }

    ImmersiveSummonBridge(
        onSummon = { origin -> summon(origin) },
        onDismiss = {
            if (visible) stageStore.dispatch(StageIntent.Dismiss)
        },
    )

    LaunchedEffect(Unit) {
        stageStore.effects.collect { effect ->
            when (effect) {
                StageEffect.RequestListen -> Unit // mic owned by agent backend in production
                StageEffect.ClusterHandOff -> host.openClusterHandOff()
                StageEffect.FinishSession -> Unit
                StageEffect.StopSession -> backend.stopSession()
            }
        }
    }

    // Forward device STT into the backend (UI stays dumb).
    // Agent production path uses VehicleAgentAssistantBackend STT; this is a
    // lightweight secondary stream — keep the wait short so it never blocks feel.
    LaunchedEffect(visible, session, enableLiveSpeech) {
        if (!visible || !enableLiveSpeech) return@LaunchedEffect
        delay(if (!awaitHotword) 120 else 80)
        if (!visible) return@LaunchedEffect
        assistantSpeechEvents(context).collectLatest { event ->
            if (!visible) return@collectLatest
            when (event) {
                AssistantSpeechEvent.Hotword -> {
                    if (!visible) summon(ImmersiveSummonOrigin.Hotword)
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
    // Do NOT stopSession on the initial hidden composition — that cancels pre-arm STT.
    var hasPresentedSession by remember { mutableStateOf(false) }
    LaunchedEffect(visible, session) {
        if (!visible) {
            if (hasPresentedSession) {
                backend.stopSession()
            }
            return@LaunchedEffect
        }
        hasPresentedSession = true
        launch {
            backend.events.collect { event ->
                if (event is AssistantSessionEvent.SessionComplete && awaitHotword) {
                    if (stageStore.state.visible) stageStore.dispatch(StageIntent.Dismiss)
                } else if (event is AssistantSessionEvent.Error) {
                    stageStore.dispatch(StageIntent.BackendEvent(event))
                    stageStore.update {
                        it.copy(transcript = event.message, speaker = DialogueSpeaker.System)
                    }
                } else {
                    stageStore.dispatch(StageIntent.BackendEvent(event))
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
            stageStore.update { it.copy(showThumbs = false) }
            return@LaunchedEffect
        }
        delay(4_000)
        stageStore.update { it.copy(showThumbs = false) }
    }

    LaunchedEffect(gesture) {
        if (gesture == FaceGesture.Nod || gesture == FaceGesture.Shake) {
            delay(700)
            if (stageStore.state.gesture == FaceGesture.Nod || stageStore.state.gesture == FaceGesture.Shake) {
                stageStore.update { it.copy(gesture = FaceGesture.None) }
            }
        }
    }

    LaunchedEffect(contextGlyph, glyphGazeActive) {
        if (!glyphGazeActive || contextGlyph == null) return@LaunchedEffect
        delay(800)
        stageStore.update { it.copy(glyphGazeActive = false) }
    }

    val backdropAlpha = remember { Animatable(0f) }
    val faceRise = remember { Animatable(0.28f) } // 1 = below screen, 0 = settled
    val faceScale = remember { Animatable(0.94f) }
    val faceAlpha = remember { Animatable(1f) }
    val transcriptAlpha = remember { Animatable(0f) }
    /** 0 = hidden, 1 = fully presented (drives icon emerge / hotword wipe). */
    val overlayReveal = remember { Animatable(0f) }
    // Avoid calling onDismiss on first composition when awaitHotword keeps us hidden.
    var hasPresented by remember { mutableStateOf(false) }
    var immersiveEnteredSession by remember { mutableIntStateOf(-1) }
    // Two-phase paint: first frame = lite scrim (no Offscreen / idle loops).
    // Rich effects (glow bloom GPU, blur blooms, infinite motion) enable after first vsync.
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

    // Enter by summon origin; exit reverses the same reveal.
    // Keys: visible + session only — do not restart when origin is assigned in the same summon.
    LaunchedEffect(visible, session) {
        if (visible) {
            hasPresented = true
            if (immersiveEnteredSession != session) {
                immersiveEnteredSession = session
                val origin = summonOrigin
                transcriptAlpha.snapTo(0f)
                overlayReveal.snapTo(0f)
                faceAlpha.snapTo(1f)
                // Start chime on first frame — do not wait for enter anim (was ~420–560ms late
                // and competed with STT / media duck on USAGE_MEDIA).
                launch {
                    withFrameNanos { }
                    wake.play()
                }
                when (origin) {
                    ImmersiveSummonOrigin.Icon -> {
                        // Emerge from assist-icon / bottom-end — whole stage scales up.
                        backdropAlpha.snapTo(1f)
                        faceRise.snapTo(0.18f)
                        faceScale.snapTo(1f)
                        try {
                            launch {
                                faceRise.animateTo(
                                    0f,
                                    spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMedium),
                                )
                            }
                            overlayReveal.animateTo(
                                1f,
                                tween(420, easing = FastOutSlowInEasing),
                            )
                        } finally {
                            if (overlayReveal.value < 0.99f) overlayReveal.snapTo(1f)
                        }
                    }
                    ImmersiveSummonOrigin.Hotword -> {
                        // Bottom → top wipe; border completes as the wipe reaches the top.
                        backdropAlpha.snapTo(1f)
                        faceRise.snapTo(0.35f)
                        faceScale.snapTo(0.96f)
                        try {
                            launch {
                                faceScale.animateTo(
                                    1f,
                                    spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium),
                                )
                            }
                            launch {
                                faceRise.animateTo(
                                    0f,
                                    spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMedium),
                                )
                            }
                            overlayReveal.animateTo(
                                1f,
                                tween(560, easing = FastOutSlowInEasing),
                            )
                        } finally {
                            if (overlayReveal.value < 0.99f) overlayReveal.snapTo(1f)
                        }
                    }
                }
                delay(100)
                transcriptAlpha.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
            }
        } else if (hasPresented) {
            richEffects = false
            wake.playDismiss()
            transcriptAlpha.animateTo(0f, tween(140))
            launch {
                faceRise.animateTo(0.35f, tween(300, easing = FastOutSlowInEasing))
            }
            overlayReveal.animateTo(0f, tween(340, easing = FastOutSlowInEasing))
            backdropAlpha.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            faceRise.snapTo(0.28f)
            faceScale.snapTo(0.94f)
            faceAlpha.snapTo(1f)
            transcriptAlpha.snapTo(0f)
            overlayReveal.snapTo(0f)
            immersiveEnteredSession = -1
            onPresentationChanged(AssistantPresentation.Compact)
            onDismiss()
        }
    }

    // User / assistant speech must be visible even mid-enter animation.
    LaunchedEffect(transcript, speaker) {
        if (transcript.isNotBlank() &&
            (speaker == DialogueSpeaker.User || speaker == DialogueSpeaker.Assistant)
        ) {
            transcriptAlpha.snapTo(1f)
        }
    }

    val brandGlow = rememberAssistantBrandGlow(mood, brandAccent).copy(alpha = 0.65f)
    val reveal = overlayReveal.value.coerceIn(0f, 1f)
    val glowReveal = when (summonOrigin) {
        // Border blooms as the icon expand finishes.
        ImmersiveSummonOrigin.Icon -> ((reveal - 0.45f) / 0.55f).coerceIn(0f, 1f)
        // Full rim under the wipe — bottom edge appears first, top completes last.
        ImmersiveSummonOrigin.Hotword -> if (reveal > 0.02f) 1f else 0f
    }
    val showOverlay = visible ||
        backdropAlpha.value > 0.02f ||
        reveal > 0.02f
    val debugStripVisible by AssistantDebugStripConfig.visible.collectAsStateWithLifecycle()

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        if (showOverlay) {
            // One transform for the whole stage: icon emerge OR hotword bottom→top wipe.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(immersiveSummonGraphics(summonOrigin, reveal)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = backdropAlpha.value.coerceIn(0f, 1f) }
                        .then(
                            if (visible) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { stageStore.dispatch(StageIntent.Dismiss) },
                                )
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    ImmersiveBackdrop(rich = richEffects)
                    if (glowReveal > 0.01f) {
                        // Faster breath while either party is talking *or* we're listening.
                        val speechActive = mood == AssistantMood.Listening ||
                            mood == AssistantMood.Speaking ||
                            speaker == DialogueSpeaker.User ||
                            (mouthAmplitude != null && mouthAmplitude > 0.04f)
                        val speechEnergy = when {
                            mouthAmplitude != null -> mouthAmplitude.coerceIn(0f, 1f)
                            // User turns / listening have no lip-sync — keep mid energy so
                            // the rim still feels responsive.
                            speaker == DialogueSpeaker.User -> 0.55f
                            mood == AssistantMood.Listening -> 0.40f
                            mood == AssistantMood.Speaking -> 0.45f
                            else -> 0f
                        }
                        ImmersiveBorderGlow(
                            glowColor = brandGlow,
                            // Hotword: full rim under the vertical wipe (bottom appears first).
                            // Icon: fade rim in as the scale-up finishes.
                            revealProgress = glowReveal,
                            speechActive = speechActive,
                            speechEnergy = speechEnergy,
                        )
                    }
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
                        // Main overlay: icons live inside the face via [faceCues], not floating HUD.
                        floatContextGlyph = false,
                        showFace = faceKind != AssistantFaceKind.None,
                        faceRise = faceRise.value,
                        faceScale = faceScale.value,
                        faceAlpha = faceAlpha.value,
                        transcriptAlpha = transcriptAlpha.value,
                        faceCues = faceCues,
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

        if (!visible && awaitHotword) {
            // Awaiting hotword — tap anywhere to summon.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onRequestHotwordListen?.invoke()
                            summon(ImmersiveSummonOrigin.Icon)
                        },
                    ),
            )
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
 * eases inward to full transparency. Colors drift slowly when idle motion is on,
 * and the rim **breathes** — width and brightness swell outward, then ease back.
 *
 * When [speechActive] is true (listening or speaking), the breath is faster
 * and wider; [speechEnergy] adds a live width/brightness kick from the voice.
 * Idle still breathes — just at the slower ambient cadence.
 *
 * [revealProgress] fades the rim in/out (parent owns icon emerge / hotword wipe).
 *
 * [windowInsets] inset the glow from system bars (e.g. bottom nav) so the rim
 * stays visible; pass [WindowInsets] with zeros for true edge-to-edge.
 */
@Composable
fun ImmersiveBorderGlow(
    modifier: Modifier = Modifier,
    glowColor: Color = Color(0xFF8AB4F8),
    windowInsets: WindowInsets = WindowInsets.systemBars,
    revealProgress: Float = 1f,
    speechActive: Boolean = false,
    speechEnergy: Float = 0f,
) {
    val idleMotion = LocalAssistantIdleMotion.current
    val sweepAngle = remember { Animatable(0f) }
    val breathScale = remember { Animatable(1f) }
    LaunchedEffect(idleMotion, speechActive) {
        val breathEnabled = idleMotion || speechActive
        if (!breathEnabled) {
            sweepAngle.snapTo(0f)
            breathScale.snapTo(1f)
            return@LaunchedEffect
        }
        // Spectrum sweep only while idle-motion is allowed (TTFR-safe first frame).
        if (idleMotion) {
            launch {
                while (true) {
                    sweepAngle.snapTo(0f)
                    sweepAngle.animateTo(
                        targetValue = 360f,
                        animationSpec = tween(durationMillis = 18_000, easing = LinearEasing),
                    )
                }
            }
        } else {
            sweepAngle.snapTo(0f)
        }
        // Idle: ambient inhale. Listening/speaking: faster, wider bloom.
        val peak = if (speechActive) 1.85f else 1.65f
        val halfCycleMs = if (speechActive) 1_500 else 2_600
        while (true) {
            breathScale.animateTo(
                targetValue = peak,
                animationSpec = tween(durationMillis = halfCycleMs, easing = FastOutSlowInEasing),
            )
            breathScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = halfCycleMs, easing = FastOutSlowInEasing),
            )
        }
    }
    val paint = remember { Paint().asFrameworkPaint().apply { isAntiAlias = true } }
    val shaderMatrix = remember { Matrix() }
    val angle = sweepAngle.value
    val energy = speechEnergy.coerceIn(0f, 1f)
    // Soft voice kick on top of the breath cycle (lip-sync / live user speech).
    val breath = breathScale.value + energy * 0.22f
    val progress = revealProgress.coerceIn(0f, 1f)
    // 0 at rest → 1 near peak inhale (speech peak ~1.85).
    val inhale = ((breath - 1f) / 0.85f).coerceIn(0f, 1f)
    // Brighten on inhale so the motion is readable (dimming canceled the old subtle breath).
    val breathFade = 0.62f + inhale * 0.38f + energy * 0.12f
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
    // Edge opacity also breathes: stronger rim on inhale, softer on exhale.
    val edgeHi = (0xA0 + (0x4C * inhale).toInt()).coerceIn(0, 0xFF)
    val edgeMid = (0x66 + (0x40 * inhale).toInt()).coerceIn(0, 0xFF)
    val edgeLo = (0x2A + (0x28 * inhale).toInt()).coerceIn(0, 0xFF)
    val fadeAlphas = intArrayOf(
        (edgeHi shl 24) or 0x00FFFFFF,
        (edgeMid shl 24) or 0x00FFFFFF,
        (edgeLo shl 24) or 0x00FFFFFF,
        0x18FFFFFF,
        0x06FFFFFF,
        0x00FFFFFF,
    )
    val fadeStops = remember {
        floatArrayOf(0.00f, 0.12f, 0.30f, 0.55f, 0.80f, 1.00f)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(windowInsets)
            .graphicsLayer { alpha = (progress * breathFade).coerceIn(0f, 1f) },
    ) {
        val w = size.width
        val h = size.height
        // Explicit bloom — rest ~52dp, idle inhale ~86dp, speech inhale ~96dp.
        val thickness = 52.dp.toPx() * breath
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
    // Centered band ≤60% of stage width so spoken words never drift far left.
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // Crossfade only when the speaker role changes; word motion lives in LiveInputText.
        AnimatedContent(
            targetState = speaker,
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
            },
            label = "immersive_transcript_speaker",
            modifier = Modifier.fillMaxWidth(0.6f),
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
                speaking = who == DialogueSpeaker.Assistant,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

private val immersiveSummonHandlers = mutableListOf<(ImmersiveSummonOrigin) -> Unit>()
private val immersiveDismissHandlers = mutableListOf<() -> Unit>()
@Volatile
private var pendingSummonOrigin: ImmersiveSummonOrigin? = null

/**
 * Whole-stage enter transform:
 * - [ImmersiveSummonOrigin.Icon] — scale up from the assist-icon corner (bottom-end).
 * - [ImmersiveSummonOrigin.Hotword] — bottom→top wipe until the border is complete.
 */
private fun immersiveSummonGraphics(
    origin: ImmersiveSummonOrigin,
    reveal: Float,
): Modifier {
    val t = reveal.coerceIn(0f, 1f)
    return when (origin) {
        ImmersiveSummonOrigin.Icon -> Modifier.graphicsLayer {
            val eased = FastOutSlowInEasing.transform(t)
            transformOrigin = TransformOrigin(0.92f, 1f) // system-bar assist icon / bottom-end
            val scale = 0.18f + 0.82f * eased
            scaleX = scale
            scaleY = scale
            // Keep a little opacity from the first frames so the stage never looks blank.
            alpha = (0.20f + 0.80f * eased).coerceIn(0f, 1f)
            translationX = (1f - eased) * 28f
            translationY = (1f - eased) * 64f
        }
        ImmersiveSummonOrigin.Hotword -> {
            if (t >= 0.999f) {
                Modifier
            } else {
                Modifier
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = (0.45f + 0.55f * t).coerceIn(0f, 1f)
                        translationY = (1f - t) * size.height * 0.06f
                    }
                    .drawWithContent {
                        drawContent()
                        // Opaque from the rising front down to the bottom → bottom-to-top unveil.
                        val front = (1f - t).coerceIn(0f, 1f)
                        val soft = 0.08f
                        val softStart = (front - soft).coerceIn(0f, 1f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    softStart to Color.Transparent,
                                    front.coerceAtLeast(softStart + 0.001f) to Color.White,
                                    1f to Color.White,
                                ),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
            }
        }
    }
}

@Composable
private fun ImmersiveSummonBridge(
    onSummon: (ImmersiveSummonOrigin) -> Unit,
    onDismiss: () -> Unit = {},
) {
    val summonState = rememberUpdatedState(onSummon)
    val dismissState = rememberUpdatedState(onDismiss)

    // Dismiss still rides the SharedFlow bus; summon is origin-aware via handlers.
    LaunchedEffect(Unit) {
        ImmersiveStageBus.dismiss.collect {
            dismissState.value()
        }
    }

    DisposableEffect(Unit) {
        val summonHandler: (ImmersiveSummonOrigin) -> Unit = { origin ->
            summonState.value(origin)
        }
        val dismissHandler: () -> Unit = { dismissState.value() }
        immersiveSummonHandlers += summonHandler
        immersiveDismissHandlers += dismissHandler
        // Deliver a summon that arrived before this composition registered.
        pendingSummonOrigin?.let { pending ->
            pendingSummonOrigin = null
            summonHandler(pending)
        }
        onDispose {
            immersiveSummonHandlers -= summonHandler
            immersiveDismissHandlers -= dismissHandler
        }
    }
}

/**
 * Summon the immersive overlay. Prefer [notifyImmersiveAssistantSummon] with an explicit origin.
 * Defaults to hotword (bottom→top) for backward-compatible wake-word callers.
 */
fun notifyImmersiveAssistantHotword() {
    notifyImmersiveAssistantSummon(ImmersiveSummonOrigin.Hotword)
}

/** Summon with an explicit enter style (icon emerge vs hotword bottom→top). */
fun notifyImmersiveAssistantSummon(origin: ImmersiveSummonOrigin) {
    // Origin-aware handler list is the source of truth (StageBus is Unit-only).
    val handlers = immersiveSummonHandlers.toList()
    if (handlers.isEmpty()) {
        // Composition may not have registered yet (session onShow races first frame).
        pendingSummonOrigin = origin
    } else {
        pendingSummonOrigin = null
        handlers.forEach { it.invoke(origin) }
    }
    notifyAssistantHotword()
}

/** Release Compose STT / stage when the VoiceInteractionSession hides. */
fun notifyImmersiveAssistantDismiss() {
    ImmersiveStageBus.notifyDismiss()
    pendingSummonOrigin = null
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
