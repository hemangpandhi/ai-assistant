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

        /** Selected Piper/Sherpa cabin TTS voice id from [TtsVoiceCatalog]. */
        const val TTS_VOICE_ID = "tts_voice_id"

        /** Speaker id for multi-speaker Piper models (LibriTTS-R, VCTK, …). */
        const val TTS_SPEAKER_ID = "tts_speaker_id"

        /** Speaking rate applied to Sherpa OfflineTts.generate(speed=…). */
        const val VOICE_RATE = "voice_rate"

        /** STT Engine selection: 'sherpa' or 'google' */
        const val STT_ENGINE = "stt_engine"
        const val STT_ENGINE_SHERPA = "sherpa"
        const val STT_ENGINE_GOOGLE = "google"

        /**
         * When true, final STT text updates the UI / transcript only and does **not**
         * call orchestrator / LLM / tools. Off by default — leaving it on re-arms the
         * recognizer forever and thrashes the mic (especially Google SpeechRecognizer).
         */
        const val EAR_TEST_MODE = "ear_test_mode"
        /** Default false: voice → agent. Set true only for isolated mic→STT bring-up. */
        const val EAR_TEST_MODE_DEFAULT = false

        /** One-shot: ear_test_mode off + platform STT default (Google on GAS, Sherpa otherwise). */
        const val MIC_THRASH_FIX_APPLIED_V2 = "mic_thrash_fix_applied_v2"

        /**
         * When true (default for ear bring-up), do not start Vosk [WakeWordService].
         * Keeps the mic free for session STT testing. Set false to re-enable hotword.
         */
        const val WAKE_WORD_DISABLED_FOR_TEST = "wake_word_disabled_for_test"
        const val WAKE_WORD_DISABLED_FOR_TEST_DEFAULT = true

        /**
         * When true, LiteRT enables speculative decoding / MTP at Engine init if the model
         * reports support (Gallery-style; default off for stability).
         */
        const val ENABLE_SPECULATIVE_DECODING = "enable_speculative_decoding"
    }

    /** Google Assistant / search package present on GAS images. */
    const val GAS_SPEECH_PACKAGE = "com.google.android.googlequicksearchbox"

    /** True when mic→STT should stop before orchestration (safe ear bring-up). */
    fun isEarTestMode(context: android.content.Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean(Prefs.EAR_TEST_MODE, Prefs.EAR_TEST_MODE_DEFAULT)
    }

    /**
     * True GAS when Assistant / search (`googlequicksearchbox`) is installed.
     * Presence of `com.google.android.tts` alone is NOT GAS — on AOSP Tangorpro its
     * GoogleTTSRecognitionService SODA offline path fails (ConfigStatus 5) and must not
     * alone decide the default engine.
     */
    fun isGasDevice(context: android.content.Context): Boolean =
        isPackageInstalled(context, GAS_SPEECH_PACKAGE)

    /**
     * Prefer Google STT when a non-stub Google [RecognitionService] is resolvable
     * (GAS Assistant, or TTS recognition used online — never forced offline).
     */
    fun hasGoogleRecognitionService(context: android.content.Context): Boolean =
        resolveGoogleRecognitionService(context) != null

    /**
     * Platform default: Google when a Google RecognitionService exists (GAS or TTS ASR);
     * Sherpa only when there is no Google recognizer at all.
     */
    fun defaultSttEngine(context: android.content.Context): String =
        if (hasGoogleRecognitionService(context) || isGasDevice(context)) {
            Prefs.STT_ENGINE_GOOGLE
        } else {
            Prefs.STT_ENGINE_SHERPA
        }

    /** Explicit pref if set, otherwise [defaultSttEngine]. */
    fun resolvedSttEngine(context: android.content.Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return prefs.getString(Prefs.STT_ENGINE, null) ?: defaultSttEngine(context)
    }

    fun prefersGoogleStt(context: android.content.Context): Boolean =
        resolvedSttEngine(context) == Prefs.STT_ENGINE_GOOGLE

    /**
     * Component for a Google [android.speech.RecognitionService] outside this app.
     * Avoids binding our VIS [StubRecognitionService], which always returns ERROR_CLIENT.
     */
    fun resolveGoogleRecognitionService(context: android.content.Context): android.content.ComponentName? {
        val intent = android.content.Intent("android.speech.RecognitionService")
        val resolves = context.packageManager.queryIntentServices(intent, 0)
        return resolves
            .asSequence()
            .mapNotNull { resolve ->
                val si = resolve.serviceInfo ?: return@mapNotNull null
                android.content.ComponentName(si.packageName, si.name)
            }
            .firstOrNull { component ->
                component.packageName != context.packageName &&
                    component.packageName.contains("google", ignoreCase = true)
            }
    }

    private fun isPackageInstalled(context: android.content.Context, packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }

    /**
     * One-shot: turn off sticky ear_test_mode and apply platform STT default
     * (Google on GAS, Sherpa on non-GAS).
     */
    fun migrateMicThrashPrefs(context: android.content.Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(Prefs.MIC_THRASH_FIX_APPLIED_V2, false)) return
        prefs.edit()
            .putBoolean(Prefs.EAR_TEST_MODE, false)
            .putString(Prefs.STT_ENGINE, defaultSttEngine(context))
            .putBoolean(Prefs.MIC_THRASH_FIX_APPLIED_V2, true)
            .apply()
    }

    /** True when Vosk / WakeWordService must not run (ear STT isolation). */
    fun isWakeWordDisabledForTest(context: android.content.Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean(
            Prefs.WAKE_WORD_DISABLED_FOR_TEST,
            Prefs.WAKE_WORD_DISABLED_FOR_TEST_DEFAULT,
        )
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
        /**
         * Default edge model (Gemma 4 E2B IT generic). [LLMManager.autoInitialize] and settings
         * both lock to this filename so cold start / voice / text skip a manual "Load Model" step.
         */
        const val DEFAULT_MODEL_FILENAME = "gemma-4-E2B-it.litertlm"

        /** Sideload path used by deploy scripts and [LLMManager.autoInitialize]. */
        const val DEFAULT_MODEL_PATH = "/data/local/tmp/llm/$DEFAULT_MODEL_FILENAME"

        /**
         * KV-cache budget (input+output). Gallery's Gemma 4 default is 4000; we stay at 3072 to
         * limit RAM/prefill cost on tablet AAOS. Do not raise without measuring OOM / TTFT.
         */
        const val MAX_NUM_TOKENS = 3072

        /** Gallery / LiteRT-LM chat sampler defaults (GPU/CPU). NPU leaves sampler null. */
        const val SAMPLER_TOP_K = 64
        const val SAMPLER_TOP_P = 0.95
        const val SAMPLER_TEMPERATURE = 1.0

        /** Official LiteRT benchmark defaults (diagnostics only). */
        const val BENCHMARK_PREFILL_TOKENS = 256
        const val BENCHMARK_DECODE_TOKENS = 256

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

        /**
         * Sideload root for Sherpa-ONNX Whisper STT (push via adb; not packaged via Git LFS).
         *
         * Expected layout:
         * ```
         * /data/local/tmp/stt/tiny.en-encoder.int8.onnx
         * /data/local/tmp/stt/tiny.en-decoder.int8.onnx
         * /data/local/tmp/stt/tiny.en-tokens.txt
         * # optional higher quality:
         * /data/local/tmp/stt/base.en-encoder.int8.onnx
         * /data/local/tmp/stt/base.en-decoder.int8.onnx
         * /data/local/tmp/stt/base.en-tokens.txt
         * ```
         * LLM models stay under `/data/local/tmp/llm/`; required Vosk wake pack under
         * `/data/local/tmp/vosk/` (see [WakeWordService]). Whisper is not packaged in the APK.
         */
        const val STT_SIDELOAD_DIR = "/data/local/tmp/stt"

        /**
         * How long after the last voiced frame we treat the utterance as finished.
         * Measured in wall-clock ms (not AudioRecord reads) so it stays stable across devices
         * whose min buffer size differs. 400ms is snappy for cabin commands without clipping
         * short pauses inside a phrase.
         */
        const val TRAILING_SILENCE_MS = 400L

        /**
         * @deprecated Prefer [TRAILING_SILENCE_MS]. Kept as an approximate frame budget for
         * coherence tests and the RMS fallback path when VAD is unavailable.
         */
        const val TRAILING_SILENCE_FRAMES = 5

        /** Give up if the user never speaks (~5s). */
        const val NO_SPEECH_TIMEOUT_MS = 5_000L

        /** Approximate frame budget mirroring [NO_SPEECH_TIMEOUT_MS] for the RMS fallback. */
        const val NO_SPEECH_TIMEOUT_FRAMES = 50

        /** AudioRecord acquisition retries, covering handoff from the wake-word process. */
        const val AUDIO_RECORD_MAX_ATTEMPTS = 20
        const val AUDIO_RECORD_RETRY_DELAY_MS = 150L

        /**
         * Grace period for the wake-word process to release the microphone. Retries in
         * [com.tcs.vehicleassistant.hardware.AndroidAudioManager] cover residual contention, so this
         * can stay short.
         */
        const val MIC_HANDOFF_DELAY_MS = 200L

        /**
         * Silero VAD: required silence before a speech segment is closed. Was 1.0s and dominated
         * voice E2E; 0.4s matches [TRAILING_SILENCE_MS] for cabin-command turn-taking.
         */
        const val VAD_MIN_SILENCE_DURATION_SEC = 0.4f

        /** Silero VAD: ignore clicks shorter than this. */
        const val VAD_MIN_SPEECH_DURATION_SEC = 0.2f

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

        /**
         * After a successful match, ignore further hypotheses for this long. Prevents a rematch
         * when the session immediately hands the mic back (or music/TTS bleeds into the mic).
         */
        const val POST_MATCH_COOLDOWN_MS = 3_000L

        /**
         * After RESTART resumes listening, ignore matches briefly so a stale Vosk final or the
         * first burst of media audio cannot reopen the overlay without a fresh wake utterance.
         */
        const val POST_RESTART_IGNORE_MS = 1_500L
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

        /**
         * Target wall time from an already-recognized user query to tool actuation + first spoken
         * / displayed reply. Full Gemma prefill exceeds this (~1.5s), so high-confidence cabin
         * commands use [com.tcs.vehicleassistant.core.DirectToolResolver] against the skills registry.
         */
        const val END_TO_END_BUDGET_MS = 1_000L
    }

    /** Screens at least this wide are treated as tablet/large-format head units. */
    const val LARGE_SCREEN_MIN_WIDTH_DP = 600
}
