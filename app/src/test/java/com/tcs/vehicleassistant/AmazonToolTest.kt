package com.tcs.vehicleassistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tcs.vehicleassistant.domain.tools.ToolRegistry
import com.tcs.vehicleassistant.domain.tools.ToolSchemaGenerator
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AmazonToolTest {

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var schemaGenerator: ToolSchemaGenerator

    @Before
    fun setup() {
        org.koin.core.context.stopKoin()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directToolResolver = com.tcs.vehicleassistant.core.DirectToolResolver()
        toolRegistry = ToolRegistry(
            com.tcs.vehicleassistant.core.ContextGuard(),
            directToolResolver
        )
        toolRegistry.initialize(context)
        schemaGenerator = ToolSchemaGenerator(toolRegistry, directToolResolver)
    }

    @Test
    fun testAmazonToolInjected() {
        val prompt = schemaGenerator.getLlmToolsPrompt("my wife's birthday is coming up. Can you find her a perfume on Amazon?")
        assertTrue("Prompt should contain searchAmazon tool", prompt.contains("searchAmazon"))
    }
}
