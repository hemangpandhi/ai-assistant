# Architecture (Cursor map)

Short routing guide. Deep dive: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). UI/UX ops: [docs/design-assistant/ASSISTANT_UI_UX.md](docs/design-assistant/ASSISTANT_UI_UX.md).

## Single module — where to put work

One Gradle module: **`:app`**. Prefer package boundaries (SOLID / SRP); edit any file when the task needs it.

| Path / package | Put here | Do not |
|----------------|----------|--------|
| **`com/assistant/ui/`** | Compose face, immersive overlay, chrome, theme, MVI stage, demo backend, UI contracts | Agent/LLM/VHAL logic |
| **`.../assistant/`** (under `com.tcs.vehicleassistant`) | VIS/session host, UiUx* bridges, runtime bootstrap | — |
| **`com/assistant/api/`** | Thin host-neutral ports (`LlmSessionPort`, `ToolCatalog`) | UI Compose |
| **`.../hardware/ear/`** | Session mic + STT (`AssistantEar`, `EarMic`, engines) | Orchestrator / LLM |
| **`:app` shell** | Manifest, assets, resources, wiring tests | — |

## UI work default search scope

For face / overlay / chrome / transcript / placement / gallery tasks:

1. Search and edit under **`app/src/main/java/com/assistant/ui/`** first.
2. Touch `.../assistant/` or app manifest only for host wiring.

## State & boundaries

- UI observes `AssistantBackend.events`; mic goes out via `onSpeechInput`.
- Cabin facts cross as `AssistantCabinContext` (primitives/strings only).
- Stage state: `.../mvi/AssistantStageStore`.
- Immersive entry: `ImmersiveAssistantOverlay` + placement config.
- Ear path: `AndroidAudioManager` → `AssistantEar` → `EarMic` + `EarSttEngine` → ViewModel.

## Heavy non-code

Large mocks/seeds → `mocks/` or `data/` (`.cursorignore`d). `@`-mention individual files when needed.
