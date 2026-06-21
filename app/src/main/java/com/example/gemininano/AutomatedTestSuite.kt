package com.example.gemininano

object AutomatedTestSuite {
    data class TestCase(val prompt: String, val expectedToolPrefix: String)

    val testCases = mutableListOf<TestCase>().apply {
        // 1. HVAC & Climate
        add(TestCase("Increase temperature", "<TOOL>increaseTemperature"))
        add(TestCase("Decrease temperature", "<TOOL>decreaseTemperature"))
        add(TestCase("Set temperature to 72 degrees", "<TOOL>setTemperature"))
        add(TestCase("I am feeling cold", "<TOOL>handleFeelingCold"))
        add(TestCase("Turn on climate control", "<TOOL>setTemperature"))
        add(TestCase("Increase FAN speed", "<TOOL>increaseFanSpeed"))
        add(TestCase("Set Airflow direction to face and feet", "<TOOL>setAirflowDirection"))
        add(TestCase("My window is freezing", "<TOOL>defogWindshield"))

        // 2. Wellness & Seating
        add(TestCase("My back is freezing.", "<TOOL>setSeatHeater"))
        add(TestCase("I am tired and my back hurts.", "<TOOL>setSeatMassager"))

        // 3. World Knowledge to Physical Action
        add(TestCase("Where was the Hollywood movie Inception filmed in Tokyo?", "<TOOL>search"))

        // 4. Navigation
        add(TestCase("Navigate me to Tokyo Tower", "<TOOL>navigate"))
        add(TestCase("Suggest nearby places to visit around Tokyo", "<TOOL>suggestNearbyPlaces"))
        
        // 5. Agentic Loops
        add(TestCase("I am running out of fuel.", "<TOOL>search"))
        add(TestCase("I'm heading home.", "<TOOL>navigate"))

        // 6. Telephony & Media
        add(TestCase("I need to talk to my mom.", "<TOOL>callContact"))
        add(TestCase("Play some classic rock music.", "<TOOL>playMusic"))
        add(TestCase("Play YOASOBI.", "<TOOL>playMusic"))

        // 7. Diagnostics
        add(TestCase("Why is my check engine light on?", "diagnostics"))
        add(TestCase("What's wrong with my car?", "diagnostics"))

        // 8. Hardware Safety Guardrails
        add(TestCase("Roll down the windows.", "<TOOL>setWindowPosition"))
        
        // General Intents
        add(TestCase("Give me today's top news highlights.", "<TOOL>getNewsHighlights"))
        add(TestCase("It's about to rain, prepare the cabin.", "<TOOL>prepareForIncomingRain"))
        add(TestCase("It is very foggy outside, I can't see the road well.", "<TOOL>improveRoadVisibility"))
    }
}
