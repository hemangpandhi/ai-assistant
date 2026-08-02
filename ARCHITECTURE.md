# Architecture (Cursor map)

Short routing guide. Deep dive: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). UI/UX ops: [docs/design-assistant/ASSISTANT_UI_UX.md](docs/design-assistant/ASSISTANT_UI_UX.md).

## Single module — where to put work

One Gradle module: **`:app`**. Ownership is by folder/package:

| Path / package | Put here | Do not |
|----------------|----------|--------|
| **`com/assistant/ui/`** | Compose face, immersive overlay, chrome, theme, MVI stage, demo backend, UI contracts | Agent/LLM/VHAL logic |
| **`.../assistant/`** (under `com.tcs.vehicleassistant`) | VIS/session host, UiUx* bridges, runtime bootstrap | Master agent classes |
| **`com/assistant/api/`** | Thin host-neutral ports (`LlmSessionPort`, `ToolCatalog`) | UI Compose |
| **Allowlisted `app/src/main/java/com/tcs/vehicleassistant/...`** | — | **Never edit** (byte-identical to `origin/master`) |
| **`:app` shell** | Manifest, assets, resources, wiring tests | Drive-by edits to master-owned agent Kotlin |

## UI work default search scope

For face / overlay / chrome / transcript / placement / gallery tasks:

1. Search and edit under **`app/src/main/java/com/assistant/ui/`** first.
2. Touch `.../assistant/` or app manifest only for host wiring.
3. Never edit allowlisted master-owned agent `.kt` files for UI work.

## State & boundaries

- UI observes `AssistantBackend.events`; mic goes out via `onSpeechInput`.
- Cabin facts cross as `AssistantCabinContext` (primitives/strings only).
- Stage state: `.../mvi/AssistantStageStore`.
- Immersive entry: `ImmersiveAssistantOverlay` + placement config.

## Heavy non-code

Large mocks/seeds → `mocks/` or `data/` (`.cursorignore`d). `@`-mention individual files when needed.
