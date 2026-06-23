package com.example.gemininano

object AutomatedTestSuite {
    data class TestCase(val prompt: String, val expectedToolPrefix: String)

    val testCases = mutableListOf<TestCase>().apply {
        // HVAC & Climate Only
        add(TestCase("Increase temperature", "<TOOL>increaseTemperature"))
        add(TestCase("Decrease temperature", "<TOOL>decreaseTemperature"))
        add(TestCase("Set temperature to 72 degrees", "<TOOL>setTemperature"))
        add(TestCase("Set driver temperature to 70 degrees", "<TOOL>setDriverTemperature"))
        add(TestCase("Set passenger temperature to 74 degrees", "<TOOL>setPassengerTemperature"))
        add(TestCase("Turn on the AC", "<TOOL>turnOnAC"))
        add(TestCase("Turn off the AC", "<TOOL>turnOffAC"))
        add(TestCase("Turn on automatic climate control", "<TOOL>turnOnAutoClimate"))
        add(TestCase("Turn off automatic climate control", "<TOOL>turnOffAutoClimate"))
        add(TestCase("Turn on the HVAC system", "<TOOL>turnOnHvacPower"))
        add(TestCase("Turn off the HVAC system", "<TOOL>turnOffHvacPower"))
        add(TestCase("I am feeling cold", "<TOOL>handleFeelingCold"))
        add(TestCase("Increase FAN speed", "<TOOL>increaseFanSpeed"))
        add(TestCase("Set Airflow direction to face and feet", "<TOOL>setAirflowDirection"))
        add(TestCase("My window is freezing", "<TOOL>defogWindshield"))
        add(TestCase("My back is freezing.", "<TOOL>setSeatHeater"))
    }
}
