package com.tcs.vehicleassistant.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.tcs.vehicleassistant.BuildConfig

/**
 * Registration for the broadcast hooks that exist purely so the app can be driven from
 * `adb shell am broadcast` during development.
 *
 * Those broadcasts arrive from the shell UID, so the receivers have to be exported. In a release
 * build that means any installed app can send them, and the diagnostics hook in particular runs
 * every registered tool against the real VHAL -- setting the cabin temperature, moving windows and
 * so on. Registration is therefore a no-op unless the build is debuggable.
 */
object DebugBroadcasts {

    private const val TAG = "DebugBroadcasts"

    const val ACTION_DIAGNOSTICS_DUMP = "com.tcs.vehicleassistant.DIAGNOSTICS_DUMP"
    const val ACTION_TEST_QUERY = "com.tcs.vehicleassistant.TEST_QUERY"
    const val ACTION_SIDELOAD_MODEL = "com.tcs.vehicleassistant.SIDELOAD_MODEL"

    /** True when [register] will actually attach receivers. */
    val isEnabled: Boolean get() = BuildConfig.DEBUG

    /**
     * Registers [receiver] for [actions] as an exported receiver, but only in a debuggable build.
     * Returns true when the receiver was attached, so callers know whether to unregister it.
     */
    fun register(context: Context, receiver: BroadcastReceiver, vararg actions: String): Boolean {
        if (!isEnabled) return false
        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        Log.i(TAG, "Registered debug receiver for ${actions.joinToString()}")
        return true
    }

    /** Unregisters a receiver attached by [register]; safe to call when registration never happened. */
    fun unregister(context: Context, receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Already gone, or never registered because this is a release build.
        }
    }
}
