package com.tcs.vehicleassistant.requirements

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tcs.vehicleassistant.ConversationMemory
import com.tcs.vehicleassistant.core.CabinSnapshot
import com.tcs.vehicleassistant.hardware.CabinSnapshotReader
import com.tcs.vehicleassistant.core.ConfirmationPolicy
import com.tcs.vehicleassistant.core.ContextGuard
import com.tcs.vehicleassistant.core.ConversationalIntent
import com.tcs.vehicleassistant.core.DirectToolResolver
import com.tcs.vehicleassistant.core.GemmaRegressionScorer
import com.tcs.vehicleassistant.core.LlmToolTurnPolicy
import com.tcs.vehicleassistant.core.SafetyCriticalTools
import com.tcs.vehicleassistant.support.RegistryTestSupport
import com.tcs.vehicleassistant.support.TabletUseCaseReport
import com.tcs.vehicleassistant.support.TabletUseCaseReport.Category
import com.tcs.vehicleassistant.support.TabletUseCaseReport.NextStepHint
import com.tcs.vehicleassistant.utils.FollowUpRouter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Standalone on-tablet use-case suite (no live mic / no live Gemma required).
 *
 * Runs DirectTool, ContextGuard safety, confirm-honesty, wellness routing, follow-ups,
 * registry gates, and Gemma fixture scoring; writes a JSON+Markdown report to
 * `/data/local/tmp/vehicleassistant_usecase_report.*` for
 * `scripts/run_tablet_usecase_report.sh`.
 *
 * Voice wake / barge-in / 8-turn LLM soak remain human rows in
 * `docs/use_cases/DRIVER_SEAT_TABLET_SUITE.md`.
 */
@RunWith(AndroidJUnit4::class)
class StandaloneTabletUseCaseReportTest {
    private val tm = RegistryTestSupport.initializedToolManager()

    @Test
    fun runAllStandaloneUseCases_andWriteReport() {
        TabletUseCaseReport.reset()
        ConversationMemory.clearMemory()
        val specs = RegistryTestSupport.directToolSpecs(tm)
        val args = InstrumentationRegistry.getArguments()
        val serial = args.getString("deviceSerial").orEmpty()
        val userId = args.getString("userId").orEmpty()

        runDirectToolMatrix(specs)
        runDriverSeatDemoPhrases(specs)
        runSafetyMatrix()
        runConfirmHonesty()
        runWellnessAndChat()
        runFollowUps(specs)
        runRegistryGates(specs)
        runGemmaFixtures()
        runLiveSnapshotSmoke()

        TabletUseCaseReport.writeToDevice(serial, userId)

        val failed = TabletUseCaseReport.failedCount()
        val total = TabletUseCaseReport.snapshot().size
        val failedCases = TabletUseCaseReport.getFailedCasesSummary()
        assertTrue(
            "Standalone tablet use-case report: $failed/${TabletUseCaseReport.totalCount()} failed.\n$failedCases\nSee /data/local/tmp/vehicleassistant_usecase_report.md",
            failed == 0,
        )
    }

    private fun runDirectToolMatrix(specs: List<DirectToolResolver.ToolSpec>) {
        val scenarios = RegistryTestSupport.buildDirectScenarios(specs)
        for (scenario in scenarios) {
            TabletUseCaseReport.runCase(
                id = "DT-${scenario.toolId}-${scenario.query.hashCode()}",
                category = Category.DIRECT_TOOL,
                title = "DirectTool '${scenario.query}' → ${scenario.toolId}",
                nextStepHint = NextStepHint.SEMANTIC_KEYWORD,
            ) {
                val outcome = tm.directToolResolver.resolve(scenario.query, specs)
                check(outcome is DirectToolResolver.Outcome.Execute) {
                    "expected Execute, got $outcome"
                }
                val hit = (outcome as DirectToolResolver.Outcome.Execute).hit
                check(hit.toolId == scenario.toolId) {
                    "expected ${scenario.toolId}, got ${hit.toolId}"
                }
            }
        }
    }

    private fun runDriverSeatDemoPhrases(specs: List<DirectToolResolver.ToolSpec>) {
        val demos = listOf(
            "turn on the ac" to "turnOnAC",
            "increase temperature" to "increaseTemperature",
            "increase fan" to "increaseFanSpeed",
            "play music" to "playMusic",
            "pause the music" to "pauseMusic",
            "stop music" to "stopMusic",
            "volume up" to "setVolumeLevel",
            "what's the weather?" to "getWeather",
            "who are you" to "answerVehicleIdentity",
            "Open the windows" to "openWindowsSlightly",
            "Close the windows" to "closeAllWindows",
            "Navigate me to Tokyo Tower" to "startNavigationTo",
            "I am feeling cold" to "handleFeelingCold",
            "Turn on the seat heater" to "setSeatHeater",
        )
        for ((query, expected) in demos) {
            TabletUseCaseReport.runCase(
                id = "DEMO-$expected",
                category = Category.DIRECT_TOOL,
                title = "Demo phrase '$query' → $expected",
                nextStepHint = NextStepHint.SEMANTIC_KEYWORD,
            ) {
                val outcome = tm.directToolResolver.resolve(query, specs)
                check(outcome is DirectToolResolver.Outcome.Execute) {
                    "expected Execute for '$query', got $outcome"
                }
                val hit = (outcome as DirectToolResolver.Outcome.Execute).hit
                check(hit.toolId == expected) {
                    "expected $expected, got ${hit.toolId}"
                }
            }
        }
    }

    private fun runSafetyMatrix() {
        val parked = CabinSnapshot(
            mediaVolumePct = 40,
            mediaPlaying = false,
            fanLevel = 3,
            cabinTempF = 72,
            seatHeaterLevel = 0,
            acOn = false,
            hvacPowerOn = true,
            defrostOn = false,
            speedMph = 0,
            gear = "Park",
            isParked = true,
            city = "Tokyo",
            fuelLevelPct = 55,
        )
        val driving = parked.copy(
            speedMph = 45,
            gear = "Drive",
            isParked = false,
            mediaPlaying = true,
            mediaVolumePct = 92,
        )

        TabletUseCaseReport.runCase(
            id = "SAFE-unlock-driving-confirm",
            category = Category.SAFETY,
            title = "Unlock while driving → Confirm",
            nextStepHint = NextStepHint.VIOLATION,
        ) {
            val d = tm.contextGuard.evaluate("unlockDoors()", driving)
            check(d is ContextGuard.Decision.Confirm) { "got $d" }
        }

        TabletUseCaseReport.runCase(
            id = "SAFE-trunk-driving-block",
            category = Category.SAFETY,
            title = "Open trunk while driving → Block",
            nextStepHint = NextStepHint.VIOLATION,
        ) {
            val d = tm.contextGuard.evaluate("openTrunk()", driving)
            check(d is ContextGuard.Decision.Block) { "got $d" }
        }

        TabletUseCaseReport.runCase(
            id = "SAFE-unlock-parked-allow",
            category = Category.SAFETY,
            title = "Unlock when parked (known gear) → Allow",
            nextStepHint = NextStepHint.VIOLATION,
        ) {
            val d = tm.contextGuard.evaluate("unlockDoors()", parked)
            check(d is ContextGuard.Decision.Allow) { "got $d" }
        }

        TabletUseCaseReport.runCase(
            id = "SAFE-unlock-unknown-gear-confirm",
            category = Category.SAFETY,
            title = "Unlock with Unknown gear → fail-closed Confirm",
            nextStepHint = NextStepHint.VIOLATION,
        ) {
            val unknown = parked.copy(gear = "Unknown", isParked = false)
            val d = tm.contextGuard.evaluate("unlockDoors()", unknown)
            check(d is ContextGuard.Decision.Confirm) { "got $d" }
            check((d as ContextGuard.Decision.Confirm).policyId == SafetyCriticalTools.GEAR_UNKNOWN_POLICY_ID)
        }

        TabletUseCaseReport.runCase(
            id = "SAFE-volume-loud-confirm",
            category = Category.SAFETY,
            title = "Volume up when loud → Confirm",
            nextStepHint = NextStepHint.VIOLATION,
        ) {
            val d = tm.contextGuard.evaluate("setVolumeLevel(up)", driving)
            check(d is ContextGuard.Decision.Confirm) { "got $d" }
            check(LlmToolTurnPolicy.looksLikeQuestion((d as ContextGuard.Decision.Confirm).message))
        }

        TabletUseCaseReport.runCase(
            id = "SAFE-decline-first",
            category = Category.SAFETY,
            title = "ConfirmationPolicy decline-first on 'yes no'",
            nextStepHint = NextStepHint.BUG,
        ) {
            check(ConfirmationPolicy.classify("yes") == ConfirmationPolicy.Reply.AFFIRM)
            check(ConfirmationPolicy.classify("no thanks") == ConfirmationPolicy.Reply.DECLINE)
            check(ConfirmationPolicy.classify("yes no") == ConfirmationPolicy.Reply.DECLINE)
        }
    }

    private fun runConfirmHonesty() {
        TabletUseCaseReport.runCase(
            id = "HONEST-unlock-never-ran",
            category = Category.CONFIRM_HONESTY,
            title = "Tool-tag-only unlock never claims 'I ran'",
            nextStepHint = NextStepHint.BUG,
        ) {
            val ask = LlmToolTurnPolicy.confirmationAskMessage(
                "unlockDoors",
                "Security Warning: Are you sure you want to unlock the vehicle doors?",
            )
            val display = LlmToolTurnPolicy.resolveEmptyProseDisplay(
                confirmationAsks = listOf(ask),
                toolFeedbacks = listOf(ask),
                actuallyExecutedToolCalls = emptyList(),
                emptyFallback = "I couldn't run a tool for that.",
            )
            check(!display.text.contains("I ran", ignoreCase = true)) { display.text }
            check(display.asQuestion) { display.text }
        }

        TabletUseCaseReport.runCase(
            id = "HONEST-executed-may-ack",
            category = Category.CONFIRM_HONESTY,
            title = "Actually executed tool may ACK 'I ran'",
            nextStepHint = NextStepHint.BUG,
        ) {
            val display = LlmToolTurnPolicy.resolveEmptyProseDisplay(
                confirmationAsks = emptyList(),
                toolFeedbacks = emptyList(),
                actuallyExecutedToolCalls = listOf("playMusic(music)"),
                emptyFallback = "",
            )
            check(display.text.contains("I ran playMusic")) { display.text }
        }

        TabletUseCaseReport.runCase(
            id = "HONEST-speak-confirm-feedback",
            category = Category.CONFIRM_HONESTY,
            title = "Pending confirm forces spoken feedback",
            nextStepHint = NextStepHint.BUG,
        ) {
            check(
                LlmToolTurnPolicy.shouldSpeakToolFeedback(
                    pendingConfirmation = true,
                    confirmationAsks = listOf("Unlock anyway?"),
                    toolFeedbacks = listOf("Unlock anyway?"),
                ),
            )
        }
    }

    private fun runWellnessAndChat() {
        TabletUseCaseReport.runCase(
            id = "CHAT-not-feeling-good",
            category = Category.WELLNESS_CHAT,
            title = "not feeling good → wellness",
            nextStepHint = NextStepHint.STABILIZATION,
        ) {
            check(ConversationalIntent.isEmotionalOrWellness("I'm not feeling good"))
            check(ConversationalIntent.isOpenChat("I'm not feeling good"))
        }

        TabletUseCaseReport.runCase(
            id = "CHAT-climate-excluded",
            category = Category.WELLNESS_CHAT,
            title = "feeling cold stays cabin (not open chat)",
            nextStepHint = NextStepHint.BUG,
        ) {
            check(!ConversationalIntent.isOpenChat("I'm feeling cold"))
            check(!ConversationalIntent.isEmotionalOrWellness("I'm feeling cold"))
        }

        TabletUseCaseReport.runCase(
            id = "CHAT-how-are-you",
            category = Category.WELLNESS_CHAT,
            title = "how are you → open chat",
            nextStepHint = NextStepHint.STABILIZATION,
        ) {
            check(ConversationalIntent.isOpenChat("how are you"))
        }
    }

    private fun runFollowUps(specs: List<DirectToolResolver.ToolSpec>) {
        TabletUseCaseReport.runCase(
            id = "FU-feeling-cold-yes-seat",
            category = Category.FOLLOW_UP,
            title = "feeling cold → yes → seat heater",
            nextStepHint = NextStepHint.BUG,
        ) {
            val outcome = tm.directToolResolver.resolve("I am feeling cold", specs)
            check(outcome is DirectToolResolver.Outcome.Execute)
            val follow = FollowUpRouter.resolveDirectTool(
                "yes",
                (outcome as DirectToolResolver.Outcome.Execute).hit.spokenResponse,
            )
            check(follow == "setSeatHeater(2)") { "got $follow" }
        }
    }

    private fun runRegistryGates(specs: List<DirectToolResolver.ToolSpec>) {
        TabletUseCaseReport.runCase(
            id = "REG-confirm-tools-not-direct",
            category = Category.REGISTRY,
            title = "requires_confirmation tools Skip DirectTool",
            nextStepHint = NextStepHint.VIOLATION,
        ) {
            val confirmTools = specs.filter { it.requiresConfirmation && it.directExecutable }
            for (tool in confirmTools) {
                val kw = tool.keywords.firstOrNull { it.length >= 5 } ?: continue
                val outcome = tm.directToolResolver.resolve(kw, specs)
                check(outcome is DirectToolResolver.Outcome.Skip) {
                    "${tool.id} keyword '$kw' should Skip, got $outcome"
                }
            }
        }

        TabletUseCaseReport.runCase(
            id = "REG-safety-critical-set",
            category = Category.REGISTRY,
            title = "SafetyCriticalTools covers unlock/trunk/windows",
            nextStepHint = NextStepHint.RISK,
        ) {
            check(SafetyCriticalTools.isSafetyCritical("unlockDoors()"))
            check(SafetyCriticalTools.isSafetyCritical("openTrunk()"))
            check(!SafetyCriticalTools.isSafetyCritical("playMusic()"))
        }
    }

    private fun runGemmaFixtures() {
        TabletUseCaseReport.runCase(
            id = "GEMMA-fixture-cabin",
            category = Category.GEMMA_FIXTURE,
            title = "GemmaRegressionScorer default cabin fixtures",
            nextStepHint = NextStepHint.STABILIZATION,
        ) {
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
            check(report.allPassed) {
                report.scores.filter { !it.passed }
                    .joinToString { "${it.caseId}:${it.reasons.joinToString()}" }
            }
        }
    }

    private fun runLiveSnapshotSmoke() {
        TabletUseCaseReport.runCase(
            id = "SNAP-live-readable",
            category = Category.OTHER,
            title = "Live CabinSnapshotReader.capture readable",
            nextStepHint = NextStepHint.RISK,
        ) {
            val snap = CabinSnapshotReader.capture(RegistryTestSupport.appContext())
            check(snap.mediaVolumePct in 0..100)
            check(snap.fanLevel >= 0)
        }
    }
}
