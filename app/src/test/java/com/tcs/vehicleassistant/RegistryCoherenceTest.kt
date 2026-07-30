package com.tcs.vehicleassistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tcs.vehicleassistant.handlers.ToolHandlerRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the contract between `vehicle_skills_registry.json`, [ToolManager], and
 * [ToolHandlerRegistry]. A broken registry entry used to ship as a silent no-op or — worse — write
 * the wrong VHAL property (openTrunk previously targeted HVAC_DEFROSTER).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RegistryCoherenceTest {

    private lateinit var toolManager: ToolManager
    private lateinit var registryJson: JSONObject

    @Before
    fun setUp() {
        toolManager = ToolManager()
        toolManager.initialize(ApplicationProvider.getApplicationContext())
        registryJson = JSONObject(
            File("src/main/assets/vehicle_skills_registry.json").readText()
        )
    }

    @Test
    fun `every CUSTOM_KOTLIN tool has a registered handler`() {
        val missing = ToolHandlerRegistry.missingHandlers(toolManager.getAllTools())
        assertTrue(
            "CUSTOM_KOTLIN tools without a ToolHandlerRegistry entry: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `GENERIC_VHAL_WRITE tools declare a property id data type and value`() {
        for ((name, tool) in toolManager.getAllTools()) {
            if (tool.handlerType != "GENERIC_VHAL_WRITE") continue
            assertTrue("$name missing property_id", tool.propertyId != null)
            assertFalse("$name missing data_type", tool.dataType.isNullOrBlank())
            assertFalse(
                "$name is a VHAL write but has no value_to_write — reads must be CUSTOM_KOTLIN",
                tool.valueToWrite.isNullOrBlank()
            )
        }
    }

    @Test
    fun `openTrunk does not write the defroster property`() {
        val trunk = toolManager.getToolDefinition("openTrunk()")
            ?: throw AssertionError("openTrunk missing from registry")
        assertEquals("CUSTOM_KOTLIN", trunk.handlerType)
        assertFalse(
            "openTrunk must not target HVAC_DEFROSTER (320865540)",
            trunk.propertyId == 320865540
        )
    }

    @Test
    fun `unlockDoors targets DOOR_LOCK not a phantom HVAC id`() {
        val unlock = toolManager.getToolDefinition("unlockDoors()")
            ?: throw AssertionError("unlockDoors missing from registry")
        assertEquals("GENERIC_VHAL_WRITE", unlock.handlerType)
        assertEquals(371198722, unlock.propertyId) // VehiclePropertyIds.DOOR_LOCK
        assertEquals("false", unlock.valueToWrite) // false = unlocked
    }

    @Test
    fun `read-only skills are CUSTOM_KOTLIN not VHAL writes`() {
        for (name in listOf("answerVehicleIdentity", "checkAllWindowsClosed")) {
            val tool = toolManager.getToolDefinition("$name()")
                ?: throw AssertionError("$name missing")
            assertEquals("$name must be CUSTOM_KOTLIN so its handler can read state", "CUSTOM_KOTLIN", tool.handlerType)
        }
    }

    @Test
    fun `no two tools share the same property and write value unless they are on off pairs`() {
        // Distinct actions writing the same (property, value, area) collide at the hardware layer.
        data class Write(val propertyId: Int, val value: String, val areaId: Int)
        val writes = mutableMapOf<Write, MutableList<String>>()
        for ((name, tool) in toolManager.getAllTools()) {
            if (tool.handlerType != "GENERIC_VHAL_WRITE") continue
            val key = Write(tool.propertyId!!, tool.valueToWrite!!, tool.areaId ?: 0)
            writes.getOrPut(key) { mutableListOf() }.add(name)
        }
        val collisions = writes.filter { it.value.size > 1 }
        assertTrue("duplicate VHAL writes: $collisions", collisions.isEmpty())
    }

    @Test
    fun `registry config caps are applied`() {
        assertTrue(toolManager.bm25TopK > 0)
        assertTrue(toolManager.maxPromptTools >= toolManager.bm25TopK)
        assertEquals(
            registryJson.getJSONObject("config").getInt("sliding_window_max_chars"),
            toolManager.slidingWindowMaxChars
        )
    }

    @Test
    fun `system_instructions reach the prompt when their keywords match`() {
        val prompt = toolManager.getLlmToolsPrompt("set the airflow to face and floor")
        assertTrue(
            "expected airflow system_instruction in prompt, got:\n$prompt",
            prompt.contains("airflow", ignoreCase = true) && prompt.contains("Tool guidance")
        )
    }

    @Test
    fun `wellness system_instruction reaches the prompt for feeling sad`() {
        val prompt = toolManager.getLlmToolsPrompt("I'm feeling sad")
        assertTrue(
            "expected wellness system_instruction in prompt, got:\n$prompt",
            prompt.contains("empathy", ignoreCase = true) || prompt.contains("feelings", ignoreCase = true),
        )
        assertFalse(
            "feeling sad must not BM25-inject handleFeelingCold",
            prompt.contains("handleFeelingCold", ignoreCase = true),
        )
        assertFalse(
            "feeling sad must not inject core playMusic tools (forces empty EOS)",
            prompt.contains("playMusic", ignoreCase = true),
        )
        assertFalse(
            "feeling sad must not inject Allowed tools catalog",
            prompt.contains("Allowed tools:", ignoreCase = true),
        )
    }

    @Test
    fun `property ids declared on tools that are still VHAL exist in the properties catalogue or are known AOSP`() {
        val propertyIds = mutableSetOf<Int>()
        val props = registryJson.getJSONArray("properties")
        for (i in 0 until props.length()) propertyIds.add(props.getJSONObject(i).getInt("id"))

        // Known AOSP ids used by GENERIC_VHAL_WRITE tools that may omit a properties[] entry.
        val knownAosp = setOf(
            371198722, // DOOR_LOCK
            320865540, // HVAC_DEFROSTER
            320865556, // rear defroster / OEM
            354419973, 354419976, 354419978, 354419984, // HVAC switches
            289410818, // CABIN_LIGHTS_SWITCH
        )

        for ((name, tool) in toolManager.getAllTools()) {
            if (tool.handlerType != "GENERIC_VHAL_WRITE") continue
            val id = tool.propertyId!!
            assertTrue(
                "$name property_id $id is neither in properties[] nor a known AOSP id",
                id in propertyIds || id in knownAosp
            )
        }
    }
}
