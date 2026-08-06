package com.assistant.ui.assistant.face

import android.content.Context
import com.assistant.ui.assistant.config.stringSettingsPreference
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide immersive assistant face selection.
 *
 * Resolution order on [install]:
 * 1. SharedPreferences (authoritative after in-app / broadcast sets)
 * 2. [android.provider.Settings.Global] [SETTINGS_KEY]
 * 3. [AssistantFaceKind.Default]
 *
 * Live updates: [set], [AssistantFaceReceiver], or Settings.Global ContentObserver.
 */
object AssistantFaceConfig {
    const val SETTINGS_KEY = "design_assistant_face"
    const val PREFS_NAME = "assistant_face"
    private const val PREF_KIND = "kind"

    private val preference = stringSettingsPreference(
        settingsKey = SETTINGS_KEY,
        prefsName = PREFS_NAME,
        prefKey = PREF_KIND,
        default = AssistantFaceKind.Default,
        parse = AssistantFaceKind::parse,
        encode = { it.adbKey },
    )

    val kind: StateFlow<AssistantFaceKind> = preference.value

    fun install(context: Context) = preference.install(context)

    fun current(): AssistantFaceKind = preference.current()

    fun set(context: Context, kind: AssistantFaceKind) = preference.set(context, kind)

    fun setFromRaw(context: Context, raw: String?): Boolean {
        val parsed = AssistantFaceKind.parse(raw) ?: return false
        set(context, parsed)
        return true
    }

    fun readResolved(context: Context): AssistantFaceKind = preference.readResolved(context)
}
