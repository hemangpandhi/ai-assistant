package com.test.design.presentation.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Immersive eyes assistant — opens directly as a fullscreen translucent overlay.
 * Appears on hotword (or tap / icon launch).
 */
@Composable
fun VirtualAssistantOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialMood: AssistantMood = AssistantMood.Idle,
    /** When true, starts listening / waiting for hotword instead of showing immediately. */
    awaitHotword: Boolean = true,
    onRequestHotwordListen: (() -> Unit)? = null,
    enableLiveSpeech: Boolean = true,
    enableTts: Boolean = true,
    onPresentationChanged: (AssistantPresentation) -> Unit = {},
    onBubbleBoundsInRoot: ((left: Int, top: Int, right: Int, bottom: Int) -> Unit)? = null,
) {
    ImmersiveAssistantOverlay(
        onDismiss = onDismiss,
        modifier = modifier,
        initialMood = initialMood,
        awaitHotword = awaitHotword,
        onRequestHotwordListen = onRequestHotwordListen,
        enableLiveSpeech = enableLiveSpeech,
        enableTts = enableTts,
        onPresentationChanged = onPresentationChanged,
        onBubbleBoundsInRoot = onBubbleBoundsInRoot,
    )
}

/**
 * Legacy NOMI orb overlay — peek / bounce / fall entrances (kept for gallery / demos).
 */
@Composable
fun NomiOrbOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialMood: AssistantMood = AssistantMood.Idle,
    awaitHotword: Boolean = true,
    onRequestHotwordListen: (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(!awaitHotword) }
    var entrance by remember { mutableStateOf(OrbEntrance.random()) }
    var mood by rememberSaveable { mutableStateOf(initialMood) }
    var session by remember { mutableStateOf(0) }
    val wake = rememberAssistantWakeFeedback()

    fun summon(nextMood: AssistantMood = randomSummonMood()) {
        entrance = OrbEntrance.random()
        mood = nextMood
        session += 1
        visible = true
    }

    LaunchedEffect(visible, session) {
        if (!visible) return@LaunchedEffect
        wake.play()
        delay(900)
        while (isActive && visible) {
            mood = randomSummonMood()
            delay(Random.nextLong(1600, 2800))
        }
    }

    LaunchedEffect(visible, session) {
        if (!visible) return@LaunchedEffect
        delay(Random.nextLong(5200, 7800))
        visible = false
        wake.playDismiss()
        delay(420)
        onDismiss()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (visible) {
                        visible = false
                    } else {
                        onRequestHotwordListen?.invoke()
                        summon()
                    }
                },
            ),
    ) {
        HotwordSummonBridge(onSummon = { summon() })

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(280)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Dim busy IVI chrome so the orb reads clearly.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(AssistantTokens.Scrim),
                )
                NomiOrbStage(
                    mood = mood,
                    entrance = entrance,
                    session = session,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Allows [VirtualAssistantActivity] to summon without recomposing the whole tree awkwardly. */
private val summonHandlers = mutableListOf<() -> Unit>()

@Composable
private fun HotwordSummonBridge(onSummon: () -> Unit) {
    DisposableEffect(onSummon) {
        summonHandlers += onSummon
        onDispose { summonHandlers -= onSummon }
    }
}

/** Called from the Activity when a hotword is detected. */
fun notifyAssistantHotword() {
    summonHandlers.toList().forEach { it.invoke() }
}

private fun randomSummonMood(): AssistantMood = listOf(
    AssistantMood.Idle,
    AssistantMood.Listening,
    AssistantMood.Happy,
    AssistantMood.Speaking,
    AssistantMood.Thinking,
    AssistantMood.Searching,
).random()

@Composable
private fun NomiOrbStage(
    mood: AssistantMood,
    entrance: OrbEntrance,
    session: Int,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val orbDp = 168.dp
        val orbPx = with(density) { orbDp.toPx() }
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        val offsetX = remember { Animatable(0f) }
        val offsetY = remember { Animatable(0f) }
        val scale = remember { Animatable(0.6f) }
        val alpha = remember { Animatable(0f) }

        LaunchedEffect(session, entrance, w, h, orbPx) {
            val restX = (w - orbPx) * 0.5f
            val restY = (h - orbPx) * 0.42f
            val springSpec = spring<Float>(
                dampingRatio = 0.62f,
                stiffness = Spring.StiffnessMediumLow,
            )
            val soft = tween<Float>(520, easing = FastOutSlowInEasing)

            when (entrance) {
                OrbEntrance.PeekBottom -> {
                    offsetX.snapTo(restX)
                    offsetY.snapTo(h - orbPx * 0.28f)
                    scale.snapTo(0.92f)
                    alpha.snapTo(1f)
                    delay(280)
                    offsetY.animateTo(h - orbPx * 0.72f, soft)
                    delay(500)
                    offsetY.animateTo(restY, springSpec)
                    scale.animateTo(1f, soft)
                }
                OrbEntrance.PeekLeft -> {
                    offsetX.snapTo(-orbPx * 0.55f)
                    offsetY.snapTo(restY)
                    scale.snapTo(0.92f)
                    alpha.snapTo(1f)
                    delay(220)
                    offsetX.animateTo(orbPx * 0.08f, soft)
                    delay(450)
                    offsetX.animateTo(restX, springSpec)
                    scale.animateTo(1f, soft)
                }
                OrbEntrance.PeekRight -> {
                    offsetX.snapTo(w - orbPx * 0.45f)
                    offsetY.snapTo(restY)
                    scale.snapTo(0.92f)
                    alpha.snapTo(1f)
                    delay(220)
                    offsetX.animateTo(w - orbPx * 1.05f, soft)
                    delay(450)
                    offsetX.animateTo(restX, springSpec)
                    scale.animateTo(1f, soft)
                }
                OrbEntrance.PeekTop -> {
                    offsetX.snapTo(restX)
                    offsetY.snapTo(-orbPx * 0.55f)
                    scale.snapTo(0.92f)
                    alpha.snapTo(1f)
                    delay(220)
                    offsetY.animateTo(orbPx * 0.05f, soft)
                    delay(450)
                    offsetY.animateTo(restY, springSpec)
                    scale.animateTo(1f, soft)
                }
                OrbEntrance.Fall -> {
                    offsetX.snapTo(restX + Random.nextFloat() * 80f - 40f)
                    offsetY.snapTo(-orbPx * 1.2f)
                    scale.snapTo(0.85f)
                    alpha.snapTo(1f)
                    offsetY.animateTo(restY + 36f, tween(480, easing = FastOutSlowInEasing))
                    offsetY.animateTo(restY - 18f, springSpec)
                    offsetY.animateTo(restY, springSpec)
                    scale.animateTo(1f, soft)
                    offsetX.animateTo(restX, soft)
                }
                OrbEntrance.Bounce -> {
                    offsetX.snapTo(restX)
                    offsetY.snapTo(h)
                    scale.snapTo(0.7f)
                    alpha.snapTo(1f)
                    offsetY.animateTo(restY - 40f, tween(420))
                    offsetY.animateTo(restY + 24f, springSpec)
                    offsetY.animateTo(restY - 10f, springSpec)
                    offsetY.animateTo(restY, springSpec)
                    scale.animateTo(1.05f, soft)
                    scale.animateTo(1f, soft)
                    // playful side hop
                    offsetX.animateTo(restX + 48f, soft)
                    offsetX.animateTo(restX - 28f, soft)
                    offsetX.animateTo(restX, springSpec)
                }
                OrbEntrance.Pop -> {
                    offsetX.snapTo(restX)
                    offsetY.snapTo(restY)
                    scale.snapTo(0.2f)
                    alpha.snapTo(0f)
                    alpha.animateTo(1f, tween(160))
                    scale.animateTo(1.12f, springSpec)
                    scale.animateTo(1f, soft)
                }
            }

            // Idle wander while on screen
            while (isActive) {
                val nx = restX + Random.nextFloat() * 56f - 28f
                val ny = restY + Random.nextFloat() * 40f - 20f
                offsetX.animateTo(nx, tween(1400, easing = FastOutSlowInEasing))
                offsetY.animateTo(ny, tween(1400, easing = FastOutSlowInEasing))
                delay(Random.nextLong(200, 600))
            }
        }

        val glow by animateFloatAsState(
            targetValue = if (mood == AssistantMood.Listening) 1f else 0.75f,
            animationSpec = tween(400),
            label = "glow",
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .size(orbDp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                    // tiny extra presence when listening
                    shadowElevation = 8f + 10f * glow
                },
        ) {
            AssistantFace(
                mood = mood,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.VirtualAssistantScreen(
    onBack: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
    initialMood: AssistantMood = AssistantMood.Idle,
    onPresentationChanged: (AssistantPresentation) -> Unit = {},
) {
    VirtualAssistantOverlay(
        onDismiss = onBack,
        modifier = modifier.fillMaxSize(),
        initialMood = initialMood,
        awaitHotword = false,
        onPresentationChanged = onPresentationChanged,
    )
}

@Composable
fun VirtualAssistantScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialMood: AssistantMood = AssistantMood.Idle,
    onPresentationChanged: (AssistantPresentation) -> Unit = {},
) {
    VirtualAssistantOverlay(
        onDismiss = onBack,
        modifier = modifier,
        initialMood = initialMood,
        awaitHotword = false,
        onPresentationChanged = onPresentationChanged,
    )
}

@Composable
fun VirtualAssistantStage(
    mood: AssistantMood,
    onMoodChange: (AssistantMood) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        ImmersiveEyesFace(mood = mood, modifier = Modifier.size(168.dp))
    }
}

@Composable
fun AssistantWidgetPreview(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = AssistantTokens.SurfaceBottom,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ImmersiveEyesFace(
                mood = mood,
                modifier = Modifier.size(64.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Assistant",
                    style = MaterialTheme.typography.titleSmall,
                    color = AssistantTokens.OnSurface,
                )
                Text(
                    text = "Say “Hey assistant”",
                    style = MaterialTheme.typography.bodySmall,
                    color = AssistantTokens.OnSurfaceMuted,
                )
            }
        }
    }
}
