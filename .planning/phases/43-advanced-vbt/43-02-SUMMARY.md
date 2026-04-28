# Plan 43-02 Summary: Real-Time Tracking & Auto-End

## Status: Complete

## Files Modified

| File | Summary |
|------|---------|
| `shared/src/commonMain/.../domain/model/Models.kt` | Added `VELOCITY_THRESHOLD_REACHED` data object to HapticEvent sealed class |
| `shared/src/androidMain/.../HapticFeedbackEffect.android.kt` | Added SoundPool mapping (boopbeepbeep), MediaPlayer fallback, VibrationEffect pattern (double heavy pulse), and legacy vibrator pattern for new event |
| `shared/src/iosMain/.../HapticFeedbackEffect.ios.kt` | Added sound mapping (boopbeepbeep) and haptic pattern (heavy impact + warning notification) for new event |
| `shared/src/commonTest/.../HapticEventAudioTest.kt` | Added `VELOCITY_THRESHOLD_REACHED` to exhaustive singleton list |
| `shared/src/commonMain/.../presentation/screen/WorkoutHud.kt` | Added `velocityLossThresholdPercent` parameter, `VelocityLossIndicator` composable with progress bar + threshold marker, replaced hardcoded velocity loss display |
| `shared/src/commonMain/.../presentation/screen/WorkoutTab.kt` | Threaded `velocityLossThresholdPercent` parameter through to WorkoutHud |
| `shared/src/commonMain/.../presentation/screen/WorkoutUiState.kt` | Added `velocityLossThresholdPercent` field (default 20) |
| `shared/src/commonMain/.../presentation/screen/ActiveWorkoutScreen.kt` | Populates `velocityLossThresholdPercent` from `userPreferences` |
| `shared/src/commonMain/.../presentation/manager/ActiveSessionEngine.kt` | Added VBT auto-end state fields, `checkVelocityThreshold()` method, haptic emission on first threshold rep, auto-end on 2nd consecutive threshold rep, reset at all 3 `biomechanicsEngine.reset()` sites |

## Verification

> verification: `grep -q 'VELOCITY_THRESHOLD_REACHED' Models.kt` → PASS
> verification: `grep -q 'VELOCITY_THRESHOLD_REACHED' HapticEventAudioTest.kt` → PASS
> verification: `grep -q 'VelocityLossIndicator' WorkoutHud.kt` → PASS
> verification: `grep -q 'velocityLossThreshold' WorkoutTab.kt` → PASS
> verification: `grep -q 'autoEndOnVelocityLoss\|shouldStopSet' ActiveSessionEngine.kt` → PASS
> verification: `grep -q 'VELOCITY_THRESHOLD_REACHED' ActiveSessionEngine.kt` → PASS
> verification: `grep -c 'velocityThresholdAlertEmitted = false' ActiveSessionEngine.kt` → 4 (3 reset sites + 1 declaration) PASS
> verification: `./gradlew :androidApp:assembleDebug` → BUILD SUCCESSFUL in 58s PASS

## Decisions

1. **Sound choice**: Used `boopbeepbeep` for VELOCITY_THRESHOLD_REACHED — same attention-getting sound as FINAL_REP/WORKOUT_COMPLETE, distinct from REP_COMPLETED's chirpchirp.
2. **Haptic pattern**: Strong double pulse (Android: 120ms+150ms at 255 amplitude; iOS: heavy impact + warning notification) to clearly signal fatigue threshold.
3. **Threshold threading approach**: Added `velocityLossThresholdPercent` to `WorkoutUiState` and populated from `userPreferences` in `ActiveWorkoutScreen`, avoiding modification of restricted files (WorkoutCoordinator, DWSM, PreferencesManager).
4. **VelocityLossIndicator threshold marker**: Used nested Box with `fillMaxWidth(thresholdFraction)` + `Alignment.CenterEnd` for correct positioning without BoxWithConstraints.
5. **Auto-end guard**: Uses `isWarmupComplete` check so threshold checking only applies to working reps, never warmup.
6. **checkVelocityThreshold() placement**: Inside `processBiomechanicsForRep`'s coroutine (after `processRep()` completes) so `latestRepResult` is populated when checked.

## Risks and Follow-ups

- The `VelocityLossIndicator` threshold marker position is approximate when the bar width varies — it positions the 2dp marker at the trailing edge of a `fillMaxWidth(thresholdFraction)` box. This is visually correct but not pixel-perfect across all screen widths.
- Auto-end calls `handleSetCompletion()` from a `Dispatchers.Default` coroutine, which is safe because `handleSetCompletion` uses `compareAndSet` atomic guard.
