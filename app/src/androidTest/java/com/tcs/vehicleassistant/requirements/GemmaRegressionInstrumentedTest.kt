package com.tcs.vehicleassistant.requirements

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.GemmaRegressionScorer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Gemma regression gate scaffolding.
 *
 * - [scoreFixtureTranscripts_offline] always runs: locks the scorer + default cabin cases
 *   against known-good / known-bad transcripts (no live model required).
 * - [liveModelSidecar_optional] only runs when the default LiteRT model is present on device;
 *   it does not drive inference itself yet — it asserts the soak prerequisites so CI/device
 *   runs can grow into a full live gate without inventing passes.
 */
@RunWith(AndroidJUnit4::class)
class GemmaRegressionInstrumentedTest {

    @Test
    fun scoreFixtureTranscripts_offline() {
        val fixtures = mapOf(
            "play_music" to "<TOOL>playMusic()</TOOL> Playing music for you right now.",
            "pause_music" to "<TOOL>pauseMusic()</TOOL> Pausing media playback.",
            "stop_music" to "<TOOL>stopMusic()</TOOL> Stopping the music for you.",
            "increase_temp" to "<TOOL>increaseTemperature(all)</TOOL> Warming up the cabin.",
            "decrease_temp" to "<TOOL>decreaseTemperature(all)</TOOL> Cooling down the cabin.",
            "ac_on" to "<TOOL>turnOnAC()</TOOL> Turning on the air conditioning.",
            "chat_only" to "I'm here with you — how can I help on the drive?",
        )
        val report = GemmaRegressionScorer.scoreSuite(
            GemmaRegressionScorer.defaultCabinCases(),
            fixtures,
        )
        assertTrue(
            "Gemma fixture gate failed: ${report.scores.filter { !it.passed }}",
            report.allPassed,
        )
    }

    @Test
    fun scoreFixtureTranscripts_detectsRefusalRegression() {
        val bad = GemmaRegressionScorer.score(
            GemmaRegressionScorer.Case("play_music", "play music", "playMusic"),
            "I am a large language model and I cannot control the vehicle audio.",
        )
        assertTrue(!bad.passed)
        assertTrue(bad.reasons.any { it.contains("refusal") || it == "missing_tool" })
    }

    @Test
    fun liveModelSidecar_optional() {
        val model = File(AssistantConfig.Llm.DEFAULT_MODEL_PATH)
        assumeTrue(
            "Sideload ${AssistantConfig.Llm.DEFAULT_MODEL_PATH} to enable live Gemma soak prerequisites",
            model.exists() && model.canRead() && model.length() > 0L,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.isNotBlank())
        assertTrue(
            "Default cabin case pack must stay non-empty for live soak",
            GemmaRegressionScorer.defaultCabinCases().isNotEmpty(),
        )
    }
}
