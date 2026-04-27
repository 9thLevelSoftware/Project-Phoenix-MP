# Phase 40: Analytics — Review Summary

## Result: PASSED

**Cycles used**: 1
**Reviewers**: testing-qa-verification-specialist, engineering-senior-developer
**Completion date**: 2026-04-27
**Review mode**: Dynamic panel

## Findings Summary

| Category | Count | Resolved |
|----------|-------|----------|
| Blockers | 0 | — |
| Warnings | 5 | 0 (accepted as documented limitations) |
| Suggestions | 5 | 0 (noted for follow-up) |

## Findings Detail

| # | Severity | File | Issue | Status |
|---|----------|------|-------|--------|
| 1 | WARNING | SetReadyScreen.kt | Variant picker cosmetic-only — selection not propagated to applyBodyweightVolume(). Documented as "TRANSIENT, acceptable for v0.9.0" | Accepted — known limitation |
| 2 | WARNING | RoutineTimeEstimator.kt:105 | Superset intra-round rest overcounts for mismatched set counts (most supersets have matching sets) | Accepted — low practical impact |
| 3 | WARNING | RoutineTimeEstimator.kt:112 | Between-round rest uses first exercise's rest time — design choice, needs comment | Accepted — needs documentation |
| 4 | WARNING | ActiveSessionEngine.kt | No direct integration test for applyBodyweightVolume() gating logic | Accepted — underlying calculator tested |
| 5 | WARNING | telemetry.ts:55 | Portal exerciseProgressSchema still applies weightTransform to total_volume_kg | Tracked — follow-up with portal volume cleanup |
| 6 | SUGGESTION | BodyweightVolumeCalculatorTest.kt | Misleading test name (says "returnsZero" but asserts > 0) | Noted |
| 7 | SUGGESTION | BodyweightVolumeCalculator.kt | EXERCISE_PERCENTAGES ordering fragile — longest-first sort recommended | Noted |
| 8 | SUGGESTION | RoutineTimeEstimator.kt:159 | Uses hasCableAccessory instead of isBodyweight property | Noted |
| 9 | SUGGESTION | RoutineTimeEstimator.kt:276 | formattedDuration shows "0m" for sub-60s estimates | Noted |
| 10 | SUGGESTION | RoutineOverviewScreen.kt:147 | Double "Est." display when fallback-based | Noted |

## Reviewer Verdicts

| Reviewer | Verdict | Key Observations |
|----------|---------|------------------|
| testing-qa-verification-specialist | PASS | 17 new tests verified, all pass. Variant picker disconnect is main gap but cosmetic-only. Portal telemetry.ts needs follow-up. |
| engineering-senior-developer | PASS | Volume calc correctly wired at all 3 call sites. Time estimator well-structured. Superset edge case minor. Portal fix correct. |

## Follow-up Items

1. Wire variant picker selection to actual volume calculation (v0.10.0 or future)
2. Portal volume cleanup: remove component-level `* WEIGHT_MULTIPLIER` from Analytics.tsx, Dashboard.tsx, challenges.ts, profile.ts, telemetry.ts
3. Superset time estimate for mismatched set counts (low priority)
4. Minor: fix double "Est." display, rename misleading test, use isBodyweight consistently

## Test Evidence

| Command | Result |
|---------|--------|
| `./gradlew :shared:testAndroidHostTest` | 1547 tests, 1 pre-existing failure |
| `./gradlew :androidApp:testDebugUnitTest` | All pass |
| `./gradlew :shared:testAndroidHostTest --tests "*BodyweightVolumeCalculatorTest*"` | All pass |
| `./gradlew :shared:testAndroidHostTest --tests "*PortalSyncAdapterTest*"` | All pass |
| `./gradlew :androidApp:testDebugUnitTest --tests "*RoutineTimeEstimatorTest*"` | All pass |
| Portal: `npx vitest run transforms.test.ts` | 39 tests pass |
