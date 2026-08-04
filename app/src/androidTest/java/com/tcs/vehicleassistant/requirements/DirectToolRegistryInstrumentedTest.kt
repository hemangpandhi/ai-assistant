package com.tcs.vehicleassistant.requirements

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tcs.vehicleassistant.core.DirectToolResolver
import com.tcs.vehicleassistant.support.RegistryTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Data-driven coverage of every `direct_executable` tool in vehicle_skills_registry.json.
 *
 * Requirements sources: LLM_All_Use_Cases.md + registry keywords/aliases.
 */
@RunWith(Parameterized::class)
class DirectToolRegistryInstrumentedTest(
    private val scenario: RegistryTestSupport.DirectScenario,
) {
    private val tm = RegistryTestSupport.initializedToolManager()


    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val specs = RegistryTestSupport.directToolSpecs()
            return RegistryTestSupport.buildDirectScenarios(specs)
                .map { arrayOf<Any>(it) }
        }
    }

    @Test
    fun resolvesToExpectedTool() {
        val specs = RegistryTestSupport.directToolSpecs()
        val outcome = tm.directToolResolver.resolve(scenario.query, specs)
        assertTrue(
            "expected Execute for '${scenario.query}' → ${scenario.toolId}, got $outcome",
            outcome is DirectToolResolver.Outcome.Execute,
        )
        val hit = (outcome as DirectToolResolver.Outcome.Execute).hit
        assertEquals(
            "query='${scenario.query}' note=${scenario.note}",
            scenario.toolId,
            hit.toolId,
        )
        val handler = specs.first { it.id == hit.toolId }.handlerKey
        assertTrue(
            hit.toolCall.startsWith(hit.toolId) || hit.toolCall.startsWith(handler),
        )
        assertTrue(hit.spokenResponse.isNotBlank())
    }
}

@RunWith(AndroidJUnit4::class)
class DirectToolCatalogueSanityInstrumentedTest {
    private val tm = RegistryTestSupport.initializedToolManager()

    private lateinit var specs: List<DirectToolResolver.ToolSpec>

    @Before
    fun setUp() {
        specs = RegistryTestSupport.directToolSpecs()
    }

    @Test
    fun registryExposesDirectExecutableCabinTools() {
        val direct = specs.filter { it.directExecutable }
        assertTrue("expected >= 20 direct_executable tools, got ${direct.size}", direct.size >= 20)
        val required = listOf(
            "increaseTemperature", "decreaseTemperature", "setTemperature",
            "turnOnAC", "playMusic", "stopMusic", "pauseMusic",
            "handleFeelingCold", "startNavigationTo", "setSeatHeater",
        )
        for (id in required) {
            assertNotNull(
                "missing direct tool $id",
                direct.firstOrNull { it.id == id || it.handlerKey == id },
            )
        }
    }

    @Test
    fun conversationalQueriesFallThroughToLlm() {
        val skips = listOf(
            "tell me a joke about quantum physics",
            "what is the capital of France",
            "why is my check engine light on",
            "where was inception filmed in tokyo",
        )
        for (q in skips) {
            val outcome = tm.directToolResolver.resolve(q, specs)
            assertTrue("expected Skip for '$q', got $outcome", outcome is DirectToolResolver.Outcome.Skip)
        }
    }

    @Test
    fun scenarioMatrixIsNonTrivial() {
        val scenarios = RegistryTestSupport.buildDirectScenarios(specs)
        assertTrue(
            "expected exhaustive direct keyword matrix (>=80), got ${scenarios.size}",
            scenarios.size >= 80,
        )
    }
}
