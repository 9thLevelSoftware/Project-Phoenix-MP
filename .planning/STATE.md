---
gsd_state_version: 1.0
milestone: v0.9.0
milestone_name: Enhancement Sweep
status: executing
last_updated: "2026-04-27T22:00:00.000Z"
progress:
  total_phases: 8
  completed_phases: 6
  total_plans: 21
  completed_plans: 19
---

# GSD State: Project Phoenix MP

## Current Position

Phase: 42 of 44 (complete)
Plan: 3/3 complete
Status: Phase 42 complete — review passed (1 cycle)
Last activity: 2026-04-27 — Phase 42 review passed

## Progress
```
[##################  ] 90% — 19/21 plans complete
```

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-21)

**Core value:** Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking — reliably, on both platforms.
**Current milestone:** v0.9.0 Enhancement Sweep
**Branch:** main

## Workflow Preferences

| Setting | Choice |
|---------|--------|
| Execution | Guided (plan approval required) |
| Depth | Deep Analysis (full spec per issue) |
| Cost | Premium (max parallelization) |

## v0.9.0 Phase Overview

| Phase | Name | Issues | Plans | Status |
|-------|------|--------|-------|--------|
| 37 | Foundation | #323 | 2 | Complete |
| 38 | Weight-Dependent | #266, #337 | 3 | Complete |
| 39 | Routine Cluster | #365, #307 | 3 | Complete |
| 40 | Analytics | #229, #225 | 3 | Complete |
| 41 | Quick Wins | #190, #228, #100 | 2 | Complete |
| 42 | Platform | #363 | 3 | Complete |
| 43 | Advanced VBT | #313 | 3 | Pending |
| 44 | Integration Validation | — | 2 | Pending |

## Dependency Map

```
#323 (Foundation) ──blocks──▶ #266, #337 (Weight-Dependent)
                              └──▶ Routine Cluster (#365, #307, #337)

Quick Wins (#190, #228, #100) ── independent
Platform (#363) ── independent
Analytics (#229, #225) ── needs body weight settings
Advanced (#313) ── uses biomechanics engine (exists)
```

## Performance Metrics

| Milestone | Phases | Plans | Velocity |
|-----------|--------|-------|----------|
| v0.5.1 | 7 | 16 | 1 day |
| v0.6.0 | 6 | 13 | 1 day |
| v0.7.0 | 3 | 8 | 1 day |
| v0.8.0 | 5 | 15 | 2 days |

## Completed Milestones

| Milestone | Shipped | Summary |
|-----------|---------|---------|
| v0.8.0 Beta Readiness | 2026-03-24 | 29 audit findings fixed (BLE, Sync, Lifecycle, iOS) |
| v0.7.0 MVP Cloud Sync | 2026-03-15 | Cloud sync UI + iOS launch |
| v0.6.0 Portal Sync | 2026-03-02 | Bidirectional Supabase sync |

## Phase 40 Results

- Plan 40-01 (Wave 1): Bodyweight Volume Integration — Complete. BodyweightVolumeCalculator wired at 3 call sites, Settings body weight input, variant picker, Exercise.isBodyweight migration.
- Plan 40-02 (Wave 1): Historical Time Estimates — Complete. RoutineTimeEstimator fixed (profileId, AMRAP 1.5x, warmup 0.7x, superset-aware, transitions), Koin registered, UI wired. 18 tests pass.
- Plan 40-03 (Wave 2): Integration Tests & Portal Fix — Complete. 17 new tests, portal transforms.ts total_volume fix (cross-repo).

## Phase 39 Results

- Plan 39-01 (Wave 1): Superset Exercise Reorder — Complete. RoutineUtils.kt + nested ReorderableColumn.
- Plan 39-02 (Wave 1): Routine Parent Grouping — Complete. RoutineGroup entity, migration 27, grouped RoutinesTab, backup v3.
- Plan 39-03 (Wave 2): Integration Tests & Regression Guards — Complete. 22 new tests, SchemaManifest updated.

## Known Issues

- SqlDelightTrainingCycleRepositoryTest.checkAndAutoAdvance — flaky (time-dependent, pre-existing)
- PortalPullPaginationTest.pullCapsLargeKnownSessionIdsToMaxParityIds — pre-existing failure, unrelated to Phase 39
- Portal transforms.ts `total_volume` weightTransform — FIXED in 40-03 (schema level). Component-level `* WEIGHT_MULTIPLIER` in Analytics.tsx, Dashboard.tsx, challenges.ts, profile.ts still needs cleanup pass

## GitHub

| Phase | Issue |
|-------|-------|
| 37 | #380 |
| 38 | #382 |
| 39 | #385 |
| 40 | #397 |
| 41 | #398 |
| 42 | #399 |

## Phase 41 Results

- Plan 41-01 (Wave 1): Routine Auto-Start & Timer Controls — Complete. LaunchedEffect redirect with one-shot guard, exercise timer pause/resume/reset (pure state, no BLE), Settings toggle, 13 tests pass.
- Plan 41-02 (Wave 2): Audio Feedback Improvements — Complete. FINAL_REP event with boopbeepbeep sound, REP_COMPLETED switched to chirpchirp (louder), warmup gated by repSoundEnabled, 28 tests pass.

## Phase 42 Results

- Plan 42-01 (Wave 1): BackupDestination Model & Platform Pickers — Complete. BackupDestination sealed class (Default/Custom with bookmarkData), PreferencesManager persistence, BackupLocationPicker expect/actual (Android SAF + iOS UIDocumentPicker with bookmarks).
- Plan 42-02 (Wave 2): UI Integration & Backup Path Routing — Complete. BackupDestinationResolver interface + platform impls, DataBackupManager custom destination routing with fallback, SettingsTab backup location UI.
- Plan 42-03 (Wave 3): Tests & Test Fixtures — Complete. 15 serialization tests, 9 routing tests, FakeBackupDestinationResolver, FakePreferencesManager fix. 24 new tests, 1612 total.

## Next Action

Run `/legion:plan 43` to plan Phase 43: Advanced VBT

---
*Last updated: 2026-04-27 — Phase 42 review passed (1 cycle, 7 warnings fixed)*
