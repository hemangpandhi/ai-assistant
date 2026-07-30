package com.tcs.vehicleassistant

import com.tcs.vehicleassistant.core.AssistantConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Covers conversation history retention and the locking added around it. `conversationHistory` used
 * to grow for the whole process lifetime and was iterated without synchronization while the
 * orchestrator appended turns from another dispatcher.
 */
class MemoryManagerHistoryTest {

    @Before
    fun clearHistory() = MemoryManager.clearMemory()

    @After
    fun tearDown() = MemoryManager.clearMemory()

    @Test
    fun `turns are recorded in order`() {
        MemoryManager.addTurn("User", "turn on the ac")
        MemoryManager.addTurn("Assistant", "Air conditioning is on.")

        val snapshot = MemoryManager.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals(MemoryManager.Turn("User", "turn on the ac"), snapshot[0])
        assertEquals(MemoryManager.Turn("Assistant", "Air conditioning is on."), snapshot[1])
    }

    @Test
    fun `blank turns are dropped`() {
        MemoryManager.addTurn("User", "")
        MemoryManager.addTurn("Assistant", "   \n ")
        assertEquals(0, MemoryManager.turnCount())
    }

    @Test
    fun `content is trimmed`() {
        MemoryManager.addTurn("User", "  open the window  ")
        assertEquals("open the window", MemoryManager.snapshot().single().content)
    }

    @Test
    fun `history is capped at the configured retention limit`() {
        val cap = AssistantConfig.Memory.MAX_RETAINED_TURNS
        repeat(cap * 3) { MemoryManager.addTurn("User", "turn number $it") }
        assertEquals(cap, MemoryManager.turnCount())
    }

    @Test
    fun `capping evicts the oldest turns and keeps the newest`() {
        val cap = AssistantConfig.Memory.MAX_RETAINED_TURNS
        repeat(cap + 5) { MemoryManager.addTurn("User", "turn number $it") }

        val snapshot = MemoryManager.snapshot()
        assertEquals("turn number 5", snapshot.first().content)
        assertEquals("turn number ${cap + 4}", snapshot.last().content)
    }

    @Test
    fun `snapshot is an immutable copy that later turns do not mutate`() {
        MemoryManager.addTurn("User", "first")
        val snapshot = MemoryManager.snapshot()
        MemoryManager.addTurn("User", "second")
        assertEquals(1, snapshot.size)
    }

    @Test
    fun `sliding window keeps the most recent turns oldest first`() {
        MemoryManager.addTurn("User", "AAAA")
        MemoryManager.addTurn("Assistant", "BBBB")
        MemoryManager.addTurn("User", "CCCC")

        assertEquals("User: AAAA\nAssistant: BBBB\nUser: CCCC", MemoryManager.getSlidingWindowContext())
    }

    @Test
    fun `sliding window drops the oldest turns once the character budget is spent`() {
        MemoryManager.addTurn("User", "A".repeat(100))
        MemoryManager.addTurn("Assistant", "B".repeat(100))
        MemoryManager.addTurn("User", "recent")

        val window = MemoryManager.getSlidingWindowContext(maxChars = 40)
        assertEquals("User: recent", window)
    }

    @Test
    fun `sliding window does not mutate stored history`() {
        MemoryManager.addTurn("User", "A".repeat(100))
        MemoryManager.addTurn("User", "recent")
        MemoryManager.getSlidingWindowContext(maxChars = 20)
        assertEquals(2, MemoryManager.turnCount())
    }

    @Test
    fun `clearMemory empties history`() {
        MemoryManager.addTurn("User", "something")
        MemoryManager.clearMemory()
        assertEquals(0, MemoryManager.turnCount())
        assertTrue(MemoryManager.getSlidingWindowContext().isEmpty())
    }

    @Test
    fun `cloud history maps roles for each provider`() {
        MemoryManager.addTurn("User", "hello")
        MemoryManager.addTurn("Assistant", "hi there")

        val anthropic = MemoryManager.getAnthropicHistory()
        assertEquals(listOf("user", "assistant"), anthropic.map { it.getString("role") })
        assertEquals("hi there", anthropic[1].getString("content"))

        val gemini = MemoryManager.getGeminiHistory()
        assertEquals(listOf("user", "model"), gemini.map { it.getString("role") })
        assertEquals(
            "hi there",
            gemini[1].getJSONArray("parts").getJSONObject(0).getString("text")
        )
    }

    @Test
    fun `concurrent writes and reads neither lose turns nor throw`() {
        // Reproduces the shape of the original defect: appends from one dispatcher while another
        // iterates the list to assemble a prompt.
        val writers = 4
        val turnsPerWriter = 200
        val pool = Executors.newFixedThreadPool(writers + 2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers + 2)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        repeat(writers) { writer ->
            pool.submit {
                try {
                    start.await()
                    repeat(turnsPerWriter) { MemoryManager.addTurn("User", "w$writer-$it") }
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    done.countDown()
                }
            }
        }
        repeat(2) {
            pool.submit {
                try {
                    start.await()
                    repeat(turnsPerWriter) {
                        MemoryManager.getSlidingWindowContext()
                        MemoryManager.getGeminiHistory()
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue("workers did not finish in time", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("concurrent access threw: $failures", emptyList<Throwable>(), failures)
        assertEquals(AssistantConfig.Memory.MAX_RETAINED_TURNS, MemoryManager.turnCount())
    }
}
