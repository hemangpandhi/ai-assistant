# Architecture (Cursor map)

Short routing guide. Deep dive: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). UI/UX ops: [docs/design-assistant/ASSISTANT_UI_UX.md](docs/design-assistant/ASSISTANT_UI_UX.md).

## Modules — where to put work

| Module | Put here | Do not |
|--------|----------|--------|
| **`:assistant-ui`** | Compose face, immersive overlay, chrome, theme, MVI stage, demo backend, UI contracts | Agent/LLM/VHAL logic |
| **`:assistant-ext`** | VIS/session host, TTFR, UiUx* bridges, Koin `uiUxModule` | Master agent classes |
| **`:assistant-api`** | Thin host-neutral ports (`LlmSessionPort`, `ToolCatalog`) | UI Compose |
| **`:app`** | Manifest, assets, resources, wiring tests | Agent Kotlin under `app/src/main/java` |
| **`:agent-core`** | — | **Never edit** (byte-identical to `origin/master`) |

## UI work default search scope

For face / overlay / chrome / transcript / placement / gallery tasks:

1. Read `assistant-ui/README.md` first.
2. Search and edit **`assistant-ui/`** only.
3. Touch `:assistant-ext` / `:app` only for host wiring or manifest.
4. Never open `:agent-core` or `app/libs/` / model assets for UI work.

## State & boundaries

- UI observes `AssistantBackend.events`; mic goes out via `onSpeechInput`.
- Cabin facts cross as `AssistantCabinContext` (primitives/strings only).
- Stage state: `assistant-ui/.../mvi/AssistantStageStore`.
- Immersive entry: `ImmersiveAssistantOverlay` + placement config.

## Heavy non-code

Large mocks/seeds → `mocks/` or `data/` (`.cursorignore`d). `@`-mention individual files when needed.
