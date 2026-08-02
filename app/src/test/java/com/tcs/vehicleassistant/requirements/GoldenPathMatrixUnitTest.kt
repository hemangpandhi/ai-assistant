package com.tcs.vehicleassistant.requirements

import com.tcs.vehicleassistant.core.DirectToolResolver
import com.tcs.vehicleassistant.utils.FollowUpRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Golden path matrix: top demo / use-case phrases → expected DirectTool id (or FollowUp tool).
 *
 * Sources: docs/demo_script.md, docs/use_cases/use-cases.md, WOW (cabin subset), registry.
 */
class GoldenPathMatrixUnitTest {

    private val specs get() = RegistryFixture.toSpecs()

    private fun expectDirect(query: String, toolId: String, argContains: String? = null) {
        val outcome = DirectToolResolver.resolve(query, specs)
        assertTrue(
            "expected DirectTool Execute for '$query' → $toolId, got $outcome",
            outcome is DirectToolResolver.Outcome.Execute,
        )
        val hit = (outcome as DirectToolResolver.Outcome.Execute).hit
        assertEquals("tool for '$query'", toolId, hit.toolId)
        if (argContains != null) {
            assertTrue(
                "args for '$query' should contain '$argContains', got ${hit.toolCall}",
                hit.toolCall.contains(argContains, ignoreCase = true),
            )
        }
    }

    private fun expectSkip(query: String) {
        val outcome = DirectToolResolver.resolve(query, specs)
        assertTrue(
            "expected DirectTool Skip for '$query', got $outcome",
            outcome is DirectToolResolver.Outcome.Skip,
        )
    }

    @Test
    fun hvacDemoPhrases() {
        expectDirect("Increase temperature", "increaseTemperature")
        expectDirect("Decrease temperature", "decreaseTemperature")
        expectDirect("Set temperature to 72 degrees", "setTemperature", "72")
        expectDirect("Turn on AC", "turnOnAC")
        expectDirect("Turn off AC", "turnOffAC")
        expectDirect("turn off the AC", "turnOffAC")
        expectDirect("AC off", "turnOffAC")
        // ASR/UI variants seen on device: "A/C" slash form and "Done" for "Turn".
        expectDirect("Turn on the A/C.", "turnOnAC")
        expectDirect("Turn off the A/C.", "turnOffAC")
        expectDirect("Done off the AC.", "turnOffAC")
        // ASR stutter must not force LLM (query_word_count) — collapse then DirectTool.
        expectDirect(
            "turn on AC turn on AC turn on AC turn on AC turn on AC turn on",
            "turnOnAC",
        )
        expectDirect(
            "play music play music play music play music play music play music play music",
            "playMusic",
        )
        expectDirect("Increase FAN speed", "increaseFanSpeed")
        expectDirect("Decrease FAN speed", "decreaseFanSpeed")
        expectDirect("I am feeling hot", "decreaseTemperature")
        expectDirect("I am feeling cold", "handleFeelingCold")
        expectDirect("clear windshield", "turnOnDefroster")
        expectDirect("turn on climate control", "turnOnHvacPower")
        expectDirect("open climate screen", "openClimateScreen")
        expectDirect("show climate panel", "openClimateScreen")
        expectDirect("open climate", "openClimateScreen")
        expectDirect("open vehicle screen", "openVehicleScreen")
        expectDirect("what's my charging level", "openVehicleScreen")
        expectDirect("is the car charging", "openVehicleScreen")
        expectDirect("battery level", "openVehicleScreen")
    }

    @Test
    fun mediaDemoPhrases() {
        expectDirect("Play music by Adele", "playMusic", "adele")
        expectDirect("Play YOASOBI", "playMusic", "yoasobi")
        expectDirect("play arijit singh music", "playMusic", "arijit")
        expectDirect("pause music", "pauseMusic")
        expectDirect("stop music", "stopMusic")
    }

    @Test
    fun navDemoPhrases() {
        expectDirect("Navigate me to Tokyo Tower", "startNavigationTo", "Tokyo Tower")
        expectDirect("go to Tokyo Skytree", "startNavigationTo", "Skytree")
        expectDirect("Take me to the Skytree", "startNavigationTo", "Skytree")
    }

    @Test
    fun volumeAndWindows() {
        expectDirect("increase volume", "setVolumeLevel", "up")
        expectDirect("decrease volume", "setVolumeLevel", "down")
        expectDirect("open windows", "openWindowsSlightly")
        expectDirect("close windows", "closeAllWindows")
    }

    @Test
    fun confirmationGatedCabin_skipsDirectTool() {
        // Registry marks these non-direct / confirm — LLM or explicit confirm path.
        expectSkip("unlock doors")
        expectSkip("lock doors")
        expectSkip("mute volume")
        expectSkip("resume music")
    }

    @Test
    fun followUpGoldenPaths() {
        assertEquals(
            "setSeatHeater(2)",
            FollowUpRouter.resolveDirectTool("yes", "Would you like me to turn on the seat heater?"),
        )
        assertEquals(
            "searchNearby(gas)",
            FollowUpRouter.resolveDirectTool("yes", "Should I find a nearby gas station?"),
        )
        val last =
            "I found these options nearby: 1. Sensō-ji Temple, 2. Tokyo Skytree, 3. Meiji Shrine. Which one?"
        assertTrue(FollowUpRouter.resolveDirectTool("the second one", last)!!.contains("Skytree"))
    }
}

/**
 * Compact parameterized lock for high-traffic cabin phrases that must DirectTool.
 */
@RunWith(Parameterized::class)
class GoldenPathParameterizedTest(
    private val query: String,
    private val toolId: String,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} → {1}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf("warmer", "increaseTemperature"),
            arrayOf("cooler", "decreaseTemperature"),
            arrayOf("turn on climate", "turnOnHvacPower"),
            arrayOf("turn on climate control", "turnOnHvacPower"),
            arrayOf("set airflow to face", "setAirflowDirection"),
            arrayOf("what is the weather", "getWeather"),
            arrayOf("who are you", "answerVehicleIdentity"),
            arrayOf("latest news", "getNewsHighlights"),
            arrayOf("turn on front defroster", "turnOnDefroster"),
            arrayOf("stop music", "stopMusic"),
            arrayOf("volume up", "setVolumeLevel"),
            arrayOf("louder", "setVolumeLevel"),
            arrayOf("quieter", "setVolumeLevel"),
        )
    }

    @Test
    fun resolves() {
        val outcome = DirectToolResolver.resolve(query, RegistryFixture.toSpecs())
        assertTrue("$query → $outcome", outcome is DirectToolResolver.Outcome.Execute)
        assertEquals(toolId, (outcome as DirectToolResolver.Outcome.Execute).hit.toolId)
    }
}
