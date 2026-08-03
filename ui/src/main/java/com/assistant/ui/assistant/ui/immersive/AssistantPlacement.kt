package com.assistant.ui.assistant.ui.immersive

/**
 * Where the immersive assistant chrome appears on stage.
 *
 * Set via adb — see [AssistantPlacementReceiver] / [AssistantPlacementConfig]:
 * ```
 * adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_PLACEMENT \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.immersive.AssistantPlacementReceiver \
 *   --es placement left
 * ```
 */
enum class AssistantPlacement(
    /** Canonical token for adb / Settings.Global. */
    val adbKey: String,
    /** Label for LocalLLMActivity spinner. */
    val label: String,
) {
    Fullscreen(
        adbKey = "fullscreen",
        label = "Full-screen overlay",
    ),
    LeftCard(
        adbKey = "left",
        label = "Left card",
    ),
    RightCard(
        adbKey = "right",
        label = "Right card",
    ),
    BottomCard(
        adbKey = "bottom",
        label = "Bottom card",
    ),
    ;

    val isCard: Boolean
        get() = this != Fullscreen

    companion object {
        val Default: AssistantPlacement = Fullscreen

        /** Accepts canonical keys plus aliases (`overlay`, `side_left`, …). */
        fun parse(raw: String?): AssistantPlacement? {
            val key = raw?.trim()?.lowercase().orEmpty()
            if (key.isEmpty()) return null
            entries.firstOrNull { it.adbKey == key }?.let { return it }
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) }?.let { return it }
            return when (key) {
                "overlay", "full", "immersive", "fullscreen_overlay", "full_screen" -> Fullscreen
                "side_left", "left_card", "card_left", "rail_left" -> LeftCard
                "side_right", "right_card", "card_right", "rail_right" -> RightCard
                "card_bottom", "bottom_card", "sheet", "bottom_sheet" -> BottomCard
                else -> null
            }
        }
    }
}
