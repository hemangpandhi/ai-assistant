package com.tcs.vehicleassistant.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registry-driven gate in front of every tool actuation. Policies live in
 * `vehicle_skills_registry.json` → `config.context_policies` so OEMs can add rules without
 * hardcoding `if (volume)` branches in Kotlin.
 *
 * Decisions:
 * - [Decision.Allow] — execute immediately
 * - [Decision.Confirm] — speak [Decision.message], stash tool for FollowUp "yes"
 * - [Decision.Block] — speak reason, do not execute
 * - [Decision.Escalate] — speak facts-only prompt; caller may fall through to LLM
 */
class ContextGuard {

    companion object {
        private const val TAG = "ContextGuard"
        
        // Backward compatibility for tests that haven't been updated to use the injected instance
        private val testInstance = ContextGuard()
        fun evaluate(toolCall: String, snapshot: com.tcs.vehicleassistant.core.CabinSnapshot): Decision = testInstance.evaluate(toolCall, snapshot)
        fun loadFromConfig(config: org.json.JSONObject?) = testInstance.loadFromConfig(config)
        fun clearRulesForTest() = testInstance.clearRulesForTest()
        fun replaceRulesForTest(rules: List<PolicyRule>, policiesEnabled: Boolean = true) = testInstance.replaceRulesForTest(rules, policiesEnabled)
        val enabled: Boolean get() = testInstance.enabled
    }

    enum class Action {
        ALLOW, CONFIRM, BLOCK, ESCALATE
    }

    data class SensorCondition(
        val source: String,
        val op: String,
        /** Literal threshold; mutually exclusive with [compareTo] in practice. */
        val value: Double? = null,
        /** Compare [source] against another cabin sensor (e.g. fan_level vs fan_max). */
        val compareTo: String? = null,
    )

    data class PolicyRule(
        val id: String,
        val appliesTo: List<String>,
        val argMatches: List<String>,
        val sensors: List<SensorCondition>,
        val requireMediaPlaying: Boolean?,
        /** When non-null, requires [CabinSnapshot.navActive] to match. */
        val requireNavActive: Boolean? = null,
        /** When true, tool args must equal/contain the active nav destination. */
        val requireNavDestMatchesArg: Boolean = false,
        /** When true, nav is active and args do *not* match the active destination. */
        val requireNavDestDiffersArg: Boolean = false,
        val action: Action,
        val message: String,
        val enabled: Boolean = true,
        val priority: Int = 100,
    )

    sealed class Decision {
        abstract val policyId: String?

        data class Allow(override val policyId: String? = null) : Decision()
        data class Confirm(
            val message: String,
            override val policyId: String,
            val originalToolCall: String,
        ) : Decision()
        data class Block(
            val message: String,
            override val policyId: String,
        ) : Decision()
        data class Escalate(
            val message: String,
            override val policyId: String,
        ) : Decision()
    }

    @Volatile
    private var rules: List<PolicyRule> = emptyList()

    @Volatile
    var enabled: Boolean = true
        private set

    fun loadFromConfig(config: JSONObject?) {
        if (config == null || !config.has("context_policies")) {
            rules = emptyList()
            enabled = true
            return
        }
        val root = config.getJSONObject("context_policies")
        enabled = root.optBoolean("enabled", true)
        val arr = root.optJSONArray("rules") ?: JSONArray()
        val parsed = mutableListOf<PolicyRule>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val action = when (o.optString("action", "allow").lowercase()) {
                "confirm" -> Action.CONFIRM
                "block", "adjust" -> Action.BLOCK
                "escalate" -> Action.ESCALATE
                else -> Action.ALLOW
            }
            val sensorsArr = o.optJSONObject("when")?.optJSONArray("sensors") ?: JSONArray()
            val sensors = mutableListOf<SensorCondition>()
            for (j in 0 until sensorsArr.length()) {
                val s = sensorsArr.getJSONObject(j)
                val compareTo = s.optString("compare_to", "").trim().ifEmpty { null }
                val value = when {
                    s.has("value") -> s.getDouble("value")
                    else -> null
                }
                if (compareTo == null && value == null) {
                    Log.w(TAG, "Skipping sensor in ${o.optString("id")}: need value or compare_to")
                    continue
                }
                sensors += SensorCondition(
                    source = s.getString("source"),
                    op = s.optString("op", ">="),
                    value = value,
                    compareTo = compareTo,
                )
            }
            val whenObj = o.optJSONObject("when")
            val argMatches = jsonStringList(whenObj?.optJSONArray("arg_matches"))
            val requirePlaying = when {
                whenObj == null || !whenObj.has("media_playing") -> null
                else -> whenObj.optBoolean("media_playing")
            }
            val requireNavActive = when {
                whenObj == null || !whenObj.has("nav_active") -> null
                else -> whenObj.optBoolean("nav_active")
            }
            parsed += PolicyRule(
                id = o.getString("id"),
                appliesTo = jsonStringList(o.optJSONArray("applies_to")),
                argMatches = argMatches,
                sensors = sensors,
                requireMediaPlaying = requirePlaying,
                requireNavActive = requireNavActive,
                requireNavDestMatchesArg = whenObj?.optBoolean("nav_dest_matches_arg", false) == true,
                requireNavDestDiffersArg = whenObj?.optBoolean("nav_dest_differs_arg", false) == true,
                action = action,
                message = o.optString("message", "Please confirm."),
                enabled = o.optBoolean("enabled", true),
                priority = o.optInt("priority", 100),
            )
        }
        rules = parsed.sortedBy { it.priority }
        Log.i(TAG, "Loaded ${rules.size} context policies (enabled=$enabled)")
    }

    /** Test / OEM injection without JSON. */
    fun replaceRulesForTest(testRules: List<PolicyRule>, policiesEnabled: Boolean = true) {
        rules = testRules.sortedBy { it.priority }
        enabled = policiesEnabled
    }

    fun clearRulesForTest() {
        rules = emptyList()
        enabled = true
    }

    fun evaluate(toolCall: String, snapshot: CabinSnapshot): Decision {
        if (!enabled || rules.isEmpty()) {
            return safetyUnknownGearGate(toolCall, snapshot) ?: Decision.Allow()
        }

        val handler = toolCall.substringBefore("(").trim()
        val args = toolCall.substringAfter("(", missingDelimiterValue = "")
            .substringBefore(")")
            .trim()
            .removeSurrounding("\"")
            .lowercase()

        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.appliesTo.none { it.equals(handler, ignoreCase = true) }) continue
            if (rule.argMatches.isNotEmpty()) {
                val argOk = rule.argMatches.any { token ->
                    val t = token.lowercase()
                    args == t || args.contains(t) ||
                        (t == "up" && (args.contains("up") || args.contains("increase") ||
                            args.contains("+") || args.contains("louder") || args.isEmpty())) ||
                        (t == "down" && (args.contains("down") || args.contains("decrease") ||
                            args.contains("-") || args.contains("quieter")))
                }
                if (!argOk) continue
            }
            if (rule.requireMediaPlaying != null && rule.requireMediaPlaying != snapshot.mediaPlaying) {
                continue
            }
            if (rule.requireNavActive != null && rule.requireNavActive != snapshot.navActive) {
                continue
            }
            if (rule.requireNavDestMatchesArg) {
                val active = snapshot.navActiveDest?.lowercase()?.trim().orEmpty()
                if (active.isEmpty() || !destMatches(args, active)) continue
            }
            if (rule.requireNavDestDiffersArg) {
                val active = snapshot.navActiveDest?.lowercase()?.trim().orEmpty()
                if (active.isEmpty() || destMatches(args, active)) continue
            }
            if (!sensorsMatch(rule.sensors, snapshot)) continue

            val message = snapshot.interpolate(rule.message)
            Log.i(TAG, "Policy hit id=${rule.id} action=${rule.action} tool=$toolCall")
            return when (rule.action) {
                Action.ALLOW -> Decision.Allow(rule.id)
                Action.CONFIRM -> Decision.Confirm(message, rule.id, toolCall)
                Action.BLOCK -> Decision.Block(message, rule.id)
                Action.ESCALATE -> Decision.Escalate(message, rule.id)
            }
        }
        // No rule fired — still refuse silent allow for safety tools when gear is unknown.
        return safetyUnknownGearGate(toolCall, snapshot) ?: Decision.Allow()
    }

    /**
     * Unlock / trunk / windows must not silently Allow when Park/Drive cannot be read.
     * Ask the driver instead of writing VHAL blind.
     */
    private fun safetyUnknownGearGate(toolCall: String, snapshot: CabinSnapshot): Decision? {
        if (!SafetyCriticalTools.isSafetyCritical(toolCall)) return null
        val gear = snapshot.gear.trim()
        if (!gear.equals("Unknown", ignoreCase = true) && gear.isNotEmpty()) return null
        Log.w(TAG, "Fail-closed confirm: safety tool with unknown gear tool=$toolCall")
        return Decision.Confirm(
            message = SafetyCriticalTools.gearUnknownConfirmMessage(toolCall),
            policyId = SafetyCriticalTools.GEAR_UNKNOWN_POLICY_ID,
            originalToolCall = toolCall,
        )
    }

    private fun sensorsMatch(sensors: List<SensorCondition>, snapshot: CabinSnapshot): Boolean {
        if (sensors.isEmpty()) return true
        for (s in sensors) {
            val actual = snapshot.sensor(s.source) ?: return false
            val expected = when {
                !s.compareTo.isNullOrBlank() -> snapshot.sensor(s.compareTo) ?: return false
                s.value != null -> s.value
                else -> return false
            }
            val ok = when (s.op) {
                ">=" -> actual >= expected
                ">" -> actual > expected
                "<=" -> actual <= expected
                "<" -> actual < expected
                "==", "=" -> actual == expected
                "!=" -> actual != expected
                else -> false
            }
            if (!ok) return false
        }
        return true
    }

    private fun destMatches(toolArgs: String, activeDest: String): Boolean {
        val a = toolArgs.lowercase().trim().removeSurrounding("\"")
        val b = activeDest.lowercase().trim().removeSurrounding("\"")
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun jsonStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
