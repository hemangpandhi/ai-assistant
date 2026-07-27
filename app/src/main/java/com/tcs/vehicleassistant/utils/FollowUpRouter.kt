package com.tcs.vehicleassistant.utils

import com.tcs.vehicleassistant.MemoryManager

/**
 * Resolves short follow-up utterances into direct tool calls using the last assistant turn.
 * Also covers high-frequency HVAC / media phrases for a zero-LLM fast path.
 */
object FollowUpRouter {

    fun extractNumberedOptions(assistantText: String): List<String> {
        val map = linkedMapOf<Int, String>()
        val patterns = listOf(
            Regex("""(?:^|[:,;\n\s])\s*(\d+)\.\s*([^,\n;.]+)"""),
            Regex("""(?:^|[:,;\n\s])\s*(\d+)\)\s*([^,\n;.]+)""")
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(assistantText)) {
                val num = match.groupValues[1].toIntOrNull() ?: continue
                val name = match.groupValues[2].trim().trimEnd('.', '!', '?', ',')
                if (name.isNotBlank()) map[num] = name
            }
        }
        if (map.isEmpty()) return emptyList()
        val max = map.keys.maxOrNull() ?: return emptyList()
        return (1..max).mapNotNull { map[it] }
    }

    fun resolveListPickIndex(query: String): Int? {
        val q = query.lowercase().trim()
        when {
            q.contains("third") || q.contains("number three") || q.contains("3rd") -> return 3
            q.contains("second") || q.contains("number two") || q.contains("2nd") -> return 2
            q.contains("first") || q.contains("number one") || q.contains("1st") -> return 1
        }
        Regex("""\b(\d+)\b""").find(q)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    /**
     * Returns a tool call to execute immediately, or null if the LLM should handle the turn.
     */
    fun resolveDirectTool(query: String, lastAssistantMessage: String): String? {
        val q = query.lowercase().trim()
        val last = lastAssistantMessage.lowercase()

        resolveDirectCommand(q)?.let { return it }

        if (MemoryManager.isAffirmative(query)) {
            when {
                last.contains("seat heater") -> return "setSeatHeater(2)"
                last.contains("gas station") || last.contains("charging station") ||
                    last.contains("find a nearby") || last.contains("find nearby") -> {
                    return if (last.contains("charg")) "searchNearby(charging)" else "searchNearby(gas)"
                }
                last.contains("navigate to") && extractNumberedOptions(lastAssistantMessage).isNotEmpty() -> {
                    val options = extractNumberedOptions(lastAssistantMessage)
                    return options.firstOrNull()?.let { navigationTool(it) }
                }
            }
        }

        if (MemoryManager.isFollowUpQuery(query)) {
            val options = extractNumberedOptions(lastAssistantMessage)
            if (options.isNotEmpty()) {
                val pickIndex = resolveListPickIndex(query) ?: return null
                val destination = options.getOrNull(pickIndex - 1) ?: return null
                return navigationTool(destination)
            }

            if (last.contains("navigate") || last.contains("which one") || last.contains("would you like to visit")) {
                val namedDest = extractNamedDestinationFromQuery(query)
                if (namedDest != null) return navigationTool(namedDest)
            }
        }

        if (isDrowsyDriverQuery(q)) {
            return "handleDrowsyDriving()"
        }

        return null
    }

    /**
     * Zero-LLM path for common cabin / media commands.
     */
    fun resolveDirectCommand(queryLower: String): String? {
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

    fun isDrowsyDriverQuery(queryLower: String): Boolean {
        val q = queryLower.lowercase()
        return q.contains("falling asleep") || q.contains("driver is asleep") ||
            q.contains("getting sleepy") || q.contains("feel drowsy") ||
            q.contains("sound_alarm") || q.contains("drowsy driving")
    }

    fun responseRequestsAlarm(response: String): Boolean {
        return response.contains("sound_alarm", ignoreCase = true) ||
            response.contains("\"action\":\"sound_alarm\"", ignoreCase = true) ||
            response.contains("\"action\": \"sound_alarm\"", ignoreCase = true)
    }

    private fun extractNamedDestinationFromQuery(query: String): String? {
        val q = query.trim()
        val patterns = listOf(
            Regex("""take me to (?:the )?(.+)""", RegexOption.IGNORE_CASE),
            Regex("""navigate to (?:the )?(.+)""", RegexOption.IGNORE_CASE),
            Regex("""go to (?:the )?(.+)""", RegexOption.IGNORE_CASE),
            Regex("""the (.+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(q) ?: continue
            val dest = match.groupValues[1].trim().trimEnd('.', '!', '?')
            if (dest.length in 3..80 && !dest.equals("one", ignoreCase = true)) return dest
        }
        return null
    }

    private fun navigationTool(destination: String): String {
        val escaped = destination.replace("\"", "\\\"")
        return "startNavigationTo(\"$escaped\")"
    }
}
