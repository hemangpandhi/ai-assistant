# Driver-seat next steps (from code + suite)

Living checklist. Update after each Pixel tablet run of
`docs/use_cases/DRIVER_SEAT_TABLET_SUITE.md`.

## Already addressed in code (this branch)

| Item | Status |
|---|---|
| LLM `requires_confirmation` narrated as “I ran X” | **Fixed** — speak confirmation ask; only ACK tools that actually executed |
| ContextGuard Confirm silent under model prose | **Fixed** — confirm/error feedback spoken; mic reopens |
| Snapshot throw fail-open on unlock/trunk/windows | **Fixed** — fail-closed Block for safety-critical tools |
| Unknown gear silent Allow on safety tools | **Fixed** — Confirm ask instead |

## Automated report (tablet)

```bash
ANDROID_SERIAL=<tablet> ANDROID_USER=10 ./scripts/run_tablet_usecase_report.sh
```

Use `docs/reports/tablet_usecase_report.md` → **Failures → next-step hints** as the
evidence for the buckets below.

## Suggested priority after green suite

1. **Stabilization — generic cabin ACK**  
   Watch D1–D4 for “Done — that's taken care of.” Prefer handler/registry-specific lines.

2. **Risk — wake-process LLM reload**  
   `WAKE_WORD_RELOAD_LLM` can load LiteRT in `:wakeword` (RAM/LMK). Gate reload to main process only.

3. **Bug — concurrent typed query while Thinking**  
   Debounce / ignore superseding sends mid-actuation (D21).

4. **Violation / policy**  
   Re-check D7–D11 whenever registry policies change; keep fail-closed tests green.

5. **Semantic keyword improvement (only if measured)**  
   If tablet logs show many paraphrase misses on registered skills (not safety bugs), add embedding retrieval as a tier between BM25 and LLM — never as auto-DirectTool for safety tools.

6. **Model export risk**  
   Refuse CPU fallback for oversized / non-`q8_ekv` exports (`MODEL_EXPORT_BUG_REPORT.md`).

## Run log (fill in)

| Date | Device | D# failed | Class (stab/bug/violation/risk/semantic) | Notes |
|---|---|---|---|---|
| | Pixel tablet user 10 | | | |
