package com.assistant.ui.assistant.ui.immersive

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
 * Process-wide immersive assistant chrome placement.
 *
 * Resolution order on [install]:
 * 1. SharedPreferences (authoritative after in-app / broadcast sets)
 * 2. [Settings.Global] [SETTINGS_KEY]
 * 3. [AssistantPlacement.Default]
 *
 * Live updates: [set], [AssistantPlacementReceiver], or Settings.Global ContentObserver.
 */
object AssistantPlacementConfig {
    const val SETTINGS_KEY = "design_assistant_placement"
    const val PREFS_NAME = "assistant_placement"
    private const val PREF_PLACEMENT = "placement"

    private val _placement = MutableStateFlow(AssistantPlacement.Default)
    val placement: StateFlow<AssistantPlacement> = _placement.asStateFlow()

    @Volatile
    private var installed = false

    private var observer: ContentObserver? = null

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = context.applicationContext
            _placement.value = readResolved(app)
            registerSettingsObserver(app)
            installed = true
        }
    }

    fun current(): AssistantPlacement = _placement.value

    fun set(context: Context, placement: AssistantPlacement) {
        val app = context.applicationContext
        install(app)
        _placement.value = placement
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_PLACEMENT, placement.adbKey)
            .apply()
        runCatching {
            Settings.Global.putString(app.contentResolver, SETTINGS_KEY, placement.adbKey)
        }
    }

    fun setFromRaw(context: Context, raw: String?): Boolean {
        val parsed = AssistantPlacement.parse(raw) ?: return false
        set(context, parsed)
        return true
    }

    fun readResolved(context: Context): AssistantPlacement {
        val app = context.applicationContext
        val fromPrefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_PLACEMENT, null)
        AssistantPlacement.parse(fromPrefs)?.let { return it }
        val fromSettings = runCatching {
            Settings.Global.getString(app.contentResolver, SETTINGS_KEY)
        }.getOrNull()
        return AssistantPlacement.parse(fromSettings) ?: AssistantPlacement.Default
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
                if (_placement.value != next) {
                    _placement.value = next
                    app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(PREF_PLACEMENT, next.adbKey)
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
