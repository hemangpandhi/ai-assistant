package com.test.design.presentation.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ADB entry points for immersive assistant face swapping:
 *
 * ```
 * adb shell am broadcast -a com.test.design.action.SET_ASSISTANT_FACE \
 *   -n com.test.design/.presentation.assistant.AssistantFaceReceiver \
 *   --es face fusion
 *
 * adb shell am broadcast -a com.test.design.action.SET_ASSISTANT_FACE \
 *   -n com.test.design/.presentation.assistant.AssistantFaceReceiver \
 *   --es face none
 *
 * adb shell am broadcast -a com.test.design.action.GET_ASSISTANT_FACE \
 *   -n com.test.design/.presentation.assistant.AssistantFaceReceiver
 * ```
 *
 * Face tokens: `none` | `eyes` | `glow` | `hybrid` | `eporo` | `fusion` | `fusionglow` | `fusioneyes` | `droid` | `glyph` (default: `eyes`)
 *
 * Also:
 * ```
 * adb shell settings put global design_assistant_face eyes
 * adb shell settings get global design_assistant_face
 * ```
 */
class AssistantFaceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SET -> {
                val raw = intent.getStringExtra(EXTRA_FACE)
                    ?: intent.getStringExtra(EXTRA_KIND)
                val ok = AssistantFaceConfig.setFromRaw(context, raw)
                if (!ok) {
                    Log.w(TAG, "Unknown face '$raw' — use none|eyes|glow|eporo|fusion|fusionglow|fusioneyes|droid|glyph")
                } else {
                    Log.i(TAG, "Assistant face → ${AssistantFaceConfig.current().adbKey}")
                }
            }
            ACTION_GET -> {
                AssistantFaceConfig.install(context)
                Log.i(TAG, "Assistant face = ${AssistantFaceConfig.current().adbKey}")
            }
        }
    }

    companion object {
        private const val TAG = "AssistantFace"

        const val ACTION_SET = "com.test.design.action.SET_ASSISTANT_FACE"
        const val ACTION_GET = "com.test.design.action.GET_ASSISTANT_FACE"
        const val EXTRA_FACE = "face"
        const val EXTRA_KIND = "kind"
    }
}
