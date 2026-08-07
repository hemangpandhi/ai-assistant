package com.assistant.ui.assistant.face

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
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.assistant.ui.assistant.face.AssistantMood

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
    AssistantMood.Sleeping,
    AssistantMood.Sad,
    AssistantMood.Relaxed,
    AssistantMood.Contentment,
    AssistantMood.Acceptance,
    AssistantMood.Doubt,
    -> ExpressiveShellKind.Arch

    AssistantMood.Listening,
    AssistantMood.Thinking,
    AssistantMood.Concentration,
    AssistantMood.Reading,
    AssistantMood.Searching,
    AssistantMood.Interest,
    AssistantMood.Surprise,
    AssistantMood.Astonishment,
    AssistantMood.Dreamy,
    AssistantMood.Concerned,
    AssistantMood.Impressed,
    -> ExpressiveShellKind.SemiCircle

    AssistantMood.Speaking,
    AssistantMood.Happy,
    AssistantMood.Amused,
    AssistantMood.Joyous,
    AssistantMood.Excited,
    AssistantMood.Jubilation,
    AssistantMood.Attraction,
    AssistantMood.Admiration,
    AssistantMood.Desire,
    AssistantMood.Gratitude,
    AssistantMood.Proud,
    AssistantMood.Triumph,
    AssistantMood.Shy,
    AssistantMood.Complicity,
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

/** Unit morph fitted into [bounds] — for fill/stroke and in-shell clipping. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun expressiveFaceShellPath(
    morphState: ExpressiveShellMorphState,
    bounds: Rect,
): Path = expressiveFaceShellPath(morphState, bounds, Path(), Matrix())

/**
 * Rebuilds the shell morph into [into], reusing [transformMatrix] to avoid per-frame
 * Path / Matrix allocations in animation loops.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun expressiveFaceShellPath(
    morphState: ExpressiveShellMorphState,
    bounds: Rect,
    into: Path,
    transformMatrix: Matrix,
): Path {
    into.rewind()
    if (bounds.width <= 0f || bounds.height <= 0f) return into
    // Material3 toPath writes the unit morph into [into] (clears first).
    morphState.morph.toPath(morphState.progress, into)
    transformMatrix.reset()
    transformMatrix.values[Matrix.ScaleX] = bounds.width
    transformMatrix.values[Matrix.ScaleY] = bounds.height
    transformMatrix.values[Matrix.TranslateX] = bounds.left
    transformMatrix.values[Matrix.TranslateY] = bounds.top
    into.transform(transformMatrix)
    return into
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private inline fun DrawScope.drawExpressiveFaceShellPath(
    morphState: ExpressiveShellMorphState,
    bounds: Rect,
    draw: DrawScope.(Path) -> Unit,
) {
    // Map the unit morph into draw-space so Brush gradients (pixel coords) sample
    // correctly. Scaling the DrawScope instead would leave brushes in the wrong space
    // and collapse glossy fills to a flat black.
    draw(expressiveFaceShellPath(morphState, bounds))
}

/** Draw a pre-built shell path (shared across glossy layers + clip). */
internal fun DrawScope.drawExpressiveFaceShell(
    path: Path,
    color: Color,
    style: DrawStyle = Fill,
) {
    drawPath(path = path, color = color, style = style)
}

/** Brush fill for a pre-built shell path. */
internal fun DrawScope.drawExpressiveFaceShell(
    path: Path,
    brush: Brush,
    style: DrawStyle = Fill,
) {
    drawPath(path = path, brush = brush, style = style)
}

/**
 * Layered black-glass face fill — replaces flat matte black with readable depth:
 * lifted crown, bright specular, cool rim whisper, soft chin shade.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun DrawScope.drawGlossyBlackFaceShell(
    morphState: ExpressiveShellMorphState,
    bounds: Rect,
    base: Color = Color(0xFF050508),
    rimTint: Color = Color(0xFF8AB4F8),
) {
    if (bounds.width <= 0f || bounds.height <= 0f) return
    drawGlossyBlackFaceShell(
        path = expressiveFaceShellPath(morphState, bounds),
        bounds = bounds,
        base = base,
        rimTint = rimTint,
    )
}

/**
 * Glossy black fill using a pre-built [path] (shared with rim stroke / clipPath).
 */
internal fun DrawScope.drawGlossyBlackFaceShell(
    path: Path,
    bounds: Rect,
    base: Color = Color(0xFF050508),
    rimTint: Color = Color(0xFF8AB4F8),
) {
    if (bounds.width <= 0f || bounds.height <= 0f) return
    val top = bounds.top
    val bottom = bounds.bottom
    val glossCenter = Offset(
        bounds.left + bounds.width * 0.36f,
        bounds.top + bounds.height * 0.20f,
    )
    val streakCenter = Offset(
        bounds.left + bounds.width * 0.50f,
        bounds.top + bounds.height * 0.14f,
    )
    val rimCenter = Offset(
        bounds.left + bounds.width * 0.70f,
        bounds.top + bounds.height * 0.55f,
    )
    val minSide = minOf(bounds.width, bounds.height)

    // 1) Base volume — clearly lifted crown → deep chin (visible on AAOS screens).
    drawExpressiveFaceShell(
        path = path,
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to Color(0xFF2A2E38),
                0.18f to Color(0xFF161920),
                0.48f to base,
                0.78f to Color(0xFF030306),
                1.00f to Color(0xFF000000),
            ),
            startY = top,
            endY = bottom,
        ),
    )
    // 2) Specular gloss — soft top-left glass highlight.
    drawExpressiveFaceShell(
        path = path,
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to Color.White.copy(alpha = 0.28f),
                0.22f to Color.White.copy(alpha = 0.12f),
                0.50f to Color.White.copy(alpha = 0.04f),
                1.00f to Color.Transparent,
            ),
            center = glossCenter,
            radius = minSide * 0.58f,
        ),
    )
    // 3) Crown streak — thin elongated specular.
    drawExpressiveFaceShell(
        path = path,
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to Color.White.copy(alpha = 0.22f),
                0.40f to Color.White.copy(alpha = 0.06f),
                1.00f to Color.Transparent,
            ),
            center = streakCenter,
            radius = minSide * 0.20f,
        ),
    )
    // 4) Cool rim whisper — blends with the outer border glow blue.
    drawExpressiveFaceShell(
        path = path,
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to rimTint.copy(alpha = 0.18f),
                0.45f to rimTint.copy(alpha = 0.06f),
                1.00f to Color.Transparent,
            ),
            center = rimCenter,
            radius = minSide * 0.68f,
        ),
    )
    // 5) Soft chin shade only — don't crush the crown gloss.
    drawExpressiveFaceShell(
        path = path,
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to Color.Transparent,
                0.55f to Color.Transparent,
                0.80f to Color.Black.copy(alpha = 0.18f),
                1.00f to Color.Black.copy(alpha = 0.38f),
            ),
            startY = top,
            endY = bottom,
        ),
    )
}
