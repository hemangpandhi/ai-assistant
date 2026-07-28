package com.assistant.ui.assistant.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ADB entry for the immersive on-screen debug strip (model / backend / live log).
 *
 * ```
 * adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_DEBUG_STRIP \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.api.AssistantDebugStripReceiver \
 *   --es visible off
 *
 * adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_DEBUG_STRIP \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.api.AssistantDebugStripReceiver \
 *   --es visible on
 *
 * adb shell am broadcast -a com.assistant.ui.action.GET_ASSISTANT_DEBUG_STRIP \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.api.AssistantDebugStripReceiver
 * ```
 *
 * Tokens: `on` | `off` | `1` | `0` | `show` | `hide` | `true` | `false`
 *
 * Also:
 * ```
 * adb shell settings put global vehicle_assistant_debug_strip off
 * adb shell settings get global vehicle_assistant_debug_strip
 * ```
 */
class AssistantDebugStripReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SET -> {
                val raw = intent.getStringExtra(EXTRA_VISIBLE)
                    ?: intent.getStringExtra(EXTRA_SHOW)
                    ?: intent.getStringExtra(EXTRA_ENABLED)
                    ?: boolExtraToRaw(intent)
                val ok = AssistantDebugStripConfig.setFromRaw(context, raw)
                if (!ok) {
                    Log.w(TAG, "Unknown visible='$raw' — use on|off|1|0|show|hide")
                } else {
                    Log.i(
                        TAG,
                        "Debug strip → ${AssistantDebugStripConfig.toAdbToken(AssistantDebugStripConfig.isVisible())}",
                    )
                }
            }
            ACTION_GET -> {
                AssistantDebugStripConfig.install(context)
                Log.i(
                    TAG,
                    "Debug strip = ${AssistantDebugStripConfig.toAdbToken(AssistantDebugStripConfig.isVisible())}",
                )
            }
        }
    }

    private fun boolExtraToRaw(intent: Intent): String? {
        if (!intent.hasExtra(EXTRA_VISIBLE_BOOL)) return null
        return if (intent.getBooleanExtra(EXTRA_VISIBLE_BOOL, true)) "on" else "off"
    }

    companion object {
        private const val TAG = "AssistantDebugStrip"

        const val ACTION_SET = "com.assistant.ui.action.SET_ASSISTANT_DEBUG_STRIP"
        const val ACTION_GET = "com.assistant.ui.action.GET_ASSISTANT_DEBUG_STRIP"
        const val EXTRA_VISIBLE = "visible"
        const val EXTRA_SHOW = "show"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_VISIBLE_BOOL = "visible_bool"
    }
}
