# Feature Research: Portal Sync Compatibility (v0.6.0)

**Domain:** Mobile-to-cloud bidirectional workout data sync (KMP mobile + Supabase/React portal)
**Researched:** 2026-03-02
**Confidence:** HIGH (both codebases read directly; patterns verified against Supabase docs)

---

## Scope

This document maps features for the **12 compatibility issues** being resolved in v0.6.0. It covers:
seven categories — Auth, Push Sync, Pull Sync, Edge Functions, DB Schema, Gamification Sync,
and Analytics/Exercise Progress sync — with complexity estimates grounded in the actual code.

---

## Feature Landscape

### Category 1: Auth Unification

**Context:** Mobile currently uses a custom JWT stored in `PortalTokenStorage` (multiplatform-settings).
The `PortalAuthResponse.token` is stored and passed as a Bearer token to the Railway URL that doesn't
exist. Portal uses Supabase Auth natively (anon key, `supabase.auth.onAuthStateChange`). These are
two separate identity systems.

**Migration target:** Mobile uses Supabase Auth REST API (`POST /auth/v1/token?grant_type=password`)
directly via Ktor. Tokens stored in PortalTokenStorage become Supabase JWTs. No supabase-kt library
needed — raw Ktor + multiplatform-settings is the right approach for this stack.

#### Table Stakes (Auth)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Mobile sign-in via Supabase Auth REST | Without real Supabase JWTs, every Edge Function call fails RLS | LOW | Change `PortalApiClient.login()` to POST `/auth/v1/token?grant_type=password`. Response shape changes: `access_token` + `refresh_token` + `expires_in` instead of `token`. PortalAuthResponse DTO must be updated. |
| Store both access_token and refresh_token | Supabase tokens expire in 1 hour by default; without refresh, users must re-login every hour | LOW | `PortalTokenStorage` needs `KEY_REFRESH_TOKEN` and `KEY_TOKEN_EXPIRES_AT`. Current code only stores one token string. |
| Token refresh on 401 | Sync fails silently at hour-mark without refresh | MEDIUM | Add Ktor plugin (HttpSend or a custom interceptor) that catches 401, calls `/auth/v1/token?grant_type=refresh_token`, retries original request. Refresh can fail (expired or revoked) — surface as `SyncState.NotAuthenticated`. |
| Pass Supabase access_token as Bearer to Edge Functions | Edge Functions verify the JWT using Supabase's JWKS. Wrong token format = 401 on every call | LOW | Only URL changes needed once auth is unified. Token format becomes correct automatically. |
| user_id in PortalTokenStorage comes from Supabase JWT sub claim | Portal DB tables require `auth.uid()` to match the JWT; must store the correct UUID | LOW | Supabase JWT `sub` field = `user_id`. Existing `KEY_USER_ID` storage is correct, just needs to be populated from the right field. |

#### Differentiators (Auth)

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Biometric re-auth for sync | User unlocks phone, sync triggers — no re-login prompt | HIGH | Deferred to v0.6.1+. Requires platform-specific BiometricPrompt on Android. |
| Silent background token refresh | User never sees "please log in again" during a workout | MEDIUM | Achievable in v0.6.0 by refreshing proactively when `expires_at - now() < 5 minutes` before sync attempt. |

#### Anti-Features (Auth)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Add supabase-kt library | "Official SDK handles auth automatically" | Adds a large dependency; this codebase already has Ktor + multiplatform-settings wired. supabase-kt v3 requires Kotlin 2.3.10 (current project is 2.3.0); creates upgrade churn. | Raw Ktor + two Supabase REST calls (sign-in + refresh) is 30 lines and covers 100% of the needed surface. |
| Store tokens in Android Keystore / EncryptedSharedPreferences | "More secure" | Over-engineering for v0.6.0; multiplatform-settings works on both platforms; Keystore is Android-only and requires actual-based impl | Accept multiplatform-settings for now; add encryption in v0.7+ if security audit requires it. |

---

### Category 2: Push Sync (Mobile → Cloud)

**Context:** `SyncManager.pushLocalChanges()` builds a `SyncPushRequest` (legacy flat DTOs: `WorkoutSessionSyncDto`,
`PersonalRecordSyncDto`, etc.) and posts to `$baseUrl/api/sync/push` — a Railway URL that doesn't exist.
`PortalSyncAdapter` already converts mobile sessions to the hierarchical `PortalSyncPayload` format, but
is never called by `SyncManager`. The fix is: wire `SyncManager` to use `PortalSyncAdapter` + `PortalSyncPayload`,
and change the endpoint to a Supabase Edge Function URL.

#### Table Stakes (Push Sync)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Wire SyncManager to PortalSyncAdapter | Nothing syncs today — SyncManager still builds legacy flat DTOs | MEDIUM | `pushLocalChanges()` must: (1) fetch sessions+reps from SQLDelight, (2) call `PortalSyncAdapter.toPortalWorkoutSessions()`, (3) build `PortalSyncPayload`, (4) POST to Edge Function URL. The adapter exists; only the wiring is missing. |
| Change endpoint from Railway to Supabase Edge Function URL | Railway backend doesn't exist; every sync call returns connection error | LOW | Change `DEFAULT_PORTAL_URL` constant. Pattern: `https://<project>.supabase.co/functions/v1/sync-push`. |
| user_id on all nested DTOs | RLS policies require `user_id` on `sets`, `rep_summaries`, `rep_telemetry` (added in migration 20260228). Missing `user_id` = DB insert rejected | MEDIUM | `PortalSyncDtos.kt` already has `user_id` on `PortalWorkoutSessionDto` and `PortalRoutineSyncDto`. Need to add `user_id` to `PortalSetDto`, `PortalRepSummaryDto`, `PortalRepTelemetryDto`. Edge Function can also inject from JWT rather than requiring it in payload. |
| Duration unit fix: ms → seconds | Mobile stores duration in milliseconds; portal schema expects seconds (`duration_seconds INT`). Wrong unit = sessions show 1000x longer duration | LOW | Fix is already in PortalSyncAdapter (`totalDuration = sorted.sumOf { it.session.duration.toInt() / 1000 }`). Verify SyncManager passes the adapted sessions, not raw sessions. |
| Batch upload of sessions since last sync | Only send changed data to avoid re-uploading entire history | LOW | Already implemented: `syncRepository.getSessionsModifiedSince(lastSync)`. Pattern is correct — keep it. |
| Idempotent upsert in Edge Function | Network retry should not create duplicate sessions | MEDIUM | Edge Function uses `supabase.from('workout_sessions').upsert(data, { onConflict: 'id' })`. Mobile-generated UUIDs must be stable across retries (they are — `PortalSyncAdapter` uses session.id as the portal session ID). |
| Partial success response | If sessions upsert succeeds but rep_telemetry fails, mobile should know what was saved | MEDIUM | Edge Function returns `{ inserted: { sessions: N, exercises: N, sets: N }, errors: [...] }`. Mobile advances lastSync only if sessions were fully saved. |
| Retry with exponential backoff | Network failures during sync should not permanently block sync | MEDIUM | Ktor's `HttpRequestRetry` plugin supports `retryOnServerErrors(maxRetries = 3)` with `exponentialDelay()`. Apply to PortalApiClient. Currently no retry logic exists. |

#### Differentiators (Push Sync)

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Rep telemetry included in push payload | Force curve visualization in portal — competitive differentiator for biometrics analysis | MEDIUM | `PortalSyncAdapter.toRepTelemetry()` already implemented. Need to include in Edge Function payload and insert into `rep_telemetry` table. Telemetry is large — consider making it opt-in or size-bounded per session. |
| Sync progress Flow | Mobile UI shows "Syncing 3/12 sessions..." during large uploads | LOW | Expose a `syncProgress: StateFlow<SyncProgress>` from SyncManager. Push each entity type as it's confirmed. |
| Soft-delete propagation | Deleted sessions/routines on mobile propagate to portal (hide from portal, don't confuse user) | MEDIUM | Mobile already has `deletedAt` fields in SQLDelight schema. Edge Function checks `deletedAt != null` and soft-deletes on portal side. |

#### Anti-Features (Push Sync)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Real-time streaming sync during workout | "Upload each rep as it happens" | BLE, workout state machine, and sync on the same thread creates blocking. Workout execution is safety-critical — any latency introduced by sync could affect rep counting | Post-workout batch sync. Trigger sync in `SyncTriggerManager` when workout ends (which already exists). |
| Two-way merge on conflict (CRDT) | "Avoid data loss if two devices edit same session" | Workout sessions are immutable once completed. The only conflict scenario is editing a routine from both mobile and portal — LWW by `updatedAt` timestamp is correct here. | Last-write-wins using `updatedAt`. For routines, portal edit wins if portal timestamp > mobile timestamp. |

---

### Category 3: Pull Sync (Cloud → Mobile)

**Context:** `pullRemoteChanges()` calls `/api/sync/pull` with a `SyncPullRequest` and expects a
`SyncPullResponse` containing legacy `WorkoutSessionSyncDto` objects. The portal has no such endpoint.
Pull sync needs to be rebuilt around the portal's actual Supabase table structure.

#### Table Stakes (Pull Sync)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Delta pull using last_sync timestamp | Don't re-download all historical data on every sync | LOW | Edge Function queries: `workout_sessions.select().gt('created_at', lastSync)`. Mobile stores `lastSync` in PortalTokenStorage. Pattern already exists, endpoint doesn't. |
| Pull routines from portal to mobile | User creates/edits a routine in portal web UI; mobile must reflect it | MEDIUM | Pull response includes `PortalRoutineSyncDto`. Mobile `SyncRepository.mergeRoutines()` does INSERT OR REPLACE (already exists in upsertRoutine pattern). Must preserve local-only fields. |
| Convert portal DTOs back to mobile models | SyncPullResponse uses portal-native DTOs, not legacy flat DTOs | MEDIUM | Need a `PortalPullAdapter` (inverse of PortalSyncAdapter) that converts `PortalWorkoutSessionDto` → `WorkoutSession`. This doesn't exist yet. The most complex pull piece. |
| Full sync fallback when lastSync = 0 | First sync after install or after clearing data | LOW | If `lastSync == 0`, Edge Function returns all user data (no timestamp filter). Mobile handles this the same as delta sync — INSERT OR REPLACE. |
| Pull personal_records | PRs set on portal must be visible on mobile | LOW | PR sync is already in `SyncPullResponse`. Need portal Edge Function to query `personal_records WHERE user_id = auth.uid() AND created_at > lastSync`. |

#### Differentiators (Pull Sync)

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Pagination for large history | Users with 500+ sessions get first sync without timeout | MEDIUM | Edge Function accepts `page` and `pageSize` params. Mobile sync loop: pull pages until `has_more: false`. Not needed for most users but prevents 50s Edge Function timeout on cold starts. |
| Pull gamification stats | RPG attributes and badges set/awarded in portal reflected on mobile HUD | MEDIUM | Included in pull payload if `rpg_attributes` / `earned_badges` tables exist. Requires those tables to be created first (DB Schema category). |

#### Anti-Features (Pull Sync)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Full sync every time | "Simplest to implement" | Supabase Edge Functions have a 50-second wall-clock limit. Users with 2 years of workout history will timeout on every sync | Delta sync with `lastSync` timestamp. 3 extra lines of code vs. minutes of lost data. |
| WebSocket / Realtime for live sync | "Always up to date" | Supabase Realtime requires persistent connection; bad for battery on mobile. Workout sessions are written in batch, not incrementally. | Trigger-based batch sync (already the pattern in SyncTriggerManager). |

---

### Category 4: Edge Function Design

**Context:** No mobile-facing Edge Functions exist yet. The portal has `process-sync-queue` (for
third-party integrations) and external OAuth functions (strava, fitbit, etc.) but nothing for
direct mobile → Supabase push/pull. Two Edge Functions are needed: `sync-push` and `sync-pull`.

#### Table Stakes (Edge Functions)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| `sync-push` Edge Function | Entry point for all mobile → portal data upload | MEDIUM | Accepts `PortalSyncPayload` JSON. Verifies Bearer JWT using Supabase JWKS (or `supabase.auth.getUser(token)`). Uses **service role client** for DB writes to bypass RLS (since we control the user_id in the payload, not via `auth.uid()`). Returns `{ sessions_inserted, exercises_inserted, errors }`. |
| `sync-pull` Edge Function | Entry point for portal → mobile data download | MEDIUM | Accepts `{ last_sync: timestamp }` in body. Verifies Bearer JWT. Queries all tables with `user_id = auth.uid()` (no service role needed for reads — user JWT is correct). Returns `PortalSyncPayload`-shaped response. |
| Dual-client pattern in Edge Functions | Need user identity (from JWT) but also need service-role for writes | LOW | Standard Supabase Edge Function pattern: create one client with user JWT to get `auth.uid()`, create second client with `SUPABASE_SERVICE_ROLE_KEY` for writes. Service role key is never exposed to mobile. |
| CORS headers for Edge Functions | Without CORS, portal web UI cannot call the same functions | LOW | Existing `_shared/cors.ts` already handles this. Reuse in sync functions. |
| Validate payload schema | Malformed mobile payloads should return 400, not 500 | LOW | Check required fields: `device_id`, `platform`, `last_sync`. Return `{ error: "missing device_id" }` with 400. Edge Functions don't need a full schema validator — guard key fields. |
| Transaction-safe inserts | Session + exercises + sets must all succeed or all fail | MEDIUM | Use Supabase's `rpc()` to call a PostgreSQL function that inserts in a single transaction, OR use sequential inserts with rollback logic. Sequential inserts with error tracking are simpler in Edge Functions (Deno TypeScript). |
| Mode display mapping in Edge Function / Portal | Mobile sends `SCREAMING_SNAKE` mode strings (`OLD_SCHOOL`, `ISOKINETIC`); portal UI shows them differently | LOW | `PortalMappings.workoutModeToSync()` already handles mobile→wire format. Portal needs a display mapper: `OLD_SCHOOL` → "Old School", `ISOKINETIC` → "Isokinetic". This is a portal-side UI concern, not an Edge Function concern. Add to portal's utility functions. |

#### Differentiators (Edge Functions)

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Compute exercise_progress during sync-push | Analytics available immediately after sync, no portal-triggered recomputation needed | MEDIUM | After inserting sets, Edge Function computes: `max_weight_kg = MAX(weight_kg)`, `total_volume = SUM(weight_kg * actual_reps)`, `estimated_1rm = Brzycki(max_weight, reps)` per exercise and inserts into `exercise_progress`. Avoids needing DB triggers. |
| Async PR detection in sync-push | Mobile might miss PRs (it only checks at workout time). Portal can verify PRs on every sync push. | MEDIUM | After sets insert, compare `MAX(weight_kg)` with `personal_records` for that exercise/user. Insert new PR if exceeded. Return PR count in response so mobile can show "2 new PRs synced". |
| Device ID tracking | Analytics for how many devices a user syncs from | LOW | Edge Function stores `(user_id, device_id, platform, last_sync_at)` in a `user_devices` table. Not blocking for v0.6.0. |

#### Anti-Features (Edge Functions)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| PostgreSQL triggers for exercise_progress | "Automated, always consistent" | Triggers fire on every insert including portal UI operations, batch imports, etc. Can create unexpected compute load. Hard to debug in Supabase dashboard. | Compute in sync-push Edge Function after inserts. Explicit, debuggable, only runs during sync. |
| Versioned API (`/sync/v1/push`) | "Allows future breaking changes" | Adds URL complexity for a single consumer (mobile app). Version the app, not the endpoint. | When breaking changes are needed, update Edge Function and release mobile update simultaneously. This is a community project with controlled deployment. |

---

### Category 5: DB Schema (Portal Side)

**Context:** Several tables the mobile DTOs reference either don't exist or are missing columns.
Identified gaps from reading both the migration files and the PortalSyncDtos:

1. `routine_exercises` — exists but missing: `superset_id`, `superset_color`, `superset_order`, `per_set_weights`, `per_set_rest`, `is_amrap`, `pr_percentage`, `rep_count_timing`, `stop_at_position`, `stall_detection`, `eccentric_load`, `echo_level`
2. `sets` — missing: `workout_mode` column (PortalSetDto has it)
3. `workout_sessions` — missing: `routine_session_id` column (PortalWorkoutSessionDto has it)
4. `rpg_attributes` table — does not exist
5. `earned_badges` table — does not exist
6. `gamification_stats` table — does not exist

#### Table Stakes (DB Schema)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Add advanced columns to `routine_exercises` | PortalRoutineExerciseSyncDto already has 9 additional fields; inserts will fail without the columns | LOW | One migration with 9 `ALTER TABLE routine_exercises ADD COLUMN IF NOT EXISTS` statements. All nullable — no backfill needed. JSONB for `per_set_weights` and `per_set_rest` is better than TEXT (allows portal queries on the arrays). |
| Add `workout_mode` to `sets` table | PortalSetDto.workoutMode is included in every set; currently dropped silently | LOW | `ALTER TABLE sets ADD COLUMN IF NOT EXISTS workout_mode TEXT;` One migration line. |
| Add `routine_session_id` to `workout_sessions` | Portal needs to identify which sessions came from a routine run vs standalone | LOW | `ALTER TABLE workout_sessions ADD COLUMN IF NOT EXISTS routine_session_id UUID;` Add index. |
| Create `rpg_attributes` table | `PortalRpgAttributesSyncDto` has no target table — gamification sync is blocked | LOW | New table: `id UUID PK`, `user_id UUID NOT NULL REFERENCES auth.users(id)`, RPG columns (strength, power, stamina, consistency, mastery, character_class, level, experience_points). RLS: `user_id = auth.uid()`. UNIQUE on user_id (one row per user, upsert pattern). |
| Create `earned_badges` table | `PortalEarnedBadgeSyncDto` has no target table | LOW | New table: `id UUID PK`, `user_id UUID`, `badge_id TEXT`, `badge_name TEXT`, `badge_tier TEXT`, `earned_at TIMESTAMPTZ`. UNIQUE on `(user_id, badge_id)` for idempotent upsert. |
| Create `gamification_stats` table | `PortalGamificationStatsSyncDto` has no target table | LOW | New table with singleton-per-user pattern: `user_id UUID PK` (no auto-gen ID needed — one row per user). Columns match GamificationStatsSyncDto. Upsert by user_id. |

#### Differentiators (DB Schema)

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| JSONB for `per_set_weights` / `per_set_rest` | Portal can query individual set weights (`per_set_weights->0`); TEXT can't be queried | LOW | Change from TEXT to `JSONB DEFAULT '[]'` in the migration. Portal UI already expects arrays. |
| Index on `workout_sessions.routine_session_id` | Allows portal to efficiently fetch all sessions in a routine run | LOW | `CREATE INDEX idx_workout_sessions_routine_session ON workout_sessions(routine_session_id)` |
| `user_id` on `sets`, `rep_summaries`, `rep_telemetry` | Already migrated in 20260228 — must include in sync inserts | LOW | The denormalization migration already ran. Edge Function must include `user_id` when inserting into these tables. Not a new migration, but a sync logic requirement. |

#### Anti-Features (DB Schema)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| JSONB blob for entire session in `workout_sessions` | "Flexible — store whatever mobile sends" | Breaks portal's analytics queries which JOIN on `exercises`, `sets`, `rep_summaries`. Portal dashboard already queries these normalized tables. | Keep the hierarchical normalized structure. The PortalSyncAdapter already produces the correct format. |
| Versioned schema columns (`v1_weight_kg`, `v2_weight_kg`) | "Backwards compat" | Clutters schema and confuses queries. This is a single-version app. | Use `IF NOT EXISTS` in migrations, handle schema drift in the Edge Function. |

---

### Category 6: Gamification / RPG Data Sync

**Context:** Mobile has `GamificationStats`, `RpgAttributes` (strength/power/stamina/consistency/mastery),
`EarnedBadge`, and character class/level data. These are locally computed from workout history.
Portal has no tables for these yet. The feature category is about syncing the *results* of local
computation — not re-computing on the portal.

#### Table Stakes (Gamification Sync)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Push RPG attributes to portal | Users expect their RPG character to appear in the portal dashboard | LOW | `PortalRpgAttributesSyncDto` is complete. Wire into `PortalSyncPayload.rpgAttributes`. Edge Function upserts into `rpg_attributes` by user_id. |
| Push earned badges to portal | Achievement history visible on portal profile page | LOW | `PortalEarnedBadgeSyncDto` list in `PortalSyncPayload.badges`. Upsert by `(user_id, badge_id)` — idempotent. |
| Push gamification stats to portal | Portal leaderboard and challenges need total_workouts, total_volume, streaks | LOW | `PortalGamificationStatsSyncDto` complete. Upsert by user_id (singleton row). |
| Pull gamification updates from portal | Portal may award badges (challenge completion, community milestones). Mobile must reflect them. | MEDIUM | Requires mobile to recognize portal-originated badges (badge_id namespacing: `portal:challenge:30day`). Mobile ignores unknown badge IDs gracefully. |
| Treat stats as authoritative mobile-side | Stats are derived from SQLDelight workout history — recomputing is expensive | LOW | Mobile always pushes its computed stats; portal always stores what mobile sends. Portal never tries to re-derive these from raw sessions. This avoids a complex reconciliation problem. |

#### Differentiators (Gamification Sync)

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Portal-awarded badges displayed on mobile | "Unlocked in portal, shown on mobile HUD" — cross-platform achievement flow | MEDIUM | Mobile pull sync includes badges. Badge display system on mobile only needs to handle unknown badge IDs gracefully (show generic icon). |
| Character class sync | User's class (Strength Athlete, Endurance Beast, etc.) visible in portal profile | LOW | Already in `PortalRpgAttributesSyncDto.characterClass`. Portal profile page can display it once table exists. |

#### Anti-Features (Gamification Sync)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Re-derive RPG stats from raw sessions in portal | "Single source of truth on server" | Portal would need to re-implement the entire gamification formula (attribute weights, badge criteria, XP curves). Complex and likely to drift from mobile. | Mobile owns stat computation. Portal is display-only for RPG data. Trust what mobile sends. |
| Real-time badge notifications via Supabase Realtime | "Instant badge toast when challenge completes" | Adds Realtime subscription complexity to mobile; badges are not time-critical | Check for new badges during pull sync. Show "N new badges earned" in sync result notification. |

---

### Category 7: Exercise Progress and Analytics Sync

**Context:** Portal has an `exercise_progress` table (migration 20260221) with per-session aggregates:
`max_weight_kg`, `total_volume_kg`, `estimated_1rm_kg`, `max_reps`, `set_count`. Mobile computes
1RM using Brzycki/Epley formulas. The portal displays progress charts from this table.

#### Table Stakes (Exercise Progress)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Compute and push exercise_progress per session | Without it, portal progress charts show nothing | MEDIUM | Two approaches: (A) Mobile pre-computes and includes in push payload, or (B) Edge Function derives from inserted sets. Approach B is better — portal data is always consistent with what's actually stored. After inserting sets for each exercise, Edge Function aggregates: `MAX(weight_kg)`, `SUM(weight_kg * actual_reps)`, `Brzycki(maxWeight, repsAtMaxWeight)`, then upserts `exercise_progress`. |
| 1RM formula alignment: Brzycki | Mobile uses both Brzycki and Epley (see `Constants.kt`). Portal `exercise_progress.estimated_1rm_kg` should use the same formula for chart consistency. | LOW | Agree on Brzycki as canonical: `1RM = weight / (1.0278 - 0.0278 * reps)`. Implement in Edge Function. Document the choice. |
| exercise_progress linked to session_id | Portal queries join `exercise_progress` with `workout_sessions` for timeline views | LOW | `exercise_progress.session_id` FK is already in the schema. Edge Function inserts with the portal session ID. |
| Idempotent progress upsert | Re-syncing same sessions should not create duplicate progress records | LOW | Upsert by `(user_id, exercise_name, session_id)`. If session was already processed, update in place. |

#### Differentiators (Exercise Progress)

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Include rep-level velocity data in progress | Portal analytics can show "velocity trend" for exercise — VBT progression | HIGH | Would require `rep_summaries` aggregation (`AVG(mean_velocity_mps)` per session). Adds a new column to `exercise_progress`. Defer to v0.6.1+. |
| PR detection during sync with portal notification | "2 new personal records synced" shown in app | MEDIUM | Edge Function compares `max_weight_kg` against existing `personal_records`. Insert new PR if exceeded. Return `pr_count` in sync response. Mobile surfaces this as a post-sync notification. |
| Volume trend by muscle group | Portal dashboard: "chest volume this week vs last week" | MEDIUM | Derivable from `exercise_progress + exercises` JOIN on `muscle_group`. Portal query, not a sync feature. |

#### Anti-Features (Exercise Progress)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Mobile pre-computes and sends exercise_progress in push payload | "Less work for the Edge Function" | Mobile data may be stale (if exercise was renamed, or weight units changed). Edge Function computing from freshly-inserted data guarantees consistency. | Edge Function derives exercise_progress from actual inserted sets immediately after insertion. |
| DB trigger to auto-compute exercise_progress | "Automated, fires on every set insert" | Trigger fires on ALL set inserts — including portal UI manual entries, bulk imports, migration backfills. Can create unexpected load. Hard to debug. Trigger logic also can't know which session is "complete". | Edge Function explicitly computes after all sets for a session are inserted. |

---

## Feature Dependencies

```
[Supabase Auth (mobile)]
    └──enables──> [Push Sync Edge Function] (JWT needed for auth verification)
                      └──enables──> [RLS-compliant inserts] (user_id from JWT)
    └──enables──> [Pull Sync Edge Function]

[DB Schema migrations]
    └──enables──> [Gamification Sync] (rpg_attributes, earned_badges, gamification_stats tables)
    └──enables──> [Routine advanced columns sync] (routine_exercises columns)
    └──enables──> [workout_mode and routine_session_id on sessions/sets]

[Push Sync wired to PortalSyncAdapter]
    └──enables──> [Exercise Progress computation in Edge Function]
    └──enables──> [Rep telemetry storage]
    └──enables──> [PR detection in Edge Function]

[Edge Function sync-push EXISTS]
    └──enables──> [Partial success handling on mobile]
    └──enables──> [Retry/backoff in PortalApiClient]

[Pull Sync with portal-native DTOs]
    └──enables──> [PortalPullAdapter (new, does not exist)]
    └──enables──> [Pull gamification updates from portal]
```

### Dependency Notes

- **Auth must land first:** Every Edge Function call is authenticated. Without Supabase JWTs, nothing else can be tested.
- **DB migrations before Edge Functions:** Edge Functions inserting into missing columns will 500-error. Schema first, code second.
- **PortalSyncAdapter wiring before exercise_progress:** Exercise progress is computed from sets that the adapter produces. Can't test progress computation without the hierarchical data flowing.
- **PortalPullAdapter is new work:** Unlike push (adapter exists, just not wired), pull has no corresponding adapter. This is the most novel piece of work in Pull Sync.

---

## MVP Definition for v0.6.0

### Launch With (the 12 compatibility issues)

- [ ] **Auth:** Mobile signs in via Supabase Auth REST, stores access_token + refresh_token, refreshes on 401
- [ ] **Push:** SyncManager wired to PortalSyncAdapter, sends PortalSyncPayload to Edge Function URL
- [ ] **Push:** user_id on all nested DTOs (sets, rep_summaries, rep_telemetry)
- [ ] **Push:** Duration unit fixed (ms → seconds, already done in adapter, needs wiring verification)
- [ ] **Push:** Mode display mapping (SCREAMING_SNAKE → display string) in portal UI
- [ ] **Edge Functions:** sync-push function implemented (dual-client pattern, service role for writes)
- [ ] **Edge Functions:** sync-pull function implemented (user JWT for reads, delta by lastSync)
- [ ] **DB Schema:** routine_exercises advanced columns migration
- [ ] **DB Schema:** workout_mode on sets, routine_session_id on workout_sessions
- [ ] **DB Schema:** rpg_attributes, earned_badges, gamification_stats tables created
- [ ] **Gamification:** RPG attributes, badges, gamification stats in push payload and Edge Function
- [ ] **Analytics:** exercise_progress computed in sync-push Edge Function after set inserts

### Add After Validation (v0.6.1)

- [ ] **Pull:** PortalPullAdapter (portal → mobile conversion) once push is stable
- [ ] **Pull:** Pagination for large history (only needed at scale)
- [ ] **Auth:** Biometric re-auth
- [ ] **Push:** Rep telemetry in push payload (large payload, needs size limits first)
- [ ] **Analytics:** Rep-level velocity in exercise_progress

### Future Consideration (v0.7+)

- [ ] Encryption for token storage (security audit trigger)
- [ ] WebSocket / Realtime for badge notifications
- [ ] Volume trend analytics by muscle group
- [ ] PR trend visualization with velocity overlay

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Supabase Auth (mobile) | HIGH | LOW | P1 |
| SyncManager wired to PortalSyncAdapter | HIGH | MEDIUM | P1 |
| sync-push Edge Function | HIGH | MEDIUM | P1 |
| DB Schema migrations (missing tables/columns) | HIGH | LOW | P1 |
| user_id on nested DTOs | HIGH | LOW | P1 |
| Duration unit fix | HIGH | LOW | P1 |
| Gamification push (RPG attrs, badges, stats) | MEDIUM | LOW | P1 |
| exercise_progress computation in Edge Function | MEDIUM | MEDIUM | P1 |
| sync-pull Edge Function | MEDIUM | MEDIUM | P2 |
| PortalPullAdapter (pull → mobile models) | MEDIUM | HIGH | P2 |
| Mode display mapping (portal UI) | LOW | LOW | P1 (fast win) |
| Token refresh on 401 | HIGH | MEDIUM | P1 |
| Retry with exponential backoff | MEDIUM | LOW | P2 |
| Rep telemetry in push | LOW | LOW | P3 |
| Pagination for large history | LOW | MEDIUM | P3 |

**Priority key:**
- P1: Must have for v0.6.0 (resolves a compatibility issue)
- P2: Should have, add in v0.6.0 if time allows, else v0.6.1
- P3: Nice to have, v0.6.1+

---

## Conflict Resolution Strategy

**Workout sessions:** Immutable once completed. No merge needed. If a session ID exists on portal, skip (idempotent upsert). If mobile has a session the portal doesn't, insert. Session data never flows portal → mobile (sessions originate on device).

**Routines:** LWW (last-write-wins) using `updatedAt` timestamp. Routine exists on both sides: compare `updatedAt`. If portal timestamp > mobile timestamp, pull wins. If mobile timestamp >= portal timestamp, push wins. This is the only true bidirectional sync case.

**PRs:** Append-only. If a new PR exists on mobile not on portal, insert it. Never delete or overwrite PRs. If portal has a PR mobile doesn't, pull it and add to local SQLDelight (pull sync path).

**RPG attributes/gamification stats:** Mobile is authoritative. Portal always takes mobile's values. Portal never overwrites mobile's computed stats.

**Badges:** Union. All badges from either side are merged. A badge earned on mobile is pushed; a badge awarded by portal is pulled. `(user_id, badge_id)` uniqueness prevents duplicates.

---

## Sources

- [Supabase Edge Functions Auth](https://supabase.com/docs/guides/functions/auth) — JWT verification patterns, dual-client setup
- [Supabase RLS Best Practices](https://supabase.com/docs/guides/database/postgres/row-level-security) — RLS policy patterns
- [Supabase Kotlin SDK](https://github.com/supabase-community/supabase-kt) — supabase-kt v3, auth token patterns
- [Supabase RLS Performance Guide](https://supabase.com/docs/guides/troubleshooting/rls-performance-and-best-practices-Z5Jjwv) — initPlan caching, denormalization rationale (already applied in 20260228 migration)
- [Supabase Transactions and RLS in Edge Functions](https://marmelab.com/blog/2025/12/08/supabase-edge-function-transaction-rls.html) — transaction handling in Deno Edge Functions
- [SQLite Sync strategies](https://www.sqliteforum.com/p/building-offline-first-applications) — LWW, delta sync, offline-first patterns
- Direct codebase inspection: `PortalSyncDtos.kt`, `PortalSyncAdapter.kt`, `SyncManager.kt`, `PortalApiClient.kt`, `PortalTokenStorage.kt`, `SyncModels.kt`, portal migrations `00002_base_schema.sql`, `20260217_phase10_tables.sql`, `20260221_exercise_progress_and_creator_stats.sql`, `20260228_rls_denormalization.sql`

---
*Feature research for: Portal Sync Compatibility (v0.6.0)*
*Researched: 2026-03-02*
*Confidence: HIGH — both codebases read directly, gap analysis grounded in actual schema and DTOs*
