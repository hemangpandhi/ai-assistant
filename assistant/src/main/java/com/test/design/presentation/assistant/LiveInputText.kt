package com.test.design.presentation.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Soft emphasized curve — ease out without a hard settle. */
private val LiveRevealEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

/** How many tokens the fade edge spans (overlap = fluid wave, not staccato). */
private const val RevealFadeWindow = 1.6f

/**
 * Tokenize [text] into reveal units for Google Assistant Live–style streaming.
 * Whitespace stays attached to the following word so layout doesn't jump.
 */
internal fun liveInputTokens(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<String>()
    val matcher = Regex("""\s*\S+""").findAll(text)
    var cursor = 0
    for (match in matcher) {
        if (match.range.first > cursor) {
            out += text.substring(cursor, match.range.first)
        }
        out += match.value
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        out += text.substring(cursor)
    }
    return out
}

/** Shared leading token count between two token lists. */
internal fun liveInputSharedPrefixCount(previous: List<String>, next: List<String>): Int {
    val limit = minOf(previous.size, next.size)
    var i = 0
    while (i < limit && previous[i] == next[i]) i++
    return i
}

/**
 * Smoothstep opacity for token [index] given continuous [reveal] progress.
 * Tokens blend across [RevealFadeWindow] so several words ease in together.
 */
internal fun liveInputTokenAlpha(index: Int, reveal: Float): Float {
    val t = ((reveal - index) / RevealFadeWindow).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Live-input transcript: one continuous reveal progress (not stepped per word),
 * stable-prefix growth for STT, soft trailing breath while listening.
 */
@Composable
fun LiveInputText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    fontSize: TextUnit = 26.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    textAlign: TextAlign = TextAlign.Center,
    maxLines: Int = 1,
) {
    val tokens = remember(text) { liveInputTokens(text) }
    var committedText by remember { mutableStateOf("") }
    val reveal = remember { Animatable(0f) }

    LaunchedEffect(text) {
        val nextTokens = liveInputTokens(text)
        val prevTokens = liveInputTokens(committedText)
        val shared = liveInputSharedPrefixCount(prevTokens, nextTokens)
        val target = nextTokens.size.toFloat()

        when {
            text.isBlank() -> {
                reveal.snapTo(0f)
                committedText = ""
            }

            // Prefix-stable growth / light correction — retarget without rewinding.
            committedText.isNotEmpty() && shared > 0 -> {
                val floor = shared.toFloat()
                when {
                    reveal.value > target -> reveal.snapTo(target)
                    reveal.value < floor -> reveal.snapTo(floor)
                }
                val delta = (target - reveal.value).coerceAtLeast(0f)
                if (delta > 0.001f) {
                    val duration = (160 + (delta * 110f).toInt()).coerceIn(160, 480)
                    reveal.animateTo(
                        target,
                        tween(durationMillis = duration, easing = FastOutSlowInEasing),
                    )
                } else {
                    reveal.snapTo(target)
                }
                committedText = text
            }

            // Fresh line — single continuous wipe across all tokens.
            else -> {
                reveal.snapTo(0f)
                if (target <= 0f) {
                    committedText = text
                } else {
                    val duration = (260 + nextTokens.size * 55).coerceIn(260, 780)
                    reveal.animateTo(
                        target,
                        tween(durationMillis = duration, easing = LiveRevealEasing),
                    )
                    committedText = text
                }
            }
        }
    }

    // Always remember the transition (Compose rules); gate with [live] multiplier.
    val breathTransition = rememberInfiniteTransition(label = "live_input_breath")
    val breath by breathTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "live_trailing_breath",
    )
    val trailingMul = if (live && tokens.isNotEmpty()) breath else 1f

    if (text.isBlank()) {
        Box(modifier = modifier.height(34.dp))
        return
    }

    val progress = reveal.value
    val annotated = buildAnnotatedString {
        tokens.forEachIndexed { index, token ->
            var a = liveInputTokenAlpha(index, progress)
            if (live && index == tokens.lastIndex && a > 0.92f) {
                a *= trailingMul
            }
            withStyle(SpanStyle(color = color.copy(alpha = color.alpha * a.coerceIn(0f, 1f)))) {
                append(token)
            }
        }
    }

    Text(
        text = annotated,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}
