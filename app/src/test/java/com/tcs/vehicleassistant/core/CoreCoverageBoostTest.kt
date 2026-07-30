package com.tcs.vehicleassistant.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ContextGuardLoadAndEscalateTest {

    private fun baseSnap() = CabinSnapshot(
        mediaVolumePct = 50,
        mediaPlaying = false,
        fanLevel = 2,
        cabinTempF = 70,
        seatHeaterLevel = 0,
        acOn = true,
        hvacPowerOn = true,
        defrostOn = false,
        speedMph = 0,
        gear = "Park",
        isParked = true,
        fuelLevelPct = 40,
        city = "Tokyo",
    )

    @After
    fun tearDown() {
        ContextGuard.clearRulesForTest()
    }

    @Test
    fun loadFromConfig_nullClearsRules() {
        ContextGuard.loadFromConfig(null)
        assertTrue(ContextGuard.enabled)
        assertTrue(ContextGuard.evaluate("setVolumeLevel(up)", baseSnap()) is ContextGuard.Decision.Allow)
    }

    @Test
    fun loadFromConfig_parsesEscalateAndConfirm() {
        val json = JSONObject(
            """
            {
              "context_policies": {
                "enabled": true,
                "rules": [
                  {
                    "id": "ask_llm",
                    "applies_to": ["searchNearby"],
                    "action": "escalate",
                    "message": "Want me to search nearby?",
                    "priority": 1,
                    "when": {}
                  },
                  {
                    "id": "loud",
                    "applies_to": ["setVolumeLevel"],
                    "action": "confirm",
                    "message": "Loud at {media_volume_pct}%?",
                    "priority": 2,
                    "when": {
                      "media_playing": true,
                      "sensors": [{"source":"media_volume_pct","op":">=","value":80}]
                    }
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        ContextGuard.loadFromConfig(json)
        assertTrue(ContextGuard.enabled)
        val esc = ContextGuard.evaluate("searchNearby(pizza)", baseSnap())
        assertTrue("expected Escalate, got $esc", esc is ContextGuard.Decision.Escalate)
        val loud = baseSnap().copy(mediaVolumePct = 90, mediaPlaying = true)
        val conf = ContextGuard.evaluate("setVolumeLevel(up)", loud)
        assertTrue(conf is ContextGuard.Decision.Confirm)
    }

    @Test
    fun disabledPoliciesAlwaysAllow() {
        ContextGuard.replaceRulesForTest(
            listOf(
                ContextGuard.PolicyRule(
                    id = "x",
                    appliesTo = listOf("openTrunk"),
                    argMatches = emptyList(),
                    sensors = emptyList(),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.BLOCK,
                    message = "no",
                    priority = 1,
                ),
            ),
            policiesEnabled = false,
        )
        assertTrue(ContextGuard.evaluate("openTrunk()", baseSnap().copy(speedMph = 40)) is ContextGuard.Decision.Allow)
    }

    @Test
    fun sensorOps_coverComparisons() {
        ContextGuard.replaceRulesForTest(
            listOf(
                ContextGuard.PolicyRule(
                    id = "eq",
                    appliesTo = listOf("increaseFanSpeed"),
                    argMatches = emptyList(),
                    sensors = listOf(ContextGuard.SensorCondition("fan_level", "==", 7.0)),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.BLOCK,
                    message = "max",
                    priority = 1,
                ),
                ContextGuard.PolicyRule(
                    id = "lte",
                    appliesTo = listOf("startNavigationTo"),
                    argMatches = emptyList(),
                    sensors = listOf(ContextGuard.SensorCondition("fuel_level_pct", "<=", 15.0)),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.CONFIRM,
                    message = "low fuel {fuel_level_pct}",
                    priority = 2,
                ),
            ),
        )
        assertTrue(
            ContextGuard.evaluate("increaseFanSpeed()", baseSnap().copy(fanLevel = 7))
                is ContextGuard.Decision.Block,
        )
        assertTrue(
            ContextGuard.evaluate("increaseFanSpeed()", baseSnap().copy(fanLevel = 3))
                is ContextGuard.Decision.Allow,
        )
        val low = ContextGuard.evaluate(
            "startNavigationTo(\"airport\")",
            baseSnap().copy(fuelLevelPct = 10),
        )
        assertTrue(low is ContextGuard.Decision.Confirm)
    }
}

class CabinFuelNormalizeTest {
    @Test
    fun normalizeFuelLevelPct() {
        assertEquals(-1, CabinSnapshot.normalizeFuelLevelPct(Float.NaN))
        assertEquals(-1, CabinSnapshot.normalizeFuelLevelPct(-1f))
        assertEquals(50, CabinSnapshot.normalizeFuelLevelPct(0.5f))
        assertEquals(80, CabinSnapshot.normalizeFuelLevelPct(80f))
        // Absolute volume without capacity must not be inventively clamped to 100%.
        assertEquals(-1, CabinSnapshot.normalizeFuelLevelPct(150f))
        assertEquals(-1, CabinSnapshot.normalizeFuelLevelPct(45000f))
    }
}

class TtsVoiceCatalogSideloadTest {
    @Test
    fun bundledAmy_andSettingsHint() {
        val amy = TtsVoiceCatalog.bundledAmy()
        assertEquals(TtsVoiceCatalog.BUNDLED_AMY_ID, amy.id)
        assertFalse(amy.isMultiSpeaker)
        val hint = TtsVoiceCatalog.settingsHint(listOf(amy))
        assertTrue(hint.contains("Only Amy"))
    }

    @Test
    fun scanSideloadRoot_discoversPack() {
        val root = createTempDirectory("tts-test").toFile()
        try {
            val pack = File(root, "lessac_medium").apply { mkdirs() }
            File(pack, "model.onnx").writeText("onnx")
            File(pack, "tokens.txt").writeText("tok")
            File(pack, "model.onnx.json").writeText(
                """{"num_speakers":2,"audio":{"sample_rate":22050}}""",
            )
            val voices = TtsVoiceCatalog.scanSideloadRoot(root)
            assertEquals(1, voices.size)
            assertEquals("lessac-medium", voices[0].id)
            assertTrue(voices[0].isMultiSpeaker)
            assertEquals(22_050, voices[0].sampleRateHint)
            val hint = TtsVoiceCatalog.settingsHint(listOf(TtsVoiceCatalog.bundledAmy()) + voices)
            assertTrue(hint.contains("sideloaded"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class DirectToolAmenityCityTest {
    @Test
    fun extractCityAndAmenity() {
        assertEquals("tokyo", DirectToolResolver.extractCityArg("weather in tokyo"))
        assertEquals(null, DirectToolResolver.extractCityArg("what is the weather"))
        assertEquals("gas", DirectToolResolver.extractAmenityArg("gas station"))
        assertEquals("pizza", DirectToolResolver.extractAmenityArg("nearby pizza"))
        assertEquals("restaurant", DirectToolResolver.extractAmenityArg("i am hungry"))
    }
}
