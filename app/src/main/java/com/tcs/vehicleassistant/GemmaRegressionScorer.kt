package com.tcs.vehicleassistant.core

import com.tcs.vehicleassistant.utils.ToolCallParser

/**
 * Offline/on-device scorer for live Gemma regression cases.
 * Does not run the model — callers supply the model transcript and expected tool behavior.
 */
object GemmaRegressionScorer {

    val DEFAULT_REFUSAL_PHRASES = listOf(
        "i am a large language model",
        "i'm a large language model",
        "i am a text-based",
        "i'm a text-based",
        "i cannot control",
        "i can't control",
        "i don't have the ability to control",
        "i do not have the ability to control",
        "as an ai",
        "as a language model",
    )

    data class Case(
        val id: String,
        val user: String,
        /** Expected tool handler name, or null when the turn must be chat-only (no tool). */
        val expectedToolName: String? = null,
        /** When non-null, at least one extracted tool must match (case-insensitive). */
        val expectedToolNamesAnyOf: List<String> = emptyList(),
        val requireTool: Boolean = expectedToolName != null || expectedToolNamesAnyOf.isNotEmpty(),
        val forbidRefusal: Boolean = true,
        val extraForbiddenSubstrings: List<String> = emptyList(),
    )

    data class Score(
        val caseId: String,
        val passed: Boolean,
        val reasons: List<String>,
        val extractedToolNames: List<String>,
        val spokenPreview: String,
    )

    data class SuiteReport(
        val scores: List<Score>,
    ) {
        val passed: Int get() = scores.count { it.passed }
        val failed: Int get() = scores.count { !it.passed }
        val total: Int get() = scores.size
        val allPassed: Boolean get() = failed == 0
    }

    fun score(case: Case, modelOutput: String): Score {
        val reasons = mutableListOf<String>()
        val tools = ToolCallParser.extractToolCalls(modelOutput).map { it.toolName }
        val spoken = ToolCallParser.stripToolTags(modelOutput).trim()
        val lowerSpoken = spoken.lowercase()
        val lowerAll = modelOutput.lowercase()

        if (case.forbidRefusal) {
            for (phrase in DEFAULT_REFUSAL_PHRASES) {
                if (lowerSpoken.contains(phrase) || lowerAll.contains(phrase)) {
                    reasons += "refusal_phrase:$phrase"
                }
            }
        }
        for (extra in case.extraForbiddenSubstrings) {
            if (extra.isNotBlank() && lowerAll.contains(extra.lowercase())) {
                reasons += "forbidden:$extra"
            }
        }

        if (case.requireTool) {
            if (tools.isEmpty()) {
                reasons += "missing_tool"
            } else {
                val expected = buildList {
                    case.expectedToolName?.let { add(it) }
                    addAll(case.expectedToolNamesAnyOf)
                }
                if (expected.isNotEmpty()) {
                    val hit = tools.any { got ->
                        expected.any { exp -> got.equals(exp, ignoreCase = true) }
                    }
                    if (!hit) {
                        reasons += "wrong_tool:got=${tools.joinToString()} expected=${expected.joinToString()}"
                    }
                }
            }
        } else if (tools.isNotEmpty()) {
            reasons += "unexpected_tool:${tools.joinToString()}"
        }

        return Score(
            caseId = case.id,
            passed = reasons.isEmpty(),
            reasons = reasons,
            extractedToolNames = tools,
            spokenPreview = spoken.take(160),
        )
    }

    fun scoreSuite(cases: List<Case>, outputsByCaseId: Map<String, String>): SuiteReport {
        val scores = cases.map { case ->
            val out = outputsByCaseId[case.id]
            if (out == null) {
                Score(case.id, false, listOf("missing_output"), emptyList(), "")
            } else {
                score(case, out)
            }
        }
        return SuiteReport(scores)
    }

    /** Default cabin-edge cases for production soak / instrumented runs. */
    fun defaultCabinCases(): List<Case> = listOf(
        Case("play_music", "play music", expectedToolName = "playMusic"),
        Case("pause_music", "pause music", expectedToolName = "pauseMusic"),
        Case("stop_music", "stop music", expectedToolName = "stopMusic"),
        Case("increase_temp", "increase temperature", expectedToolName = "increaseTemperature"),
        Case("decrease_temp", "decrease temperature", expectedToolName = "decreaseTemperature"),
        Case("ac_on", "turn on the AC", expectedToolNamesAnyOf = listOf("turnOnAC", "turnOnAc")),
        Case("chat_only", "how are you feeling today?", requireTool = false),
    )
}
