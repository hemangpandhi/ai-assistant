# Decoupling + TTFR Roadmap

Status of the VehicleEdgeAssistant migration toward ports-and-adapters + presentation MVI.

## Phase A — TTFR (done)

- Mic handoff event-driven (wake release await + ≤250ms settle); re-arm ~200ms
- Adaptive STT silence profiles (`EndpointingProfile`: ShortCommand 500/400, Default 800/600)
- Queue queries while prewarming instead of toast-reject
- Eager mid-stream tool exec on complete `</TOOL>` via [`ToolLoop`](../../app/src/main/java/com/tcs/vehicleassistant/domain/ToolLoop.kt)
- Keyword-only tool injection; semantic miss → empty (no registry dump)
- Expanded [`FollowUpRouter`](../../app/src/main/java/com/tcs/vehicleassistant/utils/FollowUpRouter.kt) HVAC/media shortcuts (uses `ConversationMemory`)

## Phase B — Ports + pipeline split (done / evolving)

### Agent ports

| Port | Impl |
|------|------|
| `LlmEngine` | `LiteRtLlmEngine` (+ existing `ILLMProvider` edge/cloud) |
| `VhalGateway` | `VehicleManagerGateway` |
| `ConversationMemory` | `MemoryManagerStore` |
| `AssistantFeatureFlags` | SharedPreferences (+ legacy companion sync during migration) |

### Pipeline split

| Component | Role |
|-----------|------|
| `QueryPipeline` | Prompt budget / history / tools / telemetry |
| `ToolLoop` + `ExecuteToolUseCase` | Eager schedule + confirmation gate |
| `SpeechPresenter` | Sentence-boundary streaming TTS |
| `AgentOrchestrator` | Coordinates stream + state; no longer owns TTS/tool policy inline |

Koin singles: audio, orchestrator, `AssistantViewModel`, pipeline helpers.  
`VehicleAgentService` unloads via `LlmEngine`. In-app bridge reuses shared ports with Activity TTS audio.

### Remaining migration debt

- `LLMManager` / `VehicleManager` / `MemoryManager` objects still exist as adapters under the ports
- `LocalLLMActivity` still a large settings/debug host (not fully thinned)
- Legacy companions synced from `AssistantFeatureFlags` until Settings UI is ported

## Phase C — Modern polish (done / ongoing)

- `AssistantViewModel` extends `androidx.lifecycle.ViewModel` + `viewModelScope`
- Domain UseCases wired through Koin
- Semantic RAG claim demoted to keyword-only in logs/docs/README
- Unit tests: `ToolCallParser`, `FollowUpRouter`, eager stream contract (`EagerToolStreamTest`)
- Optional later: Gradle `:assistant-api` split, AppFunctions, delete legacy singletons

## Non-goals

- Hilt migration, Firebase/AICore primary path, multi-process split, replacing LiteRT

## Extend-first (rebase hygiene)

UI/UX / TTFR work should **extend** `dev/refactor` rather than rewrite shared files.
See [refactor_extension_policy.md](refactor_extension_policy.md).

Additive seams already in place:

| Extension | Shared shell it keeps thin |
|-----------|----------------------------|
| `SessionAudioPort` | `IAudioManager` |
| `DirectCabinCommandRouter` | `FollowUpRouter` |
| `StreamingToolCallParser` | `ToolCallParser` |
| `CrossProcessMicLease` | `WakeWordService` companion |
| `uiUxModule` | `appModule` |
| `domain/*`, `data/*`, `assistant/*`, `:assistant-ui` | orchestrator / session / VIS |
