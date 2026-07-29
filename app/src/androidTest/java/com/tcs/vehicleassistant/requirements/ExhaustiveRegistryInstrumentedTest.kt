package com.tcs.vehicleassistant.requirements

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.core.DirectToolResolver
import com.tcs.vehicleassistant.handlers.ToolHandlerRegistry
import com.tcs.vehicleassistant.support.RegistryTestSupport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-registry instrumented matrix.
 *
 * Handler *execution* is limited to a safe cabin allowlist — executing every CUSTOM_KOTLIN
 * handler (media sessions, telephony, etc.) previously crashed the instrumented process on AAOS.
 */
@RunWith(AndroidJUnit4::class)
class ExhaustiveRegistryInstrumentedTest {

    private lateinit var toolManager: ToolManager
    private lateinit var specs: List<DirectToolResolver.ToolSpec>
    private lateinit var registry: JSONObject

    companion object {
        /** Handlers safe to invoke under instrumentation with intent interception. */
        private val SAFE_EXECUTE_KEYS = setOf(
            "increaseTemperature", "decreaseTemperature", "setTemperature",
            "turnOnAC", "turnOffAC", "increaseFanSpeed", "decreaseFanSpeed",
            "setSeatHeater", "openWindowsSlightly", "closeAllWindows",
            "playMusic", "stopMusic", "pauseMusic", "nextTrack", "prevTrack",
            "startNavigationTo", "handleFeelingCold", "setVolumeLevel",
        )
    }

    @Before
    fun setUp() {
        toolManager = RegistryTestSupport.initializedToolManager()
        specs = RegistryTestSupport.directToolSpecs(toolManager)
        registry = RegistryTestSupport.registryJson()
    }

    @Test
    fun allRegistryToolsLoadIntoToolManager() {
        val arr = registry.getJSONArray("tools")
        val expected = (0 until arr.length()).map {
            arr.getJSONObject(it).getString("handler_key")
        }.toSet()
        val loaded = toolManager.getAllTools().keys
        assertTrue("registry tools=$expected loaded=$loaded", loaded.containsAll(expected))
        assertTrue("expected ~91 tools, got ${loaded.size}", loaded.size >= 90)
    }

    @Test
    fun everyDirectKeywordResolves() {
        val scenarios = RegistryTestSupport.buildDirectScenarios(specs)
        assertTrue("expected exhaustive direct matrix >= 80, got ${scenarios.size}", scenarios.size >= 80)
        val failures = mutableListOf<String>()
        for (s in scenarios) {
            val outcome = DirectToolResolver.resolve(s.query, specs)
            if (outcome !is DirectToolResolver.Outcome.Execute) {
                failures += "${s.query} → $outcome (want ${s.toolId})"
                continue
            }
            if (outcome.hit.toolId != s.toolId) {
                failures += "${s.query} → ${outcome.hit.toolId} (want ${s.toolId})"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun everyCustomKotlinHandlerIsRegistered() {
        val arr = registry.getJSONArray("tools")
        val missing = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("handler_type") != "CUSTOM_KOTLIN") continue
            val key = o.getString("handler_key")
            val def = toolManager.getAllTools()[key]
            if (def == null) {
                missing += "no ToolDefinition for $key"
                continue
            }
            if (ToolHandlerRegistry.getHandler(key, def) == null) {
                missing += "no handler impl for $key"
            }
        }
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun safeCabinHandlersExecuteWithoutCrashing() = runBlocking {
        val ctx = RegistryTestSupport.appContext()
        val failures = mutableListOf<String>()
        val calls = listOf(
            "increaseTemperature()" to "increaseTemperature",
            "decreaseTemperature()" to "decreaseTemperature",
            "setTemperature(72)" to "setTemperature",
            "turnOnAC()" to "turnOnAC",
            "setSeatHeater(2)" to "setSeatHeater",
            "openWindowsSlightly()" to "openWindowsSlightly",
            "closeAllWindows()" to "closeAllWindows",
            "playMusic(arijit singh)" to "playMusic",
            "stopMusic()" to "stopMusic",
            "pauseMusic()" to "pauseMusic",
            "startNavigationTo(\"Tokyo Tower\")" to "startNavigationTo",
            "handleFeelingCold()" to "handleFeelingCold",
            "setVolumeLevel(up)" to "setVolumeLevel",
        )
        for ((call, key) in calls) {
            if (key !in SAFE_EXECUTE_KEYS) continue
            try {
                val msg = toolManager.executeToolCall(ctx, call) { /* intercept */ }
                assertNotNull("$key returned null", msg)
                assertTrue("$key blank message", msg.isNotBlank())
            } catch (e: Exception) {
                failures += "$key threw ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun confirmationToolsAreNotDirectExecutableWithoutGate() {
        val arr = registry.getJSONArray("tools")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!o.optBoolean("requires_confirmation", false)) continue
            if (!o.optBoolean("direct_executable", false)) continue
            val kws = o.optJSONArray("keywords") ?: continue
            if (kws.length() == 0) continue
            val kw = (0 until kws.length()).map { kws.getString(it) }
                .filter { it.length >= 5 }
                .maxByOrNull { it.length } ?: continue
            val outcome = DirectToolResolver.resolve(kw, specs)
            assertTrue(
                "confirmation tool ${o.getString("handler_key")} must Skip direct exec for '$kw', got $outcome",
                outcome is DirectToolResolver.Outcome.Skip,
            )
        }
    }

    @Test
    fun playMusicNeverCollapsesArtistToGeneric() {
        val hit = toolManager.resolveDirectHit("play arijit singh music")
        assertNotNull(hit)
        assertTrue(hit!!.toolCall.contains("arijit", ignoreCase = true))
        assertFalse(hit.toolCall.equals("playMusic(music)", ignoreCase = true))
    }

    @Test
    fun navigateMeToResolvesCorrectDestination() {
        val hit = toolManager.resolveDirectHit("Navigate me to Tokyo Tower")
        assertNotNull(hit)
        assertEqualsTool(hit!!.toolCall)
        assertTrue(hit.toolCall.contains("Tokyo Tower", ignoreCase = true))
        assertFalse(hit.toolCall.contains("me to", ignoreCase = true))
    }

    private fun assertEqualsTool(toolCall: String) {
        assertTrue(toolCall.startsWith("startNavigationTo"))
    }
}
