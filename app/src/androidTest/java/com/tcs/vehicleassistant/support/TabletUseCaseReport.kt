package com.tcs.vehicleassistant.support

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Collects standalone tablet use-case results and writes JSON (+ optional Markdown)
 * under `/data/local/tmp` for `scripts/run_tablet_usecase_report.sh` to pull.
 */
object TabletUseCaseReport {

    private const val TAG = "TabletUseCaseReport"
    const val JSON_PATH = "/data/local/tmp/vehicleassistant_usecase_report.json"
    const val MD_PATH = "/data/local/tmp/vehicleassistant_usecase_report.md"

    enum class Category {
        DIRECT_TOOL,
        SAFETY,
        CONFIRM_HONESTY,
        WELLNESS_CHAT,
        FOLLOW_UP,
        REGISTRY,
        GEMMA_FIXTURE,
        OTHER,
    }

    enum class NextStepHint {
        STABILIZATION,
        BUG,
        VIOLATION,
        RISK,
        SEMANTIC_KEYWORD,
        NONE,
    }

    data class CaseResult(
        val id: String,
        val category: Category,
        val title: String,
        val passed: Boolean,
        val detail: String = "",
        val nextStepHint: NextStepHint = NextStepHint.NONE,
    )

    private val results = mutableListOf<CaseResult>()

    fun reset() {
        results.clear()
    }

    fun record(result: CaseResult) {
        results += result
        val status = if (result.passed) "PASS" else "FAIL"
        Log.i(TAG, "$status [${result.category}] ${result.id}: ${result.title} — ${result.detail}")
    }

    fun runCase(
        id: String,
        category: Category,
        title: String,
        nextStepHint: NextStepHint = NextStepHint.BUG,
        block: () -> Unit,
    ) {
        try {
            block()
            record(
                CaseResult(
                    id = id,
                    category = category,
                    title = title,
                    passed = true,
                    detail = "ok",
                    nextStepHint = NextStepHint.NONE,
                ),
            )
        } catch (t: Throwable) {
            record(
                CaseResult(
                    id = id,
                    category = category,
                    title = title,
                    passed = false,
                    detail = t.message ?: t.javaClass.simpleName,
                    nextStepHint = nextStepHint,
                ),
            )
        }
    }

    fun snapshot(): List<CaseResult> = results.toList()

    fun passedCount(): Int = results.count { it.passed }
    fun failedCount(): Int = results.count { !it.passed }

    fun toJson(
        deviceSerial: String = "",
        userId: String = "",
        suiteName: String = "StandaloneTabletUseCaseReport",
    ): String {
        val root = JSONObject()
        root.put("suite", suiteName)
        root.put("generatedAtMs", System.currentTimeMillis())
        root.put("deviceSerial", deviceSerial)
        root.put("userId", userId)
        root.put("total", results.size)
        root.put("passed", passedCount())
        root.put("failed", failedCount())
        val arr = JSONArray()
        for (r in results) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("category", r.category.name)
                    .put("title", r.title)
                    .put("passed", r.passed)
                    .put("detail", r.detail)
                    .put("nextStepHint", r.nextStepHint.name),
            )
        }
        root.put("cases", arr)

        val hints = JSONObject()
        for (hint in NextStepHint.values()) {
            if (hint == NextStepHint.NONE) continue
            val fails = results.filter { !it.passed && it.nextStepHint == hint }
            hints.put(
                hint.name,
                JSONArray(fails.map { it.id }),
            )
        }
        root.put("nextStepBuckets", hints)
        return root.toString(2)
    }

    fun toMarkdown(
        deviceSerial: String = "",
        userId: String = "",
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# Standalone tablet use-case report")
        sb.appendLine()
        sb.appendLine("- Device: `${deviceSerial.ifBlank { "unknown" }}`")
        sb.appendLine("- User: `${userId.ifBlank { "unknown" }}`")
        sb.appendLine("- Total: **${results.size}** | Passed: **${passedCount()}** | Failed: **${failedCount()}**")
        sb.appendLine()
        sb.appendLine("## Summary by category")
        sb.appendLine()
        sb.appendLine("| Category | Pass | Fail |")
        sb.appendLine("|---|---:|---:|")
        for (cat in Category.values()) {
            val subset = results.filter { it.category == cat }
            if (subset.isEmpty()) continue
            sb.appendLine(
                "| ${cat.name} | ${subset.count { it.passed }} | ${subset.count { !it.passed }} |",
            )
        }
        sb.appendLine()
        sb.appendLine("## Failures → next-step hints")
        sb.appendLine()
        val fails = results.filter { !it.passed }
        if (fails.isEmpty()) {
            sb.appendLine("_No failures. Next: run human mic soak rows in DRIVER_SEAT_TABLET_SUITE.md (wake/barge-in)._")
        } else {
            sb.appendLine("| Id | Category | Hint | Detail |")
            sb.appendLine("|---|---|---|---|")
            for (f in fails) {
                sb.appendLine("| `${f.id}` | ${f.category} | **${f.nextStepHint}** | ${f.detail.replace("|", "/")} |")
            }
        }
        sb.appendLine()
        sb.appendLine("## All cases")
        sb.appendLine()
        sb.appendLine("| Id | Category | Result | Title |")
        sb.appendLine("|---|---|---|---|")
        for (r in results) {
            val mark = if (r.passed) "PASS" else "FAIL"
            sb.appendLine("| `${r.id}` | ${r.category} | $mark | ${r.title.replace("|", "/")} |")
        }
        sb.appendLine()
        sb.appendLine("## How to interpret hints")
        sb.appendLine()
        sb.appendLine("| Hint | Meaning |")
        sb.appendLine("|---|---|")
        sb.appendLine("| STABILIZATION | Flaky path / ACK quality / lifecycle |")
        sb.appendLine("| BUG | Incorrect routing or false success narration |")
        sb.appendLine("| VIOLATION | Safety policy not honored |")
        sb.appendLine("| RISK | Memory / model / process risk |")
        sb.appendLine("| SEMANTIC_KEYWORD | Lexical miss — candidate for alias or semantic tier |")
        return sb.toString()
    }

    fun writeToDevice(deviceSerial: String = "", userId: String = "") {
        val json = toJson(deviceSerial, userId)
        val md = toMarkdown(deviceSerial, userId)
        writeFile(JSON_PATH, json)
        writeFile(MD_PATH, md)
        // Also mirror into app files for run-as pull fallback.
        try {
            val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            File(ctx.filesDir, "vehicleassistant_usecase_report.json").writeText(json)
            File(ctx.filesDir, "vehicleassistant_usecase_report.md").writeText(md)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not write app-private report copy: ${t.message}")
        }
        Log.i(TAG, "Wrote $JSON_PATH and $MD_PATH (failed=${failedCount()}/${results.size})")
    }

    private fun writeFile(path: String, body: String) {
        try {
            File(path).writeText(body)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing $path: ${t.message}")
        }
    }
}
