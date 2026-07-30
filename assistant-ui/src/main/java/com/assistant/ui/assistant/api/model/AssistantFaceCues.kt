package com.assistant.ui.assistant.api

/**
 * Structured face anatomy cues — LLM-owned, same role as affective mood.
 *
 * Null slot = keep geometric eye / mouth / no accent.
 * Non-null eye / mouth **fully replaces** that geometric shape at the same place
 * (not necessarily the exact capsule size). Accents sit at top-left / top-right.
 */
data class AssistantFaceCues(
    val leftEye: AssistantFaceCueIcon? = null,
    val rightEye: AssistantFaceCueIcon? = null,
    val mouth: AssistantFaceCueIcon? = null,
    val leftAccent: AssistantFaceCueIcon? = null,
    val rightAccent: AssistantFaceCueIcon? = null,
) {
    val isEmpty: Boolean
        get() = leftEye == null &&
            rightEye == null &&
            mouth == null &&
            leftAccent == null &&
            rightAccent == null

    companion object {
        val Empty = AssistantFaceCues()
    }
}

/**
 * Material3-backed icon ids for face slots.
 * [key] is the LLM / tool vocabulary token (see FaceCueCatalog in :assistant-api).
 */
enum class AssistantFaceCueIcon(
    val key: String,
    val label: String,
    val category: FaceCueCategory,
) {
    Rain("rain", "Light rain", FaceCueCategory.Weather),
    Storm("storm", "Storm", FaceCueCategory.Weather),
    Snow("snow", "Snow", FaceCueCategory.Weather),
    Cloudy("cloudy", "Cloudy", FaceCueCategory.Weather),
    Sunny("sunny", "Sunny", FaceCueCategory.Weather),

    Thermostat("thermostat", "Thermostat", FaceCueCategory.Climate),
    Ac("ac", "A/C", FaceCueCategory.Climate),
    Heat("heat", "Heat", FaceCueCategory.Climate),
    Fan("fan", "Fan", FaceCueCategory.Climate),
    Defrost("defrost", "Defrost", FaceCueCategory.Climate),

    Music("music", "Music", FaceCueCategory.Media),
    Podcast("podcast", "Podcast", FaceCueCategory.Media),
    Mic("mic", "Microphone", FaceCueCategory.Media),

    Search("search", "Search", FaceCueCategory.Nav),
    Navigate("navigate", "Navigate", FaceCueCategory.Nav),

    Sparkle("sparkle", "Sparkle", FaceCueCategory.Accent),
    Star("star", "Star", FaceCueCategory.Accent),
    Wave("wave", "Wave", FaceCueCategory.Accent),
    Heart("heart", "Heart", FaceCueCategory.Accent),
    ;

    companion object {
        fun parse(raw: String?): AssistantFaceCueIcon? {
            val key = raw?.trim()?.lowercase().orEmpty()
            if (key.isEmpty() || key == "none" || key == "null" || key == "clear") return null
            return entries.firstOrNull { it.key == key }
                ?: entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
        }
    }
}

enum class FaceCueCategory {
    Weather,
    Climate,
    Media,
    Nav,
    Accent,
}
