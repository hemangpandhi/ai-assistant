package com.example.gemininano

import org.junit.Test
import org.junit.Assert.*

class TestIncreaseTemp {
    @Test
    fun testSubstring() {
        val toolCall = "increaseTemperature()"
        val valueStr = toolCall.substringAfter("(").substringBefore(")")
        val value = valueStr.toDoubleOrNull() ?: 2.0
        assertEquals(2.0, value, 0.001)

        val toolCall2 = "increaseTemperature"
        val valueStr2 = toolCall2.substringAfter("(").substringBefore(")")
        val value2 = valueStr2.toDoubleOrNull() ?: 2.0
        assertEquals(2.0, value2, 0.001)

        val toolCall3 = "increaseTemperature(5)"
        val valueStr3 = toolCall3.substringAfter("(").substringBefore(")")
        val value3 = valueStr3.toDoubleOrNull() ?: 2.0
        assertEquals(5.0, value3, 0.001)
    }
}
