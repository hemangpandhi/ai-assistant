package com.assistant.ui.assistant.ui.immersive

/**
 * How the immersive overlay enters the screen.
 *
 * - [Icon] — emerge from the assist / system-bar icon (bottom-end scale-up).
 * - [Hotword] — rise from the bottom upward until the border glow completes.
 */
enum class ImmersiveSummonOrigin {
    Icon,
    Hotword,
    ;

    companion object {
        const val BUNDLE_KEY = "summon_origin"
        const val TOKEN_ICON = "icon"
        const val TOKEN_HOTWORD = "hotword"

        fun fromBundleToken(raw: String?): ImmersiveSummonOrigin = when (raw?.trim()?.lowercase()) {
            TOKEN_HOTWORD, "wake", "voice", "mic" -> Hotword
            else -> Icon
        }
    }
}
