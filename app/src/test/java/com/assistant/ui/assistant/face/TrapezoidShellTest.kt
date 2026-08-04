package com.assistant.ui.assistant.face

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import com.assistant.ui.assistant.ui.theme.ImmersiveTrapezoidBaseOverTop
import com.assistant.ui.assistant.ui.theme.ImmersiveTrapezoidTopWidthFactor
import com.assistant.ui.assistant.ui.theme.immersiveMatchedShellBounds
import com.assistant.ui.assistant.ui.theme.immersiveTrapezoidShellBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrapezoidShellTest {

    @Test
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    fun trapezoidPolygonBuilds() {
        assertNotNull(ExpressiveShellKind.Trapezoid.toRoundedPolygon())
        assertNotNull(isoscelesTrapezoidShellPolygon(roundingRadius = 0.05f))
    }

    @Test
    fun trapezoidBoundsKeepHeightAndBaseIs20PercentWiderThanTop() {
        val matched = immersiveMatchedShellBounds(1000f, 1000f, breath = 1f)
        val trap = immersiveTrapezoidShellBounds(1000f, 1000f, breath = 1f)

        assertEquals(matched.top, trap.top, 0.01f)
        assertEquals(matched.bottom, trap.bottom, 0.01f)

        val topWidth = matched.width * ImmersiveTrapezoidTopWidthFactor
        val expectedBase = topWidth * ImmersiveTrapezoidBaseOverTop
        assertEquals(expectedBase, trap.width, 0.5f)
        assertEquals(1.20f, ImmersiveTrapezoidBaseOverTop, 0.001f)
        assertTrue(trap.width > topWidth)
        assertEquals(topWidth * 1.20f, trap.width, 0.5f)
    }
}
