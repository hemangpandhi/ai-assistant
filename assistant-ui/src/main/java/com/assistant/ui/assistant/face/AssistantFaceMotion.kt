package com.assistant.ui.assistant.face

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.assistant.ui.assistant.ui.chrome.FaceGesture
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Shared expressive-face motion constants and coroutine helpers.
 *
 * Drawing geometry stays in each face renderer; only cloned speech / gesture /
 * gaze / idle timing lives here.
 */
internal object AssistantFaceMotion {
    val PoseSpring = spring<Float>(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessLow,
    )

    const val HighContrastGlowBoost = 1.25f
    const val MaxFaceGlow = 1.2f
    const val ExternalMouthVisibleFloor = 0.85f

    // Synthetic speech mouth
    const val SpeechPeakMin = 0.3f
    const val SpeechPeakSpan = 0.4f
    const val SpeechRestMin = 0.04f
    const val SpeechRestSpan = 0.12f
    const val SpeechPeakMsMin = 70
    const val SpeechPeakMsMax = 130
    const val SpeechRestMsMin = 55
    const val SpeechRestMsMax = 100

    // Nod / shake
    const val NodUpDelta = 10f
    const val NodDownDelta = 4f
    const val NodStepMs = 120
    const val ShakeLook = 0.55f
    const val ShakeStepMs = 100
    const val GestureRepeats = 2

    // Idle cycles (ms)
    const val LifeCycleMs = 3_600
    const val BreathCycleMs = 2_800
    const val BobCycleMs = 3_400
    const val SwayCycleMs = 4_600
    const val ActivityPulseMs = 2_400
    const val BreathMin = 0.985f
    const val BreathMax = 1.02f

    // Gaze scan
    const val ReadingLookRight = 0.38f
    const val ReadingLookLeft = -0.32f
    const val ReadingRightMs = 700
    const val ReadingLeftMs = 90
    const val ReadingHoldRightMs = 100L
    const val ReadingHoldLeftMs = 70L
    const val SearchingLookA = 0.45f
    const val SearchingLookB = -0.4f
    const val SearchingLookC = 0.08f
    const val SearchingAMs = 160
    const val SearchingBMs = 200
    const val SearchingCMs = 140
    const val SearchingPauseMs = 50L
    const val BoredLookRight = 0.5f
    const val BoredLookLeft = -0.35f
    const val BoredRightMs = 1_600
    const val BoredLeftMs = 1_800
    const val BoredHoldRightMs = 600L
    const val BoredHoldLeftMs = 800L
}

internal suspend fun Animatable<Float, AnimationVector1D>.runSyntheticSpeechMouth() {
    while (coroutineContext.isActive) {
        animateTo(
            Random.nextFloat() * AssistantFaceMotion.SpeechPeakSpan + AssistantFaceMotion.SpeechPeakMin,
            tween(Random.nextInt(AssistantFaceMotion.SpeechPeakMsMin, AssistantFaceMotion.SpeechPeakMsMax)),
        )
        animateTo(
            Random.nextFloat() * AssistantFaceMotion.SpeechRestSpan + AssistantFaceMotion.SpeechRestMin,
            tween(Random.nextInt(AssistantFaceMotion.SpeechRestMsMin, AssistantFaceMotion.SpeechRestMsMax)),
        )
    }
}

internal suspend fun runFaceGesture(
    gesture: FaceGesture,
    tilt: Animatable<Float, AnimationVector1D>,
    lookX: Animatable<Float, AnimationVector1D>,
    restTilt: Float,
    restLookX: Float,
) {
    when (gesture) {
        FaceGesture.None -> Unit
        FaceGesture.Nod -> {
            repeat(AssistantFaceMotion.GestureRepeats) {
                tilt.animateTo(
                    restTilt + AssistantFaceMotion.NodUpDelta,
                    tween(AssistantFaceMotion.NodStepMs),
                )
                tilt.animateTo(
                    restTilt - AssistantFaceMotion.NodDownDelta,
                    tween(AssistantFaceMotion.NodStepMs),
                )
            }
            tilt.animateTo(restTilt, AssistantFaceMotion.PoseSpring)
        }
        FaceGesture.Shake -> {
            repeat(AssistantFaceMotion.GestureRepeats) {
                lookX.animateTo(AssistantFaceMotion.ShakeLook, tween(AssistantFaceMotion.ShakeStepMs))
                lookX.animateTo(-AssistantFaceMotion.ShakeLook, tween(AssistantFaceMotion.ShakeStepMs))
            }
            lookX.animateTo(restLookX, AssistantFaceMotion.PoseSpring)
        }
    }
}

internal suspend fun Animatable<Float, AnimationVector1D>.runMoodGazeScan(mood: AssistantMood) {
    while (coroutineContext.isActive) {
        when (mood) {
            AssistantMood.Reading -> {
                animateTo(AssistantFaceMotion.ReadingLookRight, tween(AssistantFaceMotion.ReadingRightMs))
                delay(AssistantFaceMotion.ReadingHoldRightMs)
                animateTo(AssistantFaceMotion.ReadingLookLeft, tween(AssistantFaceMotion.ReadingLeftMs))
                delay(AssistantFaceMotion.ReadingHoldLeftMs)
            }
            AssistantMood.Searching -> {
                animateTo(AssistantFaceMotion.SearchingLookA, tween(AssistantFaceMotion.SearchingAMs))
                animateTo(AssistantFaceMotion.SearchingLookB, tween(AssistantFaceMotion.SearchingBMs))
                animateTo(AssistantFaceMotion.SearchingLookC, tween(AssistantFaceMotion.SearchingCMs))
                delay(AssistantFaceMotion.SearchingPauseMs)
            }
            AssistantMood.Bored -> {
                animateTo(
                    AssistantFaceMotion.BoredLookRight,
                    tween(AssistantFaceMotion.BoredRightMs, easing = FastOutSlowInEasing),
                )
                delay(AssistantFaceMotion.BoredHoldRightMs)
                animateTo(
                    AssistantFaceMotion.BoredLookLeft,
                    tween(AssistantFaceMotion.BoredLeftMs, easing = FastOutSlowInEasing),
                )
                delay(AssistantFaceMotion.BoredHoldLeftMs)
            }
            else -> delay(500)
        }
    }
}

internal fun AssistantMood.isGazeScanMood(): Boolean =
    this == AssistantMood.Reading ||
        this == AssistantMood.Searching ||
        this == AssistantMood.Bored

internal fun AssistantMood.isSpeechMouthMood(): Boolean =
    this == AssistantMood.Speaking || this == AssistantMood.Excited
