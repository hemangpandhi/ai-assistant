package com.tcs.vehicleassistant.utils

/**
 * Zero-LLM cabin / media command matcher (UI/UX extension).
 *
 * Lives outside [FollowUpRouter] so `dev/refactor` follow-up routing can evolve
 * without conflicting with this high-churn phrase table.
 */
object DirectCabinCommandRouter {

    /**
     * @param queryLower already lowercased (or will be normalized).
     * @return a bare tool invocation string, or null if the LLM should handle the turn.
     */
    fun resolve(queryLower: String): String? {
        val q = queryLower.lowercase().trim()

        if (q.matches(Regex(""".*(turn|switch)\s+(on\s+)?(the\s+)?ac\b.*""")) ||
            q.matches(Regex(""".*(turn|switch)\s+(on\s+)?(the\s+)?air\s*condition.*""")) ||
            q.contains("enable ac") || q.contains("ac on") || q == "ac please"
        ) {
            return "turnOnAC()"
        }

        if (q.matches(Regex(""".*(turn|switch)\s+off\s+(the\s+)?ac\b.*""")) ||
            q.contains("ac off") || q.contains("disable ac")
        ) {
            return "turnOffAC()"
        }

        if (q.contains("increase temperature") || q.contains("turn up the heat") ||
            q.contains("make it warmer") || q.contains("raise the temperature") ||
            q.contains("i'm cold") || q.contains("im cold") || q.contains("it's cold") ||
            q.contains("too cold") || q == "warmer" || q == "heat up"
        ) {
            return "increaseTemperature(2)"
        }

        if (q.contains("decrease temperature") || q.contains("turn down the heat") ||
            q.contains("make it cooler") || q.contains("lower the temperature") ||
            q.contains("i'm hot") || q.contains("im hot") || q.contains("it's hot") ||
            q.contains("too hot") || q == "cooler" || q == "cool down"
        ) {
            return "decreaseTemperature(2)"
        }

        Regex("""\b(set|change)\s+(the\s+)?(temp|temperature)\s+(to\s+)?(\d{2})\b""")
            .find(q)?.groupValues?.getOrNull(5)?.let { deg ->
                return "setTemperature($deg)"
            }

        if (q.contains("increase fan") || q.contains("fan up") || q.contains("more fan") ||
            q.contains("turn up the fan") || q.contains("blow harder")
        ) {
            return "increaseFanSpeed()"
        }

        if (q.contains("decrease fan") || q.contains("fan down") || q.contains("less fan") ||
            q.contains("turn down the fan") || q.contains("blow softer")
        ) {
            return "decreaseFanSpeed()"
        }

        if (q.contains("defrost") || q.contains("defog") || q.contains("foggy windshield") ||
            q.contains("windshield is foggy")
        ) {
            return "turnOnDefroster()"
        }

        if (q.contains("seat heater") || q.contains("heat my seat") || q.contains("warm my seat") ||
            q.contains("seat warmer")
        ) {
            return "setSeatHeater(2)"
        }

        if (q.contains("recirculation") || q.contains("recirc") || q.contains("recycle air")) {
            return "turnOnRecirculation()"
        }

        if (q.contains("fresh air") || q.contains("outside air")) {
            return "enableFreshAirIntake()"
        }

        if (q.contains("play music") || q.contains("resume music") || q == "play" ||
            q.contains("start the music") || q.contains("resume playback")
        ) {
            return "playMusic(music)"
        }

        if (q.contains("pause music") || q.contains("stop music") || q == "pause" ||
            q.contains("stop the music") || q == "silence"
        ) {
            return "pauseMusic()"
        }

        if (q.contains("next song") || q.contains("skip track") || q.contains("skip song") ||
            q == "skip" || q == "next" || q.contains("next track")
        ) {
            return "nextTrack()"
        }

        if (q.contains("previous song") || q.contains("last song") || q.contains("previous track") ||
            q == "previous" || q == "go back"
        ) {
            return "prevTrack()"
        }

        if (q.contains("open the windows") || q.contains("roll down the windows") ||
            q.contains("open windows") || q.contains("crack the windows")
        ) {
            return "openWindowsSlightly()"
        }

        if (q.contains("close the windows") || q.contains("roll up the windows") ||
            q.contains("close windows") || q.contains("shut the windows")
        ) {
            return "closeAllWindows()"
        }

        return null
    }
}
