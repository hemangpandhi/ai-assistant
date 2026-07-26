package com.test.design.presentation.assistant.backend

import com.test.design.assistant.api.AssistantBackend
import com.test.design.assistant.api.AssistantCabinContext
import com.test.design.assistant.api.AssistantGesture
import com.test.design.assistant.api.AssistantMoodId
import com.test.design.assistant.api.AssistantSessionConfig
import com.test.design.assistant.api.AssistantSessionEvent
import com.test.design.assistant.api.AssistantSpeaker
import com.test.design.assistant.api.AssistantSpeechInput
import com.test.design.assistant.api.AssistantStartReason
import com.test.design.assistant.api.AssistantTtsEngine
import com.test.design.presentation.assistant.AssistantMood
import com.test.design.presentation.assistant.DialogueBeat
import com.test.design.presentation.assistant.DialogueSpeaker
import com.test.design.presentation.assistant.ImmersiveDialogueScript
import com.test.design.presentation.assistant.contextGlyphGaze
import com.test.design.presentation.assistant.faceGestureForText
import com.test.design.presentation.assistant.fatigueMoodForText
import com.test.design.presentation.assistant.gazeForSpeaker
import com.test.design.presentation.assistant.isAnswerMood
import com.test.design.presentation.assistant.shouldSpeakBeat
import com.test.design.presentation.assistant.simulatedLipSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Default scripted brain — same demo dialogue the immersive UI used inline.
 * Swap for a remote/LLM [AssistantBackend] without touching Compose.
 */
class DemoAssistantBackend(
    private val speakingTts: AssistantTtsEngine = SilentAssistantTts,
    private val silentTts: AssistantTtsEngine = SilentAssistantTts,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val script: List<DialogueBeat> = ImmersiveDialogueScript,
) : AssistantBackend {

    private val _events = MutableSharedFlow<AssistantSessionEvent>(extraBufferCapacity = 64)
    override val events: Flow<AssistantSessionEvent> = _events.asSharedFlow()

    private val _sessionActive = MutableStateFlow(false)
    override val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private var sessionJob: Job? = null
    private var liveSttActive = false
    private var sttBuffer = ""
    private var sessionConfig = AssistantSessionConfig()

    override fun startSession(
        reason: AssistantStartReason,
        cabin: AssistantCabinContext,
        config: AssistantSessionConfig,
    ) {
        stopSession()
        sessionConfig = config
        _sessionActive.value = true
        liveSttActive = false
        sttBuffer = ""
        sessionJob = scope.launch { runDemoSession(cabin) }
    }

    override fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        _sessionActive.value = false
    }

    override fun onSpeechInput(input: AssistantSpeechInput) {
        when (input) {
            AssistantSpeechInput.Hotword -> Unit
            is AssistantSpeechInput.Partial -> {
                liveSttActive = true
                sttBuffer = input.text
                scope.launch {
                    emitAll(
                        AssistantSessionEvent.Transcript(input.text, AssistantSpeaker.User),
                        AssistantSessionEvent.MoodChanged(AssistantMoodId.Listening),
                        AssistantSessionEvent.Gaze(x = -0.42f, y = 0.05f),
                    )
                }
            }
            is AssistantSpeechInput.Final -> {
                liveSttActive = true
                sttBuffer = input.text
                val gesture = faceGestureForText(input.text).toApiGesture()
                val fatigue = fatigueMoodForText(input.text)?.toMoodId()
                scope.launch {
                    emitAll(
                        AssistantSessionEvent.Transcript(input.text, AssistantSpeaker.User),
                        AssistantSessionEvent.GestureChanged(gesture),
                        AssistantSessionEvent.MoodChanged(fatigue ?: AssistantMoodId.Listening),
                    )
                }
            }
            is AssistantSpeechInput.Rms -> {
                scope.launch {
                    emitAll(
                        AssistantSessionEvent.Gaze(
                            x = -0.25f - input.normalized * 0.25f,
                            y = -0.02f + input.normalized * 0.04f,
                        ),
                    )
                }
            }
        }
    }

    override fun onThumbsFeedback(positive: Boolean) {
        scope.launch {
            emitAll(
                AssistantSessionEvent.GestureChanged(
                    if (positive) AssistantGesture.Nod else AssistantGesture.Shake,
                ),
            )
            delay(700)
            emitAll(AssistantSessionEvent.GestureChanged(AssistantGesture.None))
        }
    }

    private suspend fun runDemoSession(cabin: AssistantCabinContext) {
        val sessionScript = buildCabinBeats(cabin) + script
        val handOff = shouldHandOffToCluster(cabin.drivingUx)
        val tts = if (sessionConfig.enableTts) speakingTts else silentTts

        for (beat in sessionScript) {
            if (!_sessionActive.value) break

            if (beat.speaker == DialogueSpeaker.User &&
                liveSttActive &&
                sttBuffer.isNotBlank() &&
                sttBuffer.contains(beat.text.take(12), ignoreCase = true)
            ) {
                emitAll(
                    AssistantSessionEvent.GestureChanged(
                        faceGestureForText(sttBuffer).toApiGesture(),
                    ),
                )
                delay(beat.holdMs.coerceAtMost(1_200))
                emitAll(AssistantSessionEvent.GestureChanged(AssistantGesture.None))
                continue
            }

            if (beat.speaker == DialogueSpeaker.User ||
                beat.mood == AssistantMood.Listening
            ) {
                emitAll(AssistantSessionEvent.ThumbsVisible(false))
            }

            var mood = beat.mood
            if (beat.speaker == DialogueSpeaker.User) {
                fatigueMoodForText(beat.text)?.let { mood = it }
            }
            emitAll(
                AssistantSessionEvent.MoodChanged(mood.toMoodId()),
                AssistantSessionEvent.Transcript(beat.text, beat.speaker.toApiSpeaker()),
                AssistantSessionEvent.ContextGlyph(beat.contextGlyph),
            )

            if (beat.contextGlyph != null) {
                val glance = contextGlyphGaze()
                emitAll(AssistantSessionEvent.Gaze(glance.first, glance.second))
            } else if (beat.mood == AssistantMood.Searching ||
                beat.mood == AssistantMood.Reading ||
                beat.mood == AssistantMood.Bored
            ) {
                emitAll(AssistantSessionEvent.Gaze(null, null))
            } else {
                val gaze = gazeForSpeaker(beat.speaker)
                emitAll(AssistantSessionEvent.Gaze(gaze.first, gaze.second))
            }

            emitAll(
                AssistantSessionEvent.GestureChanged(
                    if (beat.speaker == DialogueSpeaker.User) {
                        faceGestureForText(beat.text).toApiGesture()
                    } else {
                        AssistantGesture.None
                    },
                ),
            )

            if (beat.speaker == DialogueSpeaker.User && !liveSttActive) {
                delay(beat.holdMs.coerceIn(800L, 2_400L))
            } else if (shouldSpeakBeat(beat)) {
                tts.speak(beat.text, beat.holdMs).collect { amp ->
                    emitAll(AssistantSessionEvent.MouthAmplitude(amp))
                }
                emitAll(AssistantSessionEvent.MouthAmplitude(null))
                if (isAnswerMood(beat.mood) &&
                    !cabin.drivingUx.equals("Restricted", ignoreCase = true)
                ) {
                    emitAll(AssistantSessionEvent.ThumbsVisible(true))
                }
            } else {
                delay(beat.holdMs)
            }
            emitAll(AssistantSessionEvent.GestureChanged(AssistantGesture.None))
        }

        if (_sessionActive.value && handOff) {
            emitAll(
                AssistantSessionEvent.RequestClusterHandOff,
                AssistantSessionEvent.MoodChanged(AssistantMoodId.Idle),
                AssistantSessionEvent.Transcript(
                    "Mirrored to cluster · tap to dismiss",
                    AssistantSpeaker.System,
                ),
                AssistantSessionEvent.ContextGlyph(null),
                AssistantSessionEvent.ThumbsVisible(false),
            )
            delay(2_200)
        }

        if (_sessionActive.value) {
            delay(500)
            emitAll(AssistantSessionEvent.SessionComplete)
        }
        _sessionActive.value = false
    }

    private suspend fun emitAll(vararg events: AssistantSessionEvent) {
        events.forEach { _events.emit(it) }
    }
}

/** Silent lip-sync amplitudes — used when host TTS is disabled (overlay default). */
object SilentAssistantTts : AssistantTtsEngine {
    override fun speak(text: String, holdMs: Long): Flow<Float> = simulatedLipSync(holdMs)
}

fun shouldHandOffToCluster(drivingUx: String): Boolean =
    drivingUx.equals("Driving", ignoreCase = true) ||
        drivingUx.equals("Restricted", ignoreCase = true)

fun buildCabinBeats(cabin: AssistantCabinContext): List<DialogueBeat> {
    val beats = mutableListOf<DialogueBeat>()
    when (cabin.drivingUx.lowercase()) {
        "restricted" -> beats += DialogueBeat(
            speaker = DialogueSpeaker.System,
            text = "Driver focus mode — keeping this glanceable",
            mood = AssistantMood.Listening,
            holdMs = 1_800,
        )
        "driving" -> beats += DialogueBeat(
            speaker = DialogueSpeaker.System,
            text = "You're at ${cabin.speedMph ?: "—"} mph · gear ${cabin.gear ?: "—"}",
            mood = AssistantMood.Idle,
            holdMs = 1_600,
        )
    }
    val battery = cabin.batteryPercent
    val range = cabin.rangeMiles
    if (battery != null && battery <= 25) {
        beats += DialogueBeat(
            speaker = DialogueSpeaker.Assistant,
            text = "Battery is at $battery% — want a charger along the route?",
            mood = AssistantMood.Speaking,
            holdMs = 2_800,
        )
    } else if (range != null && range <= 60) {
        beats += DialogueBeat(
            speaker = DialogueSpeaker.Assistant,
            text = "Range is about $range miles. I can find a charge stop.",
            mood = AssistantMood.Speaking,
            holdMs = 2_600,
        )
    }
    if (cabin.isCharging) {
        val kw = cabin.chargeRateKw?.toInt() ?: 0
        beats += DialogueBeat(
            speaker = DialogueSpeaker.Assistant,
            text = "Charging at $kw kW — you're all set.",
            mood = AssistantMood.Happy,
            holdMs = 2_200,
        )
    }
    // Late-night / fatigue cue when already driving slowly in restricted mode.
    if (cabin.drivingUx.equals("Restricted", ignoreCase = true) &&
        (battery == null || battery > 25)
    ) {
        beats += DialogueBeat(
            speaker = DialogueSpeaker.Assistant,
            text = "Long stretch ahead — I'll keep watch and stay quiet. Rest when you can.",
            mood = AssistantMood.Tired,
            holdMs = 2_600,
        )
    }
    return beats
}
