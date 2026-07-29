package com.tcs.vehicleassistant.hardware

import com.tcs.vehicleassistant.domain.SpeculativeToolPrep

/**
 * Adaptive STT endpointing from partial transcript (UI/UX / TTFR extension).
 */
object EndpointingProfileSelector {
    fun forPartial(partial: String): EndpointingProfile {
        val q = partial.trim().lowercase()
        if (SpeculativeToolPrep.looksLikeCommand(partial)) {
            return EndpointingProfile.ShortCommand
        }
        if (q.startsWith("what ") || q.startsWith("why ") || q.startsWith("how ") ||
            q.startsWith("where ") || q.startsWith("when ") || q.startsWith("who ") ||
            q.contains("tell me") || q.contains("explain")
        ) {
            return EndpointingProfile.OpenQuestion
        }
        return EndpointingProfile.Default
    }
}
