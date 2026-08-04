package com.tcs.vehicleassistant.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks Phase-1 turn routing: crisis outranks DirectTool/wellness; confirms beat free routing.
 */
class TurnRouterTest {

    @Test
    fun `accident routes to crisis even when a direct tool hit is present`() {
        val decision = TurnRouter.resolve(
            TurnRouter.Input(
                query = "my car got into an accident",
                directHit = TurnRouter.DirectHit(
                    toolCall = "playMusic(music)",
                    spokenResponse = "Playing",
                    matchedKeyword = "accident",
                    reason = "test",
                ),
                modelReady = true,
            ),
        )
        assertTrue(decision is TurnRouter.Decision.CrisisSupport)
    }

    @Test
    fun `mild wellness routes to wellness offer`() {
        val decision = TurnRouter.resolve(
            TurnRouter.Input(query = "I am not feeling good", modelReady = true),
        )
        assertTrue(decision is TurnRouter.Decision.WellnessOffer)
    }

    @Test
    fun `direct tool wins when no crisis or wellness`() {
        val decision = TurnRouter.resolve(
            TurnRouter.Input(
                query = "increase temperature",
                directHit = TurnRouter.DirectHit(
                    toolCall = "increaseTemperature()",
                    spokenResponse = "Warming",
                    matchedKeyword = "increase temperature",
                    reason = "keyword",
                ),
                modelReady = true,
            ),
        )
        val direct = decision as TurnRouter.Decision.DirectTool
        assertEquals("increaseTemperature()", direct.toolCall)
    }

    @Test
    fun `follow up used when no direct hit`() {
        val decision = TurnRouter.resolve(
            TurnRouter.Input(
                query = "yes",
                isAffirmativeKeepAlive = true,
                followUpToolCall = "playMusic(relaxing)",
                modelReady = true,
            ),
        )
        assertTrue(decision is TurnRouter.Decision.FollowUp)
    }

    @Test
    fun `context guard affirm wins over crisis and tools`() {
        val decision = TurnRouter.resolve(
            TurnRouter.Input(
                query = "yes",
                pendingConfirmationTool = "unlockDoors()",
                directHit = TurnRouter.DirectHit(
                    toolCall = "playMusic(music)",
                    spokenResponse = null,
                    matchedKeyword = "yes",
                    reason = "test",
                ),
                modelReady = true,
            ),
        )
        val affirm = decision as TurnRouter.Decision.ContextGuardAffirm
        assertEquals("unlockDoors()", affirm.toolCall)
    }

    @Test
    fun `offer decline clears soft music offer path`() {
        val decision = TurnRouter.resolve(
            TurnRouter.Input(
                query = "no",
                pendingOfferedTool = "playMusic(relaxing)",
                modelReady = true,
            ),
        )
        assertTrue(decision is TurnRouter.Decision.OfferDecline)
    }

    @Test
    fun `short blank becomes greeting`() {
        val decision = TurnRouter.resolve(TurnRouter.Input(query = "hi", modelReady = true))
        assertTrue(decision is TurnRouter.Decision.Greeting)
    }

    @Test
    fun `hallucination token dismisses session`() {
        val decision = TurnRouter.resolve(TurnRouter.Input(query = "thank you", modelReady = true))
        assertTrue(decision is TurnRouter.Decision.DismissSession)
    }

    @Test
    fun `unready edge model requests ensure then retry`() {
        val decision = TurnRouter.resolve(
            TurnRouter.Input(
                query = "what is the weather",
                modelReady = false,
                cloudModelActive = false,
            ),
        )
        assertTrue(decision is TurnRouter.Decision.EnsureModelThenRetry)
    }

    @Test
    fun `ready model runs llm`() {
        val decision = TurnRouter.resolve(
            TurnRouter.Input(query = "tell me a joke about traffic", modelReady = true),
        )
        assertTrue(decision is TurnRouter.Decision.RunLlm)
    }

    @Test
    fun `normalize strips seat tag`() {
        val n = TurnRouter.normalize("[Seat: Driver] increase temperature")
        assertEquals("Driver", n.speakerName)
        assertEquals("increase temperature", n.query)
    }
}
