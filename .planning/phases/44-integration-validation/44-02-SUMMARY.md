# Plan 44-02 Summary: Regression Suite & Build Validation

## Status: Complete

## Results

### Task 1: Full Regression Suite
- **1,682 total tests** — 1,681 passed, 1 failed
- Failed: `PortalPullPaginationTest.pullCapsLargeKnownSessionIdsToMaxParityIds` — **pre-existing** (MAX_PARITY_IDS raised from 500→10000, test input not updated)
- **0 new v0.9.0 regressions**
- Note: `SqlDelightTrainingCycleRepositoryTest.checkAndAutoAdvance` (documented flaky) did NOT fail this run

### Task 2: Build Verification
- **BUILD SUCCESS** — `./gradlew :androidApp:assembleDebug` (2m 7s)
- APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk` (35.9 MB)
- 0 errors, 4 pre-existing deprecation warnings (Kermit logging API)
- Schema validation passed: 292 columns across 31 tables

### Task 3: Sync DTO Parity
- RoutineGroup NOT in sync DTOs ✓
- bodyWeight NOT in sync DTOs ✓
- VBT prefs NOT in sync DTOs ✓
- 124 sync tests pass ✓
- Portal `transforms.ts` total_volume fix intact (Phase 40) ✓

### Task 4: Database Migration 27
- `27.sqm` file present — CREATE TABLE RoutineGroup + index + Routine.groupId FK ✓
- MigrationStatements.kt updated (lines 663-674) ✓
- VitruvianDatabase.sq includes RoutineGroup table ✓
- Routine.groupId is nullable with ON DELETE SET NULL ✓
- SchemaManifest covers RoutineGroup (292 cols, 31 tables) ✓
- 28 migration/schema tests pass ✓

### Task 5: Platform-Specific Files
- Expect declarations: BackupLocationPicker, BackupDestinationResolver, HapticFeedbackEffect ✓
- Android actuals: all 3 exist, HapticFeedbackEffect has FINAL_REP + VELOCITY_THRESHOLD_REACHED ✓
- iOS actuals: all 3 exist, HapticFeedbackEffect has FINAL_REP + VELOCITY_THRESHOLD_REACHED ✓
- Android sound: 66 audio files in res/raw/ including boopbeepbeep.ogg ✓
- iOS sound: pre-existing gap — .ogg→.caf conversion TODO noted in code (not v0.9.0 regression)

## Pre-Existing Issues (NOT v0.9.0)
1. PortalPullPaginationTest — test input size doesn't exceed new MAX_PARITY_IDS cap
2. Gradle clean configuration cache incompatibility with Gradle 9.4.1
3. iOS sound files — .ogg to .caf conversion TODO in HapticFeedbackEffect.ios.kt
4. Kermit logging API deprecation warnings (4 files)

## Verdict
**v0.9.0 Enhancement Sweep: VALIDATED** — zero new regressions, build clean, sync boundaries enforced, migration verified, platform files complete.
