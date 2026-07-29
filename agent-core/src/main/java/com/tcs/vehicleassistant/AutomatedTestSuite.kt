package com.tcs.vehicleassistant

object AutomatedTestSuite {
    data class TestCase(val prompt: String, val expectedToolPrefix: String)

    val testCases = mutableListOf<TestCase>().apply {
        // Temperature (Increase) - 20 cases
        for (i in 1..20) add(TestCase("I'm cold, it's freezing. Warm it up by $i degrees please.", "<TOOL>increaseTemperature"))
        // Temperature (Decrease) - 20 cases
        for (i in 1..20) add(TestCase("It is too hot in here, lower the temp by $i degrees.", "<TOOL>decreaseTemperature"))
        // Temperature (Set) - 20 cases
        for (i in 60..79) add(TestCase("Set the cabin temperature to exactly $i degrees.", "<TOOL>setTemperature"))
        // Windows - 20 cases
        for (i in 1..20) add(TestCase("Roll down the windows to ${i * 5} percent.", "<TOOL>setWindowPosition"))
        // Seat Heater - 20 cases
        for (i in 1..20) add(TestCase("Turn on the seat heater, my back is cold.", "<TOOL>setSeatHeater"))
        // Defroster - 20 cases
        for (i in 1..20) add(TestCase("The windshield is foggy, turn on the defroster.", "<TOOL>turnOnDefroster"))
        // Navigation - 30 cases
        val places = listOf("McDonalds", "Starbucks", "Gas Station", "Home", "Work", "Airport")
        for (i in 1..30) add(TestCase("Navigate me to ${places[i % places.size]}.", "<TOOL>navigate"))
        // Call - 30 cases
        val names = listOf("John", "Mom", "Wife", "Mechanic", "Boss")
        for (i in 1..30) add(TestCase("Call ${names[i % names.size]} right now.", "<TOOL>call"))
        // Diagnostic - 20 cases
        for (i in 1..20) add(TestCase("What's wrong with my car? Check the engine light.", "Do you want me to call a mechanic"))
    }
}
