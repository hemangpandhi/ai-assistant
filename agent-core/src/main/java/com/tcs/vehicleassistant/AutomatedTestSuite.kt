package com.tcs.vehicleassistant

object AutomatedTestSuite {
    data class TestCase(val prompt: String, val expectedToolPrefix: String)

    val testCases = mutableListOf<TestCase>().apply {
        add(TestCase("tell me some joke", "NO_TOOL_CALLED"))
    }
}
