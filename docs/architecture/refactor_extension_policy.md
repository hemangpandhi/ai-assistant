# Extend-first policy (on top of `origin/master`)

**Goal:** Keep `dev/ui_ux_v2` (and agent branches) rebase-friendly against
`origin/master`. Prefer **new files + thin wiring** over editing shared master
implementations.

Master-owned Kotlin lives in **`:agent-core`** and must remain byte-identical
to `origin/master` `app/src/main/java`. UI/UX / TTFR extensions live in
**`:assistant-ext`** (plus `:assistant-ui` / `:assistant-api`). Do not add
extension classes into `:agent-core` packages.

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
table. Restore shared implementations from `origin/master`; do not add
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

## Extension entry points

UI/UX startup is selected without editing refactor-owned Kotlin:

1. `UiUxVehicleApplication` loads `appModule` plus `uiUxModule`; the manifest
   selects this additive application class.
2. `UiUxAssistantVoiceInteractionService` is the system-bound VIS; it starts
   `UiUxWakeWordService` (Compose) or `WakeWordService` (XML). Refactor
   `AssistantVoiceInteractionService` stays registered but is not system-bound.
3. `UiUxAssistantSessionService` chooses Compose versus XML sessions;
   `voice_interaction_service.xml` selects this additive session service.
4. `AndroidManifest.xml` contains additive UI/UX activities, services, and receivers.
5. `app/build.gradle.kts` / `settings.gradle.kts` include `:assistant-ui`,
   `:assistant-api`, and required dependencies.

### Plugin seams

- `OrchestratorExtension` (+ `MoodOrchestratorExtension`) — mood / decoration hooks
  for `UiUxAgentOrchestrator` (`beforeQuery`, `onToken`, `onDone`, `stripDecorations`,
  `moodForDirectTool`, `onReset`). Bind additional plugins via Koin `getAll()`.
- `UiUxToolDispatcher` — `ToolCatalog` front-door that applies `HvacToolAliases`
  before find/execute.

Manifest, XML, and Gradle wiring may differ from `origin/dev/refactor`; Kotlin
files owned by that branch may not.

## Host API boundary

`:assistant-api` owns the host-neutral contracts used by additive UI/UX code:

- `LlmSessionPort` exposes model readiness, initialization, labels, and
  conversation metadata.
- `ToolCatalog` exposes tool prompt metadata, confirmation/agentic-loop flags,
  and execution.

`LlmManagerSessionAdapter` and `ToolManagerCatalog` are app-side adapters over
the refactor-owned `LLMManager` and `ToolManager`. UI/UX domain, session, and
orchestrator code depends on the ports, not those refactor implementations.

## How to take remote `master` changes

1. Fetch `origin/master`.
2. Merge or rebase while keeping every master-owned Kotlin file unchanged.
3. For conflicts, take master’s version of the shared Kotlin file and place
   UI/UX behavior in an additive type or adapter.
4. Never re-port UI/UX bodies back into shared files.

## Enforced contract

`MasterOwnedTreeContractTest` reads
`app/src/test/resources/master_owned_kotlin.txt`, generated from
`git ls-tree -r --name-only origin/master -- app/src`, and byte-compares
every listed Kotlin file with `git show origin/master:<path>`. Its seam
exception set is empty. The test is skipped only when Git or the ref is
unavailable.

## Already additive (good patterns — keep using)

- `:assistant-ui` module and `AssistantBackend` / `AssistantHost`
- `:assistant-api` module and app-side LLM/tool adapters
- `VehicleAgentAssistantBackend`, `AssistantUiProfile`, idle timeout, runtime bootstrap
- `domain/*` (`QueryPipeline`, `ToolLoop`, `SpeechPresenter`, use cases)
- `data/*` ports (`LlmEngine`, `VhalGateway`, `ConversationMemory`)
- `MicCaptureCoordinator`, `EndpointingProfile`, `AgentRuntime`, feature flags
