package com.assistant.api.face

/**
 * Maps live weather (WMO code / spoken condition) to [FaceCueCatalog] weather icon ids.
 * DirectTool `getWeather` bypasses the LLM, so cues are applied from tool results.
 */
object WeatherFaceCueMapper {

    /** Canonical face-cue icon id for an Open-Meteo WMO weather code, or null if unknown. */
    fun iconIdForWmo(code: Int): String? = when (code) {
        0, 1 -> "sunny"
        2, 3, 45, 48 -> "cloudy"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
        71, 73, 75, 77, 85, 86 -> "snow"
        95, 96, 99 -> "storm"
        else -> null
    }

    /** Canonical face-cue icon id for a spoken / WMO condition phrase. */
    fun iconIdForCondition(condition: String?): String? {
        val c = condition?.trim()?.lowercase().orEmpty()
        if (c.isEmpty()) return null
        return when {
            c.contains("thunder") || c.contains("storm") || c.contains("hail") -> "storm"
            c.contains("snow") -> "snow"
            c.contains("rain") || c.contains("drizzle") || c.contains("shower") -> "rain"
            c.contains("fog") || c.contains("overcast") || c.contains("cloud") -> "cloudy"
            c.contains("clear") || c.contains("sunny") -> "sunny"
            c.contains("mixed") -> "cloudy"
            else -> null
        }
    }

    /**
     * Infer a weather face-cue id from DirectTool spoken weather text
     * (e.g. "The current weather in Tokyo is rain, about 55 degrees…").
     */
    fun iconIdFromSpokenText(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val looksLikeWeather =
            t.contains("current weather", ignoreCase = true) ||
                t.contains("weather in", ignoreCase = true) ||
                (t.contains("degrees Fahrenheit", ignoreCase = true) &&
                    t.contains("humidity", ignoreCase = true))
        if (!looksLikeWeather) {
            // Umbrella tip / rainy destination hints
            if (t.contains("rain", ignoreCase = true) &&
                (t.contains("umbrella", ignoreCase = true) || t.contains("destination", ignoreCase = true))
            ) {
                return "rain"
            }
            return null
        }
        // "… is <condition>, about N degrees …"
        val match = Regex(
            """\bis\s+(.+?),\s*about\s+\d+""",
            RegexOption.IGNORE_CASE,
        ).find(t)
        val condition = match?.groupValues?.getOrNull(1)
        return iconIdForCondition(condition)
    }

    /** Silent `<face …/>` tag for [iconId], or empty when unknown. */
    fun faceTag(iconId: String?): String {
        val id = iconId?.takeIf { it in FaceCueCatalog.iconIds } ?: return ""
        val mouth = mouthSlot(id)
        return """<face left_eye="$id" right_eye="$id" mouth="$mouth"/>"""
    }

    private fun mouthSlot(iconId: String): String = when (iconId) {
        "sunny" -> "cloudy"
        "rain" -> "storm"
        else -> "none"
    }
}
