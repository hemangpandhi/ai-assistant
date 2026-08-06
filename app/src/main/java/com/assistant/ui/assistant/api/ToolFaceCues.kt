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

        return when (AssistantFaceCueIcon.parse(id)) {
            AssistantFaceCueIcon.Music,
            AssistantFaceCueIcon.Podcast,
            -> music()
            AssistantFaceCueIcon.Navigate -> navigate()
            AssistantFaceCueIcon.Search -> search()
            else -> null
        }
    }

    fun fromSpokenText(text: String?): AssistantFaceCues? =
        forIconId(ToolFaceCueMapper.iconIdFromSpokenText(text))

    /** Geometric eyes + two animated music notes on the cheeks (no center icon). */
    fun music(): AssistantFaceCues = AssistantFaceCues(
        leftAccent = AssistantFaceCueIcon.Music,
        rightAccent = AssistantFaceCueIcon.Music,
    )

    /** Centered animated map at the mouth — eyes stay geometric. */
    fun navigate(): AssistantFaceCues = AssistantFaceCues(
        mouth = AssistantFaceCueIcon.Navigate,
    )

    /** Search cue at the mouth so geometric eyes stay visible. */
    fun search(): AssistantFaceCues = AssistantFaceCues(
        mouth = AssistantFaceCueIcon.Search,
    )
}
