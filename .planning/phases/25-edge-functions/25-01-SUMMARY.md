# Phase 25 Plan 01 Summary: mobile-sync-push Edge Function

**Status:** Complete
**Executed:** 2026-03-02

## What Was Done

Created `supabase/functions/mobile-sync-push/index.ts` (582 lines) in the phoenix-portal repo.

## Artifact

- **File:** `C:/Users/dasbl/AndroidStudioProjects/phoenix-portal/supabase/functions/mobile-sync-push/index.ts`

## Implementation Details

1. **Imports & Types** — 10 TypeScript interfaces matching mobile DTO wire format (camelCase)
2. **CORS** — Uses existing `getCorsHeaders(req)` from `_shared/cors.ts`, handles OPTIONS preflight
3. **JWT Verification** — User-scoped client with ANON_KEY + Bearer token → `auth.getUser()` → extracts userId
4. **Service Role Client** — SERVICE_ROLE_KEY for all DB operations (bypasses RLS)
5. **Workout Hierarchy** — Batch upserts in FK order: workout_sessions → exercises → sets → rep_summaries. All `user_id` fields set from JWT, never from body.
6. **exercise_progress** — Computed server-side per exercise per session: max_weight_kg, total_volume_kg, estimated_1rm_kg (Brzycki formula, reps 1-12), max_reps, set_count
7. **personal_records** — Extracted from is_pr sets: exercise name, muscle group, weight, achieved_at
8. **Routines** — Upsert routines on `id`, delete+reinsert routine_exercises (all 22 columns including 12 advanced)
9. **Gamification** — Upserts rpg_attributes (on user_id), earned_badges (on user_id+badge_id), gamification_stats (on user_id)
10. **Response** — Returns syncTime + insertion/upsert counts for all entity types
11. **Error Handling** — try-catch with 400 (DB errors) / 401 (auth) / 405 (method) / 500 (unexpected)

## Helper

- `safeJsonParse()` — Handles perSetWeights/perSetRest JSON strings → JSONB conversion

## Verification Results

- Deno.serve: 1 match
- getCorsHeaders: 2 matches (import + usage)
- auth.getUser: 1 match
- All 11 tables referenced: workout_sessions, exercises, sets, rep_summaries, exercise_progress, personal_records, routines, routine_exercises, rpg_attributes, earned_badges, gamification_stats
- user_id from JWT: 10 occurrences of `user_id: userId`
- Error status codes: 401, 405, 400, 500 all present

## Must-Have Truths Verified

- [x] Edge Function exists at supabase/functions/mobile-sync-push/index.ts
- [x] Verifies GoTrue JWT and extracts user_id from verified user object
- [x] Inserts in strict FK order (parent before child)
- [x] user_id set from JWT on all denormalized columns
- [x] Upserts routines with all 12 advanced columns
- [x] Upserts RPG attributes, earned badges, gamification stats
- [x] Computes exercise_progress from inserted sets
- [x] Extracts personal_records from is_pr sets
- [x] Returns HTTP 200 with syncTime and counts
- [x] Returns HTTP 200 with no Origin header
- [x] Uses getCorsHeaders from _shared/cors.ts
- [x] Error responses return JSON { error: string }
