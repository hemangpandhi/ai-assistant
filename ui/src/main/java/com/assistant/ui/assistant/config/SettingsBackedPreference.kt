package com.assistant.ui.assistant.config

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared Settings.Global + SharedPreferences mirror used by UI preference objects.
 *
 * Keeps install / observer / put-string plumbing in one place so face, placement,
 * and debug-strip configs stay thin parsers over this helper (SRP).
 */
internal class SettingsBackedPreference<T>(
    private val settingsKey: String,
    private val prefsName: String,
    private val default: T,
    private val readPrefs: (SharedPreferences) -> T?,
    private val writePrefs: (SharedPreferences, T) -> Unit,
    private val parseSettings: (String?) -> T?,
    private val toSettingsToken: (T) -> String,
) {
    private val _value = MutableStateFlow(default)
    val value: StateFlow<T> = _value.asStateFlow()

    @Volatile
    private var installed = false
    private var observer: ContentObserver? = null

    fun current(): T = _value.value

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = context.applicationContext
            _value.value = readResolved(app)
            registerSettingsObserver(app)
            installed = true
        }
    }

    fun set(context: Context, next: T) {
        val app = context.applicationContext
        install(app)
        _value.value = next
        writePrefs(app.getSharedPreferences(prefsName, Context.MODE_PRIVATE), next)
        runCatching {
            Settings.Global.putString(app.contentResolver, settingsKey, toSettingsToken(next))
        }
    }

    fun readResolved(context: Context): T {
        val app = context.applicationContext
        readPrefs(app.getSharedPreferences(prefsName, Context.MODE_PRIVATE))?.let { return it }
        val fromSettings = runCatching {
            Settings.Global.getString(app.contentResolver, settingsKey)
        }.getOrNull()
        return parseSettings(fromSettings) ?: default
    }

    private fun registerSettingsObserver(app: Context) {
        if (observer != null) return
        val uri: Uri = Settings.Global.getUriFor(settingsKey)
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val next = readResolved(app)
                if (_value.value != next) {
                    _value.value = next
                    writePrefs(app.getSharedPreferences(prefsName, Context.MODE_PRIVATE), next)
                }
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(uri, false, contentObserver)
            observer = contentObserver
        }
    }
}

/** String-token preference (adb key ↔ typed value). */
internal fun <T> stringSettingsPreference(
    settingsKey: String,
    prefsName: String,
    prefKey: String,
    default: T,
    parse: (String?) -> T?,
    encode: (T) -> String,
): SettingsBackedPreference<T> = SettingsBackedPreference(
    settingsKey = settingsKey,
    prefsName = prefsName,
    default = default,
    readPrefs = { prefs -> parse(prefs.getString(prefKey, null)) },
    writePrefs = { prefs, value ->
        prefs.edit().putString(prefKey, encode(value)).apply()
    },
    parseSettings = parse,
    toSettingsToken = encode,
)

/** Boolean preference mirrored as an adb string token. */
internal fun booleanSettingsPreference(
    settingsKey: String,
    prefsName: String,
    prefKey: String,
    default: Boolean,
    parse: (String?) -> Boolean?,
    encode: (Boolean) -> String,
): SettingsBackedPreference<Boolean> = SettingsBackedPreference(
    settingsKey = settingsKey,
    prefsName = prefsName,
    default = default,
    readPrefs = { prefs ->
        if (prefs.contains(prefKey)) prefs.getBoolean(prefKey, default) else null
    },
    writePrefs = { prefs, value ->
        prefs.edit().putBoolean(prefKey, value).apply()
    },
    parseSettings = parse,
    toSettingsToken = encode,
)
