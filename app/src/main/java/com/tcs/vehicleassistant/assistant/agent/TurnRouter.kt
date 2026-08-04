package com.tcs.vehicleassistant.assistant.agent

import com.tcs.vehicleassistant.core.ConfirmationPolicy
import com.tcs.vehicleassistant.core.ConversationSafetyPolicy
import com.tcs.vehicleassistant.core.ConversationalIntent
import com.tcs.vehicleassistant.core.DirectToolResolver

/**
 * Pure turn routing for the agent pipeline (Phase 1 / ideal-arch `:feature:agent`).
 *
 * Decides **what** path a query should take without side effects. [AgentOrchestrator]
 * remains responsible for executing the decision (TTS, tools, LLM stream).
 *
 * Priority (after pending confirm/offer handling):
 * Crisis → Wellness → DirectTool → FollowUp → EnsureModel → LLM
 */
object TurnRouter {

    private val IGNORED_HALLUCINATIONS = setOf(
        "you", "thank you", "bye", "am", "i", "what", "blank audio", "thanks for watching", "a",
    )

    data class NormalizedQuery(
        val speakerName: String?,
        val query: String,
        val lowerLettersOnly: String,
        /** True when ASR-repeat collapse shortened the utterance. */
        val collapsedFromAsrRepeat: Boolean = false,
    )

    data class DirectHit(
        val toolCall: String,
        val spokenResponse: String?,
        val matchedKeyword: String,
        val reason: String,
    )

    data class Input(
        val query: String,
        val retryCount: Int = 0,
        val pendingConfirmationTool: String? = null,
        val pendingOfferedTool: String? = null,
        val isAffirmativeKeepAlive: Boolean = false,
        val directHit: DirectHit? = null,
        val followUpToolCall: String? = null,
        val modelReady: Boolean = true,
        val cloudModelActive: Boolean = false,
    )

    sealed class Decision {
        data class ContextGuardDecline(
            val query: String,
            val message: String = "Okay, I won't change that.",
        ) : Decision()

        data class ContextGuardAffirm(
            val query: String,
            val toolCall: String,
        ) : Decision()

        data class OfferDecline(
            val query: String,
            val declinedToolCall: String?,
            val message: String = "Okay — I won't do that. I'm here if you need anything else.",
        ) : Decision()

        data class OfferAffirm(
            val query: String,
            val toolCall: String,
            val preferredSpoken: String?,
        ) : Decision()

        data class Greeting(
            val query: String,
            val message: String = "How are you? What's on your mind? How can I help you?",
        ) : Decision()

        data class DismissSession(val query: String) : Decision()

        data class CrisisSupport(
            val query: String,
            val spokenResponse: String,
            val severityName: String,
        ) : Decision()

        data class WellnessOffer(val query: String) : Decision()

        data class DirectTool(
            val query: String,
            val toolCall: String,
            val preferredSpoken: String?,
            val matchedKeyword: String,
            val reason: String,
        ) : Decision()

        data class FollowUp(
            val query: String,
            val toolCall: String,
        ) : Decision()

        data class EnsureModelThenRetry(
            val query: String,
            val retryCount: Int,
        ) : Decision()

        data class RunLlm(
            val query: String,
            val retryCount: Int,
        ) : Decision()
    }

    fun normalize(rawQuery: String, directToolResolver: com.tcs.vehicleassistant.core.DirectToolResolver, defaultSpeaker: String? = null): NormalizedQuery {
        var rawTrimmed = rawQuery.trim()
        var speaker: String? = defaultSpeaker

        val seatTagRegex = Regex("^\\[Seat:\\s*(.*?)\\]\\s*(.*)")
        val matchResult = seatTagRegex.matchEntire(rawTrimmed)
        if (matchResult != null) {
            speaker = matchResult.groupValues[1]
            rawTrimmed = matchResult.groupValues[2]
        }

        val normalizedFull = directToolResolver.normalize(rawTrimmed)
        val collapsed = directToolResolver.collapseAsrRepeats(rawTrimmed)
        val collapsedApplied = collapsed.isNotBlank() &&
            collapsed.split(' ').size < normalizedFull.split(' ').size
        val trimmedQuery = if (collapsedApplied) collapsed else rawTrimmed
        val lowerQuery = trimmedQuery.lowercase().replace(Regex("[^a-z ]"), "").trim()
        return NormalizedQuery(
            speakerName = speaker,
            query = trimmedQuery,
            lowerLettersOnly = lowerQuery,
            collapsedFromAsrRepeat = collapsedApplied,
        )
    }

    fun resolve(input: Input): Decision {
        val query = input.query.trim()
        val lowerQuery = query.lowercase().replace(Regex("[^a-z ]"), "").trim()
        val isSystemEvent = query.startsWith("[")

        var pendingConfirm = input.pendingConfirmationTool
        var pendingOffer = input.pendingOfferedTool

        // 1) ContextGuard confirmation
        if (pendingConfirm != null && !isSystemEvent) {
            when (ConfirmationPolicy.classify(query)) {
                ConfirmationPolicy.Reply.DECLINE ->
                    return Decision.ContextGuardDecline(query)
                ConfirmationPolicy.Reply.AFFIRM ->
                    return Decision.ContextGuardAffirm(query, pendingConfirm)
                ConfirmationPolicy.Reply.OTHER -> {
                    // Stale confirm superseded — continue routing this utterance.
                    pendingConfirm = null
                }
            }
        }

        // 2) Soft offer affirmation
        if (pendingOffer != null && pendingConfirm == null && !isSystemEvent) {
            when (ConfirmationPolicy.classify(query)) {
                ConfirmationPolicy.Reply.DECLINE ->
                    return Decision.OfferDecline(query, pendingOffer)
                ConfirmationPolicy.Reply.AFFIRM -> {
                    val preferred = if (pendingOffer.startsWith("playMusic")) {
                        "Sure — playing something calming for you."
                    } else {
                        null
                    }
                    return Decision.OfferAffirm(query, pendingOffer, preferred)
                }
                ConfirmationPolicy.Reply.OTHER -> {
                    pendingOffer = null
                }
            }
        }

        // 3) Ghost / greeting filter
        if (!isSystemEvent && !input.isAffirmativeKeepAlive) {
            if (query.isBlank() || lowerQuery.length < 3) {
                return Decision.Greeting(query)
            }
            if (IGNORED_HALLUCINATIONS.contains(lowerQuery)) {
                return Decision.DismissSession(query)
            }
        }

        // 4) Crisis before cabin shortcuts — safety outranks DirectTool / wellness.
        if (pendingConfirm == null) {
            val crisis = ConversationSafetyPolicy.evaluate(query)
            if (crisis.isCrisis) {
                return Decision.CrisisSupport(
                    query = query,
                    spokenResponse = crisis.spokenResponse,
                    severityName = crisis.severity.name,
                )
            }
        }

        // 5) Mild wellness
        if (pendingConfirm == null && ConversationalIntent.isEmotionalOrWellness(query)) {
            return Decision.WellnessOffer(query)
        }

        // 6) DirectTool
        if (pendingConfirm == null && input.directHit != null) {
            val hit = input.directHit
            return Decision.DirectTool(
                query = query,
                toolCall = hit.toolCall,
                preferredSpoken = hit.spokenResponse,
                matchedKeyword = hit.matchedKeyword,
                reason = hit.reason,
            )
        }

        // 7) Follow-up
        if (pendingConfirm == null && !input.followUpToolCall.isNullOrBlank()) {
            return Decision.FollowUp(query, input.followUpToolCall)
        }

        // 8) Ensure on-device model, else LLM
        if (!input.cloudModelActive && !input.modelReady) {
            return Decision.EnsureModelThenRetry(query, input.retryCount)
        }
        return Decision.RunLlm(query, input.retryCount)
    }

    internal fun isIgnoredHallucination(lowerLettersOnly: String): Boolean =
        IGNORED_HALLUCINATIONS.contains(lowerLettersOnly)
}
