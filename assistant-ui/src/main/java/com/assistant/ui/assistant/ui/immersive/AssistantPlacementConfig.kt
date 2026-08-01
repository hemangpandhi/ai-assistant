package com.assistant.ui.assistant.ui.immersive

import android.content.Context
import com.assistant.ui.assistant.config.stringSettingsPreference
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide immersive assistant chrome placement.
 *
 * Resolution order on [install]:
 * 1. SharedPreferences (authoritative after in-app / broadcast sets)
 * 2. [android.provider.Settings.Global] [SETTINGS_KEY]
 * 3. [AssistantPlacement.Default]
 *
 * Live updates: [set], [AssistantPlacementReceiver], or Settings.Global ContentObserver.
 */
object AssistantPlacementConfig {
    const val SETTINGS_KEY = "design_assistant_placement"
    const val PREFS_NAME = "assistant_placement"
    private const val PREF_PLACEMENT = "placement"

    private val preference = stringSettingsPreference(
        settingsKey = SETTINGS_KEY,
        prefsName = PREFS_NAME,
        prefKey = PREF_PLACEMENT,
        default = AssistantPlacement.Default,
        parse = AssistantPlacement::parse,
        encode = { it.adbKey },
    )

    val placement: StateFlow<AssistantPlacement> = preference.value

    fun install(context: Context) = preference.install(context)

    fun current(): AssistantPlacement = preference.current()

    fun set(context: Context, placement: AssistantPlacement) = preference.set(context, placement)

    fun setFromRaw(context: Context, raw: String?): Boolean {
        val parsed = AssistantPlacement.parse(raw) ?: return false
        set(context, parsed)
        return true
    }

    fun readResolved(context: Context): AssistantPlacement = preference.readResolved(context)
}
