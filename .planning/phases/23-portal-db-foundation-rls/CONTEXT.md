# Phase 23 Context: Portal DB Foundation + RLS

**Phase:** 23 — Portal DB Foundation + RLS
**Repo:** phoenix-portal (`C:/Users/dasbl/WebstormProjects/phoenix-portal`)
**Milestone:** v0.6.0 Portal Sync Compatibility
**Created:** 2026-03-02
**Status:** Decisions locked — ready for research/planning

---

## Scope Boundary

This phase delivers schema migrations and INSERT RLS policies to the phoenix-portal Supabase project. It does NOT touch the mobile codebase. Every decision below concerns PostgreSQL DDL, RLS policies, and data migration within Supabase.

---

## Decisions

### 1. exercises INSERT RLS: Denormalize user_id

**Decision:** Add `user_id UUID` column to the `exercises` table, matching the pattern already established for `sets`, `rep_summaries`, and `rep_telemetry` in migration `20260228_rls_denormalization.sql`.

**Rationale:** Consistent with existing codebase pattern. Avoids subquery JOINs in RLS policies. Faster INSERT checks.

**Implementation:**
- 3-step migration: ADD COLUMN nullable → backfill from `workout_sessions` JOIN → ALTER SET NOT NULL
- No FK constraint to `auth.users` (matches existing denormalized columns on sets/rep_summaries/rep_telemetry)
- Edge Function sets `user_id` explicitly on INSERT (no database trigger)

### 2. Mode String Format: Wire Format Canonical

**Decision:** Standardize on SCREAMING_SNAKE wire format (`OLD_SCHOOL`, `PUMP`, `TUT`, `TUT_BEAST`, `ECCENTRIC_ONLY`, `ECHO`) as the canonical storage format in all portal tables. Migrate existing display-name data in this phase.

**Rationale:** Mobile sends wire format. Portal's `transforms.ts` already maps wire→display for UI rendering. Single canonical format eliminates ambiguity.

**Current state discovered:**
- `routine_exercises.mode` defaults to `'Old School'` (display name) — portal UI writes display names
- `workout_sessions.workout_mode` is TEXT, data is mixed (wire format from prior mobile sync, display names from portal UI)
- `transforms.ts` has a one-way `workoutModeMap`: `OLD_SCHOOL` → `"Old School"`, `ECHO` → `"Echo"`, etc.

**Migration must include:**
```sql
-- routine_exercises
UPDATE routine_exercises SET mode = 'OLD_SCHOOL' WHERE mode = 'Old School';
UPDATE routine_exercises SET mode = 'PUMP' WHERE mode = 'Pump';
UPDATE routine_exercises SET mode = 'TUT' WHERE mode = 'TUT';
UPDATE routine_exercises SET mode = 'ECHO' WHERE mode = 'Echo';
ALTER TABLE routine_exercises ALTER COLUMN mode SET DEFAULT 'OLD_SCHOOL';

-- workout_sessions (same pattern)
UPDATE workout_sessions SET workout_mode = 'OLD_SCHOOL' WHERE workout_mode = 'Old School';
UPDATE workout_sessions SET workout_mode = 'PUMP' WHERE workout_mode = 'Pump';
-- ... etc for all display-name variants
```

**Portal UI impact (Phase 23 does NOT fix this — noted for Phase 25+):**
- `RoutineBuilder.tsx` dropdown options use display names — needs updating to wire format
- `superset-types.ts` ProgramMode type uses display names — needs updating
- `transforms.ts` mapping direction should be verified still works

### 3. Array Columns: JSONB for Arrays, TEXT for Scalars

**Decision:** Use `JSONB` for `per_set_weights` and `per_set_rest` (actual JSON arrays). Use `TEXT` for the remaining 10 advanced columns (simple string/numeric values).

**Rationale:** JSONB validates JSON on INSERT (catches malformed data), supports future querying, and Supabase dashboard renders it nicely. The other columns are scalar values that don't benefit from JSONB.

**Column types for routine_exercises:**
| Column | Type | Reason |
|--------|------|--------|
| `superset_id` | TEXT | UUID string |
| `superset_color` | TEXT | Hex color string |
| `superset_order` | INTEGER | Simple integer |
| `per_set_weights` | JSONB | JSON array e.g. `[50.0, 55.0, 60.0]` |
| `per_set_rest` | JSONB | JSON array e.g. `[60, 90, 120]` |
| `is_amrap` | BOOLEAN DEFAULT false | Boolean flag |
| `pr_percentage` | NUMERIC | Decimal value |
| `rep_count_timing` | TEXT | Enum string |
| `stop_at_position` | TEXT | Enum/value string |
| `stall_detection` | BOOLEAN DEFAULT true | Boolean flag |
| `eccentric_load` | TEXT | Value string |
| `echo_level` | TEXT | Enum string |

### 4. exercises.user_id Backfill Strategy

**Decision:** Nullable → backfill → NOT NULL (3-step proven pattern from `20260228`).

**Steps:**
1. `ALTER TABLE exercises ADD COLUMN user_id UUID;`
2. `UPDATE exercises SET user_id = ws.user_id FROM workout_sessions ws WHERE exercises.session_id = ws.id;`
3. `ALTER TABLE exercises ALTER COLUMN user_id SET NOT NULL;`

**No FK constraint** — consistent with how `sets.user_id`, `rep_summaries.user_id`, and `rep_telemetry.user_id` are defined.

---

## Code Context (Existing Assets)

### Mobile DTOs (source of truth for portal schema)
- `shared/.../data/sync/PortalSyncDtos.kt` — All DTOs defining the wire format
- `shared/.../data/sync/PortalMappings.kt` — Unit conversions (mm/s→m/s, kg→N, cable→side)
- `shared/.../domain/model/Models.kt` — ProgramMode enum with `toWireFormat()`/`fromWireFormat()` methods

### Portal existing schema (files to reference during research)
- `supabase/migrations/00002_base_schema.sql` — Core tables, SELECT-only RLS policies
- `supabase/migrations/20260217_phase10_tables.sql` — routine_exercises with FOR ALL policy
- `supabase/migrations/20260228_rls_denormalization.sql` — user_id denormalization pattern (FOLLOW THIS)
- `supabase/migrations/20260303_revenuecat_schema_migration.sql` — Most recent migration (naming convention reference)
- `src/schemas/transforms.ts` — Mode mapping (wire → display)

### Portal RLS gap summary
| Table | SELECT | INSERT needed? |
|-------|--------|---------------|
| `exercises` | Yes | **YES — add after user_id denormalization** |
| `sets` | Yes (denorm) | **YES** |
| `rep_summaries` | Yes (denorm) | **YES** |
| `rep_telemetry` | Yes (denorm) | **YES** |
| `rpg_attributes` | N/A (new) | YES (create with table) |
| `earned_badges` | N/A (new) | YES (create with table) |
| `gamification_stats` | N/A (new) | YES (create with table) |

### New tables needed (from mobile DTOs)
- `rpg_attributes` — user_id (PK+FK pattern), strength, power, stamina, consistency, mastery, character_class, level, experience_points
- `earned_badges` — user_id, badge_id, badge_name, badge_description, badge_tier, earned_at
- `gamification_stats` — user_id (PK, singleton per user), total_workouts, total_reps, total_volume_kg, longest_streak, current_streak, total_time_seconds

### Missing columns on existing tables
- `workout_sessions`: ADD `routine_session_id TEXT` (nullable)
- `sets`: ADD `workout_mode TEXT` (nullable)
- `routine_exercises`: ADD all 12 advanced columns (see column types table above)

---

## Deferred Ideas

*(None captured during this discussion)*

---

## Next Steps

1. **Research** (`/gsd:research-phase 23`) — Investigate Supabase migration best practices, RLS policy syntax for INSERT WITH CHECK, JSONB column patterns
2. **Plan** (`/gsd:plan-phase 23`) — Break into migration files with specific SQL
