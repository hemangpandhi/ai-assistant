package com.tcs.vehicleassistant.requirements

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tcs.vehicleassistant.MemoryManager
import com.tcs.vehicleassistant.core.DirectToolResolver
import com.tcs.vehicleassistant.core.TtsVoiceCatalog
import com.tcs.vehicleassistant.support.RegistryTestSupport
import com.tcs.vehicleassistant.utils.FollowUpRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cabin / demo use-case matrix from docs/use_cases/use-cases.md, demo_script.md,
 * and WOW_USE_CASES.md — exercised via DirectToolResolver + FollowUpRouter (no live LLM).
 *
 * Note: AndroidJUnit4 requires @Test methods to return Unit (void), not Hit.
 */
@RunWith(AndroidJUnit4::class)
class UseCaseScenarioInstrumentedTest {

    private lateinit var specs: List<DirectToolResolver.ToolSpec>

    @Before
    fun setUp() {
        MemoryManager.clearMemory()
        specs = RegistryTestSupport.directToolSpecs()
    }

    private fun assertTool(query: String, expectedId: String): DirectToolResolver.Hit {
        val outcome = DirectToolResolver.resolve(query, specs)
        assertTrue("expected Execute for '$query', got $outcome", outcome is DirectToolResolver.Outcome.Execute)
        val hit = (outcome as DirectToolResolver.Outcome.Execute).hit
        assertEquals("query='$query'", expectedId, hit.toolId)
        return hit
    }

    // --- use-cases.md §1 HVAC ---

    @Test fun increaseTemperature() { assertTool("Increase temperature", "increaseTemperature") }
    @Test fun decreaseTemperature() { assertTool("Decrease temperature", "decreaseTemperature") }
    @Test fun setTemperature72() {
        val hit = assertTool("Set temperature to 72 degrees", "setTemperature")
        assertEquals("setTemperature(72)", hit.toolCall)
    }
    @Test fun feelingCold() {
        val hit = assertTool("I am feeling cold", "handleFeelingCold")
        assertTrue(
            hit.spokenResponse.contains("seat heater", ignoreCase = true) ||
                hit.spokenResponse.contains("?"),
        )
    }
    @Test fun feelingHot_coolDown() { assertTool("I am feeling hot", "decreaseTemperature") }
    @Test fun turnOnAc() { assertTool("Turn on AC", "turnOnAC") }
    @Test fun increaseFan() { assertTool("Increase FAN speed", "increaseFanSpeed") }
    @Test fun decreaseFan() { assertTool("Decrease FAN speed", "decreaseFanSpeed") }
    @Test fun defrostWindshield() { assertTool("clear windshield", "turnOnDefroster") }

    // --- Wellness / seating ---

    @Test fun seatHeater() {
        val hit = assertTool("Turn on the seat heater", "setSeatHeater")
        assertTrue(hit.toolCall.startsWith("setSeatHeater("))
    }

    // --- Media (use-cases §6 / WOW §3) ---

    @Test fun pauseMusic() { assertTool("pause the music", "pauseMusic") }
    @Test fun stopMusic() { assertTool("stop music", "stopMusic") }
    @Test fun nextSong() { assertTool("next song", "nextTrack") }
    @Test fun volumeUp() { assertTool("volume up", "setVolumeLevel") }

    // --- Windows ---

    @Test fun openWindows() { assertTool("Open the windows", "openWindowsSlightly") }
    @Test fun closeWindows() { assertTool("Close the windows", "closeAllWindows") }

    // --- Navigation (registry keyword is "navigate to", not "navigate me to") ---

    @Test fun navigateTokyoTower() {
        val hit = assertTool("Navigate me to Tokyo Tower", "startNavigationTo")
        assertTrue(
            hit.toolCall.contains("Tokyo Tower", ignoreCase = true),
        )
        assertTrue(!hit.toolCall.contains("me to", ignoreCase = true))
    }

    // --- Follow-ups (demo_script Scene 2/3, use-cases §5) ---

    @Test
    fun feelingCold_yes_turnsOnSeatHeater() {
        val cold = assertTool("I am feeling cold", "handleFeelingCold")
        val followUp = FollowUpRouter.resolveDirectTool("yes", cold.spokenResponse)
        assertEquals("setSeatHeater(2)", followUp)
    }

    @Test
    fun gasStationAffirmative() {
        val tool = FollowUpRouter.resolveDirectTool("yes", "Should I find a nearby gas station?")
        assertEquals("searchNearby(gas)", tool)
    }

    @Test
    fun numberedListPick_secondOne() {
        val last = "I found these options nearby: 1. Sensō-ji Temple, 2. Tokyo Skytree, 3. Meiji Shrine. Which one?"
        val tool = FollowUpRouter.resolveDirectTool("the second one", last)
        assertNotNull(tool)
        assertTrue(tool!!.contains("Skytree") || tool.contains("Tokyo"))
    }

    @Test
    fun drowsyDriverFollowUp() {
        assertEquals(
            "handleDrowsyDriving()",
            FollowUpRouter.resolveDirectTool("the driver is falling asleep", ""),
        )
    }

    // --- TTS settings ---

    @Test
    fun ttsBundledAmyAvailableOnDevice() {
        val voices = TtsVoiceCatalog.availableVoices(RegistryTestSupport.appContext())
        assertTrue("expected bundled Amy voice on device, got $voices", voices.isNotEmpty())
        val amy = TtsVoiceCatalog.bundledAmy()
        assertTrue(amy.id.contains("amy", ignoreCase = true) || amy.fromAssets)
        val resolved = TtsVoiceCatalog.findById(RegistryTestSupport.appContext(), amy.id)
        assertEquals(amy.id, resolved.id)
    }

    @Test
    fun llmAllUseCases_directTriggers_smoke() {
        val cases = listOf(
            "increase temperature" to "increaseTemperature",
            "decrease temperature" to "decreaseTemperature",
            "turn on ac" to "turnOnAC",
            "turn off ac" to "turnOffAC",
            "increase fan" to "increaseFanSpeed",
            "decrease fan" to "decreaseFanSpeed",
            "turn on front defroster" to "turnOnDefroster",
            "close windows" to "closeAllWindows",
            "open windows slightly" to "openWindowsSlightly",
            "feeling cold" to "handleFeelingCold",
            "pause music" to "pauseMusic",
            "stop music" to "stopMusic",
            "next song" to "nextTrack",
            "previous song" to "prevTrack",
            "warm my seat" to "setSeatHeater",
        )
        for ((query, expected) in cases) {
            assertTool(query, expected)
        }
    }
}
