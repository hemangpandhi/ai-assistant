package com.tcs.vehicleassistant.data.tools

import android.content.Context
import android.content.Intent
import com.assistant.api.tools.ToolCatalog
import com.tcs.vehicleassistant.handlers.hvac.HvacToolAliases

/**
 * UI/UX front-door over [ToolCatalog]: canonicalizes HVAC handler aliases before
 * find/execute so refactor [com.tcs.vehicleassistant.handlers.ToolHandlerRegistry]
 * stays byte-identical.
 */
class UiUxToolDispatcher(
    private val delegate: ToolCatalog,
) : ToolCatalog {

    override val slidingWindowMaxChars: Int
        get() = delegate.slidingWindowMaxChars

    override fun find(rawToolCall: String): ToolCatalog.ToolInfo? =
        delegate.find(canonicalizeRawToolCall(rawToolCall))

    override fun llmToolsPrompt(userQuery: String, conversationalContext: String): String =
        delegate.llmToolsPrompt(userQuery, conversationalContext)

    override suspend fun execute(
        context: Context,
        rawToolCall: String,
        intentHandler: ((Intent) -> Unit)?,
        enforcePromptAllowList: Boolean,
    ): String = delegate.execute(
        context = context,
        rawToolCall = canonicalizeRawToolCall(rawToolCall),
        intentHandler = intentHandler,
        enforcePromptAllowList = enforcePromptAllowList,
    )

    companion object {
        /**
         * Rewrites the command-name portion of a raw tool call when it matches an
         * HVAC alias (e.g. `turnOnAc(…)` → `turnOnAC(…)`).
         */
        fun canonicalizeRawToolCall(rawToolCall: String): String {
            val cleaned = rawToolCall
                .replace(Regex("(?i)<TOOL>|</TOOL>|<\\|tool_call>call:"), "")
                .trim()
            if (cleaned.isEmpty()) return rawToolCall

            val name = cleaned.substringBefore("(").trim()
            if (name.isEmpty()) return rawToolCall

            val canonical = HvacToolAliases.canonicalize(name)
            if (canonical == name) return rawToolCall

            val rest = cleaned.removePrefix(name)
            // Preserve original wrapper tags if present; ToolManager strips them again.
            return if (rawToolCall.contains(cleaned)) {
                rawToolCall.replace(cleaned, canonical + rest)
            } else {
                canonical + rest
            }
        }
    }
}
