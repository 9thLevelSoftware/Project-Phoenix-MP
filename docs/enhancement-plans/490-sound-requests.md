# Issue #490 — Sound Requests Implementation Plan

> For Hermes: implement this plan as its own PR. Use subagent-driven development with at most 3 concurrent agents. Keep this PR limited to audio/haptic cue behavior and timed-set countdown sounds.

GitHub issue: https://github.com/9thlevelsoftware/Project-Phoenix-MP/issues/490
Branch: `feat/490-sound-requests`
PR scope: more distinct rep cue and countdown tick/tock for timed sets.

## Goal

Make rep-count feedback easier to hear over music, and add a countdown/tick-tock cue for timed sets near the end of the timer.

## Current state verified in repo

Relevant files:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`
  - `HapticEvent.REP_COMPLETED`
  - `HapticEvent.FINAL_REP`
  - `HapticEvent.REP_COUNT_ANNOUNCED(repNumber)`
  - `HapticEvent.COUNTDOWN_TICK(secondsRemaining)`
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.android.kt`
  - Android `SoundPool`/`MediaPlayer` audio implementation.
  - `REP_COMPLETED` maps to `chirpchirp`.
  - `FINAL_REP` maps to `boopbeepbeep`.
  - spoken rep-number cues exist for 1-25.
  - `COUNTDOWN_TICK` currently reuses `beep`.
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.ios.kt`
  - platform equivalent that must be updated for parity.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
  - emits rep sounds around rep completion.
  - emits rest countdown ticks in existing rest timer loops.
  - timed exercise loops update `_timedExerciseRemainingSeconds` but need countdown audio emission.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt`
  - already displays timed exercise remaining seconds.

## Architecture

Use the existing haptic/audio event pipeline. Do not introduce a second audio event bus.

Two behaviors:

1. Rep-completed cue replacement.
   - Keep current user settings gates:
     - `repSoundEnabled`
     - `audioRepCountEnabled`
   - If spoken rep count is enabled, do not double-play chirp unless existing behavior already does.

2. Timed-set countdown cue.
   - Reuse `HapticEvent.COUNTDOWN_TICK(secondsRemaining)`.
   - Emit from timed exercise countdown loops for final 10 seconds.
   - Vary tick rate or pitch/intensity as timer approaches zero.

## Product behavior for v1

- Final 10 seconds of timed sets produce audible ticks when countdown beeps are enabled.
- 10..6 seconds: normal tick.
- 5..3 seconds: faster-feeling tick via higher pitch/playback rate or alternate resource.
- 2..1 seconds: most urgent tick or double-tick.
- Timer pause suppresses countdown ticks.
- Existing rest-timer countdown behavior remains unchanged.

If true sub-second “faster tick-tock” is too invasive, use one tick per second with increasing pitch; document that as v1.

## Tasks

### Task 1 — Lock down current audio routing with tests

Objective: prevent regressions in existing audio event mapping.

Files:

- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/model/HapticEventAudioTest.kt`
- Test/modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackAudioRoutingGuardTest.kt`

Steps:

1. Add tests asserting:
   - `REP_COMPLETED` has an Android cue.
   - `FINAL_REP` still has a distinct cue.
   - `COUNTDOWN_TICK(10)`, `(5)`, `(1)` route to countdown cue behavior.
   - `REP_COUNT_ANNOUNCED(1..25)` remains valid.
2. Run:
   - `./gradlew :shared:allTests --tests '*Haptic*' --tests '*Audio*'`
3. Confirm failures guide expected mapping additions only.

### Task 2 — Add/select distinct rep cue resources

Objective: make `REP_COMPLETED` more audible.

Files:

- Modify: `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.android.kt`
- Modify: Android raw resources under relevant `res/raw` folder.
- Modify: iOS audio resource mapping in `HapticFeedbackEffect.ios.kt` and iOS resources if needed.

Steps:

1. Inventory existing raw cues referenced by `AndroidCueResources`.
2. If an existing cue is sufficiently distinct, map `REP_COMPLETED` to it.
3. Otherwise add a new short cue, e.g. `rep_tick_strong`.
4. Update `AndroidCueResources.eventCues`:
   - `HapticEvent.REP_COMPLETED to repTickStrong`
5. Update fallback `cueForEvent` automatically through existing map.
6. Mirror on iOS.

Verification:

- Compile Android resources.
- Run audio routing tests.

### Task 3 — Add timed-set countdown emission

Objective: timed bodyweight/duration sets emit final-countdown ticks.

Files:

- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`

Known loop locations from inspection:

- around `2183-2199`: bodyweight timer job setting `_timedExerciseRemainingSeconds`.
- around `2471-2484`: another duration-based exercise timer loop.

Steps:

1. Add a small helper inside `ActiveSessionEngine`:
   - `private suspend fun emitExerciseCountdownTickIfNeeded(remainingSeconds: Int, lastTickedSecond: Int): Int`
   - or keep it inline if helper visibility complicates tests.
2. Gate emission:
   - remaining in `1..10`
   - not paused
   - not already ticked for that second
   - `prefs.beepsEnabled && prefs.countdownBeepsEnabled`
3. Emit `HapticEvent.COUNTDOWN_TICK(remainingSeconds)`.
4. Ensure reset/pause/resume does not double-emit the same second.
5. Keep rest-timer code unchanged.

### Task 4 — Vary countdown urgency in platform audio

Objective: make countdown feel faster/urgent near zero.

Files:

- Modify: `HapticFeedbackEffect.android.kt`
- Modify: `HapticFeedbackEffect.ios.kt`

Android approach:

- In `playSound`, when event is `COUNTDOWN_TICK`, set playback rate by seconds:
  - 10..6: `1.0f`
  - 5..3: `1.2f`
  - 2..1: `1.4f`
- If SoundPool rate sounds poor, add separate cue resources instead.
- Fire OS MediaPlayer fallback may not support rate; accept normal tick or use alternate resource.

IOS approach:

- Mirror with available AVAudioPlayer rate if enabled/supported, or separate resources.

### Task 5 — Settings copy update

Objective: ensure settings labels are not rest-only if timed sets now use them.

Files:

- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt` only if labels are hardcoded.
- Modify: `shared/src/commonMain/composeResources/values*/strings.xml`

Steps:

1. Find current labels for countdown beeps.
2. Update description to mention rest timers and timed sets.
3. Keep existing setting key to avoid migration.

## Tests

Add/extend tests for:

- Cue mapping:
  - `REP_COMPLETED` distinct from final rep and rest-ending cues.
  - countdown tick has cue for all final seconds.
- Engine timer behavior:
  - final 10 seconds emit ticks when enabled.
  - disabled beeps suppress ticks.
  - paused timer suppresses ticks.
  - no duplicate ticks for the same remaining second.
- Settings:
  - label/resource references compile.

Suggested commands:

```bash
./gradlew :shared:compileKotlinMetadata
./gradlew :shared:allTests --tests '*Haptic*' --tests '*Audio*' --tests '*Timer*'
./gradlew :androidApp:assembleDebug
./gradlew :shared:assembleXCFramework
```

## Manual QA

Android phone:

1. Enable rep sounds, disable spoken rep counts, complete reps; verify new cue is audible.
2. Enable spoken rep counts; verify no annoying double-sound regression.
3. Complete final rep; verify final-rep cue still differs.
4. Run 30-second timed set; verify final 10 seconds tick.
5. Pause timed set at 8 seconds; verify ticking stops.
6. Resume; verify ticking resumes without duplicates.

Android TV/Fire OS if available:

- Verify fallback still plays a cue.
- Verify media/music volume behavior remains reasonable.

IOS:

- Verify equivalent cue resources play or fail silently without crash.

## Risks

- Louder/distinct cue can become annoying over long workouts; keep setting gate intact.
- SoundPool playback-rate changes may behave differently across devices.
- Fire OS fallback may not support pitch/rate changes.
- Resource shrinking can drop unreferenced raw cues; reference cues statically as current code does.

## Acceptance criteria

- Rep-completed sound is more distinct than previous chirp.
- Timed sets emit countdown ticks for the final seconds when countdown beeps are enabled.
- Countdown ticks become more urgent near zero, either by faster cadence, pitch, or alternate cues.
- Timer pause suppresses ticks.
- Existing rest, final-rep, PR, and badge sounds still work.
