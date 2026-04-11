---
phase: 23-portal-db-foundation-rls
plan: 02
subsystem: database
tags: [supabase, postgres, migration, workout-modes, wire-format, screaming-snake]

# Dependency graph
requires: []
provides:
  - "SQL migration standardizing routine_exercises.mode and workout_sessions.workout_mode to SCREAMING_SNAKE wire format"
  - "routine_exercises.mode DEFAULT changed from 'Old School' to 'OLD_SCHOOL'"
  - "Stale CLASSIC and POWER values cleaned up to OLD_SCHOOL"
affects: [23-portal-db-foundation-rls, 25-edge-functions, 26-push-sync, 27-pull-sync]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Wire-format canonical storage: mode strings stored as SCREAMING_SNAKE in DB, transformed to display names by portal UI (transforms.ts workoutModeMap)"
    - "BEGIN/COMMIT transaction wrapping for all Phoenix Portal migrations"

key-files:
  created:
    - "C:/Users/dasbl/AndroidStudioProjects/phoenix-portal/supabase/migrations/20260304_mode_wire_format_migration.sql"
  modified: []

key-decisions:
  - "Wire format (SCREAMING_SNAKE) is the canonical storage format — display names only exist in UI layer via workoutModeMap"
  - "POWER and CLASSIC are not official modes — both mapped to OLD_SCHOOL as closest equivalent"
  - "Both case variants of display names included (e.g., 'Tut Beast' AND 'TUT Beast') to catch all data inconsistencies"
  - "TypeScript/portal UI not modified — transforms.ts already handles wire→display mapping correctly"

patterns-established:
  - "Migration style: BEGIN/COMMIT transaction, = separator section headers, comment explaining rationale per section"

requirements-completed: [PORTAL-02]

# Metrics
duration: 2min
completed: 2026-03-02
---

# Phase 23 Plan 02: Mode Wire Format Migration Summary

**SQL migration converting all mode strings in routine_exercises.mode and workout_sessions.workout_mode from display-name format (Old School, Pump, Echo) to SCREAMING_SNAKE wire format (OLD_SCHOOL, PUMP, ECHO) with DEFAULT fix and stale CLASSIC/POWER cleanup**

## Performance

- **Duration:** 2 min
- **Started:** 2026-03-02T20:15:29Z
- **Completed:** 2026-03-02T20:16:48Z
- **Tasks:** 1 of 1
- **Files modified:** 1

## Accomplishments

- Created migration file covering all 6 official mode conversions (OLD_SCHOOL, PUMP, TUT, TUT_BEAST, ECCENTRIC_ONLY, ECHO) for both tables
- Fixed routine_exercises.mode DEFAULT from 'Old School' (display name) to 'OLD_SCHOOL' (wire format)
- Cleaned up stale CLASSIC and POWER values (not official modes) by mapping them to OLD_SCHOOL
- Included both case variants of ambiguous display names (e.g., 'Tut Beast' and 'TUT Beast') for thorough data cleanup

## Task Commits

Each task was committed atomically:

1. **Task 1: Create mode string wire format migration file** - `9e8718f` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified

- `supabase/migrations/20260304_mode_wire_format_migration.sql` (phoenix-portal repo) — Migration standardizing mode strings to SCREAMING_SNAKE wire format across routine_exercises.mode and workout_sessions.workout_mode

## Decisions Made

- Wire format (SCREAMING_SNAKE) is the canonical DB storage format per CONTEXT.md Decision 2. Display-name rendering is exclusively the portal UI's responsibility via transforms.ts workoutModeMap.
- POWER and CLASSIC are not in the official 6-mode ProgramMode enum. Mapped to OLD_SCHOOL as the closest equivalent to avoid data loss.
- Both case variants of display names included (e.g., 'Tut Beast' and 'TUT Beast') to be defensive against inconsistent historical data from portal UI writes.
- No TypeScript files were modified — transforms.ts already correctly maps wire→display names, so no portal UI changes are needed.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Migration file is ready to apply to the Supabase database. Running `supabase db push` or applying through the Supabase dashboard will execute the migration.
- After migration runs, all mode strings in both tables will be in SCREAMING_SNAKE format, ensuring mobile push sync payloads (which always send wire format) match what's in the database with no translation required.
- Phase 25 (Edge Functions) can safely assume canonical wire format in DB when reading/writing workout mode values.

## Self-Check: PASSED

- FOUND: `C:/Users/dasbl/AndroidStudioProjects/phoenix-portal/supabase/migrations/20260304_mode_wire_format_migration.sql`
- FOUND: `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/.planning/phases/23-portal-db-foundation-rls/23-02-SUMMARY.md`
- FOUND: commit `9e8718f` (feat(23-02): add mode wire format migration for routine_exercises and workout_sessions)

---
*Phase: 23-portal-db-foundation-rls*
*Completed: 2026-03-02*
