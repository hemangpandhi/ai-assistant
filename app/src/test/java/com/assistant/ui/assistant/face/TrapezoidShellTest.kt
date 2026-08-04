package com.assistant.ui.assistant.face

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import com.assistant.ui.assistant.ui.theme.ImmersiveTrapezoidTopWidthFactor
import com.assistant.ui.assistant.ui.theme.immersiveMatchedShellBounds
import com.assistant.ui.assistant.ui.theme.immersiveTrapezoidShellBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

class TrapezoidShellTest {

    @Test
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    fun trapezoidPolygonBuilds() {
        assertNotNull(ExpressiveShellKind.Trapezoid.toRoundedPolygon())
        assertNotNull(isoscelesTrapezoidShellPolygon(roundingRadius = 0.05f))
    }

    @Test
    fun trapezoidBoundsKeepHeightAndWidenBaseFor45Degrees() {
        val matched = immersiveMatchedShellBounds(1000f, 1000f, breath = 1f)
        val trap = immersiveTrapezoidShellBounds(1000f, 1000f, breath = 1f)

        assertEquals(matched.top, trap.top, 0.01f)
        assertEquals(matched.bottom, trap.bottom, 0.01f)
        assertTrue(trap.width > matched.width)

        val topWidth = matched.width * ImmersiveTrapezoidTopWidthFactor
        val expectedBase = topWidth + 2f * matched.height
        assertEquals(expectedBase, trap.width, 0.5f)

        val inset = (trap.width - topWidth) * 0.5f
        val angleDeg = Math.toDegrees(atan((matched.height / inset).toDouble()))
        assertEquals(45.0, angleDeg, 0.5)
    }

    @Test
    fun unitTopInsetMatchesBoundsAspectFor45Degrees() {
        val matched = immersiveMatchedShellBounds(800f, 800f)
        val trap = immersiveTrapezoidShellBounds(800f, 800f)
        val topWidth = matched.width * ImmersiveTrapezoidTopWidthFactor
        val a = trap.height / trap.width
        // After ScaleX=W ScaleY=H, base angle = atan(H/(a*W)) = 45° when a = H/W.
        val angle = Math.toDegrees(atan((trap.height / (a * trap.width)).toDouble()))
        assertEquals(45.0, angle, 0.01)
        assertEquals(
            trap.height,
            tan(Math.PI / 4.0).toFloat() * ((trap.width - topWidth) * 0.5f),
            0.5f,
        )
        assertTrue(abs(topWidth - (trap.width - 2f * trap.height)) < 0.5f)
    }
}
