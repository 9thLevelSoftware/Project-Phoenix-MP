# Data Task 6 implementation report

Base: `2103af00`
Commit subject: `feat: gate live VBT by active profile`

## Delivered behavior

- Preserved unconditional real biomechanics capture and extracted the existing live decision into `ActiveSessionEngine.evaluateLatestVbtResult()` with the working-rep guard inside that production seam.
- Added one immutable `VbtRuntimeSettings(enabled, velocityLossThresholdPercent, autoEndOnVelocityLoss)` `StateFlow` in `WorkoutCoordinator`; live evaluation reads it once per completed rep.
- Initialized and updated the complete VBT triple from `SettingsManager.userPreferences`, using `map` plus `distinctUntilChanged`, rethrowing collector cancellation, and adding no new DI binding.
- Disabled VBT now suppresses only velocity-loss interpretation, threshold/verbal events, and auto-end. Raw rep results, set summaries, persistence, MCV/peak values, PR/progression inputs, and history remain available.
- Re-enabling preserves the configured threshold and auto-end choice and restores the prior alert/auto-end behavior without resetting the biomechanics baseline.
- Replaced the test-local verbal tracker with production policy `UserPreferences.verbalEncouragementEventOrNull()`. Local adult confirmation neutralizes vulgar and dominatrix routing while leaving persisted profile intent unchanged.
- Threaded `vbtEnabled` through `WorkoutUiState`, the `ActiveWorkoutScreen` remember key and value, both `WorkoutTab` layers, `WorkoutHud`, and `StatsPage`.
- Disabled UI keeps raw MCV and Peak visible in a neutral color while hiding Zone, velocity-loss, and estimated-reps-left interpretation. `BiomechanicsHistoryCard` remains ungated.

## TDD evidence

1. Baseline characterization: the original focused VBT/event/integration matrix passed (86 tests, zero failures).
2. Behavior-neutral seam extraction: the same matrix passed again (86 tests, zero failures).
3. Feature RED:
   - The first production-backed disabled test failed because live threshold processing still auto-ended and reset the real `BiomechanicsEngine`; the XML failure was `latestRepResult` unexpectedly null, with logs showing `VELOCITY_THRESHOLD_REACHED` and auto-end after two threshold reps.
   - The completed test set then failed to compile on the deliberately absent `VbtRuntimeSettings`, coherent snapshot flow, and production verbal-policy API.
   - After runtime GREEN, the two UI source-contract tests remained RED until the full Compose chain was threaded and the interpretation branches were gated.
4. GREEN: the final focused matrix contains 92 tests across 9 suites with zero failures or errors. `VbtUiWiringTest` contributes three tests, including a malformed-source proof that the gate-slicing assertions reject raw metrics moved inside a VBT gate or estimated reps moved outside the loss gate.

Runtime tests use `DWSMTestHarness`, its Ready fake profile, the real `BiomechanicsEngine`, real `WorkoutState`, real `HapticEvent` stream, and `FakeBiomechanicsRepository`. Source contracts use `readProjectFile` and verify all required Compose hops plus the absence of a history gate.

## Verification

- `:shared:testAndroidHostTest --tests "*Vbt*" --tests "*VerbalEncouragementGateTest*" --tests "*WorkoutCoordinatorEventTest*" --tests "*ActiveSessionEngineIntegrationTest*"`: PASS, 92/92.
- `:shared:compileKotlinIosArm64`: PASS.
- Android main and Android host test compilation: PASS as part of the focused matrix.
- `git diff --check`: PASS (Git only reports the repository's LF-to-CRLF checkout notices).

The build continues to print existing project deprecation/performance warnings; this task introduced no new compilation failure or Koin graph change.

## Adaptations to the real codebase

- No extra harness API was required: `DWSMTestHarness` already exposed the active engine, coordinator, Ready repository, real settings facade, and fake biomechanics persistence repository needed by the production-backed tests.
- Existing `WorkoutCoordinatorEventTest` and `ActiveSessionEngineIntegrationTest` already covered event coexistence and subordinate VBT configuration. The new runtime test adds the missing master-toggle/coherent-switch/persistence/adult-policy assertions without duplicating those fixtures.
