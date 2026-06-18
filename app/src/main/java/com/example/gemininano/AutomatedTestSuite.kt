package com.example.gemininano

object AutomatedTestSuite {
    data class TestCase(val prompt: String, val expectedToolPrefix: String)

    val testCases = mutableListOf<TestCase>().apply {
        add(TestCase("Send a text message to John saying I'll be late.", "<TOOL>sendText"))
        add(TestCase("Call the mechanic.", "<TOOL>callContact"))
        add(TestCase("What's the weather like in Seattle today?", "<TOOL>getWeather"))
        add(TestCase("Give me today's top news highlights.", "<TOOL>getNewsHighlights"))
        add(TestCase("It's about to rain, prepare the cabin.", "<TOOL>prepareForIncomingRain"))
        add(TestCase("It is very foggy outside, I can't see the road well.", "<TOOL>improveRoadVisibility"))
    }
}
