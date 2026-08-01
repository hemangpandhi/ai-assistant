package com.tcs.vehicleassistant

import com.tcs.vehicleassistant.assistant.AssistantIdleTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantIdleTimeoutTest {

    @Test
    fun parse_validSeconds() {
        assertEquals(5, AssistantIdleTimeout.parse("5"))
        assertEquals(0, AssistantIdleTimeout.parse("0"))
        assertEquals(30, AssistantIdleTimeout.parse(" 30 "))
    }

    @Test
    fun parse_clampsAndRejects() {
        assertEquals(AssistantIdleTimeout.MAX_SEC, AssistantIdleTimeout.parse("9999"))
        assertNull(AssistantIdleTimeout.parse(""))
        assertNull(AssistantIdleTimeout.parse(null))
        assertNull(AssistantIdleTimeout.parse("abc"))
        assertNull(AssistantIdleTimeout.parse("-1"))
    }

    @Test
    fun clamp_bounds() {
        assertEquals(0, AssistantIdleTimeout.clamp(-3))
        assertEquals(5, AssistantIdleTimeout.clamp(5))
        assertEquals(AssistantIdleTimeout.MAX_SEC, AssistantIdleTimeout.clamp(10_000))
    }

    @Test
    fun defaultIsFiveSeconds() {
        assertEquals(5, AssistantIdleTimeout.DEFAULT_SEC)
    }
}
