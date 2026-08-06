# Wake Word + Mic + STT Architecture

Target design for VehicleEdgeAssistant wake listening, microphone ownership, and
speech-to-text — covering **demo max-performance**, **production**, **GAS**,
**non-GAS**, **OEM DSP hotword**, and **app-space KWS** without hard-wiring a
single stack.

Related:

- [ideal-arch.md](ideal-arch.md) — modular monolith / VIS alignment
- [refactor_extension_policy.md](refactor_extension_policy.md) — ear path ownership
- [decoupling_roadmap.md](decoupling_roadmap.md) — mic lease / endpointing seams
- [../performance/DSP_SCHEDULING_ANALYSIS.md](../performance/DSP_SCHEDULING_ANALYSIS.md) — Hexagon STT ↔ LLM contention
- [../../plan.md](../../plan.md) — Tier 2 “Shared PCM ring buffer” (deferred)

---

## 1. Purpose and problem statement

On standard Android hardware (Pixel Tablet, high-spec AAOS boards **without** a
vehicle DSP hotword path), the largest listening bottleneck is **Audio Resource
Conflict**:

- If the wake-word engine and the STT engine each open `AudioRecord`, Android
  throws a microphone-in-use failure.
- If the wake path **closes** the mic and STT **reopens** it, you pay
  **~300–500ms** of handoff and often **clip the first word** of the command
  (e.g. “Hey MyCar, turn on the AC” → “…urn on the AC”).

Gemini-like demo speed needs a **single continuous audio spine**: one mic owner,
a short ring buffer for pre-roll, and in-loop routing (`isAwake`) — not separate
mic sessions.

This document defines:

1. What the repo does **today**
2. The **ideal scalable** architecture (capability-driven)
3. The **Demo-First Max-Performance** profile in full
4. **Prod / GAS / non-GAS / DSP** tradeoffs and decision trees

---

## 2. Current as-built vs target

### 2.1 What ships today

```text
IDLE (wake listening)
  WakeWordService (:wakeword process)
    AudioRecord(VOICE_RECOGNITION) + Vosk constrained grammar
       │ wake match
       ▼
  stopCustomListening() → release AudioRecord
  broadcast WAKE_WORD_DETECTED
       │
       ▼
SESSION OPEN
  ComposeAssistantSession → PAUSE wake service
  VehicleAgentAssistantBackend → delay ~80ms → AssistantEar.startUtterance()
       │
       ▼
STT (main / agent process)
  EarMic AudioRecord + Silero VAD + Sherpa Whisper
    OR Google SpeechRecognizer (releases EarMic)
       │ trailing silence / VAD endpoint
       ▼
  transcript → AssistantViewModel / AgentOrchestrator
       │ session hide
       ▼
  RESTART WakeWordService → new AudioRecord loop
```

| Stage | Owner | Behavior |
|-------|--------|----------|
| Idle / wake | `WakeWordService` in `:wakeword` | Own `AudioRecord` + **Vosk** grammar |
| On match | Same service | **Stop + release** mic, broadcast detect |
| Session | `ComposeAssistantSession` | `PAUSE` wake; open overlay |
| STT open | `VehicleAgentAssistantBackend` | `MIC_OPEN_DELAY_MS = 80` then `AssistantEar` |
| Capture | `EarMic` | Standby / per-utterance record; retries up to 20 × 150ms |
| Decode | `SherpaEarSttEngine` or Google | **Whisper batch** + Silero, or platform ASR |
| Endpoint | Silero + config | `TRAILING_SILENCE_MS = 400`, `VAD_MIN_SILENCE_DURATION_SEC = 0.4f` |
| Session end | Compose session | Stop ear; `RESTART` wake (+ cooldowns) |

Key files:

- `app/.../WakeWordService.kt` — Vosk FGS, mic owner while idle
- `data/hardware/.../ear/AssistantEar.kt`, `EarMic.kt`, `SherpaEarSttEngine.kt`
- `data/hardware/.../SherpaKwsManager.kt` — Zipformer KWS **stub, not wired**
- `core/.../AssistantConfig.kt` — sample rate, silence, handoff constants

### 2.2 Gaps vs the target continuous pipeline

| Target | Current |
|--------|---------|
| One forever `AudioRecord` | Two owners; hard release on every match |
| ~1.5s PCM ring flush into STT | No app-level pre-roll across handoff |
| Tiny KWS (Sherpa / Porcupine) | Vosk ASR + grammar (heavier always-on) |
| Streaming Zipformer (or Google partials) | Non-GAS: Whisper **batch** after silence |
| In-loop `isAwake` routing | Cross-process PAUSE / RESTART + settle delays |
| Silero ~400ms EOS | **Already aligned** on Sherpa path once STT owns mic |

`plan.md` already lists **Shared PCM ring buffer** as deferred Tier 2.

---

## 3. Ideal scalable architecture

Do **not** hard-wire “always Vosk” or “always Google”. Use a coordinator that
probes capabilities and selects ports.

### 3.1 Core invariant

**Exactly one component owns the HAL microphone at a time:**

- **OEM DSP / Trusted Hotword** while idle (no app `AudioRecord`), **or**
- **ContinuousAudioPipeline** (single forever app `AudioRecord`)

Never two app `AudioRecord` instances (wake process + agent process).

### 3.2 VoiceAudioCoordinator

```text
                    ┌─────────────────────────────────────┐
                    │     VoiceAudioCoordinator            │
                    │  (capability probe + mode select)    │
                    └─────────────────────────────────────┘
           DSP wake │         │ app KWS          │ push-to-talk
                    ▼         ▼                  ▼
         AlwaysOnHotword   ContinuousAudioPipeline   (same pipeline,
         / OEM DSP         (1 AudioRecord + ring)     armed by UI)
                    │              │
                    └──────┬───────┘
                           ▼
              UtteranceSession (isAwake / capturing)
                    ┌──────┼──────────────┐
                    ▼      ▼              ▼
                 STTPort  VADPort      UI / VIS
              (pluggable) (Silero/…)   session
```

### 3.3 Pluggable ports

| Port | Responsibility | Implementations |
|------|----------------|-----------------|
| `WakePort` | Keyword / hotword detect | DSP `AlwaysOnHotwordDetector`, Sherpa KWS, Porcupine, legacy Vosk |
| `PcmSourcePort` | PCM frames @ 16 kHz | Continuous pipeline, DSP-triggered open, one-shot / PTT |
| `SttPort` | Streaming or batch ASR | Google offline `SpeechRecognizer`, Sherpa Online Zipformer, Sherpa Whisper offline |
| `VadPort` | Endpointing | Silero ONNX (in-process STT); platform endpointing (Google owns mic) |
| `RingBufferPort` | Pre-roll | 1.5s ring when app owns continuous PCM; no-op / OEM buffer when DSP supplies audio |

### 3.4 Profiles

| Profile | Intent |
|---------|--------|
| `demo` | Max-performance Demo-First pipeline (§5). Ignore battery / thermals. |
| `prod` | Capability probe; DSP-first; efficient KWS; adaptive endpointing. |
| `gas_handoff` | Prefer Google ASR quality; accept mic release settle on wake. |
| `dsp_preferred` | OEM Trusted Hotword when present; fall back to continuous + KWS. |

Session-facing API stays [`IAudioManager`](../../data/hardware/src/main/java/com/tcs/vehicleassistant/hardware/IAudioManager.kt).
Hide the coordinator under `AndroidAudioManager` so VIS / Compose sessions do not
take a mic-ownership dependency.

### 3.5 Suggested code insertion points (future work)

| Type | Location |
|------|----------|
| `ContinuousAudioPipeline` | `data/hardware/.../ear/` — sole `AudioRecord`, 20 ms loop, `isAwake` |
| `PcmRingBuffer` | same package — ~1.5s float/short ring, `push` / `snapshot` |
| Sherpa KWS wire-up | extend `SherpaKwsManager`; feed from pipeline when `!isAwake` |
| Streaming STT | new `SherpaStreamingEarSttEngine` implementing `EarSttEngine` |
| Host | `AndroidAudioManager` / main-process FGS (collapse `:wakeword` process for continuous PCM) |

`AudioRecord` **cannot** be shared across processes. Continuous PCM implies
dropping `android:process=":wakeword"` (or paying for PCM-over-Binder — not
recommended).

---

## 4. Capability matrix

| Environment | Wake | Mic ownership | STT | VAD / endpoint | Ring buffer |
|-------------|------|---------------|-----|----------------|-------------|
| **Prod + OEM DSP** | `AlwaysOnHotwordDetector` / Trusted Hotword | DSP holds idle mic; app opens PCM after hotword (or OEM callback) | Google if GAS; else streaming Sherpa | Platform or Silero after app mic open | Optional if OEM provides buffered audio; else accept OEM latency |
| **Prod + no DSP** (tablet / AOSP) | Sherpa KWS (or Porcupine) on continuous pipeline | One app `AudioRecord` forever | Streaming Zipformer (prod-size) or Google only via handoff profile | Silero 400–600ms | **Required** 1.5s |
| **Demo max-perf** (Pixel / board) | Porcupine / Sherpa KWS; mic never sleeps | Continuous from boot | Largest real-time Zipformer **or** Google Tensor offline | Silero **400ms** | **Required** 1.5s flush |
| **GAS** | DSP if present; else app KWS | Continuous **conflicts** with platform `SpeechRecognizer` mic | Google offline = best NPU accuracy but needs HAL — see §7 | Platform endpointing when Google owns mic | Ring only feeds **in-process** STT |
| **Non-GAS / raw AOSP** | App KWS (Sherpa) | Continuous pipeline | Streaming Zipformer + cabin bias lexicon | Silero 400ms | Required |

---

## 5. Demo-First Max-Performance Pipeline

For a client demo whose sole objective is to match Google Gemini’s **speed and
accuracy** — **ignoring** battery drain, CPU usage, and thermal limits — unleash
the full processing power of the target hardware (Pixel Tablet or high-spec AAOS
board).

### 5.1 Architecture diagram

```text
[Hardware Mic]
      │ (Continuous 16kHz PCM Stream)
      ▼
[1.5s Ring Buffer]
      │
      ├───────────────────────┬───────────────────────┐
      ▼                       ▼                       ▼
[Wake Word Engine]     [Local Streaming STT]    [Silero VAD (ONNX)]
(Porcupine / KWS)       (Zipformer / Google)    (Instant Endpointing)
```

### 5.2 Unconstrained demo rules

By unconstraining battery life, eliminate warm-up delays:

| Rule | Detail |
|------|--------|
| Mic never sleeps | `AudioRecord` thread runs continuously from application boot (microphone FGS) |
| Buffer holds audio | Rolling **1.5s** in-memory ring — “Hey MyCar open the sunroof” loses no syllables |
| Streaming ingestion | Feed STT in **20ms** chunks (16 kHz, 16-bit PCM mono) as they leave the mic |
| STT standby | Engine initialized in memory before first wake (zero cold-start) |
| Non-blocking cue | Soft beep / chime via **SoundPool** — must not stop or steal the mic |

### 5.3 Local STT engine selection for demo

Because battery is ignored, choose the highest-parameter model that still runs
**real-time** on the target SoC.

#### Option A — Pixel Tablet / GAS environment

**When Google owns the utterance mic** (fastest path for general English on Tensor):

- Android `SpeechRecognizer`
- `RecognizerIntent.EXTRA_PREFER_OFFLINE = true`
- `EXTRA_PARTIAL_RESULTS` enabled
- Partials often appear within **~30–50ms** of each spoken word

**Compatibility constraint (critical):** Google ASR opens its **own** mic. A
continuous exclusive `AudioRecord` must **release** the HAL on wake unless the
OEM/DSP supplies PCM. That:

- Reintroduces settle latency (~80–200ms+), and
- **Blocks** dumping the 1.5s ring into Google (platform ASR does not accept
  arbitrary PCM from the app)

Demo choice on GAS:

| Sub-option | Behavior | Use when |
|------------|----------|----------|
| **A1 — Accuracy** | Release pipeline → Google offline + partials; accept handoff gap | Product wants Google dictation quality |
| **A2 — Zero-clip** | Keep continuous PCM + **Sherpa streaming** even on Pixel | Demo must not clip first words / must match Gemini handoff feel |

**Recommendation for Gemini-like zero-clip tablet demos:** prefer **A2**, unless
DSP / Trusted Hotword + Google utterance is available (no always-on app mic).

#### Option B — Non-GAS / raw AOSP (maximum OEM control)

Embed **Sherpa-ONNX** with a **streaming Zipformer transducer**
(e.g. `sherpa-onnx-streaming-zipformer-en-20M` or a larger model that still runs
real-time on the board).

| Topic | Guidance |
|-------|----------|
| Why Zipformer over Whisper | Whisper is **batch** — waits for silence before decode. Zipformer is a **streaming** transducer; tokens appear while the user is still speaking. |
| Accuracy boost | Load a custom **bias / hotwords lexicon** (car commands, landmarks, menu items) into Sherpa so domain accuracy can beat generic cloud models for vehicle control. |
| Repo mapping | New `SherpaStreamingEarSttEngine`. Current `SherpaEarSttEngine` (Whisper offline) is an **interim / fallback**, not the demo target. |

### 5.4 Ultra-aggressive endpointing via local VAD

The largest voice-assistant latency source is waiting to confirm the user finished
speaking. Traditional stacks wait **1000–1500ms** of silence.

| Knob | Demo value |
|------|------------|
| Engine | Silero VAD (ONNX), parallel to STT on the same PCM stream |
| Silence threshold | **400ms** |
| Action | On 400ms non-speech → immediate `END_OF_STREAM` / finalize |

This matches existing cabin tunables:

- `AssistantConfig.Audio.TRAILING_SILENCE_MS = 400`
- `AssistantConfig.Audio.VAD_MIN_SILENCE_DURATION_SEC = 0.4f`

### 5.5 Demo handoff sequence (zero latency)

```text
State 1: IDLE / LISTENING
  ├── Mic stream feeds Ring Buffer & KWS (Porcupine / Sherpa KWS)
  └── STT Engine is held in standby (initialized in memory)

State 2: WAKE WORD DETECTED
  ├── KWS flags "MATCH"
  ├── Play soft, non-blocking audio cue via SoundPool
  ├── DUMP Ring Buffer (past 1.5s audio) directly into STT Engine
  └── Instantly switch audio routing to STT + Silero VAD (bypass KWS)

State 3: SPEECH-TO-TEXT STREAMING
  ├── Live PCM chunks feed STT Engine
  ├── UI displays live partial text as words leave the user's mouth
  └── Silero VAD monitors for speech pauses

State 4: END OF SPEECH (400ms Silence Detected)
  ├── Send END_OF_STREAM to STT
  ├── Extract final string → local intent parser / orchestrator
  └── Reset state back to IDLE (no mic restart required)
```

Implementation mapping:

1. `ContinuousAudioPipeline` owns the sole `AudioRecord`
2. `PcmRingBuffer.snapshot()` on wake → `EarSttEngine.onPcm` / accept path
3. Wire `SherpaKwsManager` (or Porcupine) as idle consumer
4. STT is a **subscriber**, not a second mic owner
5. VIS `showSession` without PAUSE/RESTART **mic** dance (UI may still open)

### 5.6 Why this beats the current implementation

| Property | Demo pipeline | Current |
|----------|---------------|---------|
| Mic lockup | One `AudioRecord` — no conflict | Dual owners + release |
| Wake → STT handoff | &lt;1ms (`isAwake` branch) | 80–200ms+ settle + retries |
| First words | Ring flush preserves them | Often clipped during gap |
| Partials | Streaming Zipformer / Google partials | Whisper waits for endpoint |
| E2E speech finalize | ~400ms after last word (VAD) | Similar once ear owns mic; handoff dominates before that |

LLM TTFT remains a separate budget (see DSP scheduling analysis).

### 5.7 Lightweight wake engines for demo

Vosk and Whisper are heavy for **24/7** wake. Demo/prod idle path should use
dedicated keyword spotting:

| Option | Role |
|--------|------|
| **Sherpa-ONNX Zipformer KWS** | Best open-source / same stack as Sherpa STT; open vocabulary via keywords file; **already stubbed** as `SherpaKwsManager` |
| **Picovoice Porcupine** | Lowest CPU; console-trained custom wake words; not in tree today |

Primary open-source path for this repo: **Sherpa KWS**. Porcupine remains an
optional extreme-efficiency swap.

---

## 6. Production path (contrast with demo)

| Concern | Demo (`VOICE_PROFILE=demo`) | Prod (`VOICE_PROFILE=prod`) |
|---------|----------------------------|-----------------------------|
| Mic | Never sleeps from boot | Continuous **or** DSP idle (no app mic) |
| Wake model | Largest convenient KWS / Porcupine | Tiny KWS; prefer OEM DSP |
| STT | Largest real-time Zipformer or Google | Prod-size streaming model or Google handoff profile |
| Endpoint | Fixed **400ms** | Adaptive **400–600ms+** (`EndpointingProfile`) |
| Battery / thermal | Ignored | Duty-cycle, mute, honor `WAKE_WORD_ENABLED` |
| Process | Single PCM+STT process OK | Avoid LMK: don’t pin huge LLM + huge ASR blindly; delay LLM init |
| Wake prefs | Often forced on for demos | Must honor enable/disable on **live** UiUx VIS |

Prod selection order:

1. Probe DSP / `AlwaysOnHotwordDetector`
2. Else continuous pipeline + Sherpa KWS (or Porcupine)
3. STT: streaming Sherpa on non-GAS; Google only via explicit `gas_handoff` when quality requires it
4. Keep Silero for in-process STT; platform VAD when Google owns the mic

---

## 7. GAS vs non-GAS decision tree

**Hard constraint:** Continuous exclusive `AudioRecord` and Google
`SpeechRecognizer` **cannot both hold the mic**.

```text
Boot / profile select
        │
        ├─ dsp_preferred && DSP hotword available?
        │     YES → idle: no app mic
        │           on hotword → open utterance
        │             ├─ GAS → Google offline STT (platform mic/VAD)
        │             └─ non-GAS → Sherpa streaming + Silero (+ optional short OEM pre-roll)
        │
        ├─ VOICE_PROFILE=demo && prefer_zero_clip?
        │     YES → continuous + ring + KWS + Sherpa streaming (even on Pixel)  [A2]
        │
        ├─ GAS && (gas_handoff || !prefer_zero_clip)?
        │     YES → idle: continuous KWS OR DSP
        │           on wake: RELEASE app mic → Google SpeechRecognizer        [A1]
        │           (ring cannot feed Google; accept settle / clip risk)
        │
        └─ non-GAS / default continuous
              → continuous + ring + Sherpa KWS
              → Sherpa streaming Zipformer + Silero 400ms
              → Whisper offline only if streaming models missing
```

Summary:

| Mode | Cabin STT | Ring flush | Zero-clip |
|------|-----------|------------|-----------|
| Continuous + Sherpa | In-process | Yes | Yes |
| Handoff + Google | Platform | No | No (unless OEM pre-roll) |
| DSP + Google | Platform after trigger | OEM-dependent | Best GAS prod compromise |
| DSP + Sherpa | In-process after trigger | Optional | Strong non-GAS / privacy |

---

## 8. DSP vs without DSP

| | With DSP / Trusted Hotword | Without DSP |
|--|---------------------------|-------------|
| Idle CPU / battery | Best (hardware KWS) | App KWS on 20ms PCM — use **tiny** KWS, not Vosk/Whisper 24/7 |
| Mic conflict while idle | Avoided | Solved only by **single** continuous `AudioRecord` |
| First-word clip | Depends on OEM buffer | Ring flush **mandatory** |
| AAOS alignment | Matches Google **SHOULD** in [ideal-arch.md](ideal-arch.md) | Valid fallback; claim `CAPTURE_AUDIO_HOTWORD` only if DSP API is used |
| SA8255 note | Platform STT on Hexagon can delay LLM ~**800ms** after EOS ([DSP_SCHEDULING_ANALYSIS.md](../performance/DSP_SCHEDULING_ANALYSIS.md)). If LLM must start immediately, prefer CPU/GPU Sherpa STT or overlap carefully. | Same if Google/DSP STT is chosen for the utterance |

Ideal prod: **DSP wake when OEM supports it**; continuous + Sherpa/Porcupine KWS as
the portable fallback (Pixel tablet, Tangorpro, generic AOSP).

---

## 9. Tradeoffs and issues

| Topic | Tradeoff / issue |
|-------|------------------|
| Continuous mic vs battery | Demo wins latency; prod needs DSP or duty-cycle / mute |
| Single process vs `:wakeword` isolation | Continuous PCM requires shared process with STT; LMK risk beside LLM |
| Google STT vs zero-clip | Mutually exclusive without OEM/DSP pre-roll |
| Whisper vs Zipformer | Whisper simpler/batch; Zipformer required for streaming partials (demo target) |
| Vosk vs tiny KWS | Vosk heavier always-on; keep only as last-resort fallback |
| Porcupine vs Sherpa KWS | Porcupine lowest CPU + console training; Sherpa open + one vendor stack |
| Aggressive 400ms VAD | Snappy demo; may cut slow speakers — prod profile 500–700ms |
| Hexagon DSP STT + NPU LLM | Sequential ~800ms teardown on SA8255 |
| Real `RecognitionService` | Google **MUST** for other apps; cabin path should eventually share the same `SttPort` (stub today always `ERROR_CLIENT`) |
| TTS bleed into KWS | Keep post-TTS / post-match **cooldown on KWS arming**, not by releasing the mic |
| Foreground service | Continuous record requires `microphone` FGS + user-visible notification |

---

## 10. Recommended north star

1. **`VoiceAudioCoordinator` + ports + profiles** — one design, many devices  
2. **Invariant:** one mic owner  
3. **Continuous pipeline + 1.5s ring** whenever the app owns idle mic  
4. **Demo profile = §5 Max-Performance pipeline** (default for client demos)  
5. **Prod profile = DSP-first**, continuous + tiny KWS fallback  
6. **Pluggable STT** with an explicit Google handoff mode vs Sherpa stream mode  
7. **Silero VAD** for all in-process STT; platform VAD for Google  
8. Keep **`IAudioManager`** as the session facade  
9. Align with AAOS: DSP hotword when available; real `RecognitionService` later; honor wake mute prefs on live VIS  

### 10.1 Phased implementation pointer

| Phase | Deliverable |
|-------|-------------|
| **1 — Zero handoff** | `ContinuousAudioPipeline` + `PcmRingBuffer`; wire Sherpa KWS; STT as subscriber (Whisper/Google interim OK); remove PAUSE/RESTART mic dance and `MIC_OPEN_DELAY` on critical path; collapse `:wakeword` process |
| **2 — Streaming STT** | `SherpaStreamingEarSttEngine` (Online Zipformer); cabin bias lexicon; live partials; Whisper fallback if models missing |
| **3 — Prod hardening** | `AlwaysOnHotwordDetector` when OEM supports; `gas_handoff` / `dsp_preferred` profiles; retire Vosk wake loop; honor `WAKE_WORD_ENABLED`; docs/`plan.md` Tier 2 marked done |

Build order sketch: ring buffer (JVM-testable) → continuous read loop → KWS + flush → kill Vosk mic ownership → Zipformer → DSP/Google profiles.

---

## 11. Config knobs and references

### 11.1 Proposed / existing knobs

| Knob | Role |
|------|------|
| `VOICE_PROFILE` | `demo` \| `prod` \| `gas_handoff` \| `dsp_preferred` |
| `RING_BUFFER_MS` | `1500` (demo/prod continuous) |
| Frame size | 20ms @ 16 kHz (320 samples) |
| `TRAILING_SILENCE_MS` | `400` demo; prod may raise |
| `VAD_MIN_SILENCE_DURATION_SEC` | `0.4f` (aligned) |
| `STT_ENGINE` | `sherpa` \| `google` \| (future) `sherpa_streaming` |
| Wake phrase prefs | `wake_word` / enable flags — must apply on live VIS |
| KWS threshold / keywords file | Sherpa keywords or Porcupine sensitivity |

### 11.2 Code anchors (current)

- `app/src/main/java/com/tcs/vehicleassistant/WakeWordService.kt`
- `app/src/main/java/com/tcs/vehicleassistant/assistant/session/ComposeAssistantSession.kt`
- `app/src/main/java/com/tcs/vehicleassistant/assistant/VehicleAgentAssistantBackend.kt`
- `data/hardware/.../AndroidAudioManager.kt`
- `data/hardware/.../ear/AssistantEar.kt`, `EarMic.kt`, `SherpaEarSttEngine.kt`, `GoogleOfflineEarSttEngine.kt`
- `data/hardware/.../SherpaKwsManager.kt`
- `core/.../AssistantConfig.kt` (`Audio`, `WakeWord`, prefs)

### 11.3 Model sideload (illustrative)

| Asset | Typical path |
|-------|----------------|
| Vosk wake (legacy) | `/data/local/tmp/vosk/` |
| Sherpa KWS | `/data/local/tmp/kws/` |
| Whisper STT (current) | `/data/local/tmp/stt/` |
| Streaming Zipformer (target) | `/data/local/tmp/stt-streaming/` (or documented equivalent) |
| Silero VAD | `app` assets `silero_vad.onnx` |

---

## 12. Shared state machine (all modes)

Conceptual states used by demo continuous mode and adapted for DSP/Google:

| State | Continuous + KWS | DSP wake | Google handoff |
|-------|------------------|----------|----------------|
| IDLE | Mic → ring + KWS; STT warm, not fed | No app mic; DSP listening | Same as continuous **or** DSP |
| WAKE | `isAwake=true`; cue; ring → STT | Platform callback; open utterance | Release app mic (if held); start `SpeechRecognizer` |
| LISTENING | PCM → STT + Silero; KWS bypassed | PCM/platform ASR | Platform partials / results |
| END | EOS @ 400ms+; parse; `isAwake=false` | Finalize; release utterance mic if any | `onResults`; re-arm wake |

Never restart the continuous mic between END and IDLE — only re-route consumers.
