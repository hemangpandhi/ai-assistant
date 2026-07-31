# Driver-seat Pixel tablet suite

You are **seated in the car / driving**. This suite exercises the assistant the way a driver would: wake word, spoken cabin commands, safety confirms, chat, and recovery. Use it on an AAOS Pixel Tablet (user **10**) after slim-APK sideload (`docs/TABLET_VALIDATION_GUIDE.md`).

## 0. Preconditions

```bash
export SERIAL=<tablet>
export USER_ID=10
# Models sideloaded: STT + Vosk + Gemma under /data/local/tmp/...
adb -s "$SERIAL" shell settings get --user "$USER_ID" secure voice_interaction_service
adb -s "$SERIAL" logcat -c
```

Pass criteria for every row: **spoken/UI feedback is specific** (not only “Done — that's taken care of”), and logcat shows the expected path tag when noted.

---

## 1. Matrix (drive / parked)

| # | Cabin state | You say / do | Expected path | Pass |
|---|---|---|---|---|
| D1 | Parked | “Hey Assistant” → “Turn on the AC” | Wake → DirectTool HVAC; AC changes; specific ACK | ☐ |
| D2 | Parked | “Increase temperature” | DirectTool; temp moves; specific ACK | ☐ |
| D3 | Parked | “Increase fan” / “set fan speed to 3” | DirectTool fan | ☐ |
| D4 | Parked | “Play music” | DirectTool media; playback starts | ☐ |
| D5 | Parked | “What’s the weather?” | DirectTool weather (soft interrogative) | ☐ |
| D6 | Parked | “Who are you?” | DirectTool identity | ☐ |
| D7 | **Driving** (mock/speed ≥5) | “Unlock the doors” | **Confirm** question; **no** unlock until “yes” | ☐ |
| D8 | Driving | After D7 say “No” | Decline; doors stay locked | ☐ |
| D9 | Driving | “Unlock the doors” → “Yes” | Confirm then verified write (or honest fail) | ☐ |
| D10 | Driving | “Open the trunk” | **Block**; trunk stays closed | ☐ |
| D11 | Driving | “Open the windows” | Confirm if policy hits; never silent write | ☐ |
| D12 | Parked | “Unlock the doors” | Allow / confirm per registry; never false “I ran unlock” | ☐ |
| D13 | Any | LLM/tool-tag only unlock (adb/debug if available) | Speaks confirmation_message; **never** “Okay — I ran unlockDoors” | ☐ |
| D14 | Any | “I’m not feeling good” | Wellness offer / empathy; no fake Done; optional music on “yes” | ☐ |
| D15 | Any | “How are you?” | Open chat; no forced playMusic/climate tool | ☐ |
| D16 | Playing loud | “Volume up” | ContextGuard Confirm if loud; question spoken | ☐ |
| D17 | Nav active | “Navigate to \<same dest\>” | Confirm restart | ☐ |
| D18 | Low fuel | “Navigate to gas station” | Confirm low-fuel if policy armed | ☐ |
| D19 | Any | Cancel mid-listen ×5 | Wake still works after | ☐ |
| D20 | Speaking TTS | Barge-in with new command | Prior TTS stops; new turn runs | ☐ |
| D21 | Thinking | Second typed command quickly | No wedged mic; last turn wins cleanly | ☐ |
| D22 | Slim APK | STT/Vosk missing | Honest error; not silent forever | ☐ |
| D23 | Cold start | First query after boot | Engine ready; TTFT acceptable | ☐ |
| D24 | 8-turn chat | Mix HVAC + Q&A | No KV crash; history still useful | ☐ |

---

## 2. JVM gate (run before tablet)

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.tcs.vehicleassistant.requirements.DriverSeatScenarioMatrixUnitTest' \
  --tests 'com.tcs.vehicleassistant.core.LlmToolTurnPolicyTest' \
  --tests 'com.tcs.vehicleassistant.core.SafetyCriticalToolsTest' \
  --tests 'com.tcs.vehicleassistant.core.ContextGuardTest'
```

## 3. On-device instrumented package

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s "$SERIAL" install -r --user "$USER_ID" app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r --user "$USER_ID" app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w --user "$USER_ID" \
  -e package com.tcs.vehicleassistant.requirements \
  com.tcs.vehicleassistant.test/androidx.test.runner.AndroidJUnitRunner
```

Voice rows (D1 wake, D19–D20) still need human soak; instrumented covers text→DirectTool/ContextGuard.

## 4. Logcat watch

```bash
adb -s "$SERIAL" logcat -d | grep -E \
  'DirectTool|FollowUp|WellnessOffer|ContextGuard|LLM tool requires confirmation|fail-closed|Wake word|Engine ready|I ran'
```

**Red flags:** `Okay — I ran unlockDoors` without a prior confirm+yes; ContextGuard snapshot fail then silent Allow on unlock/trunk; every tool ACK identical generic Done line.

## 5. After the suite — how to pick next improvements

Fill `docs/reports/DRIVER_SEAT_NEXT_STEPS.md` from failures:

| Failure class | Next investment |
|---|---|
| Wrong / missing DirectTool on clear cabin phrase | Keyword / alias hygiene (lexical) |
| Paraphrase miss (“make it cooler in here”) | Consider semantic skill retrieval tier |
| Confirm not spoken / “I ran X” lie | Stabilization / safety bug (P0) |
| Unlock while moving Allowed | Policy / fail-closed violation |
| Generic Done ACK | Handler feedback bug |
| Empty Gemma / wrong tool after inject | Prompt / allow-list / few-shot |
| Wake/STT flaky | Audio lifecycle stabilization |
| LMK / GPU init | Model export / memory risk |

Do **not** jump to full semantic RAG until paraphrase misses dominate the log categories above.
