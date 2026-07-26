package com.test.design.presentation.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/**
 * Small Material 3 expressive silhouette set for the assistant outer frame.
 * Eyes/mouth stay separate — only the hard (or soft) shell morphs.
 */
enum class ExpressiveShellKind {
    Arch,
    SemiCircle,
    Oval,
    /** Material 3 gem — used for the pale outer rim plate behind the face shell. */
    Gem,
}

/** Mood → one of three face-like expressive shapes. */
internal fun AssistantMood.toShellKind(): ExpressiveShellKind = when (this) {
    AssistantMood.Idle,
    AssistantMood.Bored,
    AssistantMood.Drowsy,
    AssistantMood.Tired,
    AssistantMood.Sad,
    -> ExpressiveShellKind.Arch

    AssistantMood.Listening,
    AssistantMood.Thinking,
    AssistantMood.Reading,
    AssistantMood.Searching,
    -> ExpressiveShellKind.SemiCircle

    AssistantMood.Speaking,
    AssistantMood.Happy,
    AssistantMood.Excited,
    -> ExpressiveShellKind.Oval
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun ExpressiveShellKind.toRoundedPolygon(): RoundedPolygon = when (this) {
    ExpressiveShellKind.Arch -> MaterialShapes.Arch
    ExpressiveShellKind.SemiCircle -> MaterialShapes.SemiCircle
    ExpressiveShellKind.Oval -> MaterialShapes.Oval
    ExpressiveShellKind.Gem -> MaterialShapes.Gem
}

/**
 * Animated Morph between previous and target shell shapes.
 * Progress settles at 1f on the current mood's shape — no idle thrashing.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun rememberExpressiveShellMorph(
    mood: AssistantMood,
): ExpressiveShellMorphState = rememberExpressiveShellMorph(mood.toShellKind())

/**
 * Morph the outer shell toward [targetKind] (e.g. Eporo SemiCircle ↔ Gem).
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun rememberExpressiveShellMorph(
    targetKind: ExpressiveShellKind,
): ExpressiveShellMorphState {
    var settledKind by remember { mutableStateOf(targetKind) }
    var morph by remember {
        mutableStateOf(
            Morph(
                start = targetKind.toRoundedPolygon(),
                end = targetKind.toRoundedPolygon(),
            ),
        )
    }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(targetKind) {
        if (targetKind == settledKind && progress.value == 1f) return@LaunchedEffect
        morph = Morph(
            start = settledKind.toRoundedPolygon(),
            end = targetKind.toRoundedPolygon(),
        )
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        )
        settledKind = targetKind
    }

    return ExpressiveShellMorphState(morph = morph, progress = progress.value)
}

internal data class ExpressiveShellMorphState(
    val morph: Morph,
    val progress: Float,
)

/** Draw unit-normalized morph path fitted into [bounds]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun DrawScope.drawExpressiveFaceShell(
    morphState: ExpressiveShellMorphState,
    bounds: Rect,
    color: Color,
    style: DrawStyle = Fill,
) {
    drawExpressiveFaceShellPath(morphState, bounds) { path ->
        drawPath(path = path, color = color, style = style)
    }
}

/** Brush fill variant — used for glossy white outer shells (e.g. Eporo). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun DrawScope.drawExpressiveFaceShell(
    morphState: ExpressiveShellMorphState,
    bounds: Rect,
    brush: Brush,
    style: DrawStyle = Fill,
) {
    drawExpressiveFaceShellPath(morphState, bounds) { path ->
        drawPath(path = path, brush = brush, style = style)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private inline fun DrawScope.drawExpressiveFaceShellPath(
    morphState: ExpressiveShellMorphState,
    bounds: Rect,
    draw: DrawScope.(Path) -> Unit,
) {
    if (bounds.width <= 0f || bounds.height <= 0f) return
    val unit = morphState.morph.toPath(morphState.progress)
    translate(left = bounds.left, top = bounds.top) {
        scale(scaleX = bounds.width, scaleY = bounds.height, pivot = Offset.Zero) {
            draw(unit)
        }
    }
}
