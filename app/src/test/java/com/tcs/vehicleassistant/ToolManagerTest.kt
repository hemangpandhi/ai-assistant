package com.tcs.vehicleassistant

import org.junit.Test
import org.junit.Assert.*

class ToolManagerTest {
    @Test
    fun testToolParsing() {
        val toolCall = "increaseTemperature(2)"
        val key = "increaseTemperature"
        assertTrue(toolCall.lowercase().startsWith(key.lowercase()))
        
        val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 1.0
        assertEquals(2.0, value, 0.001)
    }
}
