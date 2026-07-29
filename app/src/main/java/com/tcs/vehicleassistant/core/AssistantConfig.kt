package com.tcs.vehicleassistant.core

/**
 * Single source of truth for tunables that were previously scattered as magic numbers and
 * duplicated string literals across the assistant pipeline.
 *
 * Everything here is a compile-time constant so it can be asserted directly in JVM unit tests
 * without an Android device, which is how the stability suite pins behaviour that used to be
 * re-implemented (and therefore un-verified) inside the tests themselves.
 */
object AssistantConfig {

    /** SharedPreferences file shared by the app process and the `:wakeword` process. */
    const val PREFS_NAME = "app_prefs"

    object Prefs {
        const val BACKEND_CHOICE = "backend_choice"
        const val SELECTED_MODEL = "selected_model"
        const val MAX_TOKENS = "max_tokens"
        const val WAKE_WORD = "wake_word"
        const val WAKE_WORD_ENABLED = "wake_word_enabled"
        const val SYSTEM_PROMPT = "system_prompt"
        const val USER_MEMORY = "user_memory"
        const val UI_LAYOUT = "ui_layout_pref"
        const val COMPANION_MODE = "companion_mode_enabled"
        const val AGENTIC_LOOP = "agentic_loop_enabled"
        const val CLOUD_FALLBACK = "cloud_fallback_enabled"

        /** Records the backend that LiteRT actually initialized with, after any fallback. */
        const val RESOLVED_BACKEND = "resolved_backend"

        /** Model path the OpenCL kernel cache was built for, used to invalidate stale kernels. */
        const val KERNEL_CACHE_MODEL = "kernel_cache_model"
    }

    object Backend {
        const val AUTO = "Auto"
        const val GPU = "GPU"
        const val CPU = "CPU"
        const val NPU = "NPU"

        /**
         * Ordered degradation chain. A GPU request that fails OpenCL initialization retries on
         * CPU rather than leaving the assistant permanently unusable, which was the previous
         * behaviour on devices without a usable OpenCL driver.
         */
        val FALLBACK_CHAIN = listOf(GPU, CPU)
    }

    object Llm {
        const val MAX_NUM_TOKENS = 3072

        /** Directory holding LiteRT serialized inference contexts / compiled OpenCL kernels. */
        const val KERNEL_CACHE_DIR = "litertlm_kernel_cache"

        /** Time budget for a full engine initialization (model load + kernel compile). */
        const val INIT_TIMEOUT_MS = 240_000L

        /** Budget for the first inference, which pays the prompt-prefill cost. */
        const val FIRST_INFERENCE_TIMEOUT_MS = 180_000L

        /** Budget for subsequent inferences once the KV cache is warm. */
        const val INFERENCE_TIMEOUT_MS = 45_000L

        /** Hard ceiling on agentic observe/act iterations per user turn. */
        const val MAX_AGENTIC_LOOPS = 3

        /** Per-tool execution budget, so one wedged VHAL write cannot hang the turn. */
        const val TOOL_TIMEOUT_MS = 10_000L

        /** Conversation is recycled past this many turns to bound KV cache growth. */
        const val CONVERSATION_RESET_TURNS = 8

        /**
         * How long forced re-init / conversation reset will wait for an in-flight LiteRT stream to
         * finish before refusing to tear down the native engine.
         */
        const val INFERENCE_DRAIN_TIMEOUT_MS = 15_000L
    }

    object Memory {
        const val DEFAULT_MAX_CHARS = 3_000

        /** Hard cap on retained turns. Without this the history list grew for the process lifetime. */
        const val MAX_RETAINED_TURNS = 40
    }

    object Streaming {
        /** Below this length a response is never considered a runaway repetition. */
        const val REPETITION_SCAN_MIN_LENGTH = 250

        /** Absolute character ceiling on a single generation before it is cut off. */
        const val RUNAWAY_LENGTH = 600

        /** Identical trailing words needed to classify output as a repetition loop. */
        const val REPETITION_WINDOW = 5
    }

    object Audio {
        const val SAMPLE_RATE_HZ = 16_000

        /** Consecutive non-speech frames after speech before the utterance is closed. */
        const val TRAILING_SILENCE_FRAMES = 10

        /** Consecutive non-speech frames with no speech at all before giving up (~5s). */
        const val NO_SPEECH_TIMEOUT_FRAMES = 50

        /** AudioRecord acquisition retries, covering handoff from the wake-word process. */
        const val AUDIO_RECORD_MAX_ATTEMPTS = 5
        const val AUDIO_RECORD_RETRY_DELAY_MS = 150L

        /** Grace period for the wake-word process to release the microphone. */
        const val MIC_HANDOFF_DELAY_MS = 400L

        /**
         * Ceiling on how long a caller waits for queued speech to drain. The wait is a marker task
         * on the TTS queue, so an interruption that discards the queue would otherwise leave the
         * waiter suspended for good — and tool execution waits on it before touching the vehicle.
         */
        const val SPEECH_DRAIN_TIMEOUT_MS = 30_000L
    }

    object WakeWord {
        const val DEFAULT_WAKE_WORD = "hey assistant"

        /** Vosk out-of-vocabulary token; never treated as a match. */
        const val UNKNOWN_TOKEN = "[unk]"

        /** Peak amplitude above which a frame is treated as speech by the gate. */
        const val SPEECH_AMPLITUDE_THRESHOLD = 500

        /** Frames after speech during which audio is still fed to the recognizer. */
        const val RECOGNITION_TAIL_FRAMES = 30

        const val RESTART_DELAY_MS = 300L
    }

    /**
     * Intents exchanged between [com.tcs.vehicleassistant.WakeWordService] and the voice session.
     * These live in one place because the session previously sent `ACTION_STOP_LISTENING` while
     * the service only handled `ACTION_STOP`, so the wake-word microphone was never released.
     */
    object WakeWordAction {
        const val START = "com.tcs.vehicleassistant.action.WAKE_WORD_START"
        const val STOP = "com.tcs.vehicleassistant.action.WAKE_WORD_STOP"
        const val PAUSE = "com.tcs.vehicleassistant.action.WAKE_WORD_PAUSE"
        const val RESTART = "com.tcs.vehicleassistant.action.WAKE_WORD_RESTART"

        const val DETECTED_BROADCAST = "com.tcs.vehicleassistant.WAKE_WORD_DETECTED"

        /** Action strings accepted from older builds and external launchers. */
        val STOP_ALIASES = setOf("ACTION_STOP", "ACTION_STOP_LISTENING", STOP)
        val PAUSE_ALIASES = setOf("ACTION_PAUSE", "ACTION_PAUSE_LISTENING", PAUSE)
        val RESTART_ALIASES = setOf("ACTION_RESTART", "ACTION_RESTART_LISTENING", RESTART)

        /** True when [action] should release the microphone and stop the service entirely. */
        fun isStop(action: String?): Boolean = action != null && action in STOP_ALIASES

        /** True when [action] should release the microphone but keep the service alive. */
        fun isPause(action: String?): Boolean = action != null && action in PAUSE_ALIASES

        /** True when [action] should re-acquire the microphone after a session ends. */
        fun isRestart(action: String?): Boolean = action != null && action in RESTART_ALIASES
    }

    object Session {
        /** Bound wait for the engine before the overlay reports failure instead of hanging. */
        const val LLM_READY_TIMEOUT_MS = 240_000L
        const val LLM_READY_POLL_MS = 250L
        const val TYPEWRITER_STEP_MS = 15L
    }

    /** Screens at least this wide are treated as tablet/large-format head units. */
    const val LARGE_SCREEN_MIN_WIDTH_DP = 600
}
