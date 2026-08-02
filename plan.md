# Deferred & capture-first techniques — plan

North star: **summon = ear open**; **speech = text committed**; **meaning / tools / LLM = async follow-on**.  
UI polish (glow, TTS quality, rich context) stays off the critical path.

## Baseline (already on `dev/ui_ux_v2`)

- Pre-arm STT before overlay (`MicCaptureCoordinator`)
- Event-driven Vosk → STT mic handoff
- Warm `SpeechRecognizer` (soft-stop on hide; no destroy/recreate every session)
- FollowUpRouter / keyword tools before LLM warm-up
- Queue queries while model prewarms
- Stream UI coalesce (~32ms); idle auto-close gated on first Listening
- Live user transcript via `StateFlow` (partials must not be clobbered by “Listening…”)
- **Face moods (two-layer):** harness pipeline (`Idle/Listening/Thinking/Speaking/…`) + optional
  LLM/heuristic affective (`Happy/Sad/Excited/…`) via `<MOOD>…</MOOD>`;
  `FaceMoodResolver` precedence — see `FaceMoodResolver` / `MoodTagParser`

---

## Tier 1 — Highest leverage (same process, low risk)

1. **Speculative tool prep** ✅  
   On strong partials (`"turn on the a…"`, `"set temp to 7…"`), resolve candidate tool + args early; **execute only on final** (or high-confidence endpoint). Cuts tool TTFR without wrong HVAC writes.  
   → `SpeculativeToolPrep` + `AgentOrchestrator.tryHandleDirectFollowUp`

2. **Two-phase utterance commit** ✅  
   - Phase A: endpoint → commit text (`liveTranscript` / `SetInputText`)  
   - Phase B: FollowUpRouter / LLM on agent dispatcher (`queryJob`)  
   Never block the next listen cycle on inference.

3. **Listen-through while thinking (barge-in lite)** ✅  
   Keep STT armed during Thinking; `cancelInFlight()` on `onBeginningOfSpeech` supersedes the turn.

4. **Partial-driven UI only** ✅  
   Partials update transcript/mouth; **never** move to Thinking on `onEndOfSpeech` — only when orchestrator starts Phase B.

5. **Prompt / KV deferral** ✅  
   Build tool top‑K + telemetry **after** final, off Main, only if FollowUpRouter misses. Short / command-like turns skip VHAL context (length ≥ 50 gate).

---

## Tier 2 — Architecture (more invasive) — deferred

6. **Shared PCM ring buffer**  
   One `AudioRecord` → wake-word + command ASR consumers. Removes exclusive Vosk ↔ SpeechRecognizer handoff (largest remaining hard latency).

7. **On-device command grammar / small SLU**  
   Tiny keyword/intent model for HVAC/media/windows in parallel with full ASR. Instant path for most cabin commands; full ASR + LLM only on miss.

8. **Speculative LLM decode**  
   When FollowUpRouter confidence is low but partial is stable, start edge decode early; discard if final routes to a tool. Needs cancelable LiteRT session.

9. **Session state machine: Capture | Understand | Act | Speak**  
   Explicit phases with non-blocking transitions; Capture never waits on Act/Speak. Makes barge-in and pre-arm rules enforceable in one place.

10. **Deferred vision / telemetry / memory**  
    Vision and long-term memory writes only after Act starts or on idle; never on summon critical path.

---

## Tier 3 — Product polish (snappy feel)

11. **Adaptive silence / endpointing** ✅  
    `EndpointingProfile` (ShortCommand / Default / OpenQuestion) from partial likelihood; applied on next `startListening`.

12. **TTS / listen overlap policy** ✅  
    Faster `QUESTION_FINAL` → StartListening (~80ms) + shorter mic re-arm; Thinking re-arms ear for barge-in.

13. **Idle timeout = “quiet after ready”** ✅ (baseline)  
    Grace after Speak; reset countdown on partial (`SetInputText` → `noteUserActivity`); never arm before Listening.

14. **Prewarm ladder** ✅ (baseline)  
    Process start: STT create (`VehicleAgentService` / `AndroidAudioManager`) → TTS → tool registry → LLM (wake-word delayed). Never LLM before ear.

---

## Suggested implementation order

| Order | Item | Status |
|------:|------|--------|
| 1 | Speculative tool prep (execute on final) | ✅ Done |
| 2 | Two-phase commit + no Thinking on partial | ✅ Done |
| 3 | Barge-in lite during Thinking | ✅ Done |
| 4 | Stricter prompt/telemetry deferral | ✅ Done |
| 5 | Shared PCM **or** small command SLU | Deferred (Tier 2) |
| 6 | Speculative LLM + cancel | Deferred (Tier 2) |

---

## Explicit non-goals (for now)

- Splitting UI/harness into two apps  
- Always-on vision  
- Destroy/recreate recognizer every session  
- Blocking overlay paint on model ready  

---

## Open defect theme (transcript)

User speech must appear on the immersive stage as soon as STT partials arrive:

- Prefer `AssistantViewModel.liveTranscript` (StateFlow) over SharedFlow-only events  
- Attach backend collectors before `startListening` in pre-arm  
- Never overwrite user partials with placeholder “Listening…” / “Thinking…”  
- Force transcript alpha visible when speaker is User (even mid-enter animation)
