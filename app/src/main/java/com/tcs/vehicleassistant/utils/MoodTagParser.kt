package com.tcs.vehicleassistant.utils

import com.assistant.ui.assistant.api.AssistantMoodId
import com.assistant.ui.assistant.api.FaceMoodResolver

/**
 * Parses optional LLM face emotion tags:
 * `<MOOD>happy</MOOD>` — affective only (not idle/listening/thinking/speaking).
 */
object MoodTagParser {

    private val COMPLETE_MOOD_REGEX =
        Regex("(?i)<MOOD>\\s*([a-zA-Z_]+)\\s*</MOOD>")

    fun extractAffectiveMood(llmOutput: String): AssistantMoodId? {
        val match = COMPLETE_MOOD_REGEX.findAll(llmOutput).lastOrNull() ?: return null
        return parseAffectiveName(match.groupValues[1])
    }

    fun parseAffectiveName(raw: String): AssistantMoodId? {
        val key = raw.trim().lowercase().replace('-', '_')
        val mood = when (key) {
            "happy", "glad", "smile", "smiling" -> AssistantMoodId.Happy
            "sad", "sorry", "empathy", "apologetic" -> AssistantMoodId.Sad
            "excited", "energetic", "thrilled" -> AssistantMoodId.Excited
            "bored" -> AssistantMoodId.Bored
            "drowsy", "sleepy" -> AssistantMoodId.Drowsy
            "tired", "fatigued" -> AssistantMoodId.Tired
            // Pipeline names from the model are ignored — harness owns those.
            "idle", "listening", "thinking", "speaking", "searching", "reading" -> null
            else -> null
        }
        return mood?.takeIf { FaceMoodResolver.isAffective(it) }
    }

    fun stripMoodTags(llmOutput: String): String {
        var cleaned = llmOutput.replace(Regex("(?i)<MOOD>[\\s\\S]*?(</MOOD>?|$)"), "")
        val last = cleaned.lastIndexOf("<")
        if (last != -1) {
            val tail = cleaned.substring(last).uppercase()
            if (tail.startsWith("<M") || tail.startsWith("</M") ||
                "<MOOD".startsWith(tail) || "</MOOD".startsWith(tail)
            ) {
                cleaned = cleaned.substring(0, last)
            }
        }
        return cleaned.trim()
    }

    /** Heuristic affective mood for zero-LLM / FollowUp tool paths. */
    fun heuristicForTool(toolCall: String, userQuery: String = ""): AssistantMoodId? {
        val t = toolCall.lowercase()
        val q = userQuery.lowercase()
        return when {
            t.startsWith("handledrowsydriving") || q.contains("drowsy") || q.contains("falling asleep") ->
                AssistantMoodId.Excited
            t.startsWith("turnonac") || t.startsWith("decreasetemperature") ||
                t.contains("cool") || q.contains("i'm hot") || q.contains("im hot") ->
                AssistantMoodId.Happy
            t.startsWith("increasetemperature") || t.startsWith("setseatheater") ||
                q.contains("i'm cold") || q.contains("im cold") ->
                AssistantMoodId.Happy
            t.startsWith("playmusic") || t.startsWith("nexttrack") ->
                AssistantMoodId.Excited
            t.contains("search") || t.contains("navigat") || t.startsWith("startnavigation") ->
                null
            t.startsWith("turnon") || t.startsWith("open") || t.startsWith("close") ||
                t.startsWith("set") || t.startsWith("increase") || t.startsWith("decrease") ->
                AssistantMoodId.Happy
            else -> null
        }
    }
}
