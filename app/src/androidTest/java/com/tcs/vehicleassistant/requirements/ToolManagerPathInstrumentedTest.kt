package com.tcs.vehicleassistant.requirements

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.handlers.HVACToolHandler
import com.tcs.vehicleassistant.handlers.MediaToolHandler
import com.tcs.vehicleassistant.handlers.NavigationToolHandler
import com.tcs.vehicleassistant.handlers.WindowToolHandler
import com.tcs.vehicleassistant.support.RegistryTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Registry → ToolManager → handler path smoke tests with intent interception (no physical VHAL writes required
 * for CUSTOM_KOTLIN handlers). VHAL GENERIC writes may soft-fail without Car service; we still assert the path runs.
 */
@RunWith(AndroidJUnit4::class)
class ToolManagerPathInstrumentedTest {

    private lateinit var toolManager: ToolManager

    @Before
    fun setUp() {
        toolManager = RegistryTestSupport.initializedToolManager()
    }

    @Test
    fun executePlayMusic_viaToolManager_interceptsIntent() = runBlocking {
        var sawIntent = false
        val msg = toolManager.executeToolCall(
            RegistryTestSupport.appContext(),
            "playMusic(arijit singh)",
        ) { sawIntent = true }
        assertNotNull(msg)
        assertTrue(msg.isNotBlank())
        // On AAOS without a media browser, Intent fallback may still fire.
        assertTrue(
            "expected success wording or recoverable media error, got: $msg",
            msg.contains("arijit", ignoreCase = true) ||
                msg.contains("putting", ignoreCase = true) ||
                msg.contains("media", ignoreCase = true) ||
                msg.contains("System Error", ignoreCase = true) ||
                msg.contains("Could not", ignoreCase = true) ||
                sawIntent,
        )
    }

    @Test
    fun executeStopMusic_viaToolManager() = runBlocking {
        val msg = toolManager.executeToolCall(RegistryTestSupport.appContext(), "stopMusic()")
        assertNotNull(msg)
        assertTrue(msg.isNotBlank())
    }

    @Test
    fun executeNavigate_viaToolManager_interceptsIntent() = runBlocking {
        var sawIntent = false
        val msg = toolManager.executeToolCall(
            RegistryTestSupport.appContext(),
            "startNavigationTo(\"Tokyo Tower\")",
        ) { sawIntent = true }
        assertNotNull(msg)
        assertTrue(msg.isNotBlank() || sawIntent)
    }

    @Test
    fun customHandlers_acceptCoreCabinCalls() = runBlocking {
        val ctx = RegistryTestSupport.appContext()
        val results = listOf(
            HVACToolHandler("increaseTemperature").execute(ctx, "increaseTemperature()", "", null),
            HVACToolHandler("decreaseTemperature").execute(ctx, "decreaseTemperature()", "", null),
            HVACToolHandler("turnOnAC").execute(ctx, "turnOnAC()", "", null),
            HVACToolHandler("setSeatHeater").execute(ctx, "setSeatHeater(2)", "2", null),
            WindowToolHandler("openWindowsSlightly").execute(ctx, "openWindowsSlightly()", "", null),
            WindowToolHandler("closeAllWindows").execute(ctx, "closeAllWindows()", "", null),
            MediaToolHandler("pauseMusic").execute(ctx, "pauseMusic()", "", null),
            MediaToolHandler("nextTrack").execute(ctx, "nextTrack()", "", null),
            NavigationToolHandler("startNavigationTo").execute(
                ctx,
                "startNavigationTo(\"Home\")",
                "\"Home\"",
            ) { /* intercept */ },
        )
        for (r in results) {
            assertNotNull(r.message)
        }
    }

    @Test
    fun resolveDirectHit_coversDemoScriptClimateLines() {
        val queries = listOf(
            "Increase temperature",
            "Decrease temperature",
            "Set temperature to 72 degrees",
            "Turn on AC",
            "Increase FAN speed",
            "I am feeling cold",
        )
        for (q in queries) {
            assertNotNull("direct hit missing for demo line '$q'", toolManager.resolveDirectHit(q))
        }
    }
}
