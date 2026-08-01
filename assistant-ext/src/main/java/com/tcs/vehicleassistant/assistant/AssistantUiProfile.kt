package com.tcs.vehicleassistant.assistant

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Selects Compose immersive (default) vs legacy XML voice-plate renderer.
 *
 * ADB:
 * ```
 * adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_ASSISTANT_UI \
 *   -n com.tcs.vehicleassistant/.assistant.AssistantUiProfileReceiver --es ui compose
 * adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_ASSISTANT_UI \
 *   -n com.tcs.vehicleassistant/.assistant.AssistantUiProfileReceiver --es ui xml:polestar
 * adb shell settings put global vehicle_assistant_ui compose
 * ```
 */
sealed class AssistantUiMode {
    data object Compose : AssistantUiMode()
    data class Xml(val layoutIndex: Int) : AssistantUiMode()

    val adbToken: String
        get() = when (this) {
            Compose -> "compose"
            is Xml -> "xml:${XmlLayoutStyles.tokenFor(layoutIndex)}"
        }
}

object XmlLayoutStyles {
    private val tokens = listOf(
        "polestar", "pill", "side", "top", "immersive", "hud", "beveled", "cinematic",
    )

    fun tokenFor(index: Int): String = tokens.getOrElse(index.coerceIn(0, tokens.lastIndex)) { "polestar" }

    fun indexFor(token: String): Int? {
        val key = token.trim().lowercase()
        tokens.indexOf(key).takeIf { it >= 0 }?.let { return it }
        return key.toIntOrNull()?.takeIf { it in tokens.indices }
    }

    const val COUNT = 8
}

object AssistantUiProfile {
    const val SETTINGS_KEY = "vehicle_assistant_ui"
    const val PREFS_NAME = "assistant_ui_profile"
    private const val PREF_UI = "ui"
    /** Mirrors legacy LocalLLMActivity key when XML mode is selected. */
    const val LEGACY_LAYOUT_PREF = "ui_layout_pref"
    const val APP_PREFS = "app_prefs"

    private val _mode = MutableStateFlow<AssistantUiMode>(AssistantUiMode.Compose)
    val mode: StateFlow<AssistantUiMode> = _mode.asStateFlow()

    @Volatile
    private var installed = false
    private var observer: ContentObserver? = null

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = context.applicationContext
            _mode.value = readResolved(app)
            registerSettingsObserver(app)
            installed = true
        }
    }

    fun current(): AssistantUiMode = _mode.value

    fun isCompose(): Boolean = current() is AssistantUiMode.Compose

    fun set(context: Context, mode: AssistantUiMode) {
        val app = context.applicationContext
        install(app)
        _mode.value = mode
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_UI, mode.adbToken)
            .apply()
        if (mode is AssistantUiMode.Xml) {
            app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(LEGACY_LAYOUT_PREF, mode.layoutIndex)
                .apply()
        }
        runCatching {
            Settings.Global.putString(app.contentResolver, SETTINGS_KEY, mode.adbToken)
        }
    }

    fun setFromRaw(context: Context, raw: String?): Boolean {
        val parsed = parse(raw) ?: return false
        set(context, parsed)
        return true
    }

    fun parse(raw: String?): AssistantUiMode? {
        val key = raw?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return null
        when (key) {
            "compose", "immersive", "design", "default" -> return AssistantUiMode.Compose
            "xml", "legacy", "voiceplate", "voice_plate" -> return AssistantUiMode.Xml(0)
        }
        if (key.startsWith("xml:")) {
            val style = key.removePrefix("xml:")
            val index = XmlLayoutStyles.indexFor(style) ?: return null
            return AssistantUiMode.Xml(index)
        }
        XmlLayoutStyles.indexFor(key)?.let { return AssistantUiMode.Xml(it) }
        return null
    }

    fun readResolved(context: Context): AssistantUiMode {
        val app = context.applicationContext
        val fromPrefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_UI, null)
        parse(fromPrefs)?.let { return it }
        val fromSettings = runCatching {
            Settings.Global.getString(app.contentResolver, SETTINGS_KEY)
        }.getOrNull()
        parse(fromSettings)?.let { return it }
        return AssistantUiMode.Compose
    }

    private fun registerSettingsObserver(app: Context) {
        if (observer != null) return
        val uri: Uri = Settings.Global.getUriFor(SETTINGS_KEY)
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange(selfChange, null)
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val next = readResolved(app)
                if (_mode.value != next) {
                    _mode.value = next
                    app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(PREF_UI, next.adbToken)
                        .apply()
                }
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(uri, false, contentObserver)
            observer = contentObserver
        }
    }
}
