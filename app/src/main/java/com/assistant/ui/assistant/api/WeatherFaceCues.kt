package com.assistant.ui.assistant.api

import com.assistant.api.face.WeatherFaceCueMapper

/** Builds [AssistantFaceCues] for weather icon ids from [WeatherFaceCueMapper]. */
object WeatherFaceCues {
    fun forIconId(iconId: String?): AssistantFaceCues? {
        val id = iconId?.trim()?.lowercase().orEmpty()
        if (id.isEmpty()) return null
        val icon = AssistantFaceCueIcon.parse(id) ?: return null
        return when (icon) {
            AssistantFaceCueIcon.Sunny -> AssistantFaceCues(
                leftEye = AssistantFaceCueIcon.Sunny,
                rightEye = AssistantFaceCueIcon.Sunny,
                mouth = AssistantFaceCueIcon.Cloudy,
            )
            AssistantFaceCueIcon.Rain -> AssistantFaceCues(
                leftEye = AssistantFaceCueIcon.Rain,
                rightEye = AssistantFaceCueIcon.Rain,
                mouth = AssistantFaceCueIcon.Storm,
            )
            AssistantFaceCueIcon.Storm -> AssistantFaceCues(
                leftEye = AssistantFaceCueIcon.Storm,
                rightEye = AssistantFaceCueIcon.Storm,
            )
            AssistantFaceCueIcon.Snow -> AssistantFaceCues(
                leftEye = AssistantFaceCueIcon.Snow,
                rightEye = AssistantFaceCueIcon.Snow,
            )
            AssistantFaceCueIcon.Cloudy -> AssistantFaceCues(
                leftEye = AssistantFaceCueIcon.Cloudy,
                rightEye = AssistantFaceCueIcon.Cloudy,
            )
            else -> null
        }
    }

    fun fromSpokenText(text: String?): AssistantFaceCues? =
        forIconId(WeatherFaceCueMapper.iconIdFromSpokenText(text))
}
