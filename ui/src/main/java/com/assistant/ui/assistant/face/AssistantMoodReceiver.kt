package com.assistant.ui.assistant.face

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ADB preview for Nomi-Mate / affective moods on the main overlay face.
 *
 * SET auto-opens the immersive stage and holds it open while the preview is
 * active (no idle / SessionComplete auto-close). Clear or dismiss explicitly.
 *
 * ```
 * adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver --es mood triumph
 * adb shell am broadcast -a com.assistant.ui.action.CLEAR_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver
 * adb shell am broadcast -a com.assistant.ui.action.GET_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver
 * ```
 *
 * Mood tokens match [AssistantMood] names (case-insensitive), e.g.
 * attraction|admiration|desire|surprise|astonishment|amused|jubilation|
 * gratitude|contentment|proud|triumph|relaxed|shy|acceptance|complicity|
 * concentration|dreamy|sleeping|doubt|concerned|impressed|happy|excited|…
 *
 * Pass `--ez summon false` to change preview without opening the overlay.
 */
class AssistantMoodReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SET -> {
                val raw = intent.getStringExtra(EXTRA_MOOD)
                    ?: intent.getStringExtra(EXTRA_EXPRESSION)
                val summon = intent.getBooleanExtra(EXTRA_SUMMON, true)
                if (raw.isNullOrBlank()) {
                    Log.w(TAG, "Pass --es mood <name> (see AssistantMood names)")
                    return
                }
                val cleared = raw.trim().lowercase() in setOf("off", "clear", "none")
                if (cleared) {
                    AssistantMoodPreview.clear()
                    Log.i(TAG, "Assistant mood → off")
                } else {
                    val ok = AssistantMoodPreview.setFromRaw(raw)
                    if (!ok) {
                        Log.w(
                            TAG,
                            "Unknown mood '$raw' — use AssistantMood names " +
                                "(e.g. triumph, sleeping, astonishment)",
                        )
                        return
                    }
                    Log.i(TAG, "Assistant mood → ${AssistantMoodPreview.describe()}")
                    if (summon) {
                        AssistantAdbPreview.summon(context)
                    }
                }
            }
            ACTION_CLEAR -> {
                AssistantMoodPreview.clear()
                Log.i(TAG, "Assistant mood → off")
            }
            ACTION_GET -> {
                Log.i(TAG, "Assistant mood = ${AssistantMoodPreview.describe()}")
            }
        }
    }

    companion object {
        private const val TAG = "AssistantMood"

        const val ACTION_SET = "com.assistant.ui.action.SET_ASSISTANT_MOOD"
        const val ACTION_CLEAR = "com.assistant.ui.action.CLEAR_ASSISTANT_MOOD"
        const val ACTION_GET = "com.assistant.ui.action.GET_ASSISTANT_MOOD"
        const val EXTRA_MOOD = "mood"
        const val EXTRA_EXPRESSION = "expression"
        const val EXTRA_SUMMON = "summon"
    }
}
