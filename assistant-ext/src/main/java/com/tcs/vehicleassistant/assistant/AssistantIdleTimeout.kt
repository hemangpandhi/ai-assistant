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
 * Seconds of quiet listening before the assistant overlay closes itself.
 *
 * Default: **5**. Use `0` to disable.
 *
 * ADB:
 * ```
 * adb shell settings put global vehicle_assistant_idle_timeout_sec 5
 * adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_IDLE_TIMEOUT \
 *   -n com.tcs.vehicleassistant/.assistant.AssistantIdleTimeoutReceiver --ei sec 5
 * ```
 */
object AssistantIdleTimeout {
    const val SETTINGS_KEY = "vehicle_assistant_idle_timeout_sec"
    const val APP_PREFS = "app_prefs"
    const val PREF_KEY = "idle_timeout_sec"
    const val DEFAULT_SEC = 5
    const val MIN_SEC = 0
    const val MAX_SEC = 600

    private val _seconds = MutableStateFlow(DEFAULT_SEC)
    val seconds: StateFlow<Int> = _seconds.asStateFlow()

    @Volatile
    private var installed = false
    private var observer: ContentObserver? = null

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = context.applicationContext
            _seconds.value = readResolved(app)
            registerSettingsObserver(app)
            installed = true
        }
    }

    fun currentSec(): Int = _seconds.value

    /** Milliseconds for the idle timer; `0` means disabled. */
    fun currentMs(): Long = currentSec().toLong().coerceAtLeast(0L) * 1_000L

    fun set(context: Context, sec: Int) {
        val app = context.applicationContext
        install(app)
        val clamped = clamp(sec)
        _seconds.value = clamped
        app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_KEY, clamped)
            .apply()
        runCatching {
            Settings.Global.putString(app.contentResolver, SETTINGS_KEY, clamped.toString())
        }
    }

    fun setFromRaw(context: Context, raw: String?): Boolean {
        val parsed = parse(raw) ?: return false
        set(context, parsed)
        return true
    }

    fun parse(raw: String?): Int? {
        val key = raw?.trim().orEmpty()
        if (key.isEmpty()) return null
        val value = key.toIntOrNull() ?: return null
        if (value < MIN_SEC) return null
        return clamp(value)
    }

    fun readResolved(context: Context): Int {
        val app = context.applicationContext
        val fromPrefs = app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        if (fromPrefs.contains(PREF_KEY)) {
            return clamp(fromPrefs.getInt(PREF_KEY, DEFAULT_SEC))
        }
        val fromSettings = runCatching {
            Settings.Global.getString(app.contentResolver, SETTINGS_KEY)
        }.getOrNull()
        return parse(fromSettings) ?: DEFAULT_SEC
    }

    fun clamp(sec: Int): Int = sec.coerceIn(MIN_SEC, MAX_SEC)

    private fun registerSettingsObserver(app: Context) {
        if (observer != null) return
        val uri: Uri = Settings.Global.getUriFor(SETTINGS_KEY)
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val next = readResolved(app)
                if (_seconds.value != next) {
                    _seconds.value = next
                    app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(PREF_KEY, next)
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
