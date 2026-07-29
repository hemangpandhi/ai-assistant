# Extend-first policy (on top of `dev/refactor`)

**Goal:** Keep `dev/ui_ux_v2` (and agent branches) rebase-friendly against `origin/dev/refactor`. Prefer **new files + thin wiring** over editing shared refactor implementations.

The refactor-owned class tree is every Kotlin or Java file under
`app/src/main/java/com/tcs/vehicleassistant/` that also exists on
`origin/dev/refactor`. Every file in that set must remain byte-identical to
`origin/dev/refactor`, except for the two Kotlin seam files listed below. UI/UX
code must live in parallel, additive types.

## Rule of thumb

| Prefer | Avoid |
|--------|--------|
| New class / object in `assistant/`, `domain/`, `data/`, `hardware/`, `di/` | Large in-place edits to refactor-owned files |
| Interface that **extends** a refactor port (`SessionAudioPort : IAudioManager`) | Widening a shared interface with UI/UX-only methods |
| Compose / UI in `:assistant-ui` | Patching XML session logic into the same class forever |
| Koin `uiUxModule` additive bindings | Expanding `appModule` with all UI/UX singles |
| Delegate from a thin wrapper in the shared file | Copy-paste forking the whole shared class |

## Refactor-owned class tree (keep byte-identical)

This rule covers the entire shared class tree, not only the examples in this
table. Restore shared implementations from `origin/dev/refactor`; do not add
visibility changes or UI/UX APIs to them.

| Refactor file | UI/UX parallel / extension |
|---|---|
| `AssistantSession.kt` | `ComposeAssistantSession` + `assistant/session/*` |
| `AndroidAudioManager.kt` / `IAudioManager.kt` | `SessionAndroidAudioManager` / `SessionAudioPort` |
| `AgentOrchestrator.kt` | `UiUxAgentOrchestrator` + `repository/uiux/*` |
| `AssistantViewModel.kt` | `UiUxAssistantViewModel` |
| `WakeWordService.kt` | `UiUxWakeWordService` + wakeword helpers |
| `FollowUpRouter.kt` / `ToolCallParser.kt` | `DirectCabinCommandRouter` / `StreamingToolCallParser` |
| `HVACToolHandler.kt` / `ToolHandlerRegistry.kt` | `HvacToolAliases` (canonicalize before dispatch) |
| `AppModule.kt` | `uiUxModule` loaded beside it |
| `LLMManager.kt` | `LiteRtLlmEngine` + `EngineStatusStore` |
| `VehicleAgentService.kt` | `UiUxVehicleAgentService` |
| `ViewModelEvent.kt` | `UiUxViewModelEvent` |

Overlay UI host: `assistant/UiUxOverlayActivity` (additive manifest entry).

## Unavoidable thin edits

These still need a one-line (or small) touch in a shared file — keep them minimal and commented `// ui_ux extension seam`:

1. `VehicleApplication.kt` — `modules(appModule, uiUxModule)` and UI/UX runtime bootstrap.
2. `AssistantSessionService.kt` — choose Compose vs XML session.
3. `AndroidManifest.xml` — additive UI/UX activities, services, and receivers only.
4. `app/build.gradle.kts` / `settings.gradle.kts` — `:assistant-ui` and required dependencies.

Only items 1 and 2 are exceptions inside the refactor-owned Kotlin/Java class
tree. Mark each shared-file edit with `// ui_ux extension seam`.

## How to take remote `dev/refactor` changes

1. Fetch `origin/dev/refactor`.
2. Prefer **merge/rebase** when shared files only differ at marked seams.
3. For conflict magnets, **take refactor’s version of the shared file**, then re-apply only the two Kotlin seam call sites.
4. Never re-port large UI/UX bodies back into shared files — put them in extension types instead.

## Already additive (good patterns — keep using)

- `:assistant-ui` module and `AssistantBackend` / `AssistantHost`
- `VehicleAgentAssistantBackend`, `AssistantUiProfile`, idle timeout, runtime bootstrap
- `domain/*` (`QueryPipeline`, `ToolLoop`, `SpeechPresenter`, use cases)
- `data/*` ports (`LlmEngine`, `VhalGateway`, `ConversationMemory`)
- `MicCaptureCoordinator`, `EndpointingProfile`, `AgentRuntime`, feature flags
