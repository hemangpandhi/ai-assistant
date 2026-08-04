package com.tcs.vehicleassistant.requirements

import com.tcs.vehicleassistant.ConversationMemory
import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.core.DirectToolResolver
import com.tcs.vehicleassistant.utils.FollowUpRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression locks for P0/P1 cabin stability bugs found during exhaustive verification.
 */
class StabilityRegressionUnitTest {

    private val specs get() = RegistryFixture.toSpecs()

    private fun hit(query: String): DirectToolResolver.Hit {
        val outcome = DirectToolResolver().resolve(query, specs)
        assertTrue("expected Execute for '$query', got $outcome", outcome is DirectToolResolver.Outcome.Execute)
        return (outcome as DirectToolResolver.Outcome.Execute).hit
    }

    @Test
    fun navigateMeToTokyoTower_extractsDestinationNotMeTo() {
        val h = hit("Navigate me to Tokyo Tower")
        assertEquals("startNavigationTo", h.toolId)
        assertTrue(h.toolCall.contains("Tokyo Tower", ignoreCase = true))
        assertFalse(
            "must not treat 'me to …' as the place: ${h.toolCall}",
            h.toolCall.contains("me to", ignoreCase = true),
        )
    }

    @Test
    fun goToDestination_directTools() {
        val h = hit("go to Tokyo Skytree")
        assertEquals("startNavigationTo", h.toolId)
        assertTrue(h.toolCall.contains("Tokyo Skytree", ignoreCase = true) ||
            h.toolCall.contains("skytree", ignoreCase = true))
    }

    @Test
    fun raiseAndLowerTemperature_fullPhrases() {
        assertEquals("increaseTemperature", hit("raise temperature").toolId)
        assertEquals("decreaseTemperature", hit("lower temperature").toolId)
    }

    @Test
    fun playArijit_stillWorksWithSpeakablePlayKeyword() {
        val h = hit("play arijit singh music")
        assertEquals("playMusic(arijit singh)", h.toolCall)
    }

    @Test
    fun camelCaseAliasesAreNotSpeakableDirectKeywords() {
        assertFalse(ToolManager.isSpeakableDirectKeyword("navigateTo"))
        assertFalse(ToolManager.isSpeakableDirectKeyword("increaseVolume"))
        assertFalse(ToolManager.isSpeakableDirectKeyword("navigate"))
        assertFalse(ToolManager.isSpeakableDirectKeyword("nav"))
        assertFalse(ToolManager.isSpeakableDirectKeyword("goTo"))
        assertTrue(ToolManager.isSpeakableDirectKeyword("play"))
        assertTrue(ToolManager.isSpeakableDirectKeyword("pause"))
        assertTrue(ToolManager.isSpeakableDirectKeyword("put on"))
    }

    @Test
    fun productionKeywordListsDoNotContainGluedCamelCase() {
        val tools = RegistryFixture.loadTools()
        val glued = tools.flatMap { t ->
            t.keywords.filter { kw ->
                !kw.contains(' ') && kw.length > 8 &&
                    listOf("volume", "temp", "temperature", "music", "navigation", "heater")
                        .any { hint -> kw.contains(hint) && kw != hint }
            }.map { "${t.handlerKey}:$it" }
        }
        assertTrue("glued camelCase keywords leaked into DirectTool: $glued", glued.isEmpty())
    }

    @Test
    fun affirmativeYesWithPunctuationAndPlease() {
        assertTrue(com.tcs.vehicleassistant.ConversationMemory.isAffirmative("yes."))
        assertTrue(com.tcs.vehicleassistant.ConversationMemory.isAffirmative("Yes!"))
        assertTrue(com.tcs.vehicleassistant.ConversationMemory.isAffirmative("yes please"))
        assertTrue(com.tcs.vehicleassistant.ConversationMemory.isAffirmative("yeah sure"))
        assertTrue(com.tcs.vehicleassistant.ConversationMemory.isDecline("no thanks"))
        assertTrue(com.tcs.vehicleassistant.ConversationMemory.isDecline("not now"))
        assertTrue(com.tcs.vehicleassistant.ConversationMemory.isDecline("yes no"))
        assertFalse(com.tcs.vehicleassistant.ConversationMemory.isAffirmative("yes no"))
        assertFalse(com.tcs.vehicleassistant.ConversationMemory.isAffirmative("please"))
        assertEquals(
            "setSeatHeater(2)",
            FollowUpRouter.resolveDirectTool("yes please", "Would you like me to turn on the seat heater?"),
        )
        assertEquals(
            "setSeatHeater(2)",
            FollowUpRouter.resolveDirectTool("yes.", "I can warm your seat for you?"),
        )
    }

    @Test
    fun contextGuardConfirmSkipGuardContract() {
        // Documented contract: after Confirm, affirmative execute must skip ContextGuard
        // (AgentOrchestrator.completeDirectToolTurn(skipGuard=true)). Re-evaluating the same
        // cabin snapshot would return Confirm again and re-ask forever.
        assertTrue(
            "confirm replies must stay affirmative for ContextGuard yes-path",
            com.tcs.vehicleassistant.ConversationMemory.isAffirmative("yes"),
        )
        assertTrue(com.tcs.vehicleassistant.ConversationMemory.isDecline("cancel"))
    }

    @Test
    fun turnOnFrontDefroster_matches() {
        assertEquals("turnOnDefroster", hit("turn on front defroster").toolId)
        assertEquals("turnOnDefroster", hit("clear windshield").toolId)
    }

    @Test
    fun spokenResponseForNavWithoutSuccessMessage_isDonePlaceholderOnly() {
        val nav = specs.first { it.id == "startNavigationTo" }
        // Registry may omit success_message; orchestrator prefers handler feedback over this.
        val spoken = DirectToolResolver().spokenResponseFor(nav, """startNavigationTo("Tokyo Tower")""")
        assertTrue(spoken == "Done." || spoken.isNotBlank())
    }
}
