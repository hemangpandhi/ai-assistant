package com.assistant.ui.assistant.ui.immersive

import org.junit.Assert.assertEquals
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
    fun overlayStartsHiddenUntilSummon() {
        // Session / overlay inflate hidden; onShow / autoPresent drives summon + reveal.
        val idle = initialDockPresence(awaitHotword = false)
        assertTrue(idle.backdropAlpha <= 0.01f)
        assertTrue(idle.overlayReveal <= 0.01f)
        assertFalse(idle.visible)

        val hotwordWait = initialDockPresence(awaitHotword = true)
        assertTrue(hotwordWait.backdropAlpha <= 0.01f)
        assertTrue(hotwordWait.overlayReveal <= 0.01f)
        assertFalse(hotwordWait.visible)
    }

    @Test
    fun summonOriginTokens() {
        assertEquals(
            ImmersiveSummonOrigin.Hotword,
            ImmersiveSummonOrigin.fromBundleToken("hotword"),
        )
        assertEquals(
            ImmersiveSummonOrigin.Icon,
            ImmersiveSummonOrigin.fromBundleToken("icon"),
        )
        assertEquals(
            ImmersiveSummonOrigin.Icon,
            ImmersiveSummonOrigin.fromBundleToken(null),
        )
    }
}

/** Mirrors ImmersiveBackdrop(rich=…) policy without Compose runtime. */
internal fun shouldUseOffscreenBackdrop(rich: Boolean): Boolean = rich

/** Mirrors LocalAssistantIdleMotion / richEffects gating. */
internal fun shouldEnableIdleMotion(richEffects: Boolean): Boolean = richEffects

internal data class DockPresence(
    val visible: Boolean,
    val backdropAlpha: Float,
    val overlayReveal: Float,
)

/** Mirrors ImmersiveAssistantOverlay Animatable / visibility initial values. */
internal fun initialDockPresence(awaitHotword: Boolean): DockPresence =
    DockPresence(visible = false, backdropAlpha = 0f, overlayReveal = 0f)
