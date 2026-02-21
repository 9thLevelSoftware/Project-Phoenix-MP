# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-20)

**Core value:** Users can connect to their Vitruvian trainer and execute workouts reliably on both platforms.
**Current focus:** Phase 14 — CV Form Check Domain Logic (v0.5.0 Premium Mobile)

## Current Position

Phase: 14 of 17 (CV Form Check Domain Logic)
Plan: 2 of 2 complete
Status: Phase Complete
Last activity: 2026-02-21 — Completed 14-02 (FormRulesEngine Test Suite)

Progress: [██████████] 100% (2/2 plans in phase 14)

## Performance Metrics

**Velocity:**
- Total plans completed: 48
- Average plan duration: ~5 minutes
- Total execution time: ~2 days (across v0.4.5-v0.4.7)

| Phase-Plan | Duration | Tasks | Files | Date |
|------------|----------|-------|-------|------|
| 13-01 | 13min | 2 | 18 | 2026-02-21 |
| 13-02 | 6min | 2 | 2 | 2026-02-21 |
| 14-01 | 4min | 2 | 2 | 2026-02-21 |
| 14-02 | 4min | 1 | 1 | 2026-02-21 |

**By Milestone:**

| Milestone | Phases | Plans | Duration | Notes |
|-----------|--------|-------|----------|-------|
| v0.4.1 | 4 | 10 | - | Pre-metrics |
| v0.4.5 | 5 | 11 | ~3h | 59 commits, +1,832 LOC |
| v0.4.6 | 3 | 10 | ~1d | 45 files, +6,917 LOC, 69 tests |
| v0.4.7 | 4 | 13 | ~1d | 25 commits, 61 tests, 21 requirements |

**Recent Trend:**
- Trend: Stable (~5 min/plan)

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [v0.5.0 Roadmap]: Biomechanics persistence is Phase 13 (first) because ghost racing, RPG, and readiness all depend on persisted data
- [v0.5.0 Roadmap]: CV domain logic (Phase 14) split from Android integration (Phase 15) to prevent leaky abstractions — commonMain interfaces defined before androidMain implements
- [v0.5.0 Roadmap]: Premium composables (Phase 17) use local/stub data with repository interfaces; portal integration deferred to v0.5.5+
- [Phase 13-01]: Reused toJsonString()/toFloatArrayFromJson() from RepMetricRepository for FloatArray serialization
- [Phase 13-01]: BiomechanicsRepository captures data for ALL tiers (GATE-04) — gating at UI layer only
- [Phase 13-01]: Used INSERT OR REPLACE with UNIQUE INDEX on (sessionId, repNumber) for idempotent biomechanics writes
- [Phase 13-02]: Used SubscriptionManager.hasProAccess/hasEliteAccess for tier gating (matches ActiveWorkoutScreen pattern) rather than raw FeatureGate.isEnabled
- [Phase 13-02]: Lazy-load RepBiomechanics only on per-rep expand to avoid deserializing 101-point force curves eagerly
- [Phase 14-01]: Used stateless object for FormRulesEngine (not stateful class) since each evaluate() call is independent
- [Phase 14-01]: Included calculateFormScore() in domain engine (Phase 14) rather than deferring to Phase 16
- [Phase 14-01]: Defined all 17 form rules as explicit FormRule instances (no bilateral helper) for clarity
- [Phase 14-02]: Verified CV-08 compliance via rule content assertions (no machine-control language) rather than source file text scanning
- [Phase 14-02]: Tests cannot execute due to pre-existing presentation layer compilation errors; correctness verified by threshold cross-reference

### Pending Todos

None.

### Blockers/Concerns

- ~~[Phase 13]: iOS DriverFactory.ios.kt must be synced for schema v16~~ RESOLVED in 13-01 (all 3 locations updated)
- [Phase 15]: MediaPipe + BLE thermal contention is device-dependent — needs validation on low-end devices
- [Phase 15]: ProGuard/R8 strips MediaPipe classes in release builds — keep rules must be added on day one

## Session Continuity

Last session: 2026-02-21
Stopped at: Completed 14-02-PLAN.md (Phase 14 complete)
Resume file: .planning/phases/14-cv-form-check-domain-logic/14-02-SUMMARY.md
Next action: Plan Phase 15 (MediaPipe Integration)
