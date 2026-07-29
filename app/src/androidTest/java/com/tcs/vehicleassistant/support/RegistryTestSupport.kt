package com.tcs.vehicleassistant.support

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.core.DirectToolResolver
import org.json.JSONObject

/**
 * Shared helpers for instrumented requirement coverage against the shipped skills registry.
 *
 * Source of truth docs used by the suite:
 * - docs/use_cases/LLM_All_Use_Cases.md (canonical tool I/O)
 * - docs/use_cases/use-cases.md (cabin demo scenarios)
 * - docs/demo_script.md (OEM demo prompts)
 * - app/src/main/assets/vehicle_skills_registry.json (direct_executable keywords)
 */
object RegistryTestSupport {

    fun appContext(): Context = ApplicationProvider.getApplicationContext()

    fun initializedToolManager(): ToolManager {
        val tm = ToolManager()
        tm.initialize(appContext())
        check(tm.isInitialized) { "ToolManager failed to load vehicle_skills_registry.json" }
        return tm
    }

    fun directToolSpecs(tm: ToolManager = initializedToolManager()): List<DirectToolResolver.ToolSpec> =
        tm.getAllTools().map { (id, tool) ->
            DirectToolResolver.ToolSpec(
                id = id,
                handlerKey = tool.handlerKey ?: id,
                promptString = tool.promptString,
                keywords = tool.keywords.orEmpty(),
                successMessage = tool.successMessage,
                requiresConfirmation = tool.requiresConfirmation,
                requiresAgenticLoop = tool.requiresAgenticLoop,
                directExecutable = tool.directExecutable,
            )
        }

    data class DirectScenario(
        val toolId: String,
        val query: String,
        val note: String = "",
    )

    /**
     * Exhaustive: one instrumentable query per speakable keyword (≥5 chars) for every
     * `direct_executable` tool. Argument-bearing tools get concrete fillers for placeholders.
     */
    fun buildDirectScenarios(specs: List<DirectToolResolver.ToolSpec>): List<DirectScenario> {
        val out = mutableListOf<DirectScenario>()
        for (spec in specs.filter { it.directExecutable && !it.requiresConfirmation && !it.requiresAgenticLoop }) {
            val keywords = spec.keywords
                .map { it.trim().lowercase() }
                .filter { it.length >= 5 }
                .filter { isSpeakableKeyword(it) }
                .distinct()
                .sortedByDescending { it.length }

            for (kw in keywords) {
                out += DirectScenario(spec.id, queryFor(spec, kw), "keyword=$kw")
            }
        }
        return out.distinctBy { it.toolId to it.query }
    }

    /**
     * Spoken phrases have spaces or are short natural tokens. CamelCase aliases merged into
     * keywords (e.g. increaseVolume → increasevolume) are API names, not user utterances.
     */
    private fun isSpeakableKeyword(keyword: String): Boolean {
        if (keyword.contains(' ')) return true
        if (keyword.length <= 8) return true
        val gluedAliasHints = listOf(
            "volume", "temp", "temperature", "music", "audio", "song", "track",
            "heater", "climate", "navigation", "playing",
        )
        return gluedAliasHints.none { hint ->
            keyword.contains(hint) && keyword != hint
        }
    }

    private fun needsTrailingArg(spec: DirectToolResolver.ToolSpec): Boolean {
        val p = spec.promptString
        return p.contains("SONG", ignoreCase = true) ||
            p.contains("PLACE_NAME", ignoreCase = true) ||
            p.contains("AMENITY", ignoreCase = true)
    }

    private fun queryFor(spec: DirectToolResolver.ToolSpec, keyword: String): String {
        val prompt = spec.promptString
        return when {
            prompt.contains("(SONG)", ignoreCase = true) -> {
                when {
                    keyword == "play" || keyword == "put on" -> "$keyword arijit singh"
                    // Keep the keyword phrase intact so whole-phrase matching still succeeds.
                    keyword.contains("music") || keyword.contains("song") || keyword.contains("playing") ->
                        "$keyword by arijit singh"
                    else -> "play arijit singh music"
                }
            }
            prompt.contains("PLACE_NAME", ignoreCase = true) -> {
                when {
                    keyword.endsWith(" to") || keyword == "drive to" || keyword == "take me to" ||
                        keyword == "go to" || keyword == "directions to" || keyword == "route to" ||
                        keyword == "navigate to" -> "$keyword Tokyo Tower"
                    else -> "navigate to Tokyo Tower"
                }
            }
            prompt.contains("(VAL)", ignoreCase = true) ||
                prompt.contains("(LEVEL)", ignoreCase = true) ||
                prompt.contains("(PCT)", ignoreCase = true) -> {
                when {
                    spec.handlerKey.contains("Volume", ignoreCase = true) &&
                        (keyword.contains("up") || keyword.contains("increase") || keyword.contains("louder")) ->
                        if (keyword.contains(' ')) keyword else "volume up"
                    spec.handlerKey.contains("Volume", ignoreCase = true) &&
                        (keyword.contains("down") || keyword.contains("decrease") || keyword.contains("quieter")) ->
                        if (keyword.contains(' ')) keyword else "volume down"
                    else -> "$keyword 3"
                }
            }
            else -> keyword
        }
    }

    /** Loads raw JSON tool entries for assertions that need fields beyond ToolManager. */
    fun registryJson(): JSONObject {
        val json = appContext().assets.open("vehicle_skills_registry.json").bufferedReader().use { it.readText() }
        return JSONObject(json)
    }
}
