# ui/immersive/

Fullscreen / edge-card immersive stage for the Compose assistant.

## Flow

1. Session host shows `ImmersiveAssistantOverlay` (or overlay service).
2. Placement (`AssistantPlacement` / config / receiver) chooses fullscreen, left, right, or bottom.
3. Chrome splits: bottom dock vs card chrome; face docks via `IslandCapsuleDock`.
4. Transcript / glow / backdrop / motion are composables in this package.
5. Stage bus / prewarmer / latency helpers support first-frame TTFR.

## Key types

| File | Role |
|------|------|
| `ImmersiveAssistantOverlay.kt` | Root immersive composition |
| `ImmersiveAssistantBottomChrome.kt` | Bottom placement chrome |
| `IslandCapsuleDock.kt` | Pill / Dynamic Island face + transcript capsule |
| `ImmersiveAssistantCardChrome.kt` | Edge card chrome |
| `AssistantPlacement*.kt` | Placement model + config + ADB |
| `ImmersiveOverlayMotion.kt` | Enter/exit / presence motion |
| `ImmersiveGlowBreath.kt` | Ambient glow breathing |

## Rules

- Speaking transcript: one line, word-by-word reveal, centered, ≤60% width (see Cursor rule `immersive-speaking-transcript`).
- Prefer `AssistantOverlayTokens` / theme tokens over ad-hoc colors.
- Keep overlay free of `:agent-core` types; talk only through `AssistantBackend` / host contracts.
- Large behavior changes stay in this package; shared waveform/presence → `ui/chrome/`.
