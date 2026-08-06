# Ideal Architecture — Vehicle Edge Assistant

Target shape for this AAOS privileged voice assistant: a **modular monolith** —
**one APK**, a small set of Gradle modules, strong ports, and a clear turn
pipeline. Not microservices, not multi-APK delivery.

Related: [decoupling_roadmap.md](decoupling_roadmap.md),
[refactor_extension_policy.md](refactor_extension_policy.md),
[wakeword-arch.md](wakeword-arch.md) (wake / mic / STT spine — demo vs prod, GAS, DSP),
[docs/ARCHITECTURE.md](../ARCHITECTURE.md).

External Google / AOSP references:

- [AAOS Voice Interaction Guide](https://source.android.com/docs/automotive/voice/voice_interaction_guide)
- [VIA app development](https://source.android.com/docs/automotive/voice/voice_interaction_guide/app_development)
- [Preloaded Assistants UX guidelines (PDF)](https://source.android.com/static/docs/automotive/voice/voice_interaction_guide/preloaded-assistants_UX-guidelines.pdf)
- [Conversation Design](https://developers.google.com/assistant/conversation-design/learn-about-conversation)

---

## Verdict

| Goal | Guidance |
|------|----------|
| Single APK | Always — priv-app / VIS / VHAL ship together |
| Multi-module Gradle | Yes — finish ownership, don’t over-split |
| Independently deployable services | No |
| **Harden / improve one module without breaking others** | **Yes — primary reason for the split** (contracts + tests, not separate APKs) |
| Practical ceiling | ~8–10 modules; **sweet spot 6–8** |
| Stop when | Modules own real code + one-way deps; further cuts are folder theater |

---

## Target module topology

```text
:app                    // shell only: Application, Manifest, Koin, VIS/session glue
:feature:assistant-ui   // Compose face, overlay, MVI presentation
:feature:voice          // wake handoff, STT/TTS session policies (no VHAL)
:feature:agent          // orchestrator, ViewModel, turn state machine
:domain:tools           // registry, schemas, confirmation/safety policy (pure-ish)
:domain:llm             // prompt/session ports + provider interfaces
:data:hardware          // VHAL, audio record/play, location, camera adapters
:data:llm               // LiteRT / cloud HTTP implementations
:core                   // shared models, config keys, pure helpers
(:api optional)         // thin contracts if UI must not see agent types
```

That’s ~8 modules (9 with `:api`). More than ~10 usually costs more than it saves.

### Current tree → target

| Ideal | Today (`dev/clean`) |
|-------|---------------------|
| `:app` thin | `:app` still fat (LLM impl, handlers, vision, orchestrator) |
| `:domain:llm` + `:data:llm` | Interface in `:domain:llm`; impl mostly in `:app` |
| `:feature:agent` | Orchestrator / VM live in `:app` |
| UI behind events/ports | Mostly good (`:ui` + backend bridge) |
| Vision optional feature | Still in `:app` |

Existing modules to **fill first** before adding new ones:

| Module | Should own |
|--------|------------|
| `:app` | Manifest, Application, VIS/session glue, Koin graph |
| `:ui` | Compose face / overlay / MVI |
| `:core` | Policies, memory, routers, shared types |
| `:domain:tools` | Registry, schemas, tool policy |
| `:domain:llm` | Provider / engine ports (+ prompt helpers if pure) |
| `:data:hardware` | VHAL, audio, camera, location |

Optional later (+1 / +2 only if boundaries hurt):

| Module | Only if… |
|--------|----------|
| `:data:llm` | LiteRT / cloud Android deps pollute domain |
| `:feature:vision` | Cockpit vision grows and rarely touches cabin tools |
| `:assistant-api` / `:api` | `:ui` still reaches into agent types |

### Do not split further

- One module per tool handler (HVAC / windows / media / …)
- Separate `:wake`, `:stt`, `:tts`, `:orchestrator` modules
- Separate MVI / ViewModel / session modules

Those stay as **packages** inside `:app`, `:feature:agent`, or `:data:hardware`.
They share mic lease, audio focus, confirmation, and latency path.

---

## Dependency rule (non-negotiable)

```text
UI / features  →  domain ports  →  data adapters
     \               ↑
      \________ :app wires everything (DI only)
```

- `:ui` / features never import VHAL, LiteRT, or tool handlers
- `:domain:*` never imports Android Car APIs or Compose
- `:data:*` implements interfaces; no business branching
- `:app` is the only place that knows the full graph

Enforce with Gradle project deps (and optionally dependency-analysis / custom checks).

---

## Independent hardening (blast-radius isolation)

The multi-module split exists so each layer can be **hardened and improved on its
own**. Fixing STT, LiteRT, tools, or the face UI must not require rewriting (or
unknowingly regressing) the others.

This is **not** separate deployability. It is **contract stability + test
ownership** inside one APK.

### Design rules

| Rule | Why |
|------|-----|
| Depend on **ports**, not concrete classes across modules | Swap / harden LiteRT or Sherpa without touching orchestrator call sites |
| Keep port APIs **small and versioned carefully** | A noisy interface change ripples every consumer |
| No “reach-through” imports (UI → `VehicleManager`, agent → Compose types) | Hidden coupling makes one fix break another layer |
| Put policy in `:domain` / `:core`; put I/O in `:data` | Safety / confirmation changes don’t require audio or VHAL edits |
| `:app` only **wires** implementations | Composition root absorbs churn; features don’t |
| Prefer additive port methods over breaking signature changes | Callers keep compiling while one impl hardens |

### What “fix one module” should look like

| Change | Allowed without touching others | Must stay stable |
|--------|----------------------------------|------------------|
| Sherpa / VAD tuning, mic focus | `:data:hardware` (+ its unit tests) | `AudioPort` listen/speak/focus API |
| LiteRT load, GPU fallback, prompt trim | `:data:llm` / LLM collaborators | `ILLMProvider` stream/cancel/ready |
| Tool schema, BM25, confirmation copy | `:domain:tools` | `IToolExecutor` / `ToolCatalog` results shape |
| HVAC / VHAL write retries, area maps | `:data:hardware` + handler package | Executor result / error contract |
| Face mood, overlay motion, MVI reduce | `:ui` | `AssistantBackend` / session event contracts |
| Orchestrator turn policy, barge-in | `:feature:agent` / `:app` agent package | Ports it consumes; events it emits |
| Skills JSON few-shots / policies | assets + registry loader tests | Tool ids + arg schemas consumers expect |

If a “local” fix forces edits in three unrelated modules, the boundary is wrong —
extract or narrow the port before continuing.

### Test ownership (each module proves itself)

| Module / layer | Owns tests for | Contract tests against |
|----------------|----------------|------------------------|
| `:data:hardware` | Audio, VHAL adapters, snapshot mapping | Fake `AudioPort` / gateway used by agent tests |
| `:data:llm` / LLM impl | Engine host, gate, locator | Fake `ILLMProvider` in orchestrator tests |
| `:domain:tools` | Registry, schema, allow-lists, confirmation policy | Frozen tool-id fixtures |
| `:feature:agent` | Turn pipeline, direct-tool path, confirm flow | Fakes for LLM, audio, tools, VHAL |
| `:ui` | MVI reduce, face/cue mapping | Fake `AssistantBackend` event streams |
| `:app` | Wiring / smoke; few integration tests | End-to-end golden paths only |

**Rule:** a module’s unit tests must pass with **fakes** for every outbound port.
No test in `:ui` may need LiteRT or Car APIs. No test in `:domain:tools` may need
Compose.

### Compatibility checklist before merging a layer fix

1. Public port / event types used by other modules did not change — or changes are additive and documented.
2. Owning module’s unit tests are green.
3. Downstream contract tests (fakes exercising the old API) still pass.
4. No new reverse dependency (`:domain` → `:ui`, `:core` → `:data`, etc.).
5. Skills / tool ids remain stable unless a coordinated registry + handler change.

### Anti-patterns that couple layers

- Shared mutable singletons across modules without a port
- Orchestrator calling Concrete `LLMManager` / `VehicleManager` methods beyond the port
- UI parsing raw LLM token streams or tool JSON
- Handlers importing Compose or face cue types
- “Just this once” Gradle `api(project(:…))` that leaks internals

---

## Runtime layers

Logical layers even when packages share a module:

| Layer | Owns | Must not own |
|-------|------|--------------|
| **Presence UI** | Face, transcript, mood, overlay | Tool execution, model load |
| **Capture** | Wake → mic lease → STT endpoint | LLM prompts, VHAL writes |
| **Understand** | Direct tools, follow-ups, LLM stream parse | Compose, Car APIs |
| **Act** | Confirmation + tool handlers + VHAL | UI animation |
| **Speak** | TTS + barge-in policy | Registry / model discovery |
| **Context** | Memory, cabin snapshot, safety policy | Screen layout |

Critical path:

```text
Capture → Understand → Act → Speak
         (UI observes events only)
```

Canonical spine:

```text
WakeWordService (Vosk, :wakeword process)
  → VIS showSession → ComposeAssistantSession
  → VehicleAgentService → AssistantViewModel + AudioPort (STT)
  → AgentOrchestrator
       ├ DirectToolResolver / FollowUpRouter / ContextGuard
       ├ Edge LLM (LiteRT) or Cloud LLM
       └ ToolExecutor → handlers → VhalGateway
  → AssistantBackend → StageStore → Compose face + TTS
```

---

## Ports that matter

Keep these thin; implement behind `:data:*` / `:app` adapters:

| Port | Responsibility |
|------|----------------|
| `LlmEngine` / `ILLMProvider` | Stream / cancel / readiness |
| `ToolCatalog` + `IToolExecutor` | Metadata + execute |
| `VhalGateway` / cabin snapshot | Vehicle read/write + fact snapshot |
| `AudioPort` | Listen / speak / focus |
| `ConversationMemory` | Turn history + durable prefs memory |
| `AssistantBackend` | Agent → UI session events |

Everything else can be packages inside the owning module.

---

## All moving parts (complete app)

Inventory by runtime concern. One APK; modules are ownership boundaries.

### 1. Process entry / Application / DI

| Part | Role |
|------|------|
| `UiUxVehicleApplication` | Process entry; Koin + Compose runtime bootstrap |
| `VehicleApplication` | Legacy Application (parity / prewarm) |
| `appModule` (Koin) | Wires tools, LLM providers, audio, orchestrator |
| `AssistantRuntimeBootstrap` | Installs Compose host / backend |
| Priv-app permissions XML | AAOS privileged grants |

### 2. VoiceInteractionService / sessions

| Part | Role |
|------|------|
| `UiUxAssistantVoiceInteractionService` | System-bound VIS |
| `UiUxAssistantSessionService` | Session factory (Compose vs XML) |
| `ComposeAssistantSession` | Live UiUx session |
| `AssistantSession` | Legacy XML voice-plate |
| Legacy VIS / session services | Unbound fallback / parity |
| `StubRecognitionService` | Platform stub; cabin STT is Sherpa |

### 3. Wake word (ear open)

| Part | Role |
|------|------|
| `WakeWordService` | Vosk KWS in `:wakeword` process; mic until handoff |
| `WakeWordPhrasePolicy` | Phrase / grammar matching |
| `SherpaKwsManager` | Optional alt KWS (not primary VIS path) |

### 4. STT / VAD / audio focus

| Part | Role |
|------|------|
| `IAudioManager` / `AudioPort` | Listen / speak lifecycle |
| `AndroidAudioManager` | Sherpa-ONNX STT + Silero VAD (+ optional Google STT) |
| `AssistantSessionAudioFocus` | Session ducking / focus |
| `SttErrorPolicy` / `SessionTurnPolicy` | Error + turn timing |

### 5. TTS / speech out

| Part | Role |
|------|------|
| `AndroidAudioManager` TTS | Primary cabin TTS (Sherpa / Piper) |
| `TtsVoiceCatalog` | Voice ids / prefs |
| Orchestrator streaming speak | Sentence-boundary speak during LLM stream |
| Compose TTS helpers | Demo / host path |

### 6. Orchestrator / ViewModel / MVI

| Part | Role |
|------|------|
| `VehicleAgentService` | Foreground agent host |
| `AssistantViewModel` | STT → intents → orchestrator |
| `AgentOrchestrator` | Agentic loop: direct tools, LLM, confirm, TTS |
| `InAppOrchestratorBridge` | Settings Activity reuse |
| `AssistantStageStore` | Presentation MVI for face / overlay |
| `VehicleAgentAssistantBackend` | Agent VM → Compose events |

### 7. LLM (edge + cloud)

| Part | Role |
|------|------|
| `ILLMProvider` | Provider interface |
| `EdgeLLMProvider` + `LLMManager` facade | On-device LiteRT path |
| LiteRT collaborators | Engine host, session, prompt, gate, locator, benchmark, status |
| `CloudLLMProvider` + Gemini / Anthropic managers | Cloud fallback |
| `LlmSessionPort` | Host-neutral readiness (UI/api) |

### 8. Tools / handlers / confirmation

| Part | Role |
|------|------|
| `vehicle_skills_registry.json` | Tools + context policies + few-shots |
| `ToolRegistry` / schema / `ToolDefinition` | Registry, retrieval, prompt schemas |
| `AppToolExecutor` + `handlers/*` | Execute + domain handlers |
| `ToolCallParser` | Parse `<TOOL>` / JSON from stream |
| `DirectToolResolver` / `FollowUpRouter` | Skip LLM on strong matches |
| `ConfirmationPolicy` / `ContextGuard` | Affirm/decline + cabin policy |
| Safety allow-lists / turn policy | Fail-closed tool gating |
| `ToolCatalog` | Host-neutral tool port |

### 9. VHAL / cabin / hardware

| Part | Role |
|------|------|
| `VehicleManager` | CarPropertyManager read/write |
| `CabinSnapshot` / `CabinSnapshotReader` | Fact snapshot for policies |
| `VhalAreaResolver` / `VehicleUnits` | Areas + units |
| Volume / media / location bridges | Cabin actuators & context |
| `AAOSUserSwitchManager` | Multi-user (vision welcome) |

### 10. Vision / cockpit (optional path)

| Part | Role |
|------|------|
| `CockpitVisionService` | Camera FGS; gestures / health / identity |
| Face / gesture / health processors | Signals |
| `ProactiveArbiter` + use cases | Priority-gated prompts into orchestrator |
| `CockpitAwarenessActivity` | Vision demo UI |
| `UserUnlockReceiver` | Unlock / greet path |

Vision should **publish intents into the agent**, never block summon.

### 11. Compose UI / face / overlay

| Part | Role |
|------|------|
| `AssistantRuntime` / `AssistantHost` / `AssistantBackend` | Process-wide UI contracts |
| Immersive overlay + face variants | Visual presence |
| Mood / face-cue catalogs & receivers | Runtime / ADB face control |
| Gallery / overlay bootstrap activities | Design & launch surfaces |
| Demo backend | Scripted UI without agent |

### 12. Memory / context / policies

| Part | Role |
|------|------|
| `MemoryManager` | Sliding + durable conversation memory |
| `SmartContextInjector` | Keyword → registry context |
| `SystemPromptBuilder` | Prompt assembly |
| `ContextGuard` + registry policies | ALLOW / CONFIRM / BLOCK / ESCALATE |
| `AssistantConfig` | Shared prefs / tunables (incl. cross-process) |
| Cabin context store | Compose host cabin facts |

### 13. Settings / debug / demo

| Part | Role |
|------|------|
| `LocalLLMActivity` | Launcher settings / debug / chat |
| Demo presets / demo backend | Demo defaults |
| Debug strip / broadcasts / scorers | Dev telemetry |

### 14. Models & assets

| Packaged in APK | Sideloaded (`/data/local/tmp/…`) |
|-----------------|----------------------------------|
| Silero VAD, Sherpa TTS (Amy), skills JSON, face tflite, native AARs | Whisper STT, Vosk wake, LiteRT LLM (`.litertlm`), optional TTS/KWS |

See [docs/MODEL_SIDELOAD.md](../MODEL_SIDELOAD.md).

---

## AAOS-specific principles

1. **One process for the agent path** — wake may stay in a second process for mic isolation.
2. **Keyword / direct tools before LLM** on the hot path.
3. **Skills registry as data**, not handlers-only hardcoding.
4. **Sideload heavy models**; keep the APK lean.
5. **Feature flags / prefs in `:core`**, not scattered.
6. **Vision / proactive are optional** — inject into the agent; never gate ear-open.
7. **UI observes** session events; it does not own Act / Understand.

---

## Migration order (ideal enough)

Finish in this order, then stop:

1. Fill `:domain:tools` and move LLM collaborators into `:domain:llm` / `:data:llm`.
2. Keep orchestrator + VIS in `:app` (or extract one `:feature:agent` when stable).
3. Leave vision in `:app` until it hurts incremental compiles.
4. Enforce one-way Gradle deps.
5. Optional `:api` only if `:ui` still imports agent types.

### Split rule of thumb

Add a module only when a chunk has:

1. A clear owner  
2. Few inbound dependencies  
3. Its own test surface  
4. Heavy libs that otherwise drag unrelated compiles  

If it fails that bar, keep it as a **package**.

---

## Google / AAOS Voice Interaction alignment

We are a capable **OEM edge agent** (VHAL + on-device LLM + custom KWS), not a
Google-shaped **AAOS Voice Interaction App (VIA)**. Ideal architecture should
close the gaps below when product goal is AAOS VIA compliance — or document
them as conscious OEM exceptions.

### Product posture

| Google preloaded-assistant principle | Ideal posture for this app |
|--------------------------------------|----------------------------|
| Integrate as system VIA (PTT / TTT / default assistant) | Keep one system-bound VIS + session; drop dual unbound VIS confusion |
| Voice-forward; UI augments, does not distract | Prefer voice-plate session; immersive face as optional OEM skin |
| Reduce complexity / familiar patterns | Car UI Library–friendly plate for OEM theming |
| Respect privacy — know when listening; easy mute | Wake toggle honored on live path; in-session mic-off affordance |
| MUST show listening / processing / fulfilling | Keep stage/mood; don’t auto-dismiss errors without recovery |
| MUST utter feedback on understand / complete | Keep TTS + chime; unify assistant audio attributes |
| MUST serve as `RecognitionService` for other apps | Real recognizer implementation (not a stub) |
| SHOULD hotword (DSP / Trusted Hotword when available) | Platform hotword first; app-space KWS as fallback |
| MAY settings / assist data / keyguard | Settings retained; assist-data path optional but clean |

### High drift (close or explicitly accept)

| Google expects | Current practice | Ideal direction |
|----------------|------------------|-----------------|
| **MUST** real `RecognitionService` for `SpeechRecognizer` clients | `StubRecognitionService` always `ERROR_CLIENT`; Sherpa STT is in-process only | Wrap Sherpa (or platform STT) in a real `RecognitionService`; keep cabin path on same engine |
| **SHOULD** hotword via DSP / `AlwaysOnHotwordDetector` | Custom Vosk `AudioRecord` in `:wakeword` FGS; `CAPTURE_AUDIO_HOTWORD` declared but unused | Prefer OEM DSP / Trusted Hotword; Vosk only as fallback; don’t claim hotword permission without the API |
| User always knows when listening + easy mute/revoke | UiUx VIS starts wake word **unconditionally**; `WAKE_WORD_ENABLED` honored only on legacy VIS | Honor prefs on **live** VIS; session UI mic-off; stop ≠ pause-only forever |
| VIS kept light; heavy work in session (ideally separate process) | Agent + LLM + STT in `VehicleAgentService`; dual VIS registered | One bound VIS; session/agent modules own heavy work; legacy VIS unbound or removed |
| Voice-forward minimal UI; don’t cover system chrome | Immersive full-bleed Compose face/overlay | Default voice plate; immersive as opt-in profile |
| Car UI Library for OEM-customizable plate | Custom Compose design system only | Expose tokens / plate that OEMs can theme (Car UI where required) |
| `NotificationListenerService` for tap-to-read | Not implemented | Add if messaging is in product scope |
| Assist data / `onHandleAssist` for system assist | Weak; Activity fallback when VIS unbound | Stay inside VIS session; optional assist-data handler |

### Medium drift (audio / conversation UX)

| Google / AAOS norm | Current practice | Ideal direction |
|--------------------|------------------|-----------------|
| Assistant stream + polite focus | Exclusive focus + reclaim after loss; Compose TTS sometimes `USAGE_MEDIA` | `USAGE_ASSISTANT` end-to-end; transient/may-duck unless OEM requires exclusive |
| Clear error recovery (no dead ends) | Immersive auto-dismisses some STT errors; XML retries | Uniform retry / re-prompt policy across UI profiles |
| Interruptible / barge-in | Mic often disarmed in Thinking/Speaking | Listen-through policy on agent port; cancel in-flight on speech start |
| Concise cooperative replies (Grice maxims) | LLM turns can be long | Prompt/length policy in `:domain:llm`; keep Confirm/Act short |
| Distraction-optimized activities | Rich settings / gallery / cockpit UIs | Gate setup with UX restrictions; keep drive-time path voice-only |

### Aligned today (preserve)

- System-bound `VoiceInteractionService` + `VoiceInteractionSessionService` + session UI
- Visual + aural progress (face / stage, chime, TTS)
- Cabin safety confirm/block (`ContextGuard`, registry policies)
- Package visibility via `<queries>` (no `QUERY_ALL_PACKAGES`)
- Release cleartext denied by default
- Wake in a separate process (isolation idea is right; API choice is the drift)
- Settings surface (`LocalLLMActivity`)

### Alignment backlog (priority if targeting Google-shaped VIA)

1. Real `RecognitionService` over the cabin STT engine — satisfy MUST.
2. Honor `WAKE_WORD_ENABLED` on UiUx VIS; obvious mic-off in session UI.
3. DSP / `AlwaysOnHotwordDetector` when OEM supports it; Vosk as fallback only.
4. Soften exclusive focus reclaim; unify TTS on `USAGE_ASSISTANT`.
5. Single bound VIS; Car UI–friendly voice-plate option for OEM.
6. `NotificationListenerService` / tap-to-read if messaging is in scope.

Module ownership for these fixes should follow blast-radius rules: audio/hotword in
`:data:hardware` or `:feature:voice`, recognizer contract in voice/app shell,
UI plate in `:ui`, prefs/policy in `:core` — without rewriting orchestrator.

---

## Non-goals

- Microservice / multi-process product split (beyond existing wake / Trusted Hotword process)
- Multi-APK or Play Feature Delivery for cabin control
- Hilt migration, Firebase / AICore as primary path
- Rewriting working agent code solely to move folders
- One Gradle module per handler or audio stage
- Full Google Assistant cloud parity (Gemini App / phone Assistant feature set)

---

## Bottom line

Ideal is not maximal modularity. It is:

- a **small module set** with **one-way dependencies**
- a **Capture → Understand → Act → Speak** pipeline that **UI only observes**
- **hardware and LLM behind ports**
- each module **hardenable in isolation** via stable contracts + owned tests
- **AAOS VIA contracts** respected (or consciously excepted): real RecognitionService,
  privacy-controllable hotword, voice-forward plate, assistant audio etiquette
- all shipping as **one privileged APK**
