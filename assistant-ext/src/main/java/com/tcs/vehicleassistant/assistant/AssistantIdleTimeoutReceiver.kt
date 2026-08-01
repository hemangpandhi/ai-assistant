package com.tcs.vehicleassistant.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ADB entry for assistant idle-close timeout (seconds; `0` disables).
 *
 * ```
 * adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_IDLE_TIMEOUT \
 *   -n com.tcs.vehicleassistant/.assistant.AssistantIdleTimeoutReceiver --ei sec 5
 * adb shell am broadcast -a com.tcs.vehicleassistant.action.GET_IDLE_TIMEOUT \
 *   -n com.tcs.vehicleassistant/.assistant.AssistantIdleTimeoutReceiver
 * ```
 */
class AssistantIdleTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SET -> {
                val sec = resolveSec(intent)
                if (sec == null) {
                    Log.w(TAG, "Missing/invalid sec — use --ei sec 5 (0 disables)")
                } else {
                    AssistantIdleTimeout.set(context, sec)
                    Log.i(TAG, "Idle timeout → ${AssistantIdleTimeout.currentSec()}s")
                }
            }
            ACTION_GET -> {
                AssistantIdleTimeout.install(context)
                Log.i(TAG, "Idle timeout = ${AssistantIdleTimeout.currentSec()}s")
            }
        }
    }

    private fun resolveSec(intent: Intent): Int? {
        if (intent.hasExtra(EXTRA_SEC)) {
            return AssistantIdleTimeout.clamp(
                intent.getIntExtra(EXTRA_SEC, AssistantIdleTimeout.DEFAULT_SEC),
            )
        }
        return AssistantIdleTimeout.parse(
            intent.getStringExtra(EXTRA_SECONDS)
                ?: intent.getStringExtra(EXTRA_SEC_STR),
        )
    }

    companion object {
        private const val TAG = "AssistantIdle"
        const val ACTION_SET = "com.tcs.vehicleassistant.action.SET_IDLE_TIMEOUT"
        const val ACTION_GET = "com.tcs.vehicleassistant.action.GET_IDLE_TIMEOUT"
        const val EXTRA_SEC = "sec"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_SEC_STR = "sec_str"
    }
}
