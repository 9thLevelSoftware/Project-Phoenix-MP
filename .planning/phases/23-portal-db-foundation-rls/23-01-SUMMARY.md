---
phase: 23-portal-db-foundation-rls
plan: 01
subsystem: database
tags: [postgres, rls, supabase, migration, denormalization]

# Dependency graph
requires:
  - phase: 20260228_rls_denormalization
    provides: "denormalized user_id on sets, rep_summaries, rep_telemetry with SELECT policies"
provides:
  - "exercises.user_id UUID NOT NULL (no FK) — denormalized from workout_sessions"
  - "exercises SELECT policy replaced with direct user_id equality (no subquery JOIN)"
  - "INSERT WITH CHECK policies on exercises, sets, rep_summaries, rep_telemetry"
  - "idx_exercises_user_id index for RLS performance"
affects: [25-portal-edge-functions, 26-portal-push-sync]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "exercises.user_id denormalization: nullable → backfill → NOT NULL, no FK (matches sets/rep_summaries/rep_telemetry pattern)"
    - "INSERT RLS: WITH CHECK ((select auth.uid()) = user_id) TO authenticated on all 4 sync-target tables"
    - "Policy replacement: CREATE new FIRST, then DROP old (zero security gap)"

key-files:
  created:
    - "supabase/migrations/20260304_exercises_denorm_insert_rls.sql (phoenix-portal)"
  modified: []

key-decisions:
  - "exercises.user_id has NO FK constraint to auth.users — matches existing denorm pattern on sets/rep_summaries/rep_telemetry"
  - "All INSERT policies use (select auth.uid()) wrapper (not bare auth.uid()) for PostgreSQL initPlan caching"
  - "New SELECT policy created BEFORE dropping old one — guarantees zero security gap during migration"

patterns-established:
  - "INSERT RLS pattern: CREATE POLICY ... FOR INSERT TO authenticated WITH CHECK ((select auth.uid()) = user_id)"

requirements-completed: [PORTAL-03]

# Metrics
duration: 2min
completed: 2026-03-02
---

# Phase 23 Plan 01: Portal DB Foundation + RLS Summary

**exercises.user_id denormalization (nullable→backfill→NOT NULL, no FK) + INSERT RLS policies on all 4 sync-target tables using (select auth.uid()) wrapper**

## Performance

- **Duration:** 2 min
- **Started:** 2026-03-02T20:15:42Z
- **Completed:** 2026-03-02T20:17:04Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments
- Added `exercises.user_id UUID NOT NULL` via 3-step migration (ADD nullable, backfill from workout_sessions, SET NOT NULL) with no FK constraint — consistent with existing denorm pattern
- Replaced multi-hop SELECT policy on exercises (`session_id IN (SELECT ... WHERE user_id = auth.uid())`) with direct user_id equality check using `(select auth.uid())` wrapper
- Added INSERT WITH CHECK policies on exercises, sets, rep_summaries, and rep_telemetry — all 4 tables Phase 25 Edge Functions will write to
- Created `idx_exercises_user_id` index for RLS query performance

## Task Commits

Each task was committed atomically:

1. **Task 1: Create exercises denormalization + INSERT RLS migration file** - `5554bf0` (feat) — in phoenix-portal repo (branch: feat/sync-compat)

## Files Created/Modified
- `supabase/migrations/20260304_exercises_denorm_insert_rls.sql` (phoenix-portal) — Single-transaction migration: exercises.user_id denorm + SELECT policy replacement + INSERT policies on 4 tables + index

## Decisions Made
- exercises.user_id has no FK constraint to auth.users — consistent with how sets, rep_summaries, and rep_telemetry have their denormalized user_id (no FK). This was a locked CONTEXT.md decision.
- Used `(select auth.uid())` wrapper on all 5 policies (1 SELECT + 4 INSERT) — enables PostgreSQL initPlan caching for ~20x performance vs bare `auth.uid()` per Supabase RLS performance guide.
- CREATE new SELECT policy BEFORE DROP old one — zero-security-gap policy replacement pattern established in 20260228_rls_denormalization.sql.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required. Migration runs via `supabase db push` or Supabase dashboard migration runner.

## Next Phase Readiness
- Phase 23-02 can proceed (mode string canonicalization migration and/or new gamification tables)
- Phase 25 Edge Functions now have INSERT RLS policies on all 4 target tables (exercises, sets, rep_summaries, rep_telemetry) — defense-in-depth is in place
- exercises.user_id denormalization unblocks future SELECT query optimization (no more subquery JOIN in SELECT policy)

## Self-Check: PASSED

- FOUND: `supabase/migrations/20260304_exercises_denorm_insert_rls.sql` (phoenix-portal)
- FOUND: `.planning/phases/23-portal-db-foundation-rls/23-01-SUMMARY.md`
- FOUND: commit `5554bf0` in phoenix-portal repo

---
*Phase: 23-portal-db-foundation-rls*
*Completed: 2026-03-02*
