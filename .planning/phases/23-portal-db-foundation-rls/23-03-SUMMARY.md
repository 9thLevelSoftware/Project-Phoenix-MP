---
phase: 23-portal-db-foundation-rls
plan: 03
subsystem: database
tags: [supabase, postgresql, rls, row-level-security, migration, gamification]

# Dependency graph
requires:
  - phase: 23-portal-db-foundation-rls
    provides: "20260302_sync_compat_rpg_gamification.sql and 20260302_sync_compat_superset_perset.sql migrations with bare auth.uid() and UUID type columns to fix"
provides:
  - "20260304_sync_compat_quality_fixes.sql — quality fixes migration replacing 9 bare auth.uid() RLS policies with (select auth.uid()) wrapper on rpg_attributes, earned_badges, gamification_stats"
  - "routine_exercises.superset_id column altered UUID → TEXT"
  - "workout_sessions.routine_session_id column altered UUID → TEXT"
  - "All INSERT RLS policies on gamification tables include TO authenticated role scoping"
affects: [24-auth, 25-edge-functions, 26-push-sync, 27-pull-sync]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "CREATE-before-DROP policy replacement: create new policy with distinct name before dropping old — zero security gap during migration"
    - "(select auth.uid()) wrapper: use subquery form instead of bare function call to enable PostgreSQL initPlan caching (~20x RLS performance)"
    - "TO authenticated on all policies: explicit role scoping for security clarity and lint compliance"

key-files:
  created:
    - supabase/migrations/20260304_sync_compat_quality_fixes.sql (phoenix-portal repo)
  modified: []

key-decisions:
  - "Policy names deliberately differ between old and new (RPG→rpg casing) to enable CREATE-before-DROP without name collision"
  - "UUID→TEXT alteration on superset_id and routine_session_id: mobile DTOs use String? (not UUID format restricted), TEXT is the correct type per CONTEXT.md"
  - "(select auth.uid()) wrapper chosen over bare auth.uid() per Supabase lint rule 0003_auth_rls_initplan and ~20x performance benefit via initPlan caching"

patterns-established:
  - "RLS policy replacement pattern: CREATE new (distinct name) → DROP old — never DROP then CREATE (security gap)"
  - "All RLS policies: use (select auth.uid()) not bare auth.uid()"
  - "All RLS policies: include TO authenticated for role scoping"

requirements-completed: [PORTAL-01, PORTAL-02]

# Metrics
duration: 2min
completed: 2026-03-02
---

# Phase 23 Plan 03: Quality Fixes Migration Summary

**9 gamification RLS policies replaced with (select auth.uid()) + TO authenticated, and superset_id/routine_session_id column types corrected from UUID to TEXT in a single transaction-wrapped migration**

## Performance

- **Duration:** ~2 min
- **Started:** 2026-03-02T20:15:37Z
- **Completed:** 2026-03-02T20:17:32Z
- **Tasks:** 1/1
- **Files modified:** 1 (phoenix-portal repo)

## Accomplishments
- Created `20260304_sync_compat_quality_fixes.sql` fixing Supabase lint 0003_auth_rls_initplan violations on all 9 gamification policies
- All 9 policies (rpg_attributes × 3, earned_badges × 3, gamification_stats × 3) now use `(select auth.uid())` wrapper for ~20x RLS performance via PostgreSQL initPlan caching
- All 9 policies include `TO authenticated` role scoping for security clarity
- `routine_exercises.superset_id` and `workout_sessions.routine_session_id` altered from UUID to TEXT to match mobile DTO String? types
- Zero security gap during migration: CREATE-before-DROP pattern with distinct policy names (RPG→rpg casing)

## Task Commits

Each task was committed atomically:

1. **Task 1: Create quality fixes migration file** - `2e15238` (feat) — committed to phoenix-portal repo (`feat/sync-compat` branch)

## Files Created/Modified
- `supabase/migrations/20260304_sync_compat_quality_fixes.sql` (phoenix-portal repo) — Quality fixes migration: 9 policy replacements with (select auth.uid()) wrapper + 2 column type alterations

## Decisions Made
- Policy names changed from "Users can view own RPG attributes" to "Users can view own rpg attributes" (RPG→rpg casing) to allow CREATE-before-DROP without triggering "policy already exists" errors
- Migration is transaction-wrapped (BEGIN/COMMIT) so all changes are atomic — either all succeed or none apply
- UUID→TEXT uses PostgreSQL's implicit UUID::TEXT cast, so any existing UUID data is preserved without transformation

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required. Migration will be applied via `supabase db push` or through the Supabase dashboard.

## Next Phase Readiness
- All gamification table RLS policies are now Supabase lint-compliant and performant
- superset_id and routine_session_id column types match mobile DTO String? types
- Phase 23 plan 04 (or next plan) can proceed with full confidence in the gamification schema quality

---
*Phase: 23-portal-db-foundation-rls*
*Completed: 2026-03-02*

## Self-Check: PASSED

- FOUND: `supabase/migrations/20260304_sync_compat_quality_fixes.sql` (phoenix-portal repo)
- FOUND: `.planning/phases/23-portal-db-foundation-rls/23-03-SUMMARY.md` (Project-Phoenix-MP repo)
- FOUND: commit `2e15238` — "feat(23-03): add quality fixes migration for sync-compat RLS policies"
