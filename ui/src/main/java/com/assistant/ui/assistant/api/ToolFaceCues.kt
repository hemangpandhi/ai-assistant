package com.assistant.ui.assistant.api

import com.assistant.api.face.ToolFaceCueMapper

/**
 * Builds [AssistantFaceCues] for DirectTool / spoken replies (weather, music, nav).
 * Layouts match ADB presets in [com.assistant.ui.assistant.face.AssistantFaceCueReceiver].
 */
object ToolFaceCues {

    fun forIconId(iconId: String?): AssistantFaceCues? {
        val id = iconId?.trim()?.lowercase().orEmpty()
        if (id.isEmpty()) return null

        WeatherFaceCues.forIconId(id)?.let { return it }

        return when (val icon = AssistantFaceCueIcon.parse(id)) {
            AssistantFaceCueIcon.Music,
            AssistantFaceCueIcon.Podcast,
            -> music(icon)
            AssistantFaceCueIcon.Navigate -> navigate()
            AssistantFaceCueIcon.Search -> search()
            AssistantFaceCueIcon.Thermostat,
            AssistantFaceCueIcon.Ac,
            AssistantFaceCueIcon.Heat,
            AssistantFaceCueIcon.Fan,
            AssistantFaceCueIcon.Defrost,
            -> climate(icon)
            else -> null
        }
    }

    fun fromSpokenText(text: String?): AssistantFaceCues? =
        forIconId(ToolFaceCueMapper.iconIdFromSpokenText(text))

    /**
     * Music note for the island status circle ([leftEye] wins [islandStatusIcon]);
     * cheek accents keep the shell face readable.
     */
    fun music(icon: AssistantFaceCueIcon = AssistantFaceCueIcon.Music): AssistantFaceCues =
        AssistantFaceCues(
            leftEye = icon,
            leftAccent = icon,
            rightAccent = icon,
        )

    /** Centered animated map at the mouth — eyes stay geometric. */
    fun navigate(): AssistantFaceCues = AssistantFaceCues(
        mouth = AssistantFaceCueIcon.Navigate,
    )

    /** Search cue at the mouth so geometric eyes stay visible. */
    fun search(): AssistantFaceCues = AssistantFaceCues(
        mouth = AssistantFaceCueIcon.Search,
    )

    /**
     * Climate / HVAC / temperature — [leftEye] drives the island status circle;
     * mouth / accents keep the shell face readable.
     */
    fun climate(
        icon: AssistantFaceCueIcon = AssistantFaceCueIcon.Thermostat,
    ): AssistantFaceCues = AssistantFaceCues(
        leftEye = icon,
        mouth = icon,
        leftAccent = AssistantFaceCueIcon.Ac,
        rightAccent = AssistantFaceCueIcon.Heat,
    )
}
