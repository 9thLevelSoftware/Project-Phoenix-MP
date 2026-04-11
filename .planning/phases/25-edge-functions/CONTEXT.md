# Phase 25 Context: Edge Functions

**Phase:** 25 — Edge Functions
**Repo:** phoenix-portal (`C:/Users/dasbl/AndroidStudioProjects/phoenix-portal`)
**Milestone:** v0.6.0 Portal Sync Compatibility
**Created:** 2026-03-02
**Status:** Decisions locked — ready for planning

---

## Scope Boundary

This phase deploys two Supabase Edge Functions (`mobile-sync-push` and `mobile-sync-pull`) in the phoenix-portal project. Both functions act as the sync boundary between the mobile app and the portal database. All changes are in the phoenix-portal repo. No mobile codebase changes.

**In scope:** Two Edge Functions (push + pull), JWT verification, hierarchical data insertion, delta pull queries, server-side exercise_progress computation, CORS compatibility for mobile clients.
**Out of scope:** Mobile SyncManager wiring (Phase 26), pull merge strategy (Phase 27), rep telemetry (deferred to v0.6.1 chunked upload).

---

## Decisions

### 1. Service Role for All DB Operations

**Decision:** Both Edge Functions use `SUPABASE_SERVICE_ROLE_KEY` to create the Supabase client, bypassing RLS entirely. The user_id is extracted from the JWT `sub` claim and injected server-side into every INSERT.

**Rationale:** Edge Functions are the trust boundary. Mobile sends a GoTrue JWT — the function verifies it, extracts the user_id, and writes data with the correct user_id. Service_role avoids RLS overhead and ensures INSERT succeeds even if policies have gaps. INSERT RLS policies (Phase 23) remain as defense-in-depth for direct PostgREST API access.

### 2. JWT Verification via supabase-js Auth

**Decision:** Use `supabase.auth.getUser(token)` for JWT verification rather than manual JWKS validation. This returns the full user object including `id` (the `sub` claim).

**Rationale:** Simpler than JWKS, leverages supabase-js built-in verification, and is the recommended pattern from Supabase docs. The getUser call validates the token against the Auth server.

### 3. CORS: Use Existing getCorsHeaders(req) + Accept Empty Origin

**Decision:** Reuse the existing `_shared/cors.ts` `getCorsHeaders(req)` function. Mobile clients send no Origin header, so `Access-Control-Allow-Origin` will be empty. This is fine — mobile HTTP clients (Ktor, URLSession) ignore CORS headers entirely.

**Rationale:** No code changes needed to `_shared/cors.ts`. The function processes requests regardless of Origin presence. CORS is a browser-only enforcement mechanism. SC #3 (HTTP 200 with no Origin) is satisfied by default.

### 4. Push: user_id from JWT, Never from Body

**Decision:** The `userId` field in PortalSyncPayload DTO body is ignored. The server extracts `user_id` from the verified JWT. All INSERTs use this server-extracted user_id.

**Rationale:** Prevents user impersonation. Matches the pattern used by portal's existing Edge Functions.

### 5. Push: Hierarchical INSERT Order

**Decision:** Insert in strict FK order: workout_sessions → exercises → sets → rep_summaries. Use the client-provided UUIDs as primary keys (mobile generates UUID v4 IDs locally).

**Rationale:** FK constraints require parent rows to exist before children. Client-generated UUIDs avoid server-side ID generation and make idempotent retries simpler.

### 6. Push: Routine UPSERT (Insert or Replace)

**Decision:** Use Supabase `.upsert()` for routines and routine_exercises. If a routine with the same ID already exists, overwrite it with the mobile's version.

**Rationale:** Mobile is authoritative for routine data during push. Portal-side routine edits are handled separately in Phase 27's merge strategy.

### 7. Push: exercise_progress Computed Server-Side

**Decision:** After inserting all sessions/exercises/sets, the push function computes and inserts `exercise_progress` rows. One row per exercise per session, containing: max_weight_kg, total_volume_kg, estimated_1rm_kg, max_reps, set_count.

**Rationale:** exercise_progress table exists (migration 20260221). Portal's progress.ts queries expect this data. Computing server-side from raw set data is more reliable than trusting client-computed aggregates.

**Computation logic:**
- `max_weight_kg`: MAX(weight_kg) across all sets for an exercise
- `total_volume_kg`: SUM(weight_kg × actual_reps) across sets
- `estimated_1rm_kg`: Brzycki formula from best set: weight × (36 / (37 - reps)), capped at reps ≤ 12
- `max_reps`: MAX(actual_reps) across sets
- `set_count`: COUNT(sets) for the exercise

### 8. Push: personal_records from is_pr Sets

**Decision:** After inserting sets, scan for `is_pr = true` sets. For each, upsert into `personal_records` with exercise_name, muscle_group, weight, achieved_at derived from the parent session.

**Rationale:** personal_records table exists with only a SELECT RLS policy (service_role bypasses this). The `is_pr` flag on sets is the signal. Prevents needing a separate personal_records array in the push payload.

### 9. Pull: Delta by Timestamp + Full Routines

**Decision:** Pull function queries:
- workout_sessions: `started_at > lastSync` and `user_id = jwt_user_id`
- Child records (exercises, sets, rep_summaries): JOIN through parent session
- routines + routine_exercises: ALL for user (no timestamp column available)
- rpg_attributes: `updated_at > lastSync`
- earned_badges: `earned_at > lastSync`
- gamification_stats: `updated_at > lastSync`

**Rationale:** routines table lacks `created_at`/`updated_at` — returning all is acceptable since routine count is small. Workout data uses `started_at` for delta. Gamification tables have proper timestamps.

### 10. Rep Telemetry: Excluded from Push

**Decision:** The push function does NOT handle rep_telemetry. The PortalSyncPayload does not include telemetry data.

**Rationale:** Per v0.6.0 architectural decision — rep telemetry is deferred to v0.6.1 chunked upload path. Telemetry data is too large for the main sync payload.

---

## Code Context (Existing Assets)

### Portal files to reference
- `supabase/functions/_shared/cors.ts` — CORS handler (reuse as-is)
- `supabase/functions/process-sync-queue/index.ts` — Pattern for Edge Function structure
- `src/schemas/transforms.ts` — Mode mapping (wire → display), already works with SCREAMING_SNAKE
- All migration files in `supabase/migrations/` — Table schemas and RLS policies

### Portal files to create
- `supabase/functions/mobile-sync-push/index.ts` — Push Edge Function
- `supabase/functions/mobile-sync-pull/index.ts` — Pull Edge Function

### Mobile DTOs (source of truth for wire format)
- `shared/.../data/sync/PortalSyncDtos.kt` — All DTOs (PortalSyncPayload, PortalWorkoutSessionDto, etc.)
- `shared/.../data/sync/PortalSyncAdapter.kt` — Session grouping, unit conversions
- `shared/.../data/sync/SyncModels.kt` — SyncPushRequest, SyncPushResponse, SyncPullResponse

### Key table schema summary (after all Phase 23 migrations)

| Table | Key columns | user_id | Timestamps |
|-------|-------------|---------|------------|
| workout_sessions | id, name, started_at, duration_seconds, total_volume, workout_mode, routine_session_id, notes | FK to auth.users | started_at |
| exercises | id, session_id, name, muscle_group, order_index | Denormalized (Phase 23-01) | — |
| sets | id, exercise_id, set_number, target_reps, actual_reps, weight_kg, rpe, is_pr, notes, workout_mode | Denormalized (20260228) | — |
| rep_summaries | id, set_id, rep_number, mean_velocity_mps, peak_velocity_mps, mean_force_n, peak_force_n, power_watts, rom_mm, tut_ms, left_force_avg, right_force_avg, asymmetry_pct, vbt_zone | Denormalized (20260228) | — |
| personal_records | id, exercise_name, muscle_group, record_type, value, unit, achieved_at, previous_value | FK to auth.users | achieved_at |
| exercise_progress | id, exercise_name, session_id, recorded_at, max_weight_kg, total_volume_kg, estimated_1rm_kg, max_reps, set_count | FK to auth.users | recorded_at |
| routines | id, name, description, exercise_count, estimated_duration, times_completed, last_used_at, tags, is_favorite | FK to auth.users | last_used_at (no created_at/updated_at) |
| routine_exercises | id, routine_id, name, muscle_group, sets, reps, weight, rest_seconds, mode, order_index + 12 advanced cols | Via routine FK | created_at |
| rpg_attributes | id, strength, power, stamina, consistency, mastery, character_class, level, experience_points | FK + UNIQUE | updated_at |
| earned_badges | id, badge_id, badge_name, badge_description, badge_tier | FK + UNIQUE(user_id, badge_id) | earned_at |
| gamification_stats | id, total_workouts, total_reps, total_volume_kg, longest_streak, current_streak, total_time_seconds | FK + UNIQUE | updated_at |

---

## Deferred Ideas

- **Batch INSERT via RPC**: For large payloads, could use a PostgreSQL function to insert all data in a single transaction. Deferred unless performance is a concern.
- **personal_records INSERT RLS**: Only SELECT policy exists. Service_role bypasses this, but a proper INSERT policy should be added for consistency. Not blocking for Phase 25.
- **routines created_at/updated_at**: Adding timestamp columns would enable proper delta pull queries. Deferred to Phase 27 if needed.

---

## Next Steps

1. **Plan 01** (Wave 1): mobile-sync-push — JWT auth, hierarchical INSERT, exercise_progress, personal_records
2. **Plan 02** (Wave 1, parallel): mobile-sync-pull — JWT auth, delta queries, formatted response
