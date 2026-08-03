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

        return WeatherFaceCueMapper.iconIdFromSpokenText(t)
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
