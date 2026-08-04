package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Locks the crisis matrix so accident / emergency turns never regress to music offers.
 *
 * Guarantee model: every phrase in [ConversationSafetyPolicy.REGRESSION_PHRASES] must
 * (1) classify as crisis, (2) produce a script with no entertainment language, and
 * (3) set [ConversationSafetyPolicy.forbidsEntertainmentOffer].
 * Expand the matrix when a new bad demo is found — do not "fix" by hoping the LLM behaves.
 */
class ConversationSafetyPolicyTest {

    @Test
    fun `accident report is emergency and never offers music`() {
        val decision = ConversationSafetyPolicy.evaluate("my car got into an accident")
        assertEquals(ConversationSafetyPolicy.Severity.Emergency, decision.severity)
        assertFalse(containsEntertainmentOffer(decision.spokenResponse))
        assertTrue(decision.spokenResponse.contains("emergency", ignoreCase = true))
    }

    @Test
    fun `regression matrix all phrases are crisis without entertainment`() {
        for (phrase in ConversationSafetyPolicy.REGRESSION_PHRASES) {
            val decision = ConversationSafetyPolicy.evaluate(phrase)
            assertTrue("expected crisis for: $phrase", decision.isCrisis)
            assertTrue(
                "forbidsEntertainmentOffer must be true for: $phrase",
                ConversationSafetyPolicy.forbidsEntertainmentOffer(phrase),
            )
            assertFalse(
                "crisis script must not offer entertainment for: $phrase → ${decision.spokenResponse}",
                containsEntertainmentOffer(decision.spokenResponse),
            )
        }
    }

    @Test
    fun `crisis chat hint forbids music`() {
        val hint = ConversationSafetyPolicy.CRISIS_CHAT_HINT.lowercase()
        assertTrue(hint.contains("crisis"))
        assertTrue(hint.contains("never suggest playing music") || hint.contains("no music"))
    }

    @Test
    fun `cabin and mild wellness are not crisis`() {
        assertFalse(ConversationSafetyPolicy.isCrisis("play some music"))
        assertFalse(ConversationSafetyPolicy.isCrisis("I'm feeling sad"))
        assertFalse(ConversationSafetyPolicy.isCrisis("I'm feeling cold"))
        assertFalse(ConversationSafetyPolicy.isCrisis("turn on the AC"))
    }

    @Test
    fun `empty model fallback uses crisis script not wellness music`() {
        val spoken = com.tcs.vehicleassistant.repository.AgentOrchestrator
            .resolveEmptyModelFallback("my car got into an accident")
        assertFalse(containsEntertainmentOffer(spoken))
        assertTrue(spoken.contains("emergency", ignoreCase = true) || spoken.contains("hurt", ignoreCase = true))
    }

    @Test
    fun `mild wellness fallback may still offer music`() {
        val spoken = com.tcs.vehicleassistant.repository.AgentOrchestrator
            .resolveEmptyModelFallback("I am not feeling good")
        assertTrue(spoken.contains("music", ignoreCase = true))
    }

    @Test
    fun `sanitize replaces music reply after accident`() {
        val cleaned = ConversationSafetyPolicy.sanitizeAssistantReply(
            userQuery = "my car got into an accident",
            modelReply = "I'm sorry. Would you like me to play some music?",
        )
        assertFalse(ConversationSafetyPolicy.containsEntertainmentOffer(cleaned))
        assertTrue(cleaned.contains("emergency", ignoreCase = true) || cleaned.contains("okay", ignoreCase = true))
    }

    @Test
    fun `sanitize leaves mild wellness music intact`() {
        val reply = "I'm sorry you're not feeling well. Would you like me to play some music?"
        val cleaned = ConversationSafetyPolicy.sanitizeAssistantReply(
            userQuery = "I am not feeling good",
            modelReply = reply,
        )
        assertEquals(reply, cleaned)
    }

    @Test
    fun `entertainment tools are detected`() {
        assertTrue(ConversationSafetyPolicy.isEntertainmentTool("playMusic(relaxing)"))
        assertFalse(ConversationSafetyPolicy.isEntertainmentTool("increaseTemperature()"))
    }

    companion object {
        fun containsEntertainmentOffer(text: String): Boolean =
            ConversationSafetyPolicy.containsEntertainmentOffer(text)
    }
}

@RunWith(Parameterized::class)
class ConversationSafetyRegressionParameterizedTest(
    private val phrase: String,
) {
    @Test
    fun phraseIsCrisisWithoutMusicOffer() {
        val decision = ConversationSafetyPolicy.evaluate(phrase)
        assertTrue(decision.isCrisis)
        assertFalse(ConversationSafetyPolicyTest.containsEntertainmentOffer(decision.spokenResponse))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<String>> =
            ConversationSafetyPolicy.REGRESSION_PHRASES.map { arrayOf(it) }
    }
}
