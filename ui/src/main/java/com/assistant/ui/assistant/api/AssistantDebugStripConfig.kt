package com.assistant.ui.assistant.api

import android.content.Context
import com.assistant.ui.assistant.config.booleanSettingsPreference
import kotlinx.coroutines.flow.StateFlow

/**
 * Shows or hides the immersive on-screen model/backend + live debug log strip.
 *
 * Default: **on** (visible on debuggable builds). Release builds still omit the
 * strip via [AssistantHost.debugInfo] returning null.
 *
 * ADB:
 * ```
 * adb shell settings put global vehicle_assistant_debug_strip 0
 * adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_DEBUG_STRIP \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.api.AssistantDebugStripReceiver \
 *   --es visible off
 * ```
 */
object AssistantDebugStripConfig {
    const val SETTINGS_KEY = "vehicle_assistant_debug_strip"
    const val PREFS_NAME = "assistant_debug_strip"
    private const val PREF_VISIBLE = "visible"
    const val DEFAULT_VISIBLE = true

    private val preference = booleanSettingsPreference(
        settingsKey = SETTINGS_KEY,
        prefsName = PREFS_NAME,
        prefKey = PREF_VISIBLE,
        default = DEFAULT_VISIBLE,
        parse = ::parse,
        encode = ::toAdbToken,
    )

    val visible: StateFlow<Boolean> = preference.value

    fun install(context: Context) = preference.install(context)

    fun isVisible(): Boolean = preference.current()

    fun set(context: Context, visible: Boolean) = preference.set(context, visible)

    fun setFromRaw(context: Context, raw: String?): Boolean {
        val parsed = parse(raw) ?: return false
        set(context, parsed)
        return true
    }

    fun parse(raw: String?): Boolean? {
        val key = raw?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return null
        return when (key) {
            "1", "true", "on", "show", "yes", "visible" -> true
            "0", "false", "off", "hide", "no", "hidden" -> false
            else -> null
        }
    }

    fun toAdbToken(visible: Boolean): String = if (visible) "on" else "off"

    fun readResolved(context: Context): Boolean = preference.readResolved(context)
}
