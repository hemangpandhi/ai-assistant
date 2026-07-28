package com.assistant.ui.assistant.api

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

    private val _visible = MutableStateFlow(DEFAULT_VISIBLE)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    @Volatile
    private var installed = false
    private var observer: ContentObserver? = null

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = context.applicationContext
            _visible.value = readResolved(app)
            registerSettingsObserver(app)
            installed = true
        }
    }

    fun isVisible(): Boolean = _visible.value

    fun set(context: Context, visible: Boolean) {
        val app = context.applicationContext
        install(app)
        _visible.value = visible
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_VISIBLE, visible)
            .apply()
        runCatching {
            Settings.Global.putString(app.contentResolver, SETTINGS_KEY, toAdbToken(visible))
        }
    }

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

    fun readResolved(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(PREF_VISIBLE)) {
            return prefs.getBoolean(PREF_VISIBLE, DEFAULT_VISIBLE)
        }
        val fromSettings = runCatching {
            Settings.Global.getString(app.contentResolver, SETTINGS_KEY)
        }.getOrNull()
        return parse(fromSettings) ?: DEFAULT_VISIBLE
    }

    private fun registerSettingsObserver(app: Context) {
        if (observer != null) return
        val uri: Uri = Settings.Global.getUriFor(SETTINGS_KEY)
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val next = readResolved(app)
                if (_visible.value != next) {
                    _visible.value = next
                    app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_VISIBLE, next)
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
