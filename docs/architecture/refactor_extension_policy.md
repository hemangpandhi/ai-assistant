# Extend-first preference (guidance, not a hard lock)

**Goal:** Keep branches rebase-friendly and packages readable. Prefer **new focused
types + thin facades** when ownership is clear (SOLID / SRP). Editing existing
agent/hardware files is allowed when the task requires it.

## Rule of thumb

| Prefer | Avoid |
|--------|--------|
| New class in the right package (`hardware/ear`, `assistant/`, `com/assistant/ui`) | God-classes that mix mic, STT, LLM, and UI |
| Interface ports (`IAudioManager`, `EarSttEngine`) | Leaking Android types into UI |
| Compose / UI in `com.assistant.ui` | Importing orchestrator/VHAL into Compose |
| Small state machines for mic lifecycle | One-shot `SpeechRecognizer` as the long-term ear |

## Ear path (mic → text)

```
VehicleAgentService
  → AndroidAudioManager (TTS + ear facade)
      → AssistantEar (state machine)
          → EarMic (standby AudioRecord)
          → EarSttEngine (Sherpa primary / Google offline demoted)
  → AssistantViewModel (UI state; optional EAR_TEST_MODE skips orchestrator)
```

## Host API boundary

`:assistant-api` / `com.assistant.api` owns host-neutral contracts used by Compose UI.
Cabin STT stays in `com.tcs.vehicleassistant.hardware.ear`.
