package com.test.design.presentation.assistant

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
 * Process-wide immersive assistant face selection.
 *
 * Resolution order on [install]:
 * 1. [Settings.Global] [SETTINGS_KEY] (adb `settings put`)
 * 2. SharedPreferences
 * 3. [AssistantFaceKind.Default]
 *
 * Live updates: [set], [AssistantFaceReceiver], or Settings.Global ContentObserver.
 */
object AssistantFaceConfig {
    const val SETTINGS_KEY = "design_assistant_face"
    const val PREFS_NAME = "assistant_face"
    private const val PREF_KIND = "kind"

    private val _kind = MutableStateFlow(AssistantFaceKind.Default)
    val kind: StateFlow<AssistantFaceKind> = _kind.asStateFlow()

    @Volatile
    private var installed = false

    private var observer: ContentObserver? = null

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = context.applicationContext
            _kind.value = readResolved(app)
            registerSettingsObserver(app)
            installed = true
        }
    }

    fun current(): AssistantFaceKind = _kind.value

    fun set(context: Context, kind: AssistantFaceKind) {
        val app = context.applicationContext
        install(app)
        _kind.value = kind
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KIND, kind.adbKey)
            .apply()
        // Best-effort mirror for `adb shell settings get` (needs WRITE_SETTINGS / shell).
        runCatching {
            Settings.Global.putString(app.contentResolver, SETTINGS_KEY, kind.adbKey)
        }
    }

    fun setFromRaw(context: Context, raw: String?): Boolean {
        val parsed = AssistantFaceKind.parse(raw) ?: return false
        set(context, parsed)
        return true
    }

    fun readResolved(context: Context): AssistantFaceKind {
        val app = context.applicationContext
        // Prefs are authoritative after in-app / broadcast sets (Global.put may be denied).
        // Settings.Global still wins on first launch / when ContentObserver syncs it into prefs.
        val fromPrefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KIND, null)
        AssistantFaceKind.parse(fromPrefs)?.let { return it }
        val fromSettings = runCatching {
            Settings.Global.getString(app.contentResolver, SETTINGS_KEY)
        }.getOrNull()
        return AssistantFaceKind.parse(fromSettings) ?: AssistantFaceKind.Default
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
                if (_kind.value != next) {
                    _kind.value = next
                    app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(PREF_KIND, next.adbKey)
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
