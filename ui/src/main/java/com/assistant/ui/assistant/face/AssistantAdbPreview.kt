package com.assistant.ui.assistant.face

import android.content.Context
import com.assistant.ui.assistant.ui.immersive.ImmersiveSummonOrigin
import com.assistant.ui.assistant.ui.immersive.ensureImmersiveAssistantSummoned

/**
 * Shared helpers for ADB mood / face-cue previews.
 *
 * While either preview is set, the immersive stage should stay open
 * (no idle / SessionComplete auto-dismiss) until the user closes it or
 * both previews are cleared.
 */
object AssistantAdbPreview {

    /** True when mood and/or face-cue ADB override is active. */
    fun isHolding(): Boolean =
        AssistantMoodPreview.current() != null || AssistantFaceCuePreview.current() != null

    fun clearAll() {
        AssistantMoodPreview.clear()
        AssistantFaceCuePreview.clear()
    }

    /** Open (or re-summon) the immersive stage for an ADB preview. */
    fun summon(context: Context) {
        ensureImmersiveAssistantSummoned(context, ImmersiveSummonOrigin.Icon)
    }
}
