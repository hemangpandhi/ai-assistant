package com.tcs.vehicleassistant.requirements

import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.core.DirectToolResolver
import org.json.JSONObject
import java.io.File

/**
 * Host-side loader for exhaustive requirement tests (no Android runtime).
 *
 * Sources of truth:
 * - app/src/main/assets/vehicle_skills_registry.json
 * - docs/use_cases/LLM_All_Use_Cases.md
 * - docs/use_cases/use-cases.md, WOW_USE_CASES.md, docs/demo_script.md
 */
object RegistryFixture {

    private val repoRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            if (File(dir, "app/src/main/assets/vehicle_skills_registry.json").isFile) return@lazy dir
            dir = dir.parentFile ?: return@lazy File(".")
        }
        File(".")
    }

    fun registryFile(): File =
        File(repoRoot, "app/src/main/assets/vehicle_skills_registry.json")

    fun llmAllUseCasesFile(): File =
        File(repoRoot, "docs/use_cases/LLM_All_Use_Cases.md")

    fun useCasesFile(): File =
        File(repoRoot, "docs/use_cases/use-cases.md")

    fun wowUseCasesFile(): File =
        File(repoRoot, "docs/use_cases/WOW_USE_CASES.md")

    fun demoScriptFile(): File =
        File(repoRoot, "docs/demo_script.md")

    data class RegistryTool(
        val handlerKey: String,
        val promptString: String,
        val keywords: List<String>,
        val aliases: List<String>,
        val handlerType: String,
        val successMessage: String?,
        val requiresConfirmation: Boolean,
        val requiresAgenticLoop: Boolean,
        val directExecutable: Boolean,
        val offlineCapable: Boolean,
    )

    fun loadTools(): List<RegistryTool> {
        val root = JSONObject(registryFile().readText())
        val arr = root.getJSONArray("tools")
        val out = mutableListOf<RegistryTool>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            fun strList(key: String): List<String> {
                if (!o.has(key)) return emptyList()
                val a = o.getJSONArray(key)
                return (0 until a.length()).map { a.getString(it) }
            }
            out += RegistryTool(
                handlerKey = o.getString("handler_key"),
                promptString = o.getString("prompt_string"),
                // Mirror ToolManager.isSpeakableDirectKeyword: only merge utterance aliases.
                keywords = run {
                    val kws = strList("keywords").map { it.lowercase() }.toMutableList()
                    for (alias in strList("aliases").map { it.lowercase() }) {
                        if (ToolManager.isSpeakableDirectKeyword(alias) && alias !in kws) kws += alias
                    }
                    kws.distinct()
                },
                aliases = strList("aliases"),
                handlerType = o.optString("handler_type", ""),
                successMessage = if (o.has("success_message") && !o.isNull("success_message")) {
                    o.getString("success_message")
                } else {
                    null
                },
                requiresConfirmation = o.optBoolean("requires_confirmation", false),
                requiresAgenticLoop = o.optBoolean("requires_agentic_loop", false),
                directExecutable = o.optBoolean("direct_executable", false),
                offlineCapable = o.optBoolean("offline_capable", true),
            )
        }
        return out
    }

    fun toSpecs(tools: List<RegistryTool> = loadTools()): List<DirectToolResolver.ToolSpec> =
        tools.map {
            DirectToolResolver.ToolSpec(
                id = it.handlerKey,
                handlerKey = it.handlerKey,
                promptString = it.promptString,
                keywords = it.keywords,
                successMessage = it.successMessage,
                requiresConfirmation = it.requiresConfirmation,
                requiresAgenticLoop = it.requiresAgenticLoop,
                directExecutable = it.directExecutable,
            )
        }

    data class MdToolExample(
        val handlerKey: String,
        val triggerPhrase: String,
    )

    fun loadLlmAllUseCaseTriggers(): List<MdToolExample> {
        val text = llmAllUseCasesFile().readText()
        val blocks = text.split("\n## Tool: `").drop(1)
        return blocks.mapNotNull { block ->
            val key = block.substringBefore("`")
            val trigger = Regex("""\*\*Trigger phrase example:\*\*\s*"([^"]+)"""")
                .find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            MdToolExample(key, trigger)
        }
    }

    /** Quoted command prompts from cabin / demo / WOW docs. */
    fun loadDocumentedUserPrompts(): List<Pair<String, String>> {
        val files = listOf(
            "use-cases.md" to useCasesFile(),
            "WOW_USE_CASES.md" to wowUseCasesFile(),
            "demo_script.md" to demoScriptFile(),
        )
        val out = mutableListOf<Pair<String, String>>()
        val patterns = listOf(
            Regex("""\*\*Prompt:\*\*\s*\*"([^"]+)""""),
            Regex("""\*\*Command:\*\*\s*`?"([^"`]+)"`?"""),
            Regex("""- \*\*"([^"]+)"\*\*"""),
            Regex("""OR `"([^"]+)"`"""),
        )
        for ((label, file) in files) {
            if (!file.isFile) continue
            val text = file.readText()
            for (p in patterns) {
                p.findAll(text).forEach { m ->
                    val q = m.groupValues[1].trim().trimEnd('.')
                    if (q.length in 3..120) out += label to q
                }
            }
        }
        return out.distinctBy { it.second.lowercase() }
    }

    data class DirectScenario(
        val toolId: String,
        val query: String,
        val note: String,
    )

    /**
     * One scenario per keyword for every direct_executable tool (exhaustive).
     * Argument-bearing tools get concrete fillers so placeholders can resolve.
     */
    fun buildExhaustiveDirectScenarios(
        tools: List<RegistryTool> = loadTools(),
    ): List<DirectScenario> {
        val out = mutableListOf<DirectScenario>()
        for (tool in tools.filter {
            it.directExecutable && !it.requiresConfirmation && !it.requiresAgenticLoop
        }) {
            val keywords = tool.keywords
                .map { it.trim() }
                .filter { it.length >= 5 }
                .filter { isSpeakableKeyword(it.lowercase()) }
                .distinct()
            for (kw in keywords) {
                out += DirectScenario(tool.handlerKey, queryFor(tool, kw), "keyword=$kw")
            }
        }
        return out.distinctBy { it.toolId to it.query.lowercase() }
    }

    /** Drop camelCase API aliases accidentally merged into keywords (increaseVolume → increasevolume). */
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

    fun queryFor(tool: RegistryTool, keyword: String): String {
        val prompt = tool.promptString
        val kw = keyword.trim()
        return when {
            prompt.contains("(SONG)", ignoreCase = true) -> {
                when {
                    kw.equals("play", ignoreCase = true) ||
                        kw.equals("put on", ignoreCase = true) -> "$kw arijit singh"
                    // Keep the keyword phrase intact so whole-phrase matching still succeeds.
                    kw.contains("music", ignoreCase = true) ||
                        kw.contains("song", ignoreCase = true) ||
                        kw.contains("playing", ignoreCase = true) ->
                        "$kw by arijit singh"
                    kw.startsWith("play ", ignoreCase = true) -> "$kw arijit singh"
                    else -> "play arijit singh music"
                }
            }
            prompt.contains("PLACE_NAME", ignoreCase = true) -> {
                when {
                    kw.endsWith(" to", ignoreCase = true) ||
                        listOf("drive to", "take me to", "go to", "directions to", "route to", "navigate to")
                            .any { kw.equals(it, ignoreCase = true) } -> "$kw Tokyo Tower"
                    else -> "navigate to Tokyo Tower"
                }
            }
            prompt.contains("(VAL)", ignoreCase = true) ||
                prompt.contains("(LEVEL)", ignoreCase = true) ||
                prompt.contains("(PCT)", ignoreCase = true) -> {
                val isVol = tool.handlerKey.contains("Volume", ignoreCase = true)
                when {
                    isVol && (kw.contains("up") || kw.contains("increase") || kw.contains("louder")) -> kw
                    isVol && (kw.contains("down") || kw.contains("decrease") || kw.contains("quieter")) -> kw
                    else -> "$kw 3"
                }
            }
            prompt.contains("AMENITY", ignoreCase = true) -> {
                if (kw.contains("gas") || kw.contains("station")) kw else "$kw gas station"
            }
            else -> kw
        }
    }
}
