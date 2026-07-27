# Decoupling + TTFR Roadmap

Status of the VehicleEdgeAssistant migration toward ports-and-adapters + presentation MVI.

## Phase A — TTFR (done)

- Mic handoff `1400ms → ~250ms`; re-arm ~200ms ([`VehicleAgentAssistantBackend`](../../app/src/main/java/com/tcs/vehicleassistant/assistant/VehicleAgentAssistantBackend.kt))
- STT silence `1500/1000 → 800/600` ([`AndroidAudioManager`](../../app/src/main/java/com/tcs/vehicleassistant/hardware/AndroidAudioManager.kt))
- Queue queries while `EngineStatus.Prewarming` instead of toast-reject
- Eager mid-stream tool exec on complete `</TOOL>`
- Keyword-only tool injection; semantic miss → empty (no registry dump)
- Expanded [`FollowUpRouter`](../../app/src/main/java/com/tcs/vehicleassistant/utils/FollowUpRouter.kt) HVAC/media shortcuts

## Phase B — Ports + UI contract (done)

### Agent ports

| Port | Impl |
|------|------|
| `LlmEngine` | `LiteRtLlmEngine` (+ existing `ILLMProvider` edge/cloud) |
| `VhalGateway` | `VehicleManagerGateway` |
| `ConversationMemory` | `MemoryManagerStore` |
| `AssistantFeatureFlags` | SharedPreferences + sync with legacy Activity companions |

Koin singles: audio, orchestrator, `AssistantViewModel`. Service no longer `new`s them.

### Compose UI

- `AssistantStageStore` MVI (`reduceStage`)
- `AssistantMicController` on backend — Session uses `asMicController()` (no concrete cast)
- `ImmersiveStageBus` SharedFlow for summon/dismiss (legacy handler lists retained as bridge)

## Phase C — Modern polish (done / ongoing)

- `AssistantViewModel` extends `androidx.lifecycle.ViewModel` + `viewModelScope`
- Domain UseCases: `ProcessQueryUseCase`, `FollowUpUseCase`, `ExecuteToolUseCase`, `SpeechPresenter`
- Semantic RAG claim demoted to keyword-only in logs/docs
- Optional later: Gradle `:assistant-api` split, AppFunctions, remove legacy singletons entirely

## Port sketches

```kotlin
interface LlmEngine {
  val status: StateFlow<EngineStatus>
  suspend fun ensureReady(context: Context, force: Boolean = false)
  fun generateStream(request: LlmRequest): Flow<TokenChunk>
  suspend fun unload()
}
```

```kotlin
sealed interface StageIntent { /* Summon, Dismiss, BackendEvent, Thumbs */ }
data class StageState(...)
sealed interface StageEffect { /* RequestListen, ClusterHandOff, FinishSession, StopSession */ }
```

## Non-goals

- Hilt migration, Firebase/AICore primary path, multi-process split, replacing LiteRT
