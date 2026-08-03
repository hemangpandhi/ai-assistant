# Ideal Architecture — Vehicle Edge Assistant

Target shape for this AAOS privileged voice assistant: a **modular monolith** —
**one APK**, a small set of Gradle modules, strong ports, and a clear turn
pipeline. Not microservices, not multi-APK delivery.

Related: [decoupling_roadmap.md](decoupling_roadmap.md),
[refactor_extension_policy.md](refactor_extension_policy.md),
[docs/ARCHITECTURE.md](../ARCHITECTURE.md).

---

## Verdict

| Goal | Guidance |
|------|----------|
| Single APK | Always — priv-app / VIS / VHAL ship together |
| Multi-module Gradle | Yes — finish ownership, don’t over-split |
| Independently deployable services | No |
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

## Non-goals

- Microservice / multi-process product split (beyond existing wake process)
- Multi-APK or Play Feature Delivery for cabin control
- Hilt migration, Firebase / AICore as primary path
- Rewriting working agent code solely to move folders
- One Gradle module per handler or audio stage

---

## Bottom line

Ideal is not maximal modularity. It is:

- a **small module set** with **one-way dependencies**
- a **Capture → Understand → Act → Speak** pipeline that **UI only observes**
- **hardware and LLM behind ports**
- all shipping as **one privileged APK**
