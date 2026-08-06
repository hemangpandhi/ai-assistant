package com.assistant.api.face

/**
 * One-shot handoff from DirectTool handlers (no LLM `<face>` tags) to the UI backend.
 * [offer] from a tool success path; [take] once when applying assistant text.
 */
object PendingToolFaceCues {
    @Volatile
    private var iconId: String? = null

    fun offer(iconId: String?) {
        this.iconId = iconId?.takeIf { it.isNotBlank() }
    }

    fun take(): String? {
        val id = iconId
        iconId = null
        return id
    }

    fun peek(): String? = iconId

    fun clear() {
        iconId = null
    }
}
