# Session State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-27)

**Core value:** Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking -- reliably, on both platforms.
**Current focus:** Phase 16 — Foundation & Board Conditions

## Position

**Milestone:** v0.5.1 Board Polish & Premium UI
**Current phase:** Phase 16 of 22 (Foundation & Board Conditions)
**Current Plan:** 2 of 2 (Phase 16 complete)
**Status:** Phase complete
**Last activity:** 2026-02-27 -- Plan 16-01 executed (FeatureGate entries, version bump, timezone fix)

Progress: [=░░░░░░░░░] 14%

## Performance Metrics

**Velocity:**
- Total plans completed: 51 (across v0.4.1 through v0.5.0)
- v0.5.1 plans completed: 2

**By Milestone:**

| Milestone | Phases | Plans |
|-----------|--------|-------|
| v0.4.1 | 4 | 10 |
| v0.4.5 | 5 | 11 |
| v0.4.6 | 3 | 10 |
| v0.4.7 | 4 | 13 |
| v0.5.0 | 3 | 7 |

## Accumulated Context

### Decisions

- (v0.5.0) MediaPipe CPU delegate only, 100ms throttle, 160x120dp PiP
- (v0.5.0) iOS stub provides no-op actual -- Form Check deferred to v0.6.0+
- (v0.5.1) RPG attributes get dedicated table (not GamificationStats singleton) -- schema v17
- (v0.5.1) Ghost racing syncs on rep index, not wall-clock -- pre-load ghost session to memory
- (v0.5.1) CV Form Check UX split from Ghost Racing into separate phase (independent concerns)
- (v0.5.1) Injectable TimeZone parameter pattern for timezone-dependent functions (default to system, explicit for tests)
- (v0.5.1) Targeted backup exclusion (allowBackup=true + specific file exclusions) rather than blanket disable
- (v0.5.1) Always-show camera rationale with on-device guarantee before permission request

### Blockers/Concerns

- Ghost racing modifies WorkoutCoordinator + ActiveSessionEngine + WorkoutHud -- must come after HUD customization stabilizes (Phase 18 before Phase 22)
- RPG schema migration v17 requires iOS DriverFactory.ios.kt CURRENT_SCHEMA_VERSION sync
- ELITE tier 50Hz ghost overlay may need deferral to v0.5.2 if mid-range device performance is insufficient
- Board of Directors review can be triggered at any point -- Phase 16 must complete first

## Session Log

- 2026-02-27: v0.5.0 shipped (Phases 13-15)
- 2026-02-27: v0.5.1 milestone started, requirements defined (26 total)
- 2026-02-27: Research completed, roadmap created (7 phases: 16-22)
- 2026-02-27: Phase 16 Plan 02 executed (backup exclusion, camera rationale, error handling)
- 2026-02-27: Phase 16 Plan 01 executed (FeatureGate v0.5.1 entries, version bump, UTC timezone fix)
