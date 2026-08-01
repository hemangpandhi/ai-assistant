# `:assistant-ui` — Compose assistant presentation

Presentation-only module for the vehicle assistant face, immersive stage, and chrome.
Agent / LLM / VHAL stay outside this module.

Full ADB + integration guide: [docs/design-assistant/ASSISTANT_UI_UX.md](../docs/design-assistant/ASSISTANT_UI_UX.md).
Boundary notes: [docs/design-assistant/assistant-standalone.md](../docs/design-assistant/assistant-standalone.md).

## Package map

```text
com.assistant.ui.assistant/
  api/         Backend/host contracts, face cues, debug strip config
  audio/       Speech helpers (STT/TTS/wake feedback used by UI)
  backend/     DemoAssistantBackend + mood mapping / platform TTS
  config/      Settings-backed UI preferences
  dialogue/    Scripts + LiveInputText reveal
  entry/       VirtualAssistantScreen / activity entry
  face/        Face implementations, moods, glyphs, motion  → face/README.md
  mvi/         Stage store (intent → state → effects)       → mvi/README.md
  ui/
    chrome/    Waveform, presence, thinking cloud, shared props
    gallery/   Variant gallery for A/B faces & placements
    immersive/ Overlay, placement, bottom/card chrome       → ui/immersive/README.md
    overlay/   Floating overlay service + car face shell
    theme/     AssistantTheme, tokens, overlay tokens
```

## Rules of the road

- **Prefer editing here** for any UI/visual/motion/transcript change.
- Do **not** import `AgentOrchestrator`, `LLMManager`, `VehicleManager`, or app ViewModels.
- New faces → `face/`; stage layout → `ui/immersive/`; shared chrome → `ui/chrome/`; tokens → `ui/theme/`.
- Host wiring (VIS session, Koin, ADB receivers) lives in `:assistant-ext` / `:app`, not here.
- Never modify `:agent-core`.

## Quick entry points

| Concern | Start file |
|---------|------------|
| Full immersive stage | `ui/immersive/ImmersiveAssistantOverlay.kt` |
| Edge / card chrome | `ui/immersive/ImmersiveAssistantBottomChrome.kt`, `…CardChrome.kt` |
| Face swap / moods | `face/AssistantFace.kt`, `face/AssistantFaceConfig.kt` |
| Transcript reveal | `dialogue/LiveInputText.kt` + immersive transcript composables |
| Stage state | `mvi/AssistantStageStore.kt` |
| Demo-only events | `backend/DemoAssistantBackend.kt` |
