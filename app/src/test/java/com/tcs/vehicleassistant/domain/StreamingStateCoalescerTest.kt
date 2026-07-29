package com.tcs.vehicleassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingStateCoalescerTest {
    @Test
    fun forceEmitsImmediately() {
        val emitted = mutableListOf<String>()
        val c = StreamingStateCoalescer(intervalMs = 10_000L, emit = { emitted += it })
        c.offer("a", force = true)
        assertEquals(listOf("a"), emitted)
    }

    @Test
    fun flushEmitsPending() {
        val emitted = mutableListOf<String>()
        var now = 0L
        val c = StreamingStateCoalescer(
            intervalMs = 100L,
            emit = { emitted += it },
            clock = { now },
        )
        c.offer("first", force = true)
        now = 10L
        c.offer("second") // coalesced, not yet emitted
        assertEquals(listOf("first"), emitted)
        c.flush()
        assertEquals(listOf("first", "second"), emitted)
    }
}

class TtsTurnIdsTest {
    @Test
    fun matchRejectsStaleGeneration() {
        val ids = TtsTurnIds()
        val id = ids.id("STATEMENT_FINAL")
        ids.advance()
        assertNull(ids.match(id))
    }

    @Test
    fun matchAcceptsCurrent() {
        val ids = TtsTurnIds()
        ids.advance()
        val id = ids.id("QUESTION_FINAL")
        assertEquals("QUESTION_FINAL", ids.match(id))
    }
}
