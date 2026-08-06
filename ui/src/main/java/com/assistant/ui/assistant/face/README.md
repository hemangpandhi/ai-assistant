# face/

Compose assistant faces and mood/glyph visuals.

## Flow

1. Host/config picks a face id (`AssistantFaceConfig` / ADB receiver).
2. Stage renders the selected face (`AssistantFace`, `ConfigurableAssistantFace`, variants).
3. Moods / cues update expression (`AssistantMood`, `FaceCueParser`, `FaceMoodResolver`).
4. Motion helpers live in `AssistantFaceMotion`; shared glyph colors in `AssistantGlyphPalette`.

## Key types

| File | Role |
|------|------|
| `AssistantFace.kt` | Face registry / selection |
| `AssistantFaceConfig.kt` | Persistable face preference |
| `ImmersiveEyesFace.kt` / `ClassicHybridEyesFace.kt` / `ImmersiveTrapezoidEyesFace.kt` / `FusionAssistantFace.kt` / … | Concrete face implementations |
| `ExpressiveFaceShell.kt` | Shared expressive shell (incl. fixed trapezoid) |
| `FaceCueIconVisuals.kt` | Cue → icon visuals |

## Rules

- Keep faces presentation-only; no agent/LLM imports.
- Prefer tokens from `ui/theme/` over hard-coded colors when sharing with chrome.
- New OEM face = new file + register in the face selector; do not grow one mega-face.
