package com.tcs.vehicleassistant.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ADB entry for Compose vs XML assistant UI switching.
 *
 * ```
 * adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_ASSISTANT_UI \
 *   -n com.tcs.vehicleassistant/.assistant.AssistantUiProfileReceiver --es ui compose
 * adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_ASSISTANT_UI \
 *   -n com.tcs.vehicleassistant/.assistant.AssistantUiProfileReceiver --es ui xml:polestar
 * adb shell am broadcast -a com.tcs.vehicleassistant.action.GET_ASSISTANT_UI \
 *   -n com.tcs.vehicleassistant/.assistant.AssistantUiProfileReceiver
 * ```
 */
class AssistantUiProfileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SET -> {
                val raw = intent.getStringExtra(EXTRA_UI)
                    ?: intent.getStringExtra(EXTRA_STYLE)
                val ok = AssistantUiProfile.setFromRaw(context, raw)
                if (!ok) {
                    Log.w(
                        TAG,
                        "Unknown ui '$raw' — use compose | xml | xml:polestar|pill|side|top|immersive|hud|beveled|cinematic",
                    )
                } else {
                    Log.i(TAG, "Assistant UI → ${AssistantUiProfile.current().adbToken}")
                }
            }
            ACTION_GET -> {
                AssistantUiProfile.install(context)
                Log.i(TAG, "Assistant UI = ${AssistantUiProfile.current().adbToken}")
            }
        }
    }

    companion object {
        private const val TAG = "AssistantUi"
        const val ACTION_SET = "com.tcs.vehicleassistant.action.SET_ASSISTANT_UI"
        const val ACTION_GET = "com.tcs.vehicleassistant.action.GET_ASSISTANT_UI"
        const val EXTRA_UI = "ui"
        const val EXTRA_STYLE = "style"
    }
}
