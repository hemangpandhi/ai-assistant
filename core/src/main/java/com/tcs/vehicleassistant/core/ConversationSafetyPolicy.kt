package com.tcs.vehicleassistant.core

/**
 * Deterministic conversation safety gate for distress / emergency utterances.
 *
 * ## Why this exists (design)
 * Edge companions historically steered *all* open/emotional turns toward "offer music"
 * so small models stay actionable and don't refuse cabin tools. That heuristic is fine
 * for "I'm bored / sad" and wrong for accidents and medical emergencies. Relying on the
 * LLM alone cannot make that distinction reliably — so safety is enforced in policy:
 * 1) classify before the model, 2) sanitize after the model, 3) lock a regression matrix.
 *
 * Expand [EMERGENCY] / [DISTRESS] / [REGRESSION_PHRASES] whenever a demo finds a gap.
 */
object ConversationSafetyPolicy {

    enum class Severity {
        /** No crisis signal. */
        None,
        /** Serious but not an immediate life-threat script (e.g. shaken after a scare). */
        Distress,
        /** Accident / injury / fire / medical emergency — highest priority script. */
        Emergency,
    }

    data class Decision(
        val severity: Severity,
        val spokenResponse: String,
    ) {
        val isCrisis: Boolean get() = severity != Severity.None
    }

    /**
     * Emergency: collision, injury, fire, can't breathe, call emergency services, etc.
     * Keep patterns explicit and test-locked — prefer false negatives over false music offers
     * on true emergencies; expand the matrix when demos uncover gaps.
     */
    private val EMERGENCY = Regex(
        """(?i)\b(""" +
            """accident|colli(?:ded|sion)|crash(?:ed|ing)?|wreck(?:ed)?|airbags? (?:went|deployed)|""" +
            """hit (?:another |a |the )?(?:car|vehicle|truck|bus)|""" +
            """rear[- ]?ended|t[- ]?boned|rolled (?:the )?(?:car|vehicle)|totaled|""" +
            """(?:car|vehicle|we|i) (?:got |was |were )?(?:into |in )?(?:an? )?accident|""" +
            """(?:someone|passenger|driver|i|we) (?:got |is |are |was |were )?(?:hurt|injured|bleeding|unconscious)|""" +
            """broken (?:arm|leg|bone|rib)|bleeding|unconscious|not breathing|can'?t breathe|cannot breathe|""" +
            """heart attack|stroke|seizure|overdose|chok(?:e|ing)|""" +
            """(?:on )?fire|smoke in (?:the )?(?:cabin|car)|car (?:is )?on fire|""" +
            """call (?:911|999|112|emergency)|emergency services|ambulance|need (?:a )?hospital""" +
            """)\b""",
    )

    /** Elevated distress that still must never get a music offer. */
    private val DISTRESS = Regex(
        """(?i)\b(""" +
            """help me|i need help|sos|mayday|""" +
            """i'?m (?:scared|terrified|panicking|in (?:danger|trouble))|""" +
            """i am (?:scared|terrified|panicking|in (?:danger|trouble))|""" +
            """we'?re in (?:danger|trouble)|pull over(?: now)?|""" +
            """someone (?:is )?(?:attacking|following) (?:me|us)""" +
            """)\b""",
    )

    private val ENTERTAINMENT_OFFER = Regex(
        """(?i)\b(""" +
            """music|playlist|song|track|tune|radio|""" +
            """play (?:some |something )?(?:relaxing |calming |upbeat )?music|""" +
            """would you like(?: me to)? play|want (?:me to play|some music)|""" +
            """lighten the mood|fun trivia""" +
            """)\b""",
    )

    private const val EMERGENCY_RESPONSE =
        "I'm here with you. Are you and any passengers okay? " +
            "I can help you call emergency services, share your location, or guide you to pull over safely. " +
            "What do you need right now?"

    private const val DISTRESS_RESPONSE =
        "I hear you — I'm with you. You're safe to talk to me. " +
            "Do you need emergency help, to pull over, or to call someone?"

    /** LLM steer when a crisis turn still reaches the model (must never suggest music). */
    const val CRISIS_CHAT_HINT =
        "[System: CRISIS turn. Reply with calm safety support only — no tools, no music, no climate, " +
            "no entertainment. Acknowledge the situation, ask if anyone is hurt, and offer emergency " +
            "call / pull over / share location. Never suggest playing music.]\n"

    fun evaluate(query: String): Decision {
        val q = query.trim()
        if (q.isEmpty() || q.startsWith("[")) {
            return Decision(Severity.None, "")
        }
        // Climate comfort must not be treated as a crisis ("I'm freezing" etc.).
        if (isClimateComfortOnly(q)) {
            return Decision(Severity.None, "")
        }
        if (EMERGENCY.containsMatchIn(q)) {
            return Decision(Severity.Emergency, EMERGENCY_RESPONSE)
        }
        if (DISTRESS.containsMatchIn(q)) {
            return Decision(Severity.Distress, DISTRESS_RESPONSE)
        }
        return Decision(Severity.None, "")
    }

    fun isCrisis(query: String): Boolean = evaluate(query).isCrisis

    /** True when entertainment / wellness music offers are forbidden for this utterance. */
    fun forbidsEntertainmentOffer(query: String): Boolean = isCrisis(query)

    fun containsEntertainmentOffer(text: String): Boolean =
        text.isNotBlank() && ENTERTAINMENT_OFFER.containsMatchIn(text)

    /**
     * Defense in depth: if the user turn is crisis and the model still offered entertainment,
     * replace with the fixed safety script. Safe no-op when there is no crisis.
     */
    fun sanitizeAssistantReply(userQuery: String, modelReply: String): String {
        val decision = evaluate(userQuery)
        if (!decision.isCrisis) return modelReply
        if (modelReply.isBlank()) return decision.spokenResponse
        return if (containsEntertainmentOffer(modelReply)) decision.spokenResponse else modelReply
    }

    /** Entertainment tools that must not run on a crisis user turn. */
    fun isEntertainmentTool(toolCall: String): Boolean {
        val name = toolCall.substringBefore('(').trim().lowercase()
        return name in setOf(
            "playmusic",
            "playmusicbyartist",
            "resumeplayback",
            "skiptrack",
            "previoustrack",
        ) || name.contains("music")
    }

    /**
     * Phrases that must never produce a music / entertainment offer in spoken output.
     * Used by regression tests and as documentation of the locked matrix.
     */
    val REGRESSION_PHRASES: List<String> = listOf(
        "my car got into an accident",
        "we were in an accident",
        "I crashed the car",
        "we just crashed",
        "the airbags deployed",
        "we were rear-ended",
        "someone is hurt",
        "I'm bleeding",
        "I can't breathe",
        "call 911",
        "call emergency services",
        "the car is on fire",
        "smoke in the cabin",
        "I need an ambulance",
        "help me",
        "I'm scared",
        "we're in danger",
        "pull over now",
    )

    private fun isClimateComfortOnly(query: String): Boolean {
        // Reuse the same climate exclusion idea as ConversationalIntent — freezing/hot is HVAC.
        return Regex(
            """(?i)\b(feeling|feel|i'?m|i am)\s+(very\s+)?(too\s+)?(hot|cold|warm|chilly|freezing)\b""" +
                """|\b(too\s+hot|too\s+cold)\b""",
        ).containsMatchIn(query) && !EMERGENCY.containsMatchIn(query) && !DISTRESS.containsMatchIn(query)
    }
}
