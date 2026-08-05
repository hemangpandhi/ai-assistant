package com.tcs.vehicleassistant.hardware.ear

/**
 * Session-scoped ear lifecycle. Mic stays owned across utterances; engines are not
 * torn down between [Capturing] → [Armed] re-arms.
 */
enum class EarState {
    /** No mic / models. */
    Closed,

    /** Allocating standby [android.media.AudioRecord] and loading STT/VAD. */
    Prewarm,

    /** Mic allocated (unstarted), models ready — waiting for an utterance. */
    Armed,

    /** Recording + streaming PCM into the STT engine. */
    Capturing,

    /** Endpointed; decoding / emitting final text before returning to [Armed]. */
    Finalizing,
}
