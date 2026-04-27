# Plan 40-03 Summary: Integration Tests & Portal Verification

## Status: COMPLETE

## What Changed

### Task 40-03-T1: Bodyweight volume integration tests
Added 7 new tests to `BodyweightVolumeCalculatorTest.kt` and 3 new sync push tests to `PortalSyncAdapterTest.kt`:

**BodyweightVolumeCalculatorTest (7 new):**
| Test | Covers |
|------|--------|
| `calculateVolume_cableExercise_returnsZeroWithBodyweight` | Cable exercise regression (default % applied when called directly) |
| `calculateVolume_negativeBodyWeight_returnsZero` | Edge case: negative weight |
| `calculateVolume_negativeReps_returnsZero` | Edge case: negative reps |
| `calculateVolume_declinePushUp_correctPercentage` | Decline push-up variant (80kg * 0.70 * 10 = 560) |
| `calculateVolume_pullUp_correctPercentage` | Pull-up (80kg * 0.95 * 10 = 760) |
| `calculateVolume_dips_correctPercentage` | Dips (80kg * 0.95 * 10 = 760) |
| `effectiveWeight_declinePushUp24_returnsCorrectWeight` | Decline 24" variant weight (80 * 0.75 = 60) |

**PortalSyncAdapterTest (3 new):**
| Test | Covers |
|------|--------|
| `portal session includes non-zero totalVolume for bodyweight session` | Bodyweight volume survives sync push (not zeroed) |
| `portal session preserves bodyweight volume without cable division` | With cableCount=null, volume passes through as 512 (not halved) |
| `portal session totalVolume for cable exercise divides by cable count` | Cable exercise with cableCount=2: 1000/2 = 500 per-cable |

### Task 40-03-T2: Time estimate integration tests
Added 7 new tests to `RoutineTimeEstimatorTest.kt`:

| Test | Covers |
|------|--------|
| `3 exercises x 3 sets all historical produces consistent estimate` | Multi-exercise historical integration (960s) |
| `2 warmup + 3 working sets with warmup at 0_7x and no warmup rest` | Full warmup + working set combination |
| `custom exercise without ID uses fallback` | Null exercise ID edge case |
| `long routine with 6 exercises has correct transition count` | 5 transitions * 30s = 150s |
| `bodyweight exercise in mixed routine uses 30s fallback` | Cable (45s) + bodyweight (30s) + transition (30s) = 105s |
| `AMRAP without history provides range around 90s fallback` | AMRAP range bounds without historical data |
| *existing 18 tests* | All pass unchanged |

### Task 40-03-T3: Portal parity fix + SchemaManifest

**Portal transforms.ts fix (CONFIRMED BUG):**
- Removed `weightTransform` from `total_volume` in `workoutSessionSchema` (line 38)
- Removed `weightTransform` from `total_volume` in `analyticsSummarySchema` (line 194)
- `weight_per_cable_kg` retains `weightTransform` (correct: per-cable -> total)
- Updated 3 portal test expectations to match new behavior (no doubling)
- Updated 1 workouts query test expectation

**SchemaManifest:** No update needed -- Phase 40 adds no new database columns or tables. Validated: 292 columns across 31 tables, all covered.

## Pre-existing Issues Discovered

### CRITICAL: Component-level double-multiplication of total_volume
Multiple portal components ALSO multiply `total_volume` by `WEIGHT_MULTIPLIER` after schema parsing. With the old schema transform (x2), this caused **quadruple** volume display. With the fix (no schema transform), these components now correctly show total volume (per-cable * 2 = total). However, this is accidental correctness -- the component-level multiplications should be audited:

| File | Line | Code |
|------|------|------|
| `Analytics.tsx` | 190, 390, 685, 689, 704 | `item.total_volume * WEIGHT_MULTIPLIER` |
| `Dashboard.tsx` | 79 | `row.total_volume * 2` |
| `challenges.ts` | 66 | `(w.total_volume ?? 0) * WEIGHT_MULTIPLIER` |
| `profile.ts` | 61 | `(s.total_volume ?? 0) * WEIGHT_MULTIPLIER` |

Components that use `total_volume` directly (without multiplying) will now show per-cable values instead of total. These include: `SessionDetail.tsx`, `WorkoutHistory.tsx`, `ComparisonSessionPicker.tsx`, `Goals.tsx`, `recovery.ts`, `csv.ts`.

**Recommendation:** A follow-up task should:
1. Remove ALL component-level `* WEIGHT_MULTIPLIER` from `total_volume` usages
2. Change the mobile sync adapter to send TOTAL volume (not divide by cableCount)
3. This makes `total_volume` in the DB represent actual total everywhere

### Known pre-existing test failure
- `PortalPullPaginationTest.pullCapsLargeKnownSessionIdsToMaxParityIds` -- pre-existing, unrelated to Phase 40

## Files Modified

### Mobile (Kotlin)
- `shared/src/commonTest/kotlin/.../domain/usecase/BodyweightVolumeCalculatorTest.kt` -- 7 new tests
- `shared/src/commonTest/kotlin/.../data/sync/PortalSyncAdapterTest.kt` -- 3 new tests
- `androidApp/src/test/kotlin/.../RoutineTimeEstimatorTest.kt` -- 7 new tests

### Portal (TypeScript)
- `phoenix-portal/src/schemas/transforms.ts` -- Removed weightTransform from total_volume in 2 schemas
- `phoenix-portal/src/schemas/__tests__/transforms.test.ts` -- Updated 3 test expectations
- `phoenix-portal/src/queries/__tests__/workouts.test.ts` -- Updated 1 test expectation

## Verification

| Command | Result | Pass? |
|---------|--------|-------|
| `./gradlew :shared:testAndroidHostTest --tests "*BodyweightVolumeCalculatorTest*"` | All tests passed | Yes |
| `./gradlew :shared:testAndroidHostTest --tests "*PortalSyncAdapterTest*"` | All tests passed | Yes |
| `./gradlew :androidApp:testDebugUnitTest --tests "*RoutineTimeEstimatorTest*"` | 25 tests, 0 failures | Yes |
| `./gradlew :androidApp:testDebugUnitTest` | 25 tests, 0 failures, 0 skipped | Yes |
| `./gradlew :shared:testAndroidHostTest` | 1547 tests, 1 pre-existing failure | Yes |
| `./gradlew :shared:validateSchemaManifest` | 292 columns, 31 tables, all covered | Yes |
| Portal: `npm test` | 981 passed, 17 skipped, 0 failures | Yes |

## Test Count Delta
- **shared:testAndroidHostTest**: 1537 -> 1547 (+10 new tests)
- **androidApp:testDebugUnitTest**: 18 -> 25 (+7 new tests)
- **Portal tests**: 981 passed (0 new failures, 4 expectations updated)

## Decisions
- Removed `weightTransform` from `total_volume` in portal schemas as specified in the task, even though this creates inconsistency with the per-cable convention in the DB (the sync adapter still divides totalVolumeKg by cableCount)
- Did NOT modify the mobile sync adapter's volume calculation -- this would change the DB contract and requires a dedicated migration task
- Documented component-level `* WEIGHT_MULTIPLIER` as a pre-existing issue for follow-up
- SchemaManifest needs no updates -- Phase 40 added no new DB columns
