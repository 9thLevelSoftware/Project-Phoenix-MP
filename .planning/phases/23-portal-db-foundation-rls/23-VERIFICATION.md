---
phase: 23-portal-db-foundation-rls
verified: 2026-03-02T21:00:00Z
status: human_needed
score: 7/7 automated must-haves verified
human_verification:
  - test: "Apply all 3 migration files via supabase db push against the production project"
    expected: "All 3 files apply with no errors and no data loss on existing rows"
    why_human: "Cannot verify live database execution programmatically — requires real Supabase CLI run against production project"
  - test: "Confirm rpg_attributes, earned_badges, gamification_stats tables visible in Supabase dashboard with correct columns and RLS policies"
    expected: "All 3 tables shown, RLS enabled, 9 replacement policies with (select auth.uid()) visible in Auth > Policies panel"
    why_human: "Supabase dashboard state cannot be verified from migration files alone — requires live dashboard inspection"
  - test: "Confirm exercises, sets, rep_summaries, rep_telemetry INSERT policies visible in Supabase Auth > Policies panel"
    expected: "4 INSERT WITH CHECK policies present, each showing 'TO authenticated' and '(select auth.uid()) = user_id'"
    why_human: "Policy visibility in the dashboard requires live database state inspection"
---

# Phase 23: Portal DB Foundation + RLS Verification Report

**Phase Goal:** Portal database has the complete schema required for v0.6.0 data model, with INSERT RLS policies on all tables that Edge Functions will write to
**Verified:** 2026-03-02T21:00:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| #  | Truth | Status | Evidence |
|----|-------|--------|----------|
| 1  | exercises.user_id UUID NOT NULL column exists with no FK constraint | VERIFIED | Line 42: `ALTER TABLE exercises ADD COLUMN IF NOT EXISTS user_id UUID;` — no REFERENCES clause. Line 52: `ALTER TABLE exercises ALTER COLUMN user_id SET NOT NULL;` |
| 2  | exercises INSERT WITH CHECK policy uses (select auth.uid()) = user_id TO authenticated | VERIFIED | Lines 72-75 of Plan 01 migration: `CREATE POLICY "Users can insert own exercises" ON exercises FOR INSERT TO authenticated WITH CHECK ((select auth.uid()) = user_id);` |
| 3  | exercises SELECT policy replaced from multi-hop subquery to direct user_id equality | VERIFIED | Plan 01 creates "Users can view own exercises" with direct equality, then `DROP POLICY IF EXISTS "Users can view exercises in own sessions" ON exercises;` (line 66) |
| 4  | sets, rep_summaries, rep_telemetry each have INSERT WITH CHECK policies using (select auth.uid()) TO authenticated | VERIFIED | Lines 84-99 of Plan 01: three distinct CREATE POLICY FOR INSERT statements, all using `(select auth.uid()) = user_id` and `TO authenticated` |
| 5  | All 9 gamification RLS policies use (select auth.uid()) wrapper instead of bare auth.uid() | VERIFIED | Plan 03 has 9 CREATE POLICY statements (3 per table × 3 tables), each with `(select auth.uid()) = user_id`. Zero bare `auth.uid()` calls outside comments confirmed by grep. |
| 6  | routine_exercises.superset_id is TEXT type; workout_sessions.routine_session_id is TEXT type | VERIFIED | Plan 03 lines 105-108: `ALTER TABLE routine_exercises ALTER COLUMN superset_id TYPE TEXT;` and `ALTER TABLE workout_sessions ALTER COLUMN routine_session_id TYPE TEXT;` |
| 7  | routine_exercises has all 12 advanced columns present as nullable (no migration failures structurally) | VERIFIED | `20260302_sync_compat_superset_perset.sql` adds all 12 columns using `ADD COLUMN IF NOT EXISTS`: superset_id, superset_color, superset_order, per_set_weights, per_set_rest, is_amrap, pr_percentage, rep_count_timing, stop_at_position, stall_detection, eccentric_load, echo_level |

**Score:** 7/7 automated truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `supabase/migrations/20260304_exercises_denorm_insert_rls.sql` | exercises.user_id denorm + INSERT RLS on 4 tables | VERIFIED | Exists, 108 lines, 5 CREATE POLICY, BEGIN/COMMIT, no bare auth.uid() |
| `supabase/migrations/20260304_mode_wire_format_migration.sql` | Data migration: display names → SCREAMING_SNAKE | VERIFIED | Exists, 62 lines, 22 UPDATE statements, SET DEFAULT 'OLD_SCHOOL', BEGIN/COMMIT |
| `supabase/migrations/20260304_sync_compat_quality_fixes.sql` | Quality fixes: 9 policy replacements + 2 column type changes | VERIFIED | Exists, 110 lines, 9 CREATE POLICY, 9 DROP POLICY, 2 TYPE TEXT alterations, BEGIN/COMMIT |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `20260304_exercises_denorm_insert_rls.sql` | `20260228_rls_denormalization.sql` | Follows same denormalization pattern (nullable → backfill → NOT NULL) | WIRED | Pattern match confirmed: `ADD COLUMN IF NOT EXISTS user_id UUID` (no FK), 3-step pattern present |
| `20260304_exercises_denorm_insert_rls.sql` | `00002_base_schema.sql` | Replaces multi-hop SELECT policy | WIRED | `DROP POLICY IF EXISTS "Users can view exercises in own sessions" ON exercises;` present at line 66 |
| `20260304_sync_compat_quality_fixes.sql` | `20260302_sync_compat_rpg_gamification.sql` | Replaces 9 bare auth.uid() policies | WIRED | 9 DROP POLICY statements match the exact policy names from the original migration; CREATE-before-DROP pattern confirmed |
| `20260304_sync_compat_quality_fixes.sql` | `20260302_sync_compat_superset_perset.sql` | Fixes superset_id and routine_session_id UUID→TEXT | WIRED | Both `ALTER COLUMN superset_id TYPE TEXT` and `ALTER COLUMN routine_session_id TYPE TEXT` present |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| PORTAL-01 | Plan 03 | rpg_attributes, earned_badges, gamification_stats tables exist with proper RLS policies | SATISFIED | Tables created in `20260302_sync_compat_rpg_gamification.sql`; all 9 policies replaced with (select auth.uid()) + TO authenticated in `20260304_sync_compat_quality_fixes.sql` |
| PORTAL-02 | Plans 02, 03 | routine_exercises table includes 12 advanced columns | SATISFIED | All 12 columns present in `20260302_sync_compat_superset_perset.sql`; superset_id type corrected UUID→TEXT in Plan 03 |
| PORTAL-03 | Plan 01 | INSERT WITH CHECK RLS policies on exercises, sets, rep_summaries, rep_telemetry | SATISFIED | 4 INSERT policies created in `20260304_exercises_denorm_insert_rls.sql`, all using (select auth.uid()) and TO authenticated |

No orphaned requirements: REQUIREMENTS.md maps PORTAL-01, PORTAL-02, PORTAL-03 all to Phase 23, and all three are claimed and fulfilled by the plans.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `20260304_sync_compat_quality_fixes.sql` | 9, 29 | Comments reference "RPG" (uppercase) in description text | Info | No functional impact — only in comment lines, not policy SQL |

No blockers or warnings. No TODO/FIXME/placeholder comments. No empty implementations. All migrations are proper DDL/DML, not stubs.

**Plan 03 (select auth.uid()) count note:** `grep -c` returns 10, but line 7 is a header comment and line 9 is also a comment. Functional policy body uses are exactly 9 — one per CREATE POLICY statement. Verified via line-by-line inspection.

**Plan 01 (select auth.uid()) count note:** `grep -c` returns 6, but line 21 is a comment. Functional uses are exactly 5 — one per CREATE POLICY statement.

### Human Verification Required

#### 1. supabase db push execution

**Test:** Run `supabase db push` against the production Supabase project from the phoenix-portal directory.
**Expected:** All 3 migration files (`20260304_exercises_denorm_insert_rls.sql`, `20260304_mode_wire_format_migration.sql`, `20260304_sync_compat_quality_fixes.sql`) apply without errors. Existing rows in exercises, workout_sessions, routine_exercises, sets, rep_summaries, rep_telemetry, rpg_attributes, earned_badges, and gamification_stats are preserved.
**Why human:** Requires a real Supabase CLI connection and production credentials. Static analysis of migration SQL cannot verify runtime execution, backfill correctness on real data, or transaction rollback behavior.

#### 2. Supabase dashboard — gamification tables + RLS policies

**Test:** Open Supabase dashboard → Table Editor and Auth → Policies panel for rpg_attributes, earned_badges, gamification_stats.
**Expected:** Three tables visible. Each shows the new policy names ("Users can view own rpg attributes", "Users can insert rpg attributes", "Users can update rpg attributes", etc.). No old "RPG" (uppercase) policy names remain. All show (select auth.uid()) in the policy expression.
**Why human:** Dashboard state reflects what was actually applied to the live database, which requires visual inspection.

#### 3. Supabase dashboard — INSERT policies on sync target tables

**Test:** Open Supabase dashboard → Auth → Policies for exercises, sets, rep_summaries, rep_telemetry.
**Expected:** Each table shows an INSERT policy with "authenticated" role and `(select auth.uid()) = user_id` as the WITH CHECK expression. exercises also shows the new SELECT policy using direct user_id equality.
**Why human:** Requires live database inspection to confirm policies were applied and are active.

### Gaps Summary

No automated gaps found. All 7 observable truths are verified against the actual SQL in the migration files. The three migration files are substantive (not stubs), properly structured with BEGIN/COMMIT transactions, and correctly wired to the prior migrations they reference or amend.

The only items pending are human verification of live database state after `supabase db push` — which is Success Criterion 4 from the ROADMAP and cannot be verified without actually running the migrations against the production Supabase project.

---

## Detailed Artifact Evidence

### Plan 01: `20260304_exercises_denorm_insert_rls.sql`

- **Line 42:** `ALTER TABLE exercises ADD COLUMN IF NOT EXISTS user_id UUID;` — no FK, matches CONTEXT.md Decision 1
- **Lines 45-49:** Backfill UPDATE from workout_sessions via session_id FK
- **Line 52:** `ALTER TABLE exercises ALTER COLUMN user_id SET NOT NULL;`
- **Lines 61-64:** New SELECT policy with `(select auth.uid())` wrapper
- **Line 66:** `DROP POLICY IF EXISTS "Users can view exercises in own sessions" ON exercises;` — old multi-hop policy removed after new one created
- **Lines 72-99:** 4 INSERT WITH CHECK policies on exercises, sets, rep_summaries, rep_telemetry, all with `TO authenticated` and `(select auth.uid())`
- **Line 105:** `CREATE INDEX IF NOT EXISTS idx_exercises_user_id ON exercises(user_id);`

### Plan 02: `20260304_mode_wire_format_migration.sql`

- **Lines 23-35:** 11 UPDATE statements for routine_exercises.mode covering all 6 official modes + CLASSIC/POWER cleanup
- **Line 42:** `ALTER TABLE routine_exercises ALTER COLUMN mode SET DEFAULT 'OLD_SCHOOL';`
- **Lines 49-60:** 11 UPDATE statements for workout_sessions.workout_mode with identical coverage
- All 6 official modes covered: OLD_SCHOOL, PUMP, TUT, TUT_BEAST, ECCENTRIC_ONLY, ECHO
- Both case variants of ambiguous names included (e.g., 'Tut Beast' AND 'TUT Beast')

### Plan 03: `20260304_sync_compat_quality_fixes.sql`

- **Sections 1-3:** 9 CREATE POLICY + 9 DROP POLICY IF EXISTS (3 tables × 3 policies each)
- **Policy naming strategy:** Old names used "RPG"/"badges"/"gamification stats"; new names use "rpg"/"earned badges"/"gamification stats" — distinct names prevent collision during CREATE-before-DROP
- **Line 105:** `ALTER TABLE routine_exercises ALTER COLUMN superset_id TYPE TEXT;`
- **Line 108:** `ALTER TABLE workout_sessions ALTER COLUMN routine_session_id TYPE TEXT;`
- No bare `auth.uid()` in any functional SQL line

---

_Verified: 2026-03-02T21:00:00Z_
_Verifier: Claude (gsd-verifier)_
