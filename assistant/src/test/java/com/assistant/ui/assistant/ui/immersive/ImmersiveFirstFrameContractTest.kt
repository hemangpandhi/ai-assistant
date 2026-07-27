package com.assistant.ui.assistant.ui.immersive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the two-phase immersive paint contract used for &lt;100ms TTFF.
 */
class ImmersiveFirstFrameContractTest {

    @Test
    fun liteBackdropSkipsOffscreenPasses() {
        // rich=false → single scrim + vertical gradient (no CompositingStrategy.Offscreen).
        assertFalse(shouldUseOffscreenBackdrop(rich = false))
        assertTrue(shouldUseOffscreenBackdrop(rich = true))
    }

    @Test
    fun idleMotionDisabledOnFirstFrame() {
        assertFalse(shouldEnableIdleMotion(richEffects = false))
        assertTrue(shouldEnableIdleMotion(richEffects = true))
    }

    @Test
    fun dockPathStartsVisible() {
        // awaitHotword=false (system-bar / session) must not start at alpha 0.
        val dock = initialDockPresence(awaitHotword = false)
        assertTrue(dock.backdropAlpha >= 0.99f)
        assertTrue(dock.faceAlpha >= 0.99f)
        assertTrue(dock.faceRise <= 0.2f)

        val hotword = initialDockPresence(awaitHotword = true)
        assertTrue(hotword.backdropAlpha <= 0.01f)
        assertTrue(hotword.faceAlpha <= 0.01f)
        assertTrue(hotword.faceRise >= 0.99f)
    }
}

/** Mirrors ImmersiveBackdrop(rich=…) policy without Compose runtime. */
internal fun shouldUseOffscreenBackdrop(rich: Boolean): Boolean = rich

/** Mirrors LocalAssistantIdleMotion / richEffects gating. */
internal fun shouldEnableIdleMotion(richEffects: Boolean): Boolean = richEffects

internal data class DockPresence(
    val backdropAlpha: Float,
    val faceAlpha: Float,
    val faceRise: Float,
)

/** Mirrors ImmersiveAssistantOverlay Animatable initial values for dock vs hotword. */
internal fun initialDockPresence(awaitHotword: Boolean): DockPresence =
    if (!awaitHotword) {
        DockPresence(backdropAlpha = 1f, faceAlpha = 1f, faceRise = 0.12f)
    } else {
        DockPresence(backdropAlpha = 0f, faceAlpha = 0f, faceRise = 1f)
    }
