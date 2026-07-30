package com.assistant.ui.assistant.face

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.assistant.ui.assistant.api.AssistantFaceCueIcon
import com.assistant.ui.assistant.api.AssistantFaceCues
import com.assistant.ui.assistant.api.FaceCueParser
import com.assistant.ui.assistant.ui.immersive.ImmersiveSummonOrigin
import com.assistant.ui.assistant.ui.immersive.notifyImmersiveAssistantSummon

/**
 * ADB preview for in-face Material cues (eyes / mouth / L-R accents).
 *
 * ```
 * # Per-slot (omit a slot to leave it geometric / none)
 * adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_FACE_CUES \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver \
 *   --es left_eye sunny --es right_eye sunny --es mouth music \
 *   --es left_accent sparkle --es right_accent star
 *
 * # Compact XML tag (same vocabulary as the LLM)
 * adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_FACE_CUES \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver \
 *   --es face '<face left_eye="sunny" right_eye="rain" mouth="music"/>'
 *
 * # Named presets
 * adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_FACE_CUES \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver \
 *   --es preset weather
 *
 * # Clear override (back to LLM / geometry)
 * adb shell am broadcast -a com.assistant.ui.action.CLEAR_ASSISTANT_FACE_CUES \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver
 *
 * adb shell am broadcast -a com.assistant.ui.action.GET_ASSISTANT_FACE_CUES \
 *   -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver
 * ```
 *
 * Icons: rain|storm|snow|cloudy|sunny|thermostat|ac|heat|fan|defrost|
 * music|podcast|mic|search|navigate|sparkle|star|wave|heart
 *
 * Presets: weather|music|search|climate|sparkle|nav|clear
 *
 * Pass `--ez summon false` to skip opening the immersive overlay.
 */
class AssistantFaceCueReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SET -> {
                val summon = intent.getBooleanExtra(EXTRA_SUMMON, true)
                val cues = resolveCues(intent)
                if (cues == null && !intent.hasExtra(EXTRA_PRESET) &&
                    intent.getStringExtra(EXTRA_FACE).isNullOrBlank() &&
                    !hasAnySlotExtra(intent)
                ) {
                    Log.w(
                        TAG,
                        "No cues — pass --es left_eye/… or --es face '<face…/>' or --es preset weather|music|search|climate|sparkle|nav|clear",
                    )
                    return
                }
                if (cues == null || cues.isEmpty) {
                    AssistantFaceCuePreview.clear()
                    Log.i(TAG, "Face cues → off")
                } else {
                    AssistantFaceCuePreview.set(cues)
                    Log.i(TAG, "Face cues → ${AssistantFaceCuePreview.describe()}")
                }
                if (summon) {
                    notifyImmersiveAssistantSummon(ImmersiveSummonOrigin.Icon)
                }
            }
            ACTION_CLEAR -> {
                AssistantFaceCuePreview.clear()
                Log.i(TAG, "Face cues → off")
            }
            ACTION_GET -> {
                Log.i(TAG, "Face cues = ${AssistantFaceCuePreview.describe()}")
            }
        }
    }

    private fun resolveCues(intent: Intent): AssistantFaceCues? {
        val preset = intent.getStringExtra(EXTRA_PRESET)?.trim()?.lowercase()
        if (!preset.isNullOrEmpty()) {
            return presetCues(preset)
        }
        val faceTag = intent.getStringExtra(EXTRA_FACE)
            ?: intent.getStringExtra(EXTRA_TAG)
        if (!faceTag.isNullOrBlank()) {
            val parsed = FaceCueParser.parse(faceTag)
            return if (parsed.found) parsed.cues else {
                Log.w(TAG, "Could not parse face tag: $faceTag")
                null
            }
        }
        if (!hasAnySlotExtra(intent)) return null
        return AssistantFaceCues(
            leftEye = AssistantFaceCueIcon.parse(intent.getStringExtra(EXTRA_LEFT_EYE)),
            rightEye = AssistantFaceCueIcon.parse(intent.getStringExtra(EXTRA_RIGHT_EYE)),
            mouth = AssistantFaceCueIcon.parse(intent.getStringExtra(EXTRA_MOUTH)),
            leftAccent = AssistantFaceCueIcon.parse(intent.getStringExtra(EXTRA_LEFT_ACCENT)),
            rightAccent = AssistantFaceCueIcon.parse(intent.getStringExtra(EXTRA_RIGHT_ACCENT)),
        )
    }

    private fun hasAnySlotExtra(intent: Intent): Boolean =
        listOf(
            EXTRA_LEFT_EYE,
            EXTRA_RIGHT_EYE,
            EXTRA_MOUTH,
            EXTRA_LEFT_ACCENT,
            EXTRA_RIGHT_ACCENT,
        ).any { intent.hasExtra(it) }

    private fun presetCues(preset: String): AssistantFaceCues? = when (preset) {
        "clear", "off", "none", "reset" -> AssistantFaceCues.Empty
        "weather", "sunny" -> AssistantFaceCues(
            leftEye = AssistantFaceCueIcon.Sunny,
            rightEye = AssistantFaceCueIcon.Sunny,
            mouth = AssistantFaceCueIcon.Cloudy,
        )
        "rain" -> AssistantFaceCues(
            leftEye = AssistantFaceCueIcon.Rain,
            rightEye = AssistantFaceCueIcon.Rain,
            mouth = AssistantFaceCueIcon.Storm,
        )
        "music" -> AssistantFaceCues(
            mouth = AssistantFaceCueIcon.Music,
            leftAccent = AssistantFaceCueIcon.Sparkle,
            rightAccent = AssistantFaceCueIcon.Sparkle,
        )
        "search" -> AssistantFaceCues(
            leftEye = AssistantFaceCueIcon.Search,
            rightEye = AssistantFaceCueIcon.Search,
        )
        "climate", "hvac", "ac" -> AssistantFaceCues(
            mouth = AssistantFaceCueIcon.Thermostat,
            leftAccent = AssistantFaceCueIcon.Ac,
            rightAccent = AssistantFaceCueIcon.Heat,
        )
        "sparkle", "excited" -> AssistantFaceCues(
            leftAccent = AssistantFaceCueIcon.Sparkle,
            rightAccent = AssistantFaceCueIcon.Star,
        )
        "nav", "navigate" -> AssistantFaceCues(
            leftEye = AssistantFaceCueIcon.Navigate,
            rightEye = AssistantFaceCueIcon.Navigate,
            mouth = AssistantFaceCueIcon.Search,
        )
        else -> {
            Log.w(TAG, "Unknown preset '$preset' — weather|rain|music|search|climate|sparkle|nav|clear")
            null
        }
    }

    companion object {
        private const val TAG = "AssistantFaceCue"

        const val ACTION_SET = "com.assistant.ui.action.SET_ASSISTANT_FACE_CUES"
        const val ACTION_CLEAR = "com.assistant.ui.action.CLEAR_ASSISTANT_FACE_CUES"
        const val ACTION_GET = "com.assistant.ui.action.GET_ASSISTANT_FACE_CUES"

        const val EXTRA_LEFT_EYE = "left_eye"
        const val EXTRA_RIGHT_EYE = "right_eye"
        const val EXTRA_MOUTH = "mouth"
        const val EXTRA_LEFT_ACCENT = "left_accent"
        const val EXTRA_RIGHT_ACCENT = "right_accent"
        const val EXTRA_FACE = "face"
        const val EXTRA_TAG = "tag"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_SUMMON = "summon"
    }
}
