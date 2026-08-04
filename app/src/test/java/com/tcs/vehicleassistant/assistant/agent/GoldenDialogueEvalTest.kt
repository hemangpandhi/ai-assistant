package com.tcs.vehicleassistant.assistant.agent

import com.tcs.vehicleassistant.core.ConversationSafetyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Phase 1 #10 — golden dialogue evals for the agent turn pipeline.
 *
 * These are **routing** goldens (no LiteRT): user utterance (+ optional pending state) →
 * expected [TurnRouter.Decision] kind, and crisis replies must never offer entertainment.
 *
 * Expand [CASES] whenever a demo fails; do not "fix" by hoping the model behaves.
 */
class GoldenDialogueEvalTest {

    private val confirms = ConfirmationCoordinator()

    @Test
    fun `accident then yes does not affirm a stale music offer`() {
        confirms.setSoftOffer("playMusic(relaxing)")
        val accident = route("my car got into an accident", confirms)
        assertTrue(accident is TurnRouter.Decision.CrisisSupport)
        // Crisis dispatch clears soft offers in the orchestrator; mirror that here.
        confirms.clearOffer()

        val yes = route("yes", confirms)
        // No pending offer / confirm → not OfferAffirm / ContextGuardAffirm.
        assertFalse(yes is TurnRouter.Decision.OfferAffirm)
        assertFalse(yes is TurnRouter.Decision.ContextGuardAffirm)
        assertFalse(
            ConversationSafetyPolicy.containsEntertainmentOffer(
                (accident as TurnRouter.Decision.CrisisSupport).spokenResponse,
            ),
        )
    }

    @Test
    fun `context guard yes executes safety tool not wellness music`() {
        confirms.setConfirmation("unlockDoors()")
        val decision = route("yes", confirms)
        val affirm = decision as TurnRouter.Decision.ContextGuardAffirm
        assertEquals("unlockDoors()", affirm.toolCall)
        confirms.clearAll()
    }

    @Test
    fun `wellness yes can affirm music offer`() {
        confirms.setSoftOffer("playMusic(relaxing)")
        val decision = route("yes", confirms)
        val affirm = decision as TurnRouter.Decision.OfferAffirm
        assertEquals("playMusic(relaxing)", affirm.toolCall)
    }

    @Test
    fun `new cabin command supersedes stale soft offer`() {
        confirms.setSoftOffer("playMusic(relaxing)")
        confirms.applySupersedeIfNeeded("increase temperature")
        assertNull(confirms.pendingOfferedTool)
        val decision = route(
            "increase temperature",
            confirms,
            directHit = TurnRouter.DirectHit(
                toolCall = "increaseTemperature()",
                spokenResponse = "Warming",
                matchedKeyword = "increase temperature",
                reason = "keyword",
            ),
        )
        assertTrue(decision is TurnRouter.Decision.DirectTool)
    }

    @Test
    fun `crisis spoken scripts never mention music across matrix`() {
        for (phrase in ConversationSafetyPolicy.REGRESSION_PHRASES) {
            val decision = route(phrase, ConfirmationCoordinator())
            val crisis = decision as TurnRouter.Decision.CrisisSupport
            assertFalse(
                "music leak for '$phrase': ${crisis.spokenResponse}",
                ConversationSafetyPolicy.containsEntertainmentOffer(crisis.spokenResponse),
            )
        }
    }

    companion object {
        fun route(
            query: String,
            confirms: ConfirmationCoordinator,
            directHit: TurnRouter.DirectHit? = null,
            followUpToolCall: String? = null,
            modelReady: Boolean = true,
        ): TurnRouter.Decision {
            val normalized = TurnRouter.normalize(query)
            confirms.applySupersedeIfNeeded(normalized.query)
            val snap = confirms.snapshot()
            return TurnRouter.resolve(
                TurnRouter.Input(
                    query = normalized.query,
                    pendingConfirmationTool = snap.pendingConfirmationTool,
                    pendingOfferedTool = snap.pendingOfferedTool,
                    isAffirmativeKeepAlive = normalized.lowerLettersOnly in setOf("yes", "ok", "okay", "sure", "yep"),
                    directHit = directHit,
                    followUpToolCall = followUpToolCall,
                    modelReady = modelReady,
                    cloudModelActive = false,
                ),
            )
        }
    }
}

@RunWith(Parameterized::class)
class GoldenDialogueMatrixTest(
    private val user: String,
    private val expectedKind: String,
) {
    @Test
    fun routesToExpectedKind() {
        val decision = GoldenDialogueEvalTest.route(user, ConfirmationCoordinator())
        val kind = decision::class.simpleName
        assertEquals("for '$user'", expectedKind, kind)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} → {1}")
        fun data(): Collection<Array<String>> = listOf(
            arrayOf("my car got into an accident", "CrisisSupport"),
            arrayOf("I crashed the car", "CrisisSupport"),
            arrayOf("call 911", "CrisisSupport"),
            arrayOf("I'm scared", "CrisisSupport"),
            arrayOf("I am not feeling good", "WellnessOffer"),
            arrayOf("I'm feeling sad", "WellnessOffer"),
            arrayOf("hi", "Greeting"),
            arrayOf("thank you", "DismissSession"),
            arrayOf("tell me a joke about traffic lights", "RunLlm"),
        )
    }
}

class ConfirmationCoordinatorTest {

    @Test
    fun `setConfirmation clears soft offer`() {
        val c = ConfirmationCoordinator()
        c.setSoftOffer("playMusic(relaxing)")
        c.setConfirmation("unlockDoors()")
        assertEquals("unlockDoors()", c.pendingConfirmationTool)
        assertNull(c.pendingOfferedTool)
    }

    @Test
    fun `soft offer ignored while confirm pending`() {
        val c = ConfirmationCoordinator()
        c.setConfirmation("unlockDoors()")
        c.setSoftOffer("playMusic(relaxing)")
        assertNull(c.pendingOfferedTool)
    }

    @Test
    fun `other supersedes soft offer`() {
        val c = ConfirmationCoordinator()
        c.setSoftOffer("playMusic(relaxing)")
        val cleared = c.applySupersedeIfNeeded("navigate home")
        assertTrue(cleared.contains("SoftOffer"))
        assertNull(c.pendingOfferedTool)
    }
}
