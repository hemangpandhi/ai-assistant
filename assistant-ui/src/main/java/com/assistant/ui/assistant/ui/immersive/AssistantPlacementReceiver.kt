package com.assistant.ui.assistant.ui.immersive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ADB entry points for immersive assistant chrome placement:
 *
 * ```
 * adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_PLACEMENT \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.immersive.AssistantPlacementReceiver \
 *   --es placement left
 *
 * adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_ASSISTANT_PLACEMENT \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.immersive.AssistantPlacementReceiver \
 *   --es placement right
 *
 * adb shell am broadcast -a com.assistant.ui.action.GET_ASSISTANT_PLACEMENT \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.immersive.AssistantPlacementReceiver
 * ```
 *
 * Tokens: `fullscreen` | `left` | `right` | `bottom`
 * Aliases: `overlay`, `full`, `side_left`, `side_right`, `card_bottom`
 *
 * Also:
 * ```
 * adb shell settings put global design_assistant_placement right
 * adb shell settings get global design_assistant_placement
 * ```
 */
class AssistantPlacementReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SET, ACTION_SET_LEGACY -> {
                val raw = intent.getStringExtra(EXTRA_PLACEMENT)
                    ?: intent.getStringExtra(EXTRA_MODE)
                val ok = AssistantPlacementConfig.setFromRaw(context, raw)
                if (!ok) {
                    Log.w(TAG, "Unknown placement '$raw' — use fullscreen|left|right|bottom")
                } else {
                    Log.i(TAG, "Assistant placement → ${AssistantPlacementConfig.current().adbKey}")
                }
            }
            ACTION_GET, ACTION_GET_LEGACY -> {
                AssistantPlacementConfig.install(context)
                Log.i(TAG, "Assistant placement = ${AssistantPlacementConfig.current().adbKey}")
            }
        }
    }

    companion object {
        private const val TAG = "AssistantPlacement"

        const val ACTION_SET = "com.assistant.ui.action.SET_ASSISTANT_PLACEMENT"
        const val ACTION_GET = "com.assistant.ui.action.GET_ASSISTANT_PLACEMENT"
        /** Plan / host-package alias. */
        const val ACTION_SET_LEGACY = "com.tcs.vehicleassistant.action.SET_ASSISTANT_PLACEMENT"
        const val ACTION_GET_LEGACY = "com.tcs.vehicleassistant.action.GET_ASSISTANT_PLACEMENT"
        const val EXTRA_PLACEMENT = "placement"
        const val EXTRA_MODE = "mode"
    }
}
