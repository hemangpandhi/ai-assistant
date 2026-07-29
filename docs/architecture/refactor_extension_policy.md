# Extend-first policy (on top of `dev/refactor`)

**Goal:** Keep `dev/ui_ux_v2` (and agent branches) rebase-friendly against `origin/dev/refactor`. Prefer **new files + thin wiring** over editing shared refactor implementations.

## Rule of thumb

| Prefer | Avoid |
|--------|--------|
| New class / object in `assistant/`, `domain/`, `data/`, `hardware/`, `di/` | Large in-place edits to refactor-owned files |
| Interface that **extends** a refactor port (`SessionAudioPort : IAudioManager`) | Widening a shared interface with UI/UX-only methods |
| Compose / UI in `:assistant-ui` | Patching XML session logic into the same class forever |
| Koin `uiUxModule` additive bindings | Expanding `appModule` with all UI/UX singles |
| Delegate from a thin wrapper in the shared file | Copy-paste forking the whole shared class |

## Shared files that should stay thin

These exist on `dev/refactor` and change often upstream. Treat them as **integration shells**:

- `AssistantSession.kt` → prefer parallel `ComposeAssistantSession` + factory hook
- `AndroidAudioManager.kt` / `IAudioManager.kt` → keep baseline on `IAudioManager`; put session extras on `SessionAudioPort`
- `AgentOrchestrator.kt` → prefer `OrchestratorExtension` / domain helpers (`QueryPipeline`, `ToolLoop`, `SpeechPresenter`)
- `WakeWordService.kt` → prefer `CrossProcessMicLease`, `MicCaptureCoordinator`, duty-cycle helpers
- `AssistantViewModel.kt` → prefer backend/controller in `assistant/`
- `FollowUpRouter.kt` / `ToolCallParser.kt` → prefer `DirectCabinCommandRouter` / `StreamingToolCallParser`
- `AppModule.kt` → keep refactor-ish core; load `uiUxModule` from `VehicleApplication`

## Unavoidable thin edits

These still need a one-line (or small) touch in a shared file — keep them minimal and commented `// ui_ux extension seam`:

1. `VehicleApplication` — `modules(appModule, uiUxModule)`
2. `AssistantSessionService.onNewSession()` — choose Compose vs XML session (when parallel session lands)
3. Manifest / `build.gradle.kts` — new activities, services, `:assistant-ui`, AAR assets
4. Optional no-op extension constructor arg on `AgentOrchestrator` (future)

## How to take remote `dev/refactor` changes

1. Fetch `origin/dev/refactor`.
2. Prefer **merge/rebase** when shared files only differ at marked seams.
3. For conflict magnets, **take refactor’s version of the shared file**, then re-apply only the seam call sites.
4. Never re-port large UI/UX bodies back into shared files — put them in extension types instead.

## Already additive (good patterns — keep using)

- `:assistant-ui` module and `AssistantBackend` / `AssistantHost`
- `VehicleAgentAssistantBackend`, `AssistantUiProfile`, idle timeout, runtime bootstrap
- `domain/*` (`QueryPipeline`, `ToolLoop`, `SpeechPresenter`, use cases)
- `data/*` ports (`LlmEngine`, `VhalGateway`, `ConversationMemory`)
- `MicCaptureCoordinator`, `EndpointingProfile`, `AgentRuntime`, feature flags
