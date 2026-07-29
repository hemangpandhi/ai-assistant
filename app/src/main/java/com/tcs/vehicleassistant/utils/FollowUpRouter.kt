package com.tcs.vehicleassistant.utils

import com.tcs.vehicleassistant.data.memory.ConversationMemory
import com.tcs.vehicleassistant.data.memory.MemoryManagerStore

/**
 * Resolves short follow-up utterances into direct tool calls using the last assistant turn.
 *
 * Zero-LLM cabin/media phrase matching is delegated to [DirectCabinCommandRouter]
 * (UI/UX extension) so this object can track `dev/refactor` more closely.
 */
object FollowUpRouter {

    private val defaultMemory: ConversationMemory = MemoryManagerStore()

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
    fun resolveDirectTool(
        query: String,
        lastAssistantMessage: String,
        memory: ConversationMemory = defaultMemory,
    ): String? {
        val q = query.lowercase().trim()
        val last = lastAssistantMessage.lowercase()

        // ui_ux extension seam — cabin commands live in DirectCabinCommandRouter
        DirectCabinCommandRouter.resolve(q)?.let { return it }

        if (memory.isAffirmative(query)) {
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

        if (memory.isFollowUpQuery(query)) {
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

    /** @deprecated Prefer [DirectCabinCommandRouter.resolve]; kept for call-site compatibility. */
    fun resolveDirectCommand(queryLower: String): String? =
        DirectCabinCommandRouter.resolve(queryLower)

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
