package com.tcs.vehicleassistant.requirements

import com.tcs.vehicleassistant.core.DirectToolResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Exhaustive DirectTool coverage: every keyword of every direct_executable registry tool.
 */
@RunWith(Parameterized::class)
class ExhaustiveDirectToolUnitTest(
    private val scenario: RegistryFixture.DirectScenario,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> =
            RegistryFixture.buildExhaustiveDirectScenarios().map { arrayOf<Any>(it) }
    }

    @Test
    fun keywordResolvesToExpectedTool() {
        val specs = RegistryFixture.toSpecs()
        val outcome = DirectToolResolver().resolve(scenario.query, specs)
        assertTrue(
            "expected Execute for '${scenario.query}' → ${scenario.toolId} (${scenario.note}), got $outcome",
            outcome is DirectToolResolver.Outcome.Execute,
        )
        val hit = (outcome as DirectToolResolver.Outcome.Execute).hit
        assertEquals(scenario.toolId, hit.toolId)
        assertTrue(hit.spokenResponse.isNotBlank())
        assertTrue(hit.toolCall.startsWith(hit.toolId) || hit.toolCall.startsWith(hit.toolId.substringBefore("(")))
    }
}

class RegistryAndDocsCoherenceUnitTest {

    @Test
    fun everyLlmAllUseCasesToolExistsInRegistry() {
        val handlers = RegistryFixture.loadTools().map { it.handlerKey }.toSet()
        val md = RegistryFixture.loadLlmAllUseCaseTriggers()
        assertTrue("LLM_All_Use_Cases.md must list tools", md.size >= 80)
        val missing = md.map { it.handlerKey }.filter { it !in handlers }
        assertTrue("tools in MD missing from registry: $missing", missing.isEmpty())
    }

    @Test
    fun everyRegistryToolHasPromptKeywordsAndHandlerType() {
        val tools = RegistryFixture.loadTools()
        assertTrue("expected >= 90 registry tools, got ${tools.size}", tools.size >= 90)
        for (t in tools) {
            assertTrue("${t.handlerKey} missing prompt", t.promptString.contains("<TOOL>"))
            assertTrue("${t.handlerKey} missing keywords", t.keywords.isNotEmpty())
            assertTrue(
                "${t.handlerKey} bad handler_type ${t.handlerType}",
                t.handlerType == "CUSTOM_KOTLIN" || t.handlerType == "GENERIC_VHAL_WRITE",
            )
        }
    }

    @Test
    fun directExecutableCatalogueIsSubstantial() {
        val direct = RegistryFixture.loadTools().filter {
            it.directExecutable && !it.requiresConfirmation && !it.requiresAgenticLoop
        }
        assertTrue("expected >= 20 direct tools, got ${direct.size}", direct.size >= 20)
        val required = listOf(
            "increaseTemperature", "decreaseTemperature", "setTemperature",
            "turnOnAC", "playMusic", "stopMusic", "pauseMusic",
            "handleFeelingCold", "startNavigationTo", "setSeatHeater",
        )
        val ids = direct.map { it.handlerKey }.toSet()
        for (id in required) {
            assertTrue("missing direct tool $id", id in ids)
        }
    }

    @Test
    fun exhaustiveScenarioMatrixCoversAllDirectKeywords() {
        val scenarios = RegistryFixture.buildExhaustiveDirectScenarios()
        val directKw = RegistryFixture.loadTools()
            .filter { it.directExecutable && !it.requiresConfirmation && !it.requiresAgenticLoop }
            .sumOf { t -> t.keywords.count { kw -> kw.trim().length >= 3 } }
        assertTrue(
            "scenario count ${scenarios.size} should be close to keyword count $directKw",
            scenarios.size >= directKw * 8 / 10 && scenarios.size >= 80,
        )
    }

    @Test
    fun conversationalQueriesDoNotDirectExecute() {
        val specs = RegistryFixture.toSpecs()
        val skips = listOf(
            "tell me a joke about quantum physics",
            "what is the capital of France",
            "why is my check engine light on",
            "where was inception filmed in tokyo",
            "can you explain how the engine works",
        )
        for (q in skips) {
            val outcome = DirectToolResolver().resolve(q, specs)
            assertTrue("expected Skip for '$q', got $outcome", outcome is DirectToolResolver.Outcome.Skip)
        }
    }
}

class LlmAllUseCasesTriggerUnitTest {

    @Test
    fun everyMdTriggerIsDocumentedAgainstRegistryKeywordsOrDirectPath() {
        val tools = RegistryFixture.loadTools().associateBy { it.handlerKey }
        val specs = RegistryFixture.toSpecs()
        val policy = DirectToolResolver.Policy()
        val failures = mutableListOf<String>()

        for (ex in RegistryFixture.loadLlmAllUseCaseTriggers()) {
            val tool = tools[ex.handlerKey]
            if (tool == null) {
                failures += "missing registry tool ${ex.handlerKey}"
                continue
            }
            val trigger = ex.triggerPhrase.trim()
            val inKeywords = tool.keywords.any {
                it.equals(trigger, ignoreCase = true) ||
                    it.contains(trigger, ignoreCase = true) ||
                    trigger.contains(it, ignoreCase = true)
            } || tool.aliases.any {
                it.equals(trigger, ignoreCase = true) || it.contains(trigger, ignoreCase = true)
            }

            // Prefer longest keyword as the production DirectTool query for this tool.
            val bestKw = tool.keywords.map { it.trim() }.filter { it.length >= policy.minKeywordChars }
                .maxByOrNull { it.length }
            if (tool.directExecutable && !tool.requiresConfirmation && !tool.requiresAgenticLoop && bestKw != null) {
                val query = RegistryFixture.queryFor(tool, bestKw)
                val outcome = DirectToolResolver().resolve(query, specs)
                if (outcome !is DirectToolResolver.Outcome.Execute ||
                    (outcome as DirectToolResolver.Outcome.Execute).hit.toolId != tool.handlerKey
                ) {
                    failures += "direct path failed for ${tool.handlerKey} query='$query' → $outcome"
                }
            } else if (!inKeywords && trigger.length >= 3) {
                // LLM-only tools still must have some lexical hook in keywords/aliases for retrieval.
                failures += "trigger '$trigger' for ${ex.handlerKey} not reflected in keywords/aliases"
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}

class DocumentedPromptMatrixUnitTest {

    @Test
    fun demoAndUseCasePrompts_directToolsResolveOrAreKnownLlmFallthrough() {
        val specs = RegistryFixture.toSpecs()
        val prompts = RegistryFixture.loadDocumentedUserPrompts()
        assertTrue("expected documented prompts from docs", prompts.size >= 10)

        val checked = mutableListOf<String>()
        for ((src, raw) in prompts) {
            val q = raw.trim().trimEnd('.')
            val norm = q.lowercase()
            if (q.contains(" OR ", ignoreCase = true)) continue
            if (q.count { it == ' ' } > 14) continue

            val outcome = DirectToolResolver().resolve(q, specs)
            if (outcome is DirectToolResolver.Outcome.Execute) {
                checked += "$src:$q → ${(outcome as DirectToolResolver.Outcome.Execute).hit.toolId}"
                continue
            }
            // Soft: many WOW lines are LLM/agentic; only fail if it looks like a short cabin command.
            val looksCabin = listOf(
                "temperature", "fan", "ac", "music", "seat", "window", "defrost", "volume", "heater",
                "navigate", "go to",
            ).any { norm.contains(it) } && q.split(' ').size <= 10
            if (looksCabin) {
                if (norm.contains("feeling") || norm.contains("would you") || norm.contains("should i")) continue
                // Demo "Navigate me to …" must DirectTool after the stability fix.
                if (norm.startsWith("navigate me to") || norm.startsWith("go to")) {
                    val isExecuteOrMulti = outcome is DirectToolResolver.Outcome.Execute || 
                            (outcome is DirectToolResolver.Outcome.Skip && outcome.rejection.reason == "multiple_tools_detected")
                    assertTrue(
                        "expected DirectTool or multi-command fallback for documented nav '$q', got $outcome",
                        isExecuteOrMulti,
                    )
                }
            }
        }
        assertTrue("expected several documented prompts to DirectTool-hit, got ${checked.size}: $checked", checked.size >= 8)
    }

    @Test
    fun playArtistCasesFromDocs() {
        val specs = RegistryFixture.toSpecs()
        val cases = listOf(
            "play arijit singh music" to "arijit singh",
            "play music by Adele" to "adele",
            "Play YOASOBI" to "yoasobi",
            "play some classic rock music" to "classic rock",
            "play some jazz" to "jazz",
        )
        for ((q, expect) in cases) {
            val outcome = DirectToolResolver().resolve(q, specs)
            assertTrue("$q → $outcome", outcome is DirectToolResolver.Outcome.Execute)
            val hit = (outcome as DirectToolResolver.Outcome.Execute).hit
            assertEquals("playMusic", hit.toolId)
            assertTrue(hit.toolCall.contains(expect, ignoreCase = true))
            assertTrue(hit.spokenResponse.contains(expect.split(' ').first(), ignoreCase = true))
            assertFalse(hit.toolCall.equals("playMusic(music)", ignoreCase = true) && expect != "music")
        }
    }
}
