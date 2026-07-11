package com.tcs.vehicleassistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ConfigurationValidationTest {

    private lateinit var jsonObject: JSONObject

    @Before
    fun setUp() {
        val file = File("src/main/assets/vehicle_skills_registry.json")
        assertTrue("Registry file must exist at ${file.absolutePath}", file.exists())
        jsonObject = JSONObject(file.readText())
    }

    @Test
    fun testJsonStructureIsValid() {
        assertTrue("JSON must contain 'properties' array", jsonObject.has("properties"))
        assertTrue("JSON must contain 'tools' array", jsonObject.has("tools"))
    }

    @Test
    fun testToolsConfiguration() {
        val toolsArray = jsonObject.getJSONArray("tools")
        val validHandlerTypes = setOf("CUSTOM_KOTLIN", "GENERIC_VHAL_WRITE")

        for (i in 0 until toolsArray.length()) {
            val tool = toolsArray.getJSONObject(i)
            assertTrue("Tool must have a prompt_string", tool.has("prompt_string"))
            val promptString = tool.getString("prompt_string")
            assertTrue(
                "prompt_string must contain <TOOL> tags",
                promptString.contains("<TOOL>") && promptString.contains("</TOOL>")
            )

            val handlerType = if (tool.has("handler_type")) tool.getString("handler_type") else "CUSTOM_KOTLIN"
            assertTrue("Handler type must be valid", validHandlerTypes.contains(handlerType))

            if (handlerType == "GENERIC_VHAL_WRITE") {
                assertTrue("GENERIC_VHAL_WRITE must have property_id", tool.has("property_id"))
                assertTrue("GENERIC_VHAL_WRITE must have data_type", tool.has("data_type"))
                val dataType = tool.getString("data_type")
                assertTrue("data_type must be supported", setOf("BOOLEAN", "INT", "FLOAT", "STRING").contains(dataType))
            } else if (handlerType == "CUSTOM_KOTLIN") {
                assertTrue("CUSTOM_KOTLIN must have handler_key", tool.has("handler_key"))
            }
        }
    }

    @Test
    fun testTelemetryConfiguration() {
        val propertiesArray = jsonObject.getJSONArray("properties")
        for (i in 0 until propertiesArray.length()) {
            val prop = propertiesArray.getJSONObject(i)
            assertTrue("Property must have name", prop.has("name"))
            assertTrue("Property must have id", prop.has("id") || prop.has("property_id"))
            assertTrue("Property must have type", prop.has("type") || prop.has("data_type"))
        }
    }
}
