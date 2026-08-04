package com.tcs.vehicleassistant.llm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import com.tcs.vehicleassistant.MemoryManager

/**
 * Covers history construction for the cloud providers. Both managers used to ignore their
 * `userMessage` argument and send whatever `MemoryManager` happened to hold, so a change in the
 * order the orchestrator recorded turns would have sent a request without the actual question.
 */
class CloudRequestBuilderTest {

    private fun user(content: String) = MemoryManager.Turn("User", content)
    private fun assistant(content: String) = MemoryManager.Turn("Assistant", content)

    @Before
    fun clearHistory() = MemoryManager.clearMemory()

    @After
    fun tearDown() = MemoryManager.clearMemory()

    @Test
    fun `the user message is appended when history does not contain it`() {
        val result = CloudRequestBuilder.withUserMessage(
            listOf(user("hello"), assistant("hi there")),
            "what is the cabin temperature"
        )
        assertEquals(3, result.size)
        assertEquals(user("what is the cabin temperature"), result.last())
    }

    @Test
    fun `the user message is not duplicated when history already ends with it`() {
        val history = listOf(assistant("hi there"), user("what is the cabin temperature"))
        val result = CloudRequestBuilder.withUserMessage(history, "what is the cabin temperature")
        assertEquals(history, result)
    }

    @Test
    fun `duplicate detection ignores surrounding whitespace`() {
        val history = listOf(user("open the window"))
        assertEquals(history, CloudRequestBuilder.withUserMessage(history, "  open the window  "))
    }

    @Test
    fun `an identical earlier message is still appended`() {
        // Repeating a question is legitimate; only the final turn is de-duplicated.
        val history = listOf(user("are we there yet"), assistant("not yet"))
        val result = CloudRequestBuilder.withUserMessage(history, "are we there yet")
        assertEquals(3, result.size)
        assertEquals(user("are we there yet"), result.last())
    }

    @Test
    fun `an assistant turn with the same text does not suppress the user message`() {
        val history = listOf(assistant("open the window"))
        val result = CloudRequestBuilder.withUserMessage(history, "open the window")
        assertEquals(2, result.size)
        assertEquals(user("open the window"), result.last())
    }

    @Test
    fun `an empty history yields a single user turn`() {
        val result = CloudRequestBuilder.withUserMessage(emptyList(), "hello")
        assertEquals(listOf(user("hello")), result)
    }

    @Test
    fun `a blank user message leaves history untouched`() {
        val history = listOf(user("hello"))
        assertEquals(history, CloudRequestBuilder.withUserMessage(history, "   "))
    }

    @Test
    fun `the request always ends with a user turn, which both APIs require`() {
        MemoryManager.addTurn("User", "hello")
        MemoryManager.addTurn("Assistant", "hi there")

        val gemini = CloudRequestBuilder.geminiContents("set the temperature to 72")
        assertEquals("user", gemini.last().getString("role"))
        assertEquals(
            "set the temperature to 72",
            gemini.last().getJSONArray("parts").getJSONObject(0).getString("text")
        )

        val anthropic = CloudRequestBuilder.anthropicMessages("set the temperature to 72")
        assertEquals("user", anthropic.last().getString("role"))
        assertEquals("set the temperature to 72", anthropic.last().getString("content"))
    }

    @Test
    fun `gemini uses the model role for assistant turns`() {
        MemoryManager.addTurn("User", "hello")
        MemoryManager.addTurn("Assistant", "hi there")

        val roles = CloudRequestBuilder.geminiContents("and now").map { it.getString("role") }
        assertEquals(listOf("user", "model", "user"), roles)
    }

    @Test
    fun `anthropic uses the assistant role for assistant turns`() {
        MemoryManager.addTurn("User", "hello")
        MemoryManager.addTurn("Assistant", "hi there")

        val roles = CloudRequestBuilder.anthropicMessages("and now").map { it.getString("role") }
        assertEquals(listOf("user", "assistant", "user"), roles)
    }

    @Test
    fun `an empty conversation still produces a request carrying the question`() {
        val gemini = CloudRequestBuilder.geminiContents("how far to the next charger")
        assertEquals(1, gemini.size)
        assertEquals(
            "how far to the next charger",
            gemini.single().getJSONArray("parts").getJSONObject(0).getString("text")
        )
    }
}
