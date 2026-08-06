package com.assistant.api.face

/**
 * Infers glanceable face-cue icon ids from DirectTool spoken replies
 * (playMusic / startNavigationTo bypass the LLM `<face>` tags).
 */
object ToolFaceCueMapper {

    fun iconIdFromSpokenText(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null

        if (looksLikeMusic(t)) return "music"
        if (looksLikeSearch(t)) return "search"
        if (looksLikeNavigation(t)) return "navigate"
        climateIconId(t)?.let { return it }

        return WeatherFaceCueMapper.iconIdFromSpokenText(t)
    }

    /** HVAC / climate spoken replies → thermostat / heat / ac / fan / defrost. */
    private fun climateIconId(t: String): String? {
        val lower = t.lowercase()
        if (lower.contains("defrost") || lower.contains("defog")) return "defrost"
        if (lower.contains("fan")) return "fan"
        if (lower.contains("a/c") || lower.contains(" ac") ||
            lower.contains("air conditioning") || lower.contains("cooling it down") ||
            (lower.contains("cool") && (
                lower.contains("temp") || lower.contains("cabin") ||
                    lower.contains("things") || lower.contains("feel")
                ))
        ) {
            return "ac"
        }
        if (lower.contains("warming") || lower.contains("warmer") ||
            lower.contains("heat") || lower.contains("hotter")
        ) {
            return "heat"
        }
        if (lower.contains("temperature") || lower.contains("degrees") ||
            lower.contains("climate") || lower.contains("hvac") ||
            lower.contains("thermostat") || lower.contains("set it to")
        ) {
            return "thermostat"
        }
        return null
    }

    private fun looksLikeMusic(t: String): Boolean {
        val lower = t.lowercase()
        if (lower.contains("paused") || lower.contains("stopped") ||
            lower.contains("couldn't find an active media")
        ) {
            return false
        }
        return lower.contains("great choice") ||
            lower.contains("putting on") ||
            lower.contains("putting some music") ||
            lower.contains("playing something") ||
            (lower.contains("playing") && (lower.contains("music") || lower.contains("track"))) ||
            lower.startsWith("playing next") ||
            lower.startsWith("playing the previous")
    }

    private fun looksLikeNavigation(t: String): Boolean {
        val lower = t.lowercase()
        return lower.contains("on the road to") ||
            lower.contains("getting you on the road") ||
            lower.contains("navigat") ||
            (lower.contains("route") && lower.contains("alternate")) ||
            lower.contains("lane-level") ||
            lower.contains("lane level")
    }

    private fun looksLikeSearch(t: String): Boolean {
        val lower = t.lowercase()
        return lower.contains("on the map") ||
            (lower.contains("found these options nearby") && lower.contains("navigate")) ||
            lower.contains("search results")
    }
}
