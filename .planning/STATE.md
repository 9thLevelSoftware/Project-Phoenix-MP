---
gsd_state_version: 1.0
milestone: v0.6.0
milestone_name: Portal Sync Compatibility
status: in_progress
last_updated: "2026-03-02T20:22:44.723Z"
progress:
  total_phases: 6
  completed_phases: 0
  total_plans: 3
  completed_plans: 3
---

# GSD State: Project Phoenix MP

## Current Position

Phase: 25 — Edge Functions
Plan: 02 of 02 — all plans executed
Status: Phase 25 complete — both Edge Functions created in phoenix-portal
Last activity: 2026-03-02 — Phase 25 execution complete

Progress: [#####.....] ~50% — Phase 23-25 complete (v0.6.0)

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-02)

**Core value:** Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking — reliably, on both platforms.
**Current focus:** v0.6.0 Portal Sync Compatibility — Phase 26: Mobile Push Wire-Up

## Performance Metrics

| Milestone | Phases | Plans | Velocity |
|-----------|--------|-------|----------|
| v0.5.1 (last shipped) | 7 | 16 | 1 day |
| v0.6.0 (current) | 6 | TBD | — |
| Phase 23-portal-db-foundation-rls P03 | 2 | 1 tasks | 1 files |
| Phase 23-portal-db-foundation-rls P01 | 2 | 1 tasks | 1 files |

## Accumulated Context

### From v0.5.1
- Portal sync adapter added (commit 3737fa73) with correct hierarchy, unit conversions, cable mapping
- PortalSyncDtos.kt and PortalSyncAdapter.kt exist but are NOT wired into SyncManager
- SyncManager.kt still uses legacy SyncPushRequest with flat WorkoutSessionSyncDto
- Portal repo: `C:\Users\dasbl\WebstormProjects\phoenix-portal`

### Cross-Repo Coordination
- Mobile changes: SyncManager rewiring, DTO additions, auth migration (Project-Phoenix-MP, branch: new_ideas)
- Portal changes: Edge Functions, DB migrations, table additions, mode display mapping (phoenix-portal, branch: main)
- Each phase labels its target repo clearly

### v0.6.0 Architectural Decisions
- Supabase GoTrue REST API via raw Ktor HTTP (NO supabase-kt — version conflict with Kotlin 2.3.0)
- user_id injected server-side from JWT in Edge Functions (never trusted from DTO body)
- Rep telemetry EXCLUDED from main sync payload — deferred to v0.6.1 chunked upload path
- Auth phase (24) is self-contained: can be tested against Supabase Auth REST without Edge Functions deployed
- Phase ordering is strict: 23 (schema) → 24 (auth) can run in parallel → 25 (edge fns, needs 23) → 26 (push, needs 24+25) → 27 (pull, needs 25+26) → 28 (validation)
- Wire format (SCREAMING_SNAKE) is the canonical DB storage format for mode strings — display names are portal UI only via transforms.ts workoutModeMap (Phase 23-02)
- POWER and CLASSIC mode values are not official ProgramMode enum values — both map to OLD_SCHOOL (Phase 23-02)
- All RLS policies must use (select auth.uid()) not bare auth.uid() — Supabase lint 0003, ~20x perf (Phase 23-03)
- CREATE-before-DROP policy replacement: create with distinct name first for zero security gap (Phase 23-03)
- superset_id and routine_session_id are TEXT columns (not UUID) — mobile DTOs use String? (Phase 23-03)

### Critical Pitfalls to Remember
- Legacy SyncPushRequest cutover must be atomic: URL change + format change in ONE commit (Phase 26)
- INSERT RLS policies must be confirmed on exercises/sets/rep_summaries/rep_telemetry before Edge Function writes (Phase 23)
- Pull sync merge strategy must be defined BEFORE any merge code is written (Phase 27)
- service_role key: Edge Functions only, never in mobile BuildConfig (mobile uses anon key only)

### Open Research Flags
- updated_at on workout_sessions in portal schema: not present in base schema; may need 4th migration or simplified merge semantics for sessions — clarify before Phase 27
- exercises table INSERT RLS: service_role bypasses RLS so may not block push; confirm before Phase 25
- Supabase anon key + project URL injection mechanism via local.properties/buildConfigField — confirm before Phase 24

## Todos

- [ ] Plan Phase 23 (Portal DB Foundation + RLS) — run /gsd:plan-phase 23
- [ ] Confirm whether exercises table needs an INSERT RLS policy (service_role bypasses RLS)
- [ ] Confirm updated_at presence on portal workout_sessions table before Phase 27

## Blockers

- Pre-commit hook blocks commits: daem0nmcp Python module not installed in pythoncore-3.14-64. Hook calls `python -m daem0nmcp.cli`. Install daem0nmcp in that Python env or fix hook Python path.

---
*Last updated: 2026-03-02 — Phase 25 complete: mobile-sync-push + mobile-sync-pull Edge Functions created in phoenix-portal*
