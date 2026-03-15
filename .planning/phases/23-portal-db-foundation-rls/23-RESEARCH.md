# Phase 23: Portal DB Foundation + RLS - Research

**Researched:** 2026-03-02
**Domain:** Supabase PostgreSQL migrations, RLS policies, schema evolution
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**1. exercises INSERT RLS: Denormalize user_id**
Add `user_id UUID` column to the `exercises` table. 3-step migration: ADD COLUMN nullable → backfill from `workout_sessions` JOIN → ALTER SET NOT NULL. No FK constraint to `auth.users`. Edge Function sets `user_id` explicitly on INSERT (no database trigger).

**2. Mode String Format: Wire Format Canonical**
Standardize on SCREAMING_SNAKE wire format (`OLD_SCHOOL`, `PUMP`, `TUT`, `TUT_BEAST`, `ECCENTRIC_ONLY`, `ECHO`) as canonical storage format. Migrate existing display-name data in this phase. Portal UI impact (RoutineBuilder.tsx, superset-types.ts) is deferred to Phase 25.

**3. Array Columns: JSONB for Arrays, TEXT for Scalars**
Use `JSONB` for `per_set_weights` and `per_set_rest`. Use `TEXT`/`BOOLEAN`/`INTEGER`/`NUMERIC` for the remaining 10 advanced columns on routine_exercises.

**4. exercises.user_id Backfill Strategy**
Nullable → backfill via `workout_sessions` JOIN → NOT NULL (3-step proven pattern from `20260228`).

### Claude's Discretion

*(No discretion areas defined — all architectural decisions are locked)*

### Deferred Ideas (OUT OF SCOPE)

*(None captured during discussion)*

---

**Scope boundary:** This phase delivers SQL migrations only. No mobile code changes. No portal frontend changes. No Edge Function code.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| PORTAL-01 | rpg_attributes, earned_badges, and gamification_stats tables exist with proper RLS policies | New table CREATE TABLE patterns, user_id singleton/PK patterns, INSERT WITH CHECK RLS syntax |
| PORTAL-02 | routine_exercises table includes 12 advanced columns (superset_id, superset_color, superset_order, per_set_weights, per_set_rest, is_amrap, pr_percentage, rep_count_timing, stop_at_position, stall_detection, eccentric_load, echo_level) | ALTER TABLE ADD COLUMN IF NOT EXISTS pattern, JSONB for arrays, appropriate scalar types |
| PORTAL-03 | INSERT WITH CHECK RLS policies exist on exercises, sets, rep_summaries, and rep_telemetry tables | service_role bypass behavior confirmed, (select auth.uid()) performance pattern, INSERT policy syntax |
</phase_requirements>

---

## Summary

Phase 23 is pure PostgreSQL DDL work inside the phoenix-portal Supabase project (`C:/Users/dasbl/WebstormProjects/phoenix-portal`). There is no mobile code and no portal TypeScript to change. The deliverable is one or more `.sql` migration files in `supabase/migrations/` that: (1) create three new gamification tables with RLS, (2) add 12 advanced columns to `routine_exercises`, (3) add two missing columns to `workout_sessions` and `sets`, (4) denormalize `user_id` onto `exercises` and add its INSERT RLS policy, and (5) add INSERT RLS policies to `sets`, `rep_summaries`, and `rep_telemetry` which already have denormalized `user_id` from migration `20260228`. A mode-string data migration is also required.

The critical clarification from research: **service_role key used by Edge Functions ALWAYS bypasses RLS entirely** — it doesn't evaluate INSERT policies at all. The INSERT policies being added in this phase protect against unauthorized anon/authenticated-role inserts from the PostgREST API, not from Edge Functions. Edge Functions will use service_role and bypass all policies. This means PORTAL-03 is about defense-in-depth (PostgREST API protection) rather than being a prerequisite for Edge Function writes — Edge Functions will write successfully with or without these policies.

**Primary recommendation:** Produce a single transaction-wrapped migration file. Follow `20260228_rls_denormalization.sql` as the style template: BEGIN/COMMIT, section comments, CREATE POLICY before DROP POLICY, index after each policy.

---

## Standard Stack

### Core
| Tool | Version | Purpose | Why Standard |
|------|---------|---------|--------------|
| PostgreSQL (via Supabase) | 15/16 | DDL, RLS, JSONB | Supabase manages the Postgres version |
| Supabase CLI | latest | Migration file management | Standard project tooling already configured |
| SQL migration files | — | All schema changes | Established pattern in this project — every change is a dated .sql file |

### Supporting
| Feature | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `JSONB` | PostgreSQL built-in | Array columns (per_set_weights, per_set_rest) | Any column storing JSON arrays/objects |
| `(select auth.uid())` | Supabase RLS | User identity in policies | ALWAYS — wrapping enables initPlan caching, ~20x perf improvement vs bare `auth.uid()` |
| `IF NOT EXISTS` / `IF EXISTS` | PostgreSQL DDL | Idempotent migrations | All CREATE TABLE, ADD COLUMN, CREATE INDEX, CREATE POLICY, DROP POLICY statements |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| JSONB for per_set_weights/per_set_rest | TEXT[] (native arrays) | JSONB validates JSON structure on INSERT; TEXT[] requires exact type; JSONB is more flexible for future querying; JSONB matches what the mobile DTO sends as a JSON string |
| Single migration file | Multiple migration files | Single file is simpler for a related set of changes; multiple files only needed if changes have different risk profiles or need independent rollback |
| `BEGIN;`/`COMMIT;` wrapper | No transaction | Transaction ensures all-or-nothing — if mode migration UPDATE fails, nothing is applied. Already the pattern in `20260228` and `20260303` |

**Installation:** No new packages. This is raw SQL. Apply via `supabase db push` or through the Supabase dashboard SQL editor.

---

## Architecture Patterns

### Recommended Migration File Structure

One primary migration file for all Phase 23 changes:

```
supabase/migrations/
└── 20260302HHMMSS_portal_db_foundation_rls.sql
```

The filename follows the pattern from the most recent migration (`20260303_revenuecat_schema_migration.sql`): `YYYYMMDD_descriptive_name.sql` (the existing project omits HHmmss — match the existing style, not the Supabase CLI default).

### Pattern 1: INSERT RLS Policy on Denormalized user_id Column

**What:** Add an INSERT policy using WITH CHECK against a denormalized `user_id` column. The `(select auth.uid())` wrapper is mandatory for performance.

**When to use:** Any table where user_id is directly stored (not reached via JOIN).

```sql
-- Source: 20260228_rls_denormalization.sql (project pattern) +
--         https://supabase.com/docs/guides/database/postgres/row-level-security
CREATE POLICY "Users can insert own exercises"
  ON exercises FOR INSERT
  TO authenticated
  WITH CHECK ((select auth.uid()) = user_id);
```

For tables that already have denormalized user_id (sets, rep_summaries, rep_telemetry — from `20260228`):
```sql
-- Same pattern — just FOR INSERT instead of FOR SELECT
CREATE POLICY "Users can insert own sets"
  ON sets FOR INSERT
  TO authenticated
  WITH CHECK ((select auth.uid()) = user_id);

CREATE POLICY "Users can insert own rep summaries"
  ON rep_summaries FOR INSERT
  TO authenticated
  WITH CHECK ((select auth.uid()) = user_id);

CREATE POLICY "Users can insert own telemetry"
  ON rep_telemetry FOR INSERT
  TO authenticated
  WITH CHECK ((select auth.uid()) = user_id);
```

### Pattern 2: 3-Step Nullable → Backfill → NOT NULL Column Addition

**What:** Safely add a NOT NULL column to a populated table by staging the constraint addition.

**When to use:** Any time a non-nullable column is added to a table that may already have rows.

```sql
-- Source: 20260228_rls_denormalization.sql (established project pattern)

-- Step 1: Add as nullable (safe on populated table, no row scan needed)
ALTER TABLE exercises ADD COLUMN IF NOT EXISTS user_id UUID;

-- Step 2: Backfill from parent table
UPDATE exercises e
SET user_id = ws.user_id
FROM workout_sessions ws
WHERE e.session_id = ws.id
  AND e.user_id IS NULL;

-- Step 3: Apply NOT NULL constraint (safe after backfill)
ALTER TABLE exercises ALTER COLUMN user_id SET NOT NULL;

-- Step 4: Index for RLS performance (mandatory after every denormalized user_id)
CREATE INDEX IF NOT EXISTS idx_exercises_user_id ON exercises(user_id);
```

**Critical note:** The `20260228` migration used `REFERENCES auth.users(id) ON DELETE CASCADE` on the FK. CONTEXT.md locks decision 1 as NO FK constraint on exercises.user_id. Use bare `UUID` with no `REFERENCES`.

### Pattern 3: New Table with Singleton-per-user (gamification_stats)

**What:** Table where each user has exactly one row. Enforce via UNIQUE on user_id, not by using user_id as PK (preserves id UUID PK pattern for consistency).

**When to use:** User profile-like tables where one row per user is a business constraint.

```sql
-- Source: Pattern derived from profiles table and DTO schema
CREATE TABLE IF NOT EXISTS gamification_stats (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  -- ... stats columns ...
  UNIQUE(user_id)
);

ALTER TABLE gamification_stats ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can view own gamification stats"
  ON gamification_stats FOR SELECT
  USING ((select auth.uid()) = user_id);

CREATE POLICY "Users can insert own gamification stats"
  ON gamification_stats FOR INSERT
  TO authenticated
  WITH CHECK ((select auth.uid()) = user_id);

CREATE POLICY "Users can update own gamification stats"
  ON gamification_stats FOR UPDATE
  USING ((select auth.uid()) = user_id);

CREATE INDEX IF NOT EXISTS idx_gamification_stats_user_id ON gamification_stats(user_id);
```

**Alternative for rpg_attributes:** Since mobile DTO treats rpg_attributes as a singleton, user_id could be PRIMARY KEY (eliminates id + UNIQUE). However, the base schema pattern always uses `id UUID PRIMARY KEY DEFAULT gen_random_uuid()` with a separate `user_id` FK. Follow the project pattern for consistency.

### Pattern 4: Mode String Data Migration

**What:** UPDATE existing TEXT column data from display names to wire format, then change the DEFAULT.

**When to use:** Whenever canonical format changes for stored data.

```sql
-- Source: CONTEXT.md Decision 2 — locked migration content

-- routine_exercises mode column
UPDATE routine_exercises SET mode = 'OLD_SCHOOL' WHERE mode = 'Old School';
UPDATE routine_exercises SET mode = 'PUMP' WHERE mode = 'Pump';
UPDATE routine_exercises SET mode = 'TUT' WHERE mode = 'TUT';  -- already wire format
UPDATE routine_exercises SET mode = 'TUT_BEAST' WHERE mode = 'Tut Beast';
UPDATE routine_exercises SET mode = 'ECCENTRIC_ONLY' WHERE mode = 'Eccentric Only';
UPDATE routine_exercises SET mode = 'ECHO' WHERE mode = 'Echo';
ALTER TABLE routine_exercises ALTER COLUMN mode SET DEFAULT 'OLD_SCHOOL';

-- workout_sessions workout_mode column
UPDATE workout_sessions SET workout_mode = 'OLD_SCHOOL' WHERE workout_mode = 'Old School';
UPDATE workout_sessions SET workout_mode = 'PUMP' WHERE workout_mode = 'Pump';
UPDATE workout_sessions SET workout_mode = 'TUT' WHERE workout_mode = 'Tut';
UPDATE workout_sessions SET workout_mode = 'TUT_BEAST' WHERE workout_mode = 'Tut Beast';
UPDATE workout_sessions SET workout_mode = 'ECCENTRIC_ONLY' WHERE workout_mode = 'Eccentric Only';
UPDATE workout_sessions SET workout_mode = 'ECHO' WHERE workout_mode = 'Echo';
-- Also clean up stale CLASSIC values (not an official mode — legacy incorrect data)
UPDATE workout_sessions SET workout_mode = 'OLD_SCHOOL' WHERE workout_mode = 'CLASSIC';
```

**Pitfall:** `transforms.ts` has `CLASSIC: "Old School"` — stale incorrect data from old mobile syncs. `CLASSIC` is NOT an official mode name. The official modes are: `OLD_SCHOOL`, `PUMP`, `TUT`, `TUT_BEAST`, `ECCENTRIC_ONLY`, `ECHO`. The workout_sessions table may have `CLASSIC` values that must be corrected to `OLD_SCHOOL` during migration.

### Pattern 5: ADD COLUMN for Optional New Columns

**What:** Add nullable columns to existing tables without backfill needed (they're optional per DTO).

**When to use:** When new columns are nullable with no default needed.

```sql
-- Source: 20260303_revenuecat_schema_migration.sql pattern
-- workout_sessions: add routine_session_id
ALTER TABLE workout_sessions
  ADD COLUMN IF NOT EXISTS routine_session_id TEXT;

-- sets: add workout_mode
ALTER TABLE sets
  ADD COLUMN IF NOT EXISTS workout_mode TEXT;
```

No backfill needed (nullable, existing rows legitimately have no value). No NOT NULL constraint. No default.

### Anti-Patterns to Avoid

- **`auth.uid()` bare in RLS policies:** Always wrap as `(select auth.uid())` — Supabase Security Advisor will flag unwrapped calls (lint rule `0003_auth_rls_initplan`). The existing `20260217` migration uses bare `auth.uid()` in the FOR ALL policy — do not replicate that.
- **DROP POLICY before CREATE POLICY:** Creates a security gap. Always CREATE new policy FIRST, then DROP old one (pattern from `20260228`).
- **Forgetting `ALTER TABLE ... ENABLE ROW LEVEL SECURITY`:** New tables have RLS disabled by default. Missing this means all policies are ignored.
- **Missing index on denormalized user_id:** Every denormalized user_id column needs an index. The RLS policy evaluation hits user_id on every row read — without an index this is a table scan.
- **Not wrapping data migration in transaction:** If the mode UPDATE partially completes and an error occurs, you end up with mixed data. The `BEGIN;`/`COMMIT;` wrapper in the migration file prevents this.
- **FOR ALL policy on routine_exercises:** The existing `20260217` policy uses `FOR ALL` with both USING and WITH CHECK. For INSERT-only needs, use explicit `FOR INSERT` with `WITH CHECK` only — this is cleaner and avoids ambiguity.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| User identity in RLS | Custom user tracking / session tables | `auth.uid()` from Supabase auth | Built into Postgres JWT verification |
| Idempotent column creation | Manual column existence checks | `ADD COLUMN IF NOT EXISTS` | Native PostgreSQL DDL |
| Idempotent table creation | Manual table existence checks | `CREATE TABLE IF NOT EXISTS` | Native PostgreSQL DDL |
| Idempotent index creation | Manual index existence checks | `CREATE INDEX IF NOT EXISTS` | Native PostgreSQL DDL |
| Array storage in PostgreSQL | TEXT with manual parsing | `JSONB` | Validated on INSERT, indexable, queryable |
| Migration atomicity | Manual error handling | `BEGIN;`/`COMMIT;` transaction | All-or-nothing — matches project pattern |

**Key insight:** Every DDL statement in Supabase migrations should use `IF NOT EXISTS` / `IF EXISTS` modifiers. Supabase runs migrations exactly once in order, but during local development `supabase db reset` re-runs all migrations — idempotent DDL prevents failures on reset.

---

## Common Pitfalls

### Pitfall 1: service_role Bypass — RLS Policies Don't Block Edge Functions

**What goes wrong:** Developer adds INSERT RLS policies expecting them to enforce authorization for Edge Function writes. Edge Functions use service_role key, which bypasses RLS entirely at the database level.

**Why it happens:** The Supabase client initialized without a user Authorization header header uses service_role role, which has `BYPASSRLS` attribute in PostgreSQL — the database never evaluates any policy for this role.

**How to avoid:** Understand the layered model: INSERT RLS policies protect against unauthorized PostgREST API calls (anon/authenticated role clients). Edge Functions are trusted server-side code using service_role. The policies being added in this phase provide defense-in-depth for the PostgREST REST API, NOT the mechanism by which Edge Functions gain write access.

**Warning signs:** None — this is expected behavior. If an Edge Function write fails, the cause is NOT RLS (since service_role bypasses it). Look for schema mismatches, constraint violations, or NOT NULL violations instead.

**State.md open flag resolved:** "exercises table INSERT RLS: service_role bypasses RLS so may not block push; confirm before Phase 25" — CONFIRMED: service_role always bypasses RLS. The INSERT policies on exercises/sets/rep_summaries/rep_telemetry are for PostgREST API protection, not Edge Function protection.

### Pitfall 2: exercises.user_id Backfill — NO FK Constraint

**What goes wrong:** Developer copies the `20260228` pattern verbatim, which includes `REFERENCES auth.users(id) ON DELETE CASCADE` on the user_id columns for sets/rep_summaries/rep_telemetry.

**Why it happens:** Migration `20260228` uses FK constraints. CONTEXT.md Decision 1 explicitly locks that exercises.user_id has NO FK constraint.

**How to avoid:** Use bare `ALTER TABLE exercises ADD COLUMN IF NOT EXISTS user_id UUID;` (no REFERENCES clause). The backfill is still needed, but the FK constraint is omitted.

**Warning signs:** Migration running `ADD COLUMN user_id UUID REFERENCES auth.users...` — wrong. Must be `ADD COLUMN user_id UUID` only.

### Pitfall 3: Stale CLASSIC Values in workout_sessions

**What goes wrong:** Mode migration only converts display names (`Old School`, `Pump`, etc.) but misses stale `CLASSIC` values from old mobile syncs.

**Why it happens:** `transforms.ts` workoutModeMap includes `CLASSIC: "Old School"` — legacy incorrect data. `CLASSIC` is NOT an official mode name. The six official modes are: `OLD_SCHOOL`, `PUMP`, `TUT`, `TUT_BEAST`, `ECCENTRIC_ONLY`, `ECHO`.

**How to avoid:** Include `UPDATE workout_sessions SET workout_mode = 'OLD_SCHOOL' WHERE workout_mode = 'CLASSIC';` in the data migration to correct stale values.

**Warning signs:** After migration, querying `SELECT DISTINCT workout_mode FROM workout_sessions` should return only NULL or one of the six official mode names. Any `CLASSIC` or display-name values indicate the migration was incomplete.

### Pitfall 4: Forgetting `TO authenticated` on INSERT Policies

**What goes wrong:** INSERT policy without `TO authenticated` also applies to the `anon` role — meaning anonymous (unauthenticated) users are evaluated against the policy.

**Why it happens:** `WITH CHECK (false)` is implied for anon users if RLS is enabled but no anon policy exists. However, explicitly adding `TO authenticated` is the correct pattern for clarity.

**How to avoid:** All INSERT policies that require a user identity should include `TO authenticated`. Verified in `20260221` exercise_progress example and from Supabase docs.

### Pitfall 5: routine_exercises FOR ALL Policy Conflict

**What goes wrong:** The existing `20260217` policy `"Users manage exercises in own routines"` uses `FOR ALL` — it covers SELECT, INSERT, UPDATE, DELETE. Adding a new INSERT policy creates duplicate INSERT coverage.

**Why it happens:** PostgreSQL evaluates ALL applicable policies; multiple policies are OR'd together. Duplicate INSERT coverage won't cause errors but is messy.

**How to avoid:** The existing `FOR ALL` policy on `routine_exercises` already covers INSERT via the JOIN-based pattern (`routine_id IN (SELECT id FROM routines WHERE user_id = auth.uid())`). This phase does NOT need to add an INSERT policy to `routine_exercises` — it already has one. Only add INSERT policies to the four tables that currently lack them: `exercises`, `sets`, `rep_summaries`, `rep_telemetry`.

### Pitfall 6: Migration Filename Format

**What goes wrong:** Using Supabase CLI default format `YYYYMMDDHHmmss_name.sql` (14-digit timestamp) when existing project migrations use `YYYYMMDD_name.sql` (8-digit date only).

**Why it happens:** Supabase CLI generates the full timestamp format, but this project's existing migrations only use date (e.g., `20260228_rls_denormalization.sql`, `20260303_revenuecat_schema_migration.sql`).

**How to avoid:** Follow the existing project naming: `20260302_portal_db_foundation_rls.sql`. PostgreSQL applies migrations in lexicographic order — both formats work, but consistency matters for readability.

---

## Code Examples

Verified patterns from existing migrations and official Supabase docs:

### Complete exercises.user_id Denormalization

```sql
-- Source: Pattern from 20260228_rls_denormalization.sql, modified per CONTEXT.md Decision 1
-- (no FK constraint on exercises.user_id)

-- Step 1: Add nullable column (no FK — per CONTEXT.md Decision 1)
ALTER TABLE exercises ADD COLUMN IF NOT EXISTS user_id UUID;

-- Step 2: Backfill from workout_sessions
UPDATE exercises e
SET user_id = ws.user_id
FROM workout_sessions ws
WHERE e.session_id = ws.id
  AND e.user_id IS NULL;

-- Step 3: Set NOT NULL (safe after backfill)
ALTER TABLE exercises ALTER COLUMN user_id SET NOT NULL;

-- Step 4: RLS policy (CREATE before DROP for zero security gap)
CREATE POLICY "Users can view own exercises"
  ON exercises FOR SELECT
  TO authenticated
  USING ((select auth.uid()) = user_id);

DROP POLICY IF EXISTS "Users can view exercises in own sessions" ON exercises;

-- INSERT policy for PostgREST API protection
CREATE POLICY "Users can insert own exercises"
  ON exercises FOR INSERT
  TO authenticated
  WITH CHECK ((select auth.uid()) = user_id);

-- Step 5: Index (mandatory for RLS performance)
CREATE INDEX IF NOT EXISTS idx_exercises_user_id ON exercises(user_id);
```

### New Gamification Table: rpg_attributes

```sql
-- Source: PortalRpgAttributesSyncDto + project table patterns from 00002_base_schema.sql
CREATE TABLE IF NOT EXISTS rpg_attributes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  strength INT NOT NULL DEFAULT 0,
  power INT NOT NULL DEFAULT 0,
  stamina INT NOT NULL DEFAULT 0,
  consistency INT NOT NULL DEFAULT 0,
  mastery INT NOT NULL DEFAULT 0,
  character_class TEXT,
  level INT NOT NULL DEFAULT 1,
  experience_points INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id)
);

ALTER TABLE rpg_attributes ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can view own rpg attributes"
  ON rpg_attributes FOR SELECT
  USING ((select auth.uid()) = user_id);

CREATE POLICY "Users can insert own rpg attributes"
  ON rpg_attributes FOR INSERT
  TO authenticated
  WITH CHECK ((select auth.uid()) = user_id);

CREATE POLICY "Users can update own rpg attributes"
  ON rpg_attributes FOR UPDATE
  USING ((select auth.uid()) = user_id);

CREATE INDEX IF NOT EXISTS idx_rpg_attributes_user_id ON rpg_attributes(user_id);
```

### New Gamification Table: earned_badges

```sql
-- Source: PortalEarnedBadgeSyncDto + project table patterns
CREATE TABLE IF NOT EXISTS earned_badges (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  badge_id TEXT NOT NULL,
  badge_name TEXT NOT NULL,
  badge_description TEXT,
  badge_tier TEXT NOT NULL DEFAULT 'bronze',
  earned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, badge_id)
);

ALTER TABLE earned_badges ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can view own badges"
  ON earned_badges FOR SELECT
  USING ((select auth.uid()) = user_id);

CREATE POLICY "Users can insert own badges"
  ON earned_badges FOR INSERT
  TO authenticated
  WITH CHECK ((select auth.uid()) = user_id);

CREATE INDEX IF NOT EXISTS idx_earned_badges_user_id ON earned_badges(user_id);
```

### New Gamification Table: gamification_stats

```sql
-- Source: PortalGamificationStatsSyncDto + project table patterns
-- UNIQUE(user_id) enforces singleton per user
CREATE TABLE IF NOT EXISTS gamification_stats (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  total_workouts INT NOT NULL DEFAULT 0,
  total_reps INT NOT NULL DEFAULT 0,
  total_volume_kg NUMERIC NOT NULL DEFAULT 0,
  longest_streak INT NOT NULL DEFAULT 0,
  current_streak INT NOT NULL DEFAULT 0,
  total_time_seconds INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id)
);

ALTER TABLE gamification_stats ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can view own gamification stats"
  ON gamification_stats FOR SELECT
  USING ((select auth.uid()) = user_id);

CREATE POLICY "Users can insert own gamification stats"
  ON gamification_stats FOR INSERT
  TO authenticated
  WITH CHECK ((select auth.uid()) = user_id);

CREATE POLICY "Users can update own gamification stats"
  ON gamification_stats FOR UPDATE
  USING ((select auth.uid()) = user_id);

CREATE INDEX IF NOT EXISTS idx_gamification_stats_user_id ON gamification_stats(user_id);
```

### routine_exercises Advanced Columns

```sql
-- Source: CONTEXT.md Decision 3 + PortalRoutineExerciseSyncDto
-- All columns added as nullable (no backfill needed — optional per DTO)
ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS superset_id TEXT;
ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS superset_color TEXT;
ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS superset_order INTEGER;
ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS per_set_weights JSONB;  -- e.g. [50.0, 55.0, 60.0]
ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS per_set_rest JSONB;    -- e.g. [60, 90, 120]
ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS is_amrap BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS pr_percentage NUMERIC;
ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS rep_count_timing TEXT;
ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS stop_at_position TEXT;
ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS stall_detection BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS eccentric_load TEXT;
ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS echo_level TEXT;
```

### INSERT Policies on Already-Denormalized Tables

```sql
-- Source: Pattern from 20260228 (SELECT policies) + Supabase RLS docs
-- sets, rep_summaries, rep_telemetry already have denormalized user_id from 20260228

CREATE POLICY "Users can insert own sets"
  ON sets FOR INSERT
  TO authenticated
  WITH CHECK ((select auth.uid()) = user_id);

CREATE POLICY "Users can insert own rep summaries"
  ON rep_summaries FOR INSERT
  TO authenticated
  WITH CHECK ((select auth.uid()) = user_id);

CREATE POLICY "Users can insert own telemetry"
  ON rep_telemetry FOR INSERT
  TO authenticated
  WITH CHECK ((select auth.uid()) = user_id);
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `auth.uid()` bare in RLS | `(select auth.uid())` wrapped | Supabase perf guide (ongoing) | ~20x performance via initPlan caching |
| Multi-hop JOIN RLS policies | Denormalized user_id + direct equality | Phase 15 (20260228) | Eliminates O(n) JOIN cost per row evaluated |
| FOR ALL policies | Explicit FOR SELECT / FOR INSERT | Supabase best practice | Clearer intent, avoids policy conflicts |
| Raw `auth.uid()` without `TO authenticated` | `TO authenticated` role scoping | Supabase Security Advisor | Prevents policy evaluation for anon role |

**Deprecated/outdated in this project:**
- Multi-hop RLS on exercises (`session_id IN (SELECT id FROM workout_sessions WHERE user_id = auth.uid())`): Will be replaced by direct user_id equality check in this phase
- `mode` DEFAULT 'Old School' on routine_exercises: Replaced by DEFAULT 'OLD_SCHOOL' in this phase

---

## Open Questions

1. **`updated_at` on workout_sessions (State.md open flag)**
   - What we know: `workout_sessions` in `00002_base_schema.sql` has no `updated_at` column. State.md notes this as needed before Phase 27 (pull sync merge strategy).
   - What's unclear: Phase 23 scope doesn't include adding `updated_at` to workout_sessions (CONTEXT.md has no such decision). Does Phase 23 need to add it proactively?
   - Recommendation: Phase 23 does NOT add `updated_at` — it is not in scope (CONTEXT.md scope boundary). Leave as State.md open flag for Phase 27 planner.

2. **TUT and TUT_BEAST display names**
   - What we know: `transforms.ts` workoutModeMap includes `OLD_SCHOOL`, `ECHO`, `PUMP`, `POWER`, and the stale `CLASSIC` entry. No `TUT` or `TUT_BEAST` entries.
   - What's unclear: What display names are stored in `routine_exercises.mode` or `workout_sessions.workout_mode` for TUT modes? CONTEXT.md shows `mode = 'TUT'` (already wire format) but doesn't cover TUT_BEAST or ECCENTRIC_ONLY display variants.
   - Recommendation: The mode migration should use `WHERE mode NOT IN ('OLD_SCHOOL', 'PUMP', 'TUT', 'TUT_BEAST', 'ECCENTRIC_ONLY', 'ECHO')` as a verification query after migration rather than trying to enumerate every possible display variant. Run a `SELECT DISTINCT mode FROM routine_exercises` in dev before writing the migration to confirm actual data.

3. **personal_records INSERT RLS**
   - What we know: `personal_records` table in `00002_base_schema.sql` has only a SELECT policy (`FOR SELECT USING (auth.uid() = user_id)`). CONTEXT.md's RLS gap table does NOT list `personal_records` as needing an INSERT policy.
   - What's unclear: Phase 25 Edge Function will write to `personal_records`. If it uses service_role this is fine (bypasses RLS). But PORTAL-03 requirements list only `exercises`, `sets`, `rep_summaries`, `rep_telemetry`. Personal records are excluded.
   - Recommendation: Do NOT add INSERT policy to `personal_records` in this phase — it is not in PORTAL-03 scope. Note for Phase 25 planner.

---

## Sources

### Primary (HIGH confidence)
- Project migration `20260228_rls_denormalization.sql` — denormalization pattern, 3-step backfill, policy CREATE-before-DROP, index creation
- Project migration `00002_base_schema.sql` — base table structure, existing RLS policies on exercises/sets/rep_summaries/rep_telemetry
- Project migration `20260217_phase10_tables.sql` — routine_exercises table definition
- Project migration `20260303_revenuecat_schema_migration.sql` — migration file naming convention, transaction wrapper pattern
- `PortalSyncDtos.kt` — authoritative column definitions for all gamification DTOs and routine exercise advanced columns
- [Supabase RLS Docs](https://supabase.com/docs/guides/database/postgres/row-level-security) — INSERT WITH CHECK syntax, `(select auth.uid())` performance pattern, FOR ALL vs FOR INSERT distinction
- [Supabase RLS Troubleshooting](https://supabase.com/docs/guides/troubleshooting/why-is-my-service-role-key-client-getting-rls-errors-or-not-returning-data-7_1K9z) — service_role always bypasses RLS, BYPASSRLS attribute

### Secondary (MEDIUM confidence)
- [Supabase RLS Performance Guide](https://supabase.com/docs/guides/troubleshooting/rls-performance-and-best-practices-Z5Jjwv) — index on RLS columns, `TO authenticated` scoping
- [GitHub Discussion #15631](https://github.com/orgs/supabase/discussions/15631) — Edge Functions two-client pattern (user JWT validation + service_role bypass)
- [Supabase Database Migrations](https://supabase.com/docs/guides/deployment/database-migrations) — migration workflow, file ordering

### Tertiary (LOW confidence)
- None required — all critical claims are HIGH or MEDIUM confidence

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all tools are established in this project with existing migrations as proof
- Architecture patterns: HIGH — derived directly from project's own migration history + official Supabase docs
- service_role bypass behavior: HIGH — confirmed via Supabase troubleshooting docs, multiple sources agree
- Pitfalls: HIGH — all derived from direct code inspection of existing migrations or official documentation
- Open questions: These are genuine gaps that research cannot resolve without running queries against the live database

**Research date:** 2026-03-02
**Valid until:** 2026-06-02 (90 days — Supabase RLS docs are stable; migration patterns are internal and stable)
