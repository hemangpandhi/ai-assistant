package com.tcs.vehicleassistant.core

/**
 * Deterministic conversation safety gate for distress / emergency utterances.
 *
 * Wellness / companion prompts elsewhere in the stack default to "offer music".
 * That is wrong for accidents, medical emergencies, and similar crises. This policy
 * short-circuits those turns with fixed scripts and forbids entertainment offers.
 *
 * This does **not** cover every possible phrasing in the world — it covers an explicit
 * regression matrix. Expand [EMERGENCY] / [DISTRESS] when new failures are found.
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
            """accident|colli(?:ded|sion)|crash(?:ed|ing)?|hit (?:another |a |the )?(?:car|vehicle|truck|bus)|""" +
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

    /**
     * Phrases that must never produce a music / entertainment offer in spoken output.
     * Used by regression tests and as documentation of the locked matrix.
     */
    val REGRESSION_PHRASES: List<String> = listOf(
        "my car got into an accident",
        "we were in an accident",
        "I crashed the car",
        "we just crashed",
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
