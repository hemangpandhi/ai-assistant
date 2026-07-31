package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

class EdgeLocalHardeningPhase2Test {

    @Test
    fun vhalAreaResolver_doesNotInventIdsWhenEmpty() {
        assertTrue(VhalAreaResolver.filterByZone(intArrayOf(), "all").isEmpty())
        assertTrue(VhalAreaResolver.filterByZone(intArrayOf(), "driver").isEmpty())
    }

    @Test
    fun vhalAreaResolver_filtersDriverPassengerBits_orFallsBackToConfigAreas() {
        val areas = intArrayOf(49, 68) // ROW_1_LEFT=1|..., ROW_1_RIGHT includes bit 4 in AOSP demo
        // 49 = 0b110001 → has bit 1; 68 = 0b1000100 → has bit 4
        val driver = VhalAreaResolver.filterByZone(areas, "driver")
        val passenger = VhalAreaResolver.filterByZone(areas, "passenger")
        assertTrue(driver.isNotEmpty())
        assertTrue(passenger.isNotEmpty())
        assertEquals(areas.toList(), VhalAreaResolver.filterByZone(areas, "all"))
    }

    @Test
    fun vhalAreaResolver_unknownOemAreas_useAllConfigIds() {
        // Areas without seat bitmasks still resolve to the config list (no invented 49/68).
        val oem = intArrayOf(1001, 1002)
        assertEquals(oem.toList(), VhalAreaResolver.filterByZone(oem, "driver"))
    }

    @Test
    fun directToolPolicy_usesRegistryFanMaxForMaxUtterance() {
        val tool = DirectToolResolver.ToolSpec(
            id = "setFanSpeed",
            handlerKey = "setFanSpeed",
            promptString = "<TOOL>setFanSpeed(LEVEL)</TOOL>",
            keywords = listOf("set fan to max", "fan maximum", "max fan"),
            successMessage = "Fan max.",
            requiresConfirmation = false,
            requiresAgenticLoop = false,
            directExecutable = true,
        )
        val policy = DirectToolResolver.Policy(fanMax = 9, minKeywordChars = 3)
        val outcome = DirectToolResolver.resolve("max fan", listOf(tool), policy)
        assertTrue(outcome is DirectToolResolver.Outcome.Execute)
        assertEquals(
            "setFanSpeed(9)",
            (outcome as DirectToolResolver.Outcome.Execute).hit.toolCall,
        )
    }

    @Test
    fun localPromptFewShots_filterMissingTools() {
        val shots = listOf(
            LocalLlmPromptSupport.FewShot("pause music", "<TOOL>pauseMusic()</TOOL> Pausing."),
            LocalLlmPromptSupport.FewShot("teleport", "<TOOL>teleportHome()</TOOL> Gone."),
        )
        val filtered = LocalLlmPromptSupport.filterByAvailableTools(
            shots,
            setOf("pauseMusic", "playMusic"),
        )
        assertEquals(1, filtered.size)
        assertEquals("pause music", filtered.single().user)
        val formatted = LocalLlmPromptSupport.formatFewShots(filtered)
        assertTrue(formatted.contains("pauseMusic()"))
        assertTrue(!formatted.contains("teleportHome"))
    }

    @Test
    fun registry_declaresDirectExecutionLimitsAndFewShots() {
        val file = File("src/main/assets/vehicle_skills_registry.json")
        assertTrue(file.exists())
        val config = JSONObject(file.readText()).getJSONObject("config")
        val de = config.getJSONObject("direct_execution")
        assertEquals(7, de.getInt("fan_max"))
        assertEquals(100, de.getInt("volume_max"))
        assertEquals(2, de.getInt("seat_heater_on_default"))
        assertTrue(config.getJSONArray("llm_few_shots").length() >= 6)
        var foundWellnessChatShot = false
        val few = config.getJSONArray("llm_few_shots")
        for (i in 0 until few.length()) {
            val shot = few.getJSONObject(i)
            val user = shot.getString("user").lowercase()
            val assistant = shot.getString("assistant")
            if (user.contains("not feeling good") && !assistant.contains("<TOOL>", ignoreCase = true)) {
                foundWellnessChatShot = true
            }
        }
        assertTrue("expected a chat-only wellness few-shot", foundWellnessChatShot)

        val rules = config.getJSONObject("context_policies").getJSONArray("rules")
        var foundCompare = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.getString("id") != "fan_already_max") continue
            val sensors = rule.getJSONObject("when").getJSONArray("sensors")
            val s0 = sensors.getJSONObject(0)
            assertEquals("fan_max", s0.getString("compare_to"))
            foundCompare = true
        }
        assertTrue(foundCompare)
    }

    @Test
    fun contextGuard_compareToFanMax_blocks() {
        ContextGuard.replaceRulesForTest(
            listOf(
                ContextGuard.PolicyRule(
                    id = "fan_already_max",
                    appliesTo = listOf("increaseFanSpeed"),
                    argMatches = emptyList(),
                    sensors = listOf(
                        ContextGuard.SensorCondition(
                            source = "fan_level",
                            op = ">=",
                            value = null,
                            compareTo = "fan_max",
                        ),
                    ),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.BLOCK,
                    message = "The fan is already at maximum (level {fan_level}).",
                    priority = 10,
                ),
            ),
        )
        try {
            val snap = CabinSnapshot(
                mediaVolumePct = 10,
                mediaPlaying = false,
                fanLevel = 5,
                fanMax = 5,
                cabinTempF = 72,
                seatHeaterLevel = 0,
                acOn = false,
                hvacPowerOn = true,
                defrostOn = false,
                speedMph = 0,
                gear = "Park",
            )
            val d = ContextGuard.evaluate("increaseFanSpeed()", snap)
            assertTrue(d is ContextGuard.Decision.Block)
        } finally {
            ContextGuard.clearRulesForTest()
        }
    }
}
