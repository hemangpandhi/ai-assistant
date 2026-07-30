package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the BM25 ranking that replaced `SemanticSearchManager`. The old implementation returned an
 * arbitrary slice of the catalogue for any query, so these assertions are the first real check that
 * retrieval has anything to do with what the driver said.
 */
class ToolRetrieverTest {

    private val catalogue = listOf(
        ToolRetriever.document("setTemperature", "set temperature", "temperature warmer colder degrees climate", "Sets the cabin temperature"),
        ToolRetriever.document("playMusic", "play music", "music song play track artist album", "Starts music playback"),
        ToolRetriever.document("stopMusic", "stop music", "music stop pause silence", "Stops music playback"),
        ToolRetriever.document("openWindow", "open window", "window open lower roll down", "Opens a window"),
        ToolRetriever.document("startNavigation", "start navigation", "navigate navigation directions route destination", "Starts turn-by-turn navigation")
    )

    @Test
    fun `tokenize lowercases and splits on non-alphanumerics`() {
        assertEquals(listOf("set", "cabin", "temp", "to", "72"), ToolRetriever.tokenize("Set the cabin temp to 72!"))
    }

    @Test
    fun `tokenize drops single characters, which carry no signal`() {
        // "A/C" splits into two single letters and is lost; the registry lists "ac" as a keyword.
        assertEquals(listOf("turn", "on"), ToolRetriever.tokenize("Turn the A/C on"))
    }

    @Test
    fun `tokenize drops stop words so function words cannot dominate the ranking`() {
        assertEquals(listOf("open", "driver", "window"), ToolRetriever.tokenize("Could you please open the driver window for me"))
    }

    @Test
    fun `tokenize of punctuation only yields nothing`() {
        assertTrue(ToolRetriever.tokenize("... !?").isEmpty())
    }

    @Test
    fun `climate query ranks the temperature tool first`() {
        val ranked = ToolRetriever.rank("it is too cold, raise the temperature", catalogue, topK = 3)
        assertEquals("setTemperature", ranked.first().id)
    }

    @Test
    fun `navigation query ranks the navigation tool first`() {
        val ranked = ToolRetriever.rank("give me directions to the airport", catalogue, topK = 3)
        assertEquals("startNavigation", ranked.first().id)
    }

    @Test
    fun `a term shared by two tools returns both`() {
        val ranked = ToolRetriever.rank("music", catalogue, topK = 5).map { it.id }
        assertTrue("expected both music tools, got $ranked", ranked.containsAll(listOf("playMusic", "stopMusic")))
    }

    @Test
    fun `rare terms outrank common ones`() {
        // "window" appears in one document, "music" in two, so the window tool must win a query
        // mentioning both even though the music tools match too.
        val ranked = ToolRetriever.rank("window music", catalogue, topK = 5)
        assertEquals("openWindow", ranked.first().id)
    }

    @Test
    fun `topK bounds the result size`() {
        assertEquals(2, ToolRetriever.rank("music temperature window", catalogue, topK = 2).size)
    }

    @Test
    fun `a query matching nothing returns nothing rather than an arbitrary slice`() {
        // This is the specific regression: the previous implementation answered such a query with
        // getAllTools().take(topK), injecting unrelated tools into the prompt.
        assertTrue(ToolRetriever.rank("tell me a joke about quantum physics", catalogue, topK = 4).isEmpty())
    }

    @Test
    fun `an empty query returns nothing`() {
        assertTrue(ToolRetriever.rank("", catalogue, topK = 4).isEmpty())
    }

    @Test
    fun `an empty catalogue returns nothing`() {
        assertTrue(ToolRetriever.rank("play music", emptyList(), topK = 4).isEmpty())
    }

    @Test
    fun `a non-positive topK returns nothing`() {
        assertTrue(ToolRetriever.rank("play music", catalogue, topK = 0).isEmpty())
    }

    @Test
    fun `scores are positive and strictly ordered`() {
        val ranked = ToolRetriever.rank("stop the music please", catalogue, topK = 5)
        assertTrue("expected at least two matches, got $ranked", ranked.size >= 2)
        assertTrue(ranked.all { it.score > 0.0 })
        assertEquals(ranked.sortedByDescending { it.score }.map { it.id }, ranked.map { it.id })
    }

    @Test
    fun `ranking is deterministic for tied scores`() {
        val first = ToolRetriever.rank("music", catalogue, topK = 5).map { it.id }
        val second = ToolRetriever.rank("music", catalogue.reversed(), topK = 5).map { it.id }
        assertEquals(first, second)
    }

    @Test
    fun `matching is case insensitive`() {
        val lower = ToolRetriever.rank("open the window", catalogue, topK = 1)
        val upper = ToolRetriever.rank("OPEN THE WINDOW", catalogue, topK = 1)
        assertEquals(lower.map { it.id }, upper.map { it.id })
    }

    @Test
    fun `document ignores null fields`() {
        val doc = ToolRetriever.document("id", "keyword alias", null, "description text")
        assertEquals(listOf("keyword", "alias", "description", "text"), doc.terms)
        assertFalse(doc.terms.contains("null"))
    }

    @Test
    fun `document repeats terms per field so multi-field matches outrank single ones`() {
        val doc = ToolRetriever.document("setTemperature", "temperature", "temperature climate", "Sets temperature")
        assertEquals(3, doc.terms.count { it == "temperature" })
    }
}
