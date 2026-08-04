package com.tcs.vehicleassistant.core

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.tcs.vehicleassistant.DemoSettingsPresets
import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.handlers.ParameterParser
import com.tcs.vehicleassistant.handlers.ToolExecutionResult
import com.tcs.vehicleassistant.handlers.VolumeLevelResolver
import com.tcs.vehicleassistant.handlers.WeatherApiClient
import com.tcs.vehicleassistant.utils.FollowUpRouter
import com.tcs.vehicleassistant.utils.ToolCallParser
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Branch-heavy gap fill for pure / lightly-Android logic that JaCoCo still missed after the
 * overnight suite (FollowUpRouter named destinations, ContextGuard ops, TTS sideload flat layout,
 * Memory long-term facts, VolumeLevelResolver edge feedback, ParameterParser).
 */
class CoverageGapFillJvmTest {

    private fun snap(
        mediaPlaying: Boolean = false,
        volume: Int = 40,
        fan: Int = 3,
        fuel: Int = 50,
        nav: String? = null,
        speed: Int = 0,
        gear: String = "Park",
    ) = CabinSnapshot(
        mediaVolumePct = volume,
        mediaPlaying = mediaPlaying,
        fanLevel = fan,
        cabinTempF = 70,
        seatHeaterLevel = 0,
        acOn = true,
        hvacPowerOn = true,
        hvacAutoOn = false,
        defrostOn = false,
        speedMph = speed,
        gear = gear,
        fuelLevelPct = fuel,
        windowOpenPct = 0,
        navActiveDest = nav,
        city = "Tokyo",
        latitude = 35.6,
        longitude = 139.7,
    )

    @After
    fun clearGuard() {
        ContextGuard.clearRulesForTest()
    }

    @Test
    fun contextGuard_sensorOps_gt_lt_neq_andUnknown() {
        ContextGuard.replaceRulesForTest(
            listOf(
                ContextGuard.PolicyRule(
                    id = "gt",
                    appliesTo = listOf("setVolumeLevel"),
                    argMatches = emptyList(),
                    sensors = listOf(ContextGuard.SensorCondition("media_volume_pct", ">", 80.0)),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.BLOCK,
                    message = "too loud",
                    priority = 1,
                ),
                ContextGuard.PolicyRule(
                    id = "lt",
                    appliesTo = listOf("decreaseFanSpeed"),
                    argMatches = emptyList(),
                    sensors = listOf(ContextGuard.SensorCondition("fan_level", "<", 2.0)),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.CONFIRM,
                    message = "already low",
                    priority = 2,
                ),
                ContextGuard.PolicyRule(
                    id = "neq",
                    appliesTo = listOf("setSeatHeater"),
                    argMatches = emptyList(),
                    sensors = listOf(ContextGuard.SensorCondition("seat_heater_level", "!=", 0.0)),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.ALLOW,
                    message = "ok",
                    priority = 3,
                ),
                ContextGuard.PolicyRule(
                    id = "bogus",
                    appliesTo = listOf("openTrunk"),
                    argMatches = emptyList(),
                    sensors = listOf(ContextGuard.SensorCondition("speed_mph", "~~", 1.0)),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.BLOCK,
                    message = "never",
                    priority = 4,
                ),
            ),
        )
        assertTrue(
            ContextGuard.evaluate("setVolumeLevel(up)", snap(volume = 90)) is ContextGuard.Decision.Block,
        )
        assertTrue(
            ContextGuard.evaluate("setVolumeLevel(up)", snap(volume = 80)) is ContextGuard.Decision.Allow,
        )
        assertTrue(
            ContextGuard.evaluate("decreaseFanSpeed()", snap(fan = 1)) is ContextGuard.Decision.Confirm,
        )
        assertTrue(
            ContextGuard.evaluate("setSeatHeater(2)", snap().copy(seatHeaterLevel = 2))
                is ContextGuard.Decision.Allow,
        )
        // Unknown op never matches → Allow fall-through.
        assertTrue(ContextGuard.evaluate("openTrunk()", snap()) is ContextGuard.Decision.Allow)
    }

    @Test
    fun contextGuard_navDestMatchesAndDiffers() {
        ContextGuard.replaceRulesForTest(
            listOf(
                ContextGuard.PolicyRule(
                    id = "same_dest",
                    appliesTo = listOf("startNavigationTo"),
                    argMatches = emptyList(),
                    sensors = emptyList(),
                    requireMediaPlaying = null,
                    requireNavActive = true,
                    requireNavDestMatchesArg = true,
                    action = ContextGuard.Action.BLOCK,
                    message = "already going to {nav_active_dest}",
                    priority = 1,
                ),
                ContextGuard.PolicyRule(
                    id = "diff_dest",
                    appliesTo = listOf("startNavigationTo"),
                    argMatches = emptyList(),
                    sensors = emptyList(),
                    requireMediaPlaying = null,
                    requireNavActive = true,
                    requireNavDestDiffersArg = true,
                    action = ContextGuard.Action.CONFIRM,
                    message = "reroute from {nav_active_dest}?",
                    priority = 2,
                ),
            ),
        )
        val withNav = snap(nav = "Tokyo Tower")
        assertTrue(
            ContextGuard.evaluate("startNavigationTo(\"Tokyo Tower\")", withNav)
                is ContextGuard.Decision.Block,
        )
        val differ = ContextGuard.evaluate("startNavigationTo(\"Skytree\")", withNav)
        assertTrue("expected Confirm, got $differ", differ is ContextGuard.Decision.Confirm)
    }

    @Test
    fun contextGuard_argMatchesUpDownAndEqualsOp() {
        ContextGuard.replaceRulesForTest(
            listOf(
                ContextGuard.PolicyRule(
                    id = "vol_up",
                    appliesTo = listOf("setVolumeLevel"),
                    argMatches = listOf("up"),
                    sensors = listOf(ContextGuard.SensorCondition("media_volume_pct", "=", 50.0)),
                    requireMediaPlaying = true,
                    action = ContextGuard.Action.CONFIRM,
                    message = "confirm {media_volume_pct}",
                    priority = 1,
                ),
            ),
        )
        val playing = snap(mediaPlaying = true, volume = 50)
        assertTrue(
            ContextGuard.evaluate("setVolumeLevel(louder)", playing) is ContextGuard.Decision.Confirm,
        )
        assertTrue(
            ContextGuard.evaluate("setVolumeLevel()", playing) is ContextGuard.Decision.Confirm,
        )
        assertTrue(
            ContextGuard.evaluate("setVolumeLevel(down)", playing) is ContextGuard.Decision.Allow,
        )
    }

    @Test
    fun contextGuard_loadParsesAdjustAsBlockAndAllowDefault() {
        val json = JSONObject(
            """
            {
              "context_policies": {
                "enabled": true,
                "rules": [
                  {
                    "id": "adjust_rule",
                    "applies_to": ["setVolumeLevel"],
                    "action": "adjust",
                    "message": "no",
                    "priority": 1,
                    "when": {}
                  },
                  {
                    "id": "allow_rule",
                    "applies_to": ["playMusic"],
                    "action": "allow",
                    "message": "ok",
                    "priority": 2,
                    "when": {}
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        ContextGuard.loadFromConfig(json)
        assertTrue(ContextGuard.evaluate("setVolumeLevel(up)", snap()) is ContextGuard.Decision.Block)
        val allow = ContextGuard.evaluate("playMusic()", snap())
        assertTrue(allow is ContextGuard.Decision.Allow)
        assertEquals("allow_rule", (allow as ContextGuard.Decision.Allow).policyId)
    }

    @Test
    fun cabinSnapshot_sensorAliasesAndDefaults() {
        val s = snap().copy(
            mediaPlaying = true,
            hvacAutoOn = true,
            defrostOn = true,
            seatHeaterLevel = 2,
        )
        assertEquals(40.0, s.sensor("volume")!!, 0.001)
        assertEquals(1.0, s.sensor("playing")!!, 0.001)
        assertEquals(3.0, s.sensor("fan")!!, 0.001)
        assertEquals(7.0, s.sensor("fan_max")!!, 0.001)
        assertEquals(70.0, s.sensor("temp_f")!!, 0.001)
        assertEquals(2.0, s.sensor("seat_heater")!!, 0.001)
        assertEquals(3.0, s.sensor("seat_heater_max")!!, 0.001)
        assertEquals(1.0, s.sensor("ac_on")!!, 0.001)
        assertEquals(1.0, s.sensor("hvac_power_on")!!, 0.001)
        assertEquals(1.0, s.sensor("hvac_auto_on")!!, 0.001)
        assertEquals(1.0, s.sensor("defrost_on")!!, 0.001)
        assertEquals(0.0, s.sensor("speed")!!, 0.001)
        assertEquals(1.0, s.sensor("parked")!!, 0.001)
        assertEquals(0.0, s.sensor("window_open_pct")!!, 0.001)
        assertEquals(139.7, s.sensor("lng")!!, 0.001)
        assertEquals(139.7, s.sensor("lon")!!, 0.001)
        assertEquals(7, CabinSnapshot.DEFAULT_FAN_MAX)
        assertEquals(
            "yes yes on 0",
            s.interpolate("{is_parked} {media_playing} {hvac_auto_on} {window_open_pct}"),
        )
    }

    @Test
    fun followUpRouter_namedDestinationAndAlarmAndCharging() {
        // Short replies (≤3 words) count as follow-ups; named-destination extraction then runs.
        assertEquals(
            "startNavigationTo(\"Tokyo Skytree\")",
            FollowUpRouter.resolveDirectTool(
                "the Tokyo Skytree",
                "Which place would you like to visit?",
            ),
        )
        assertEquals(
            "startNavigationTo(\"Olive Garden\")",
            FollowUpRouter.resolveDirectTool(
                "yes",
                "Would you like me to navigate to 1. Olive Garden, 2. Mario's?",
            ),
        )
        assertEquals(
            "searchNearby(charging)",
            FollowUpRouter.resolveDirectTool("sure", "Should I find a nearby charging station?"),
        )
        assertTrue(FollowUpRouter.responseRequestsAlarm("""{"action":"sound_alarm"}"""))
        assertTrue(FollowUpRouter.responseRequestsAlarm("""{"action": "sound_alarm"}"""))
        assertTrue(FollowUpRouter.responseRequestsAlarm("please sound_alarm now"))
        assertFalse(FollowUpRouter.responseRequestsAlarm("all clear"))
        assertTrue(FollowUpRouter.isDrowsyDriverQuery("getting sleepy"))
        assertTrue(FollowUpRouter.isDrowsyDriverQuery("feel drowsy"))
        assertEquals(1, FollowUpRouter.resolveListPickIndex("1st option"))
        assertEquals(3, FollowUpRouter.resolveListPickIndex("number three"))
        val parenOptions = FollowUpRouter.extractNumberedOptions("Pick: 1) Alpha, 2) Beta")
        assertEquals(listOf("Alpha", "Beta"), parenOptions)
    }

    @Test
    fun directToolResolver_volumeSeatDirectionAlertBranches() {
        fun tool(
            id: String,
            key: String,
            prompt: String,
            keywords: List<String>,
        ) = DirectToolResolver.ToolSpec(
            id = id,
            handlerKey = key,
            promptString = prompt,
            keywords = keywords,
            successMessage = "ok",
            requiresConfirmation = false,
            requiresAgenticLoop = false,
            directExecutable = true,
        )

        val volume = tool(
            "vol",
            "setVolumeLevel",
            "<TOOL>setVolumeLevel(VAL)</TOOL>",
            listOf("volume up", "mute volume", "volume down", "maximum volume"),
        )
        val seat = tool(
            "seat",
            "setSeatHeater",
            "<TOOL>setSeatHeater(LEVEL)</TOOL>",
            listOf("seat heater", "turn on seat heater", "disable seat heater"),
        )
        val airflow = tool(
            "air",
            "setAirflowDirection",
            "<TOOL>setAirflowDirection(DIRECTION)</TOOL>",
            listOf("airflow face and feet", "air to the floor"),
        )
        val alert = tool(
            "alert",
            "setAlertLevel",
            "<TOOL>setAlertLevel(ALERT_LEVEL)</TOOL>",
            listOf("set alert level"),
        )

        val mute = DirectToolResolver().resolve("mute volume", listOf(volume))
        assertTrue(mute is DirectToolResolver.Outcome.Execute)
        assertEquals("setVolumeLevel(0)", (mute as DirectToolResolver.Outcome.Execute).hit.toolCall)

        val louder = DirectToolResolver().resolve("volume up", listOf(volume))
        assertTrue(louder is DirectToolResolver.Outcome.Execute)

        val maxFan = tool(
            "fan",
            "setFanSpeed",
            "<TOOL>setFanSpeed(LEVEL)</TOOL>",
            listOf("maximum fan"),
        )
        val max = DirectToolResolver().resolve("maximum fan", listOf(maxFan))
        assertTrue(max is DirectToolResolver.Outcome.Execute)
        assertEquals("setFanSpeed(7)", (max as DirectToolResolver.Outcome.Execute).hit.toolCall)

        val seatOn = DirectToolResolver().resolve("turn on seat heater", listOf(seat))
        assertTrue(seatOn is DirectToolResolver.Outcome.Execute)
        assertEquals("setSeatHeater(2)", (seatOn as DirectToolResolver.Outcome.Execute).hit.toolCall)

        val seatOff = DirectToolResolver().resolve("disable seat heater", listOf(seat))
        assertTrue(seatOff is DirectToolResolver.Outcome.Execute)
        assertEquals("setSeatHeater(0)", (seatOff as DirectToolResolver.Outcome.Execute).hit.toolCall)

        val dir = DirectToolResolver().resolve("airflow face and feet", listOf(airflow))
        assertTrue(dir is DirectToolResolver.Outcome.Execute)
        assertEquals(
            "setAirflowDirection(face and floor)",
            (dir as DirectToolResolver.Outcome.Execute).hit.toolCall,
        )

        val alertHit = DirectToolResolver().resolve("set alert level", listOf(alert))
        assertTrue(alertHit is DirectToolResolver.Outcome.Execute)
        assertEquals("setAlertLevel(2)", (alertHit as DirectToolResolver.Outcome.Execute).hit.toolCall)

        assertEquals(
            "arijit singh",
            DirectToolResolver.extractSongArg("play songs by arijit singh music"),
        )
        assertNull(DirectToolResolver.extractSongArg("play music"))
        assertTrue(DirectToolResolver.containsWholePhrase("turn on the ac", "turn on"))
        assertFalse(DirectToolResolver.containsWholePhrase("turnout", "turn on"))
    }

    @Test
    fun volumeLevelResolver_maxMinUnknownAndZeroStep() {
        assertEquals(0, VolumeLevelResolver.relativeStep(0))
        val maxPlan = VolumeLevelResolver.plan("MAX", currentIndex = 5, maxIndex = 20)
        assertEquals(20, maxPlan.targetIndex)
        assertFalse(maxPlan.relative)

        val minFeedback = VolumeLevelResolver.feedback(
            VolumeLevelResolver.plan("down", currentIndex = 0, maxIndex = 20),
            appliedIndex = 0,
        )
        assertTrue(minFeedback.contains("minimum"))

        val already = VolumeLevelResolver.feedback(
            VolumeLevelResolver.Plan(
                targetIndex = 5,
                previousIndex = 5,
                maxIndex = 20,
                relative = false,
                increasing = null,
            ),
            appliedIndex = 5,
        )
        assertTrue(already.contains("already at"))

        val unknown = VolumeLevelResolver.plan("weird", currentIndex = 4, maxIndex = 20)
        assertTrue(unknown.relative)
        assertEquals(5, unknown.targetIndex)

        val decreaseHint = VolumeLevelResolver.plan(
            "weird",
            currentIndex = 4,
            maxIndex = 20,
            toolCall = "decreaseVolume()",
        )
        assertEquals(3, decreaseHint.targetIndex)

        val absoluteIndex = VolumeLevelResolver.plan("3", currentIndex = 1, maxIndex = 20)
        assertEquals(3, absoluteIndex.targetIndex)
        assertFalse(absoluteIndex.relative)
    }

    @Test
    fun parameterParser_andToolExecutionResult() {
        assertEquals(72.5, ParameterParser.extractDouble("temp=72.5"), 0.001)
        assertEquals(0.0, ParameterParser.extractDouble("none"), 0.001)
        assertEquals(3, ParameterParser.extractInt("level 3"))
        assertEquals(0, ParameterParser.extractInt("x"))
        assertEquals("Tokyo", ParameterParser.extractString("\"Tokyo\""))
        assertEquals("fallback", ParameterParser.extractString("  ", "fallback"))
        val result = ToolExecutionResult(success = true, message = "done")
        assertTrue(result.success)
        assertEquals("done", result.message)
    }

    @Test
    fun weatherWmo_coversRemainingCodes() {
        assertEquals("partly cloudy", WeatherApiClient.wmoCondition(2))
        assertEquals("overcast", WeatherApiClient.wmoCondition(3))
        assertEquals("foggy", WeatherApiClient.wmoCondition(45))
        assertEquals("drizzle", WeatherApiClient.wmoCondition(53))
        assertEquals("freezing drizzle", WeatherApiClient.wmoCondition(56))
        assertEquals("freezing rain", WeatherApiClient.wmoCondition(66))
        assertEquals("snow", WeatherApiClient.wmoCondition(73))
        assertEquals("snow grains", WeatherApiClient.wmoCondition(77))
        assertEquals("rain showers", WeatherApiClient.wmoCondition(81))
        assertEquals("snow showers", WeatherApiClient.wmoCondition(85))
        assertEquals("thunderstorms with hail", WeatherApiClient.wmoCondition(99))
        assertEquals("mixed conditions", WeatherApiClient.wmoCondition(1234))
    }

    @Test
    fun weatherParseCurrent_missingCurrentReturnsNull() {
        assertNull(WeatherApiClient.parseCurrent("""{"foo":1}""", "Tokyo"))
        assertNull(WeatherApiClient.parseCurrent("not-json", "Tokyo"))
    }

    @Test
    fun toolCallParser_jsonWithoutArguments() {
        val calls = ToolCallParser.extractToolCalls(
            """<tool_call>{"name": "stopMusic"}</tool_call>""",
        )
        assertEquals(1, calls.size)
        assertEquals("stopMusic", calls.single().toolName)
        assertEquals("", calls.single().args)
    }

    @Test
    fun toolManager_speakableKeywordHelper() {
        assertTrue(ToolManager.isSpeakableDirectKeyword("seat heater"))
        assertFalse(ToolManager.isSpeakableDirectKeyword("a"))
        assertFalse(ToolManager.isSpeakableDirectKeyword("!!!"))
    }

    @Test
    fun ttsVoiceCatalog_flatOnnxLayout_andHumanize() {
        val root = File(System.getProperty("java.io.tmpdir"), "tts-flat-${System.nanoTime()}")
            .also { it.mkdirs() }
        try {
            File(root, "tokens.txt").writeText("t")
            File(root, "en_GB-cori-medium.onnx").writeText("onnx")
            File(root, "mystery-voice.onnx").writeText("onnx")
            File(root, "mystery-voice.tokens.txt").writeText("t2")
            File(root, "broken.onnx").writeText("x") // no tokens → skipped
            File(root, "encoder-model.onnx").writeText("x") // encoder filtered

            val voices = TtsVoiceCatalog.scanSideloadRoot(root)
            val ids = voices.map { it.id }.toSet()
            assertTrue(ids.contains("cori-medium"))
            assertTrue(ids.contains("mystery-voice"))
            assertTrue(voices.first { it.id == "mystery-voice" }.displayName.contains("Mystery"))
            assertTrue(voices.first { it.id == "cori-medium" }.displayName.contains("Cori"))

            // Bad JSON should fall back to defaults without crashing.
            val pack = File(root, "alan-low").also { it.mkdirs() }
            File(pack, "alan.onnx").writeText("x")
            File(pack, "tokens.txt").writeText("t")
            File(pack, "alan.onnx.json").writeText("{not json")
            val alan = TtsVoiceCatalog.scanSideloadRoot(root).first { it.id == "alan-low" }
            assertEquals(1, alan.numSpeakers)

            val hintEmpty = TtsVoiceCatalog.settingsHint(listOf(TtsVoiceCatalog.bundledAmy()))
            assertTrue(hintEmpty.contains("Only Amy"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun demoPreset_findById() {
        assertEquals(DemoSettingsPresets.TOKYO_OEM, DemoSettingsPresets.findById("tokyo_oem"))
        assertNull(DemoSettingsPresets.findById("missing"))
        assertNull(DemoSettingsPresets.findById(null))
        assertEquals(
            DemoSettingsPresets.SAGAMIHARA.coordinates,
            Pair(DemoSettingsPresets.SAGAMIHARA.longitude, DemoSettingsPresets.SAGAMIHARA.latitude),
        )
    }

    @Test
    fun contextGuard_missingPoliciesKeyAndBareToolName() {
        ContextGuard.loadFromConfig(JSONObject("""{"other":true}"""))
        assertTrue(ContextGuard.evaluate("setVolumeLevel", snap()) is ContextGuard.Decision.Allow)
        ContextGuard.replaceRulesForTest(
            listOf(
                ContextGuard.PolicyRule(
                    id = "any",
                    appliesTo = listOf("setVolumeLevel"),
                    argMatches = emptyList(),
                    sensors = emptyList(),
                    requireMediaPlaying = null,
                    action = ContextGuard.Action.CONFIRM,
                    message = "confirm",
                    priority = 1,
                ),
            ),
        )
        // No '(' → empty args path still matches applies_to.
        assertTrue(ContextGuard.evaluate("setVolumeLevel", snap()) is ContextGuard.Decision.Confirm)
    }

    @Test
    fun followUpRouter_nullPicksAndRejectedDestinations() {
        assertNull(FollowUpRouter.resolveListPickIndex("please"))
        assertNull(
            FollowUpRouter.resolveDirectTool(
                "the one",
                "Would you like to visit somewhere?",
            ),
        )
        assertNull(
            FollowUpRouter.resolveDirectTool(
                "navigate to x",
                "Would you like to visit somewhere?",
            ),
        )
    }

    @Test
    fun directToolResolver_cityForecastAndAirflowDefrost() {
        assertEquals("osaka", DirectToolResolver.extractCityArg("forecast for osaka"))
        assertEquals("kyoto", DirectToolResolver.extractCityArg("current weather at kyoto"))
        assertEquals(
            "nagoya",
            DirectToolResolver.extractCityArg("please check the weather in nagoya"),
        )
        val airflow = DirectToolResolver.ToolSpec(
            id = "air",
            handlerKey = "setAirflowDirection",
            promptString = "<TOOL>setAirflowDirection(DIRECTION)</TOOL>",
            keywords = listOf("defrost airflow"),
            successMessage = "ok",
            requiresConfirmation = false,
            requiresAgenticLoop = false,
            directExecutable = true,
        )
        val hit = DirectToolResolver().resolve("defrost airflow", listOf(airflow))
        assertTrue(hit is DirectToolResolver.Outcome.Execute)
        assertEquals(
            "setAirflowDirection(defrost)",
            (hit as DirectToolResolver.Outcome.Execute).hit.toolCall,
        )
        assertEquals("charging", DirectToolResolver.extractAmenityArg("need charging nearby"))
        assertEquals("coffee shop", DirectToolResolver.extractAmenityArg("i want coffee"))
        assertEquals("coffee", DirectToolResolver.extractAmenityArg("find coffee"))
        assertNull(DirectToolResolver.extractSongArg("play something"))
        assertTrue(
            DirectToolResolver().resolve("zzzz", emptyList()) is DirectToolResolver.Outcome.Skip,
        )
        assertTrue(
            DirectToolResolver().resolve(
                "turn on ac",
                emptyList(),
                DirectToolResolver.Policy(enabled = false),
            ) is DirectToolResolver.Outcome.Skip,
        )
    }

    @Test
    fun volumeLevelResolver_absoluteSameIndexAndNegativeDelta() {
        val same = VolumeLevelResolver.plan("25%", currentIndex = 5, maxIndex = 20)
        assertEquals(5, same.targetIndex)
        assertNull(same.increasing)
        val downPct = VolumeLevelResolver.plan("-10%", currentIndex = 10, maxIndex = 20)
        assertTrue(downPct.relative)
        assertEquals(false, downPct.increasing)
        val downIdx = VolumeLevelResolver.plan("-2", currentIndex = 10, maxIndex = 20)
        assertEquals(8, downIdx.targetIndex)
        val zeroDelta = VolumeLevelResolver.plan("+0", currentIndex = 4, maxIndex = 20)
        assertNull(zeroDelta.increasing)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoverageGapFillRobolectricTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var conversationMemory: com.tcs.vehicleassistant.ConversationMemory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        conversationMemory = com.tcs.vehicleassistant.ConversationMemory()
        conversationMemory.clearMemory()
    }

    @Test
    fun memoryManager_capturesAndDedupesLongTermFacts() {
        assertEquals("", conversationMemory.getLongTermMemory(context))
        assertTrue(conversationMemory.captureLongTermFacts(context, "remember that I prefer 72 degrees"))
        assertTrue(conversationMemory.getLongTermMemory(context).contains("I prefer 72 degrees"))
        assertFalse(conversationMemory.captureLongTermFacts(context, "remember that I prefer 72 degrees"))
        assertTrue(conversationMemory.captureLongTermFacts(context, "my name is Hemang"))
        assertTrue(conversationMemory.getLongTermMemory(context).contains("Hemang"))
        assertFalse(conversationMemory.captureLongTermFacts(context, "short"))
        assertFalse(conversationMemory.captureLongTermFacts(context, "remember that"))
    }

    @Test
    fun ttsVoiceCatalog_availableVoicesAndFindById() {
        val filesTts = File(context.filesDir, "tts").also { it.mkdirs() }
        val pack = File(filesTts, "lessac-high").also { it.mkdirs() }
        File(pack, "model.onnx").writeText("onnx")
        File(pack, "tokens.txt").writeText("tok")
        File(pack, "model.onnx.json").writeText(
            """{"num_speakers":1,"audio":{"sample_rate":22050}}""",
        )

        val voices = TtsVoiceCatalog.availableVoices(context)
        assertTrue(voices.any { it.id == TtsVoiceCatalog.BUNDLED_AMY_ID && it.fromAssets })
        assertTrue(voices.any { it.id == "lessac-high" })

        assertEquals(TtsVoiceCatalog.BUNDLED_AMY_ID, TtsVoiceCatalog.findById(context, null).id)
        assertEquals(TtsVoiceCatalog.BUNDLED_AMY_ID, TtsVoiceCatalog.findById(context, "  ").id)
        assertEquals("lessac-high", TtsVoiceCatalog.findById(context, "lessac-high").id)
        assertEquals(
            TtsVoiceCatalog.BUNDLED_AMY_ID,
            TtsVoiceCatalog.findById(context, "does-not-exist").id,
        )
    }

    @Test
    fun debugBroadcasts_registerAndUnregister() {
        assertTrue(DebugBroadcasts.isEnabled)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = Unit
        }
        val registered = DebugBroadcasts.register(
            context,
            receiver,
            DebugBroadcasts.ACTION_TEST_QUERY,
        )
        assertTrue(registered)
        DebugBroadcasts.unregister(context, receiver)
        // Second unregister must be safe.
        DebugBroadcasts.unregister(context, receiver)
    }

    @Test
    fun deviceCapabilities_largeScreenAndDescribe() {
        assertTrue(DeviceCapabilities.cpuCoreCount() >= 1)
        val large = DeviceCapabilities.isLargeScreen(context)
        val desc = DeviceCapabilities.describe(context)
        assertTrue(desc.contains("device="))
        assertTrue(desc.contains("cores="))
        assertTrue(desc.contains("largeScreen=$large"))
        assertTrue(desc.contains("openCL="))
        // Probe twice to hit the cached OpenCL path.
        DeviceCapabilities.hasOpenCl()
        DeviceCapabilities.openClLibraryPath()
        val chain = DeviceCapabilities.backendFallbackChain("Auto")
        assertTrue(chain.isNotEmpty())
        assertTrue(chain.contains(AssistantConfig.Backend.CPU) || chain.any { it == "CPU" })
    }

    @Test
    fun demoSettingsPresets_applyAndGetSelected() {
        DemoSettingsPresets.apply(context, DemoSettingsPresets.SAGAMIHARA)
        assertEquals(DemoSettingsPresets.SAGAMIHARA.id, DemoSettingsPresets.getSelected(context).id)
        DemoSettingsPresets.ensureDefaults(context)
        assertTrue(
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getBoolean(DemoSettingsPresets.PREF_INITIALIZED, false),
        )
    }

    @Test
    fun toolManager_resolveDirectHit_andBm25Config() {
        val tm = ToolManager()
        tm.initialize(context)
        assertTrue(tm.isInitialized)
        assertTrue(tm.bm25TopK > 0)
        assertTrue(tm.maxPromptTools > 0)
        assertNotNull(tm.directExecutionPolicy)
        // A clear HVAC phrase should either hit DirectTool or safely return null without crashing.
        tm.resolveDirectHit("turn on the air conditioning")
        tm.resolveDirectHit("zzzz not a tool")
    }
}
