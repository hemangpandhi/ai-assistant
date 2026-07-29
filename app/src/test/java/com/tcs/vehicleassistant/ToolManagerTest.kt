package com.tcs.vehicleassistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the real [ToolManager] against the shipped `vehicle_skills_registry.json`, rather than
 * asserting on string operations copied into the test body.
 *
 * A plain [Application] stands in for `VehicleApplication`, which starts Koin and kicks off model
 * initialization on create — neither of which the tool catalogue needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ToolManagerTest {

    private lateinit var toolManager: ToolManager

    @Before
    fun setUp() {
        MemoryManager.clearMemory()
        toolManager = ToolManager()
        toolManager.initialize(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `the registry loads and reports itself initialized`() {
        assertTrue(toolManager.isInitialized)
        assertTrue("expected a non-trivial tool catalogue", toolManager.getAllTools().size > 10)
    }

    @Test
    fun `sliding window size comes from the registry config`() {
        assertTrue("expected a positive character budget", toolManager.slidingWindowMaxChars > 0)
    }

    @Test
    fun `every registered tool carries the fields its handler type requires`() {
        for ((name, tool) in toolManager.getAllTools()) {
            assertTrue("$name must declare a prompt string", tool.promptString.contains("<TOOL>"))
            when (tool.handlerType) {
                "GENERIC_VHAL_WRITE" -> {
                    assertNotNull("$name must declare property_id", tool.propertyId)
                    assertNotNull("$name must declare data_type", tool.dataType)
                }
                "CUSTOM_KOTLIN" -> assertNotNull("$name must declare handler_key", tool.handlerKey)
                else -> throw AssertionError("$name has unknown handler type ${tool.handlerType}")
            }
        }
    }

    @Test
    fun `a tool call resolves to its definition`() {
        val name = toolManager.getAllTools().keys.first()
        assertNotNull(toolManager.getToolDefinition("$name(72.0)"))
        assertNotNull(toolManager.getToolDefinition("<TOOL>$name(72.0)</TOOL>"))
    }

    @Test
    fun `an unknown tool call resolves to nothing`() {
        assertNull(toolManager.getToolDefinition("launchRocket(now)"))
    }

    @Test
    fun `aliases resolve to the same definition as the canonical name`() {
        val aliased = toolManager.getAllTools().entries.firstOrNull { it.value.aliases?.isNotEmpty() == true }
            ?: return // The registry may legitimately declare no aliases.
        val alias = aliased.value.aliases!!.first()
        assertEquals(aliased.value, toolManager.getToolDefinition("$alias()"))
    }

    @Test
    fun `a climate utterance retrieves a temperature tool`() {
        val tools = toolManager.getRelevantTools("it's too cold in here, raise the temperature")
        assertTrue(
            "expected a temperature tool, got ${tools.map { it.handlerKey }}",
            tools.any { it.handlerKey?.contains("Temperature", ignoreCase = true) == true }
        )
    }

    @Test
    fun `retrieval never returns an empty set for a non-blank query`() {
        // An empty tool block leaves the model with nothing to call, so the fallback tiers must
        // always produce something.
        val queries = listOf(
            "turn on the ac",
            "play some music",
            "navigate to the airport",
            "tell me a joke about quantum physics",
            "zzzzz qqqqq",
            "72"
        )
        for (query in queries) {
            assertTrue("no tools retrieved for '$query'", toolManager.getRelevantTools(query).isNotEmpty())
        }
    }

    @Test
    fun `a blank query returns the whole catalogue`() {
        assertEquals(toolManager.getAllTools().size, toolManager.getRelevantTools("").size)
    }

    @Test
    fun `a bare temperature value retrieves a temperature tool`() {
        val tools = toolManager.getRelevantTools("make it 72")
        assertTrue(
            "expected a temperature tool for a bare setpoint, got ${tools.map { it.handlerKey }}",
            tools.any { it.handlerKey?.contains("Temperature", ignoreCase = true) == true }
        )
    }

    @Test
    fun `the prompt block lists the retrieved tools and an allow-list`() {
        val prompt = toolManager.getLlmToolsPrompt("play some music")
        assertTrue("prompt must contain tool signatures", prompt.contains("<TOOL>"))
        assertTrue("prompt must declare the allowed tools", prompt.contains("Allowed tools:"))
    }

    @Test
    fun `the prompt block is bounded so it cannot overflow the model context`() {
        val prompt = toolManager.getLlmToolsPrompt("turn on the ac and play music and navigate home")
        val toolLines = prompt.lines().count { it.startsWith("- ") }
        assertTrue("expected at most 8 tool lines, got $toolLines", toolLines in 1..8)
    }

    @Test
    fun `tool descriptions in the prompt are stripped of conversational questions`() {
        val withQuestion = toolManager.getAllTools().values
            .firstOrNull { it.description?.contains("?") == true } ?: return
        val prompt = toolManager.getLlmToolsPrompt(withQuestion.keywords?.firstOrNull() ?: return)
        val questionTail = withQuestion.description!!.substringAfter("?")
        if (questionTail.isNotBlank()) {
            assertFalse("prompt leaked a conversational description", prompt.contains(questionTail))
        }
    }

    @Test
    fun `a second initialize call is a no-op`() {
        val before = toolManager.getAllTools().size
        toolManager.initialize(ApplicationProvider.getApplicationContext())
        assertEquals(before, toolManager.getAllTools().size)
    }
}
