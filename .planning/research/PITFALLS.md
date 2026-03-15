# Pitfalls Research

**Domain:** Bidirectional mobile↔Supabase sync — adding to existing KMP app
**Researched:** 2026-03-02
**Confidence:** HIGH (verified against actual codebase + official Supabase docs + portal migrations)

---

## Critical Pitfalls

### Pitfall 1: CORS Whitelist Blocks Mobile HTTP Clients

**What goes wrong:**
The existing `getCorsHeaders()` helper in `_shared/cors.ts` validates the `Origin` header against a whitelist (`APP_URL`, `localhost:5173`, `localhost:3000`). Native mobile HTTP clients (Ktor on Android/iOS) do **not** send an `Origin` header — they are not browsers. The current implementation returns an empty string for `Access-Control-Allow-Origin` when `origin` is `""`, which means any new sync Edge Functions that import `getCorsHeaders` will appear to reject mobile requests with a CORS error.

In practice, native clients don't enforce CORS themselves, so the missing header won't block the request — but any response handler that checks for a valid CORS header will fail, and more critically: if you copy the `getCorsHeaders` pattern into a new mobile-facing Edge Function without recognizing this, future browser-based debugging of those endpoints will be broken silently.

**Why it happens:**
CORS is a browser concept. The portal's existing Edge Functions were designed for browser (React SPA) callers. New mobile-facing sync functions need a **different** CORS strategy: pass-through for non-browser origins (no `Origin` header) while still enforcing for browser origins. The shared helper doesn't distinguish.

**How to avoid:**
For mobile-facing Edge Functions (sync-push, sync-pull), do not use `getCorsHeaders(req)` directly. Use a modified helper that returns `'Access-Control-Allow-Origin': '*'` when `origin` is absent or empty, and applies whitelist validation only when an `Origin` header is present. This covers both browser debugging and native mobile callers.

```typescript
// Pattern for mobile-facing functions
export function getMobileCorsHeaders(req: Request): Record<string, string> {
  const origin = req.headers.get('origin') ?? '';
  // Mobile clients don't send Origin — allow through; browsers get whitelist check
  const allowOrigin = origin === '' ? '*' : (ALLOWED_ORIGINS.includes(origin) ? origin : '');
  return {
    'Access-Control-Allow-Origin': allowOrigin,
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
    'Vary': 'Origin',
  };
}
```

**Warning signs:**
- Sync calls return 200 from mobile but Edge Function dashboard shows CORS-related errors
- Browser-based testing of sync endpoint fails with CORS error
- Any new function that calls `getCorsHeaders(req)` without checking for mobile context

**Phase to address:** Phase 1 (Edge Function scaffolding) — define the mobile-safe CORS helper before writing any sync functions.

---

### Pitfall 2: Supabase Auth Token vs. Custom JWT — Two Identity Systems, One User

**What goes wrong:**
`PortalApiClient` currently targets `https://phoenix-portal-backend.up.railway.app` (Railway backend that does not exist). The mobile auth stores a custom JWT in `PortalTokenStorage` using `russhwolf/settings`. When you switch to Supabase Edge Functions, the authorization model changes: Edge Functions validate the **Supabase JWT** (issued by GoTrue), not the custom token format. If the mobile app sends its existing stored custom token as a bearer token to a Supabase Edge Function, the function's built-in JWT check will reject it with 401.

Additionally, the portal uses `auth.uid()` throughout its RLS policies. The mobile must send the Supabase access token — the one issued by GoTrue after email/password login to Supabase Auth — not any custom-generated token.

**Why it happens:**
The mobile's `PortalAuthResponse` has a `token: String` field and `PortalUser.id: String`. These currently map to whatever the (non-existent) Railway backend returned. The new target is `auth.users.id` (UUID) and GoTrue's JWT. The `PortalTokenStorage` stores a single `KEY_TOKEN` — it will hold the right value once login is wired to Supabase Auth's `/auth/v1/token` endpoint, but there is no migration path for users who had a token stored from any previous session with a different backend.

**How to avoid:**
- Wire `PortalApiClient.login()` directly to Supabase Auth (`https://{PROJECT_REF}.supabase.co/auth/v1/token?grant_type=password`) and parse the GoTrue response (`access_token`, `refresh_token`, `user.id`)
- Update `PortalAuthResponse` to include `refreshToken: String` for silent re-auth
- On first launch after update: if `hasToken()` is true but the stored token is not a valid GoTrue JWT (check for 3 dot-separated segments, or attempt a status check), call `clearAuth()` and require re-login rather than sending a stale token
- Store both `access_token` and `refresh_token` in `PortalTokenStorage`

**Warning signs:**
- All sync calls return 401 immediately after auth migration
- `auth.uid()` returns null in Edge Function even though `Authorization` header is present
- `isAuthenticated` StateFlow is true but sync fails at the first authenticated call

**Phase to address:** Phase 1 (Auth migration) — must be resolved before any Edge Function calls. No sync works without a valid Supabase JWT.

---

### Pitfall 3: Access Token Expiry Without Silent Refresh

**What goes wrong:**
Supabase GoTrue access tokens expire in 1 hour by default. The current `PortalTokenStorage` stores only the access token — there is no `refresh_token` key, and there is no token refresh logic in `PortalApiClient`. A user who authenticated once and then opens the app more than an hour later will get 401 on their first sync attempt. The `SyncManager` treats 401 as `SyncState.NotAuthenticated` and surfaces a re-login prompt, which is jarring for a user who considers themselves "logged in."

On mobile, the app can be backgrounded for hours or days. The GoTrue JS SDK handles background refresh via timers, but those timers don't survive sleep mode even in JS. In KMP/Ktor, there is no automatic timer — refresh is entirely manual.

**Why it happens:**
The existing token storage was designed for a custom backend where token lifetime was not yet defined. Supabase GoTrue has hard expiry enforced server-side. KMP has no lifecycle-aware background refresh.

**How to avoid:**
- Add `KEY_REFRESH_TOKEN` to `PortalTokenStorage`
- In `PortalApiClient.authenticatedRequest()`, catch 401 responses and attempt a token refresh via `POST /auth/v1/token?grant_type=refresh_token` before propagating the error
- On successful refresh, save the new `access_token` and `refresh_token` pair to storage
- If refresh itself returns 400 (refresh token expired/revoked), then call `clearAuth()` and emit `SyncState.NotAuthenticated`
- Optionally: check token expiry proactively at sync start using the JWT `exp` claim (parseable without a library by base64-decoding the payload segment)

**Warning signs:**
- Sync works on first use, silently fails after 1 hour of inactivity
- Users report being "logged out" randomly
- 401 errors appearing in logs from users who auth'd > 1 hour ago

**Phase to address:** Phase 1 (Auth migration) — must be built alongside the auth wiring, not deferred.

---

### Pitfall 4: Missing `user_id` on Nested DTOs Breaks INSERT RLS

**What goes wrong:**
The portal's `exercises`, `sets`, `rep_summaries`, and `rep_telemetry` tables do not have their own `user_id` at the INSERT level (before the denormalization migration). Their RLS SELECT policies use multi-hop JOINs to reach `workout_sessions.user_id`. Crucially, there are **no INSERT policies** defined on these tables in the base schema — only SELECT policies. INSERT operations from Edge Functions using the user's JWT will be rejected by Postgres (`new row violates row-level security policy`) if INSERT policies are missing or if the user's JWT is not propagating correctly through the Supabase client in the Edge Function.

After the denormalization migration (`20260228_rls_denormalization.sql`), `sets`, `rep_summaries`, and `rep_telemetry` have a `user_id NOT NULL` column — but the mobile DTOs (`PortalSetDto`, `PortalRepSummaryDto`, `PortalRepTelemetryDto`) do **not** include `user_id`. The Edge Function must populate `user_id` server-side from the authenticated JWT, not from the DTO.

**Why it happens:**
The mobile DTO hierarchy was designed correctly: `user_id` is on the top-level `PortalWorkoutSessionDto` and `PortalRoutineSyncDto`. But the DB now requires `user_id` on intermediate tables too. If the Edge Function does a naive `INSERT INTO sets VALUES (dto.id, dto.exerciseId, ...)` it will fail with a NOT NULL constraint or RLS violation on `user_id`.

**How to avoid:**
In the Edge Function, extract `user_id` from the JWT at the top of the handler:
```typescript
const { data: { user } } = await supabase.auth.getUser();
const userId = user?.id;
if (!userId) return new Response('Unauthorized', { status: 401 });
```
Then inject `user_id: userId` into every INSERT for `sets`, `rep_summaries`, and `rep_telemetry`. Do not rely on the mobile DTO to carry `user_id` for these tables.

Also add INSERT policies where they are missing:
```sql
CREATE POLICY "Users can insert own sets"
  ON sets FOR INSERT WITH CHECK ((select auth.uid()) = user_id);
```

**Warning signs:**
- `INSERT` succeeds for `workout_sessions` and `exercises` but fails for `sets` with a 42501 RLS error
- Edge Function returns 200 but sets/summaries are missing from the portal
- Partial data in portal: sessions visible, exercises visible, no set data

**Phase to address:** Phase 2 (Edge Function implementation) — review every INSERT table for RLS completeness before writing the upsert logic.

---

### Pitfall 5: Rep Telemetry Payload Exceeds Edge Function Practical Limits

**What goes wrong:**
`PortalSyncAdapter.toRepTelemetry()` generates 4 time-series arrays per rep (concentric + eccentric for cable A + B). At 50Hz BLE polling, a 5-second rep produces ~250 data points per cable. A set of 10 reps at 50Hz = ~2,000–4,000 `PortalRepTelemetryDto` objects per set. A full workout of 5 exercises × 4 sets = up to 80,000 telemetry rows in a single sync payload.

Each `PortalRepTelemetryDto` serializes to approximately 150–200 bytes of JSON. 80,000 rows ≈ 12–16 MB of JSON. Edge Functions have no documented per-request body size limit in official docs, but community reports (`request entity too large` errors) suggest a practical limit around 10–12 MB, and the 2-second CPU time limit means parsing a 16MB JSON body will likely hit the CPU cap before completing.

Even if the payload fits, the downstream upsert into `rep_telemetry` (thousands of rows) may hit the 150-second wall clock limit on the Free tier.

**Why it happens:**
The PortalSyncAdapter was written correctly for the data model but without accounting for Edge Function constraints. Telemetry sync was listed as "wired into sync payload" in the milestone scope without a batching strategy.

**How to avoid:**
- **Separate telemetry from the main sync payload.** The main sync-push Edge Function handles sessions/exercises/sets/summaries. Telemetry is uploaded via a separate `sync-telemetry` Edge Function, called after the main sync succeeds.
- **Chunk by set.** Upload telemetry one set at a time (one HTTP call per set), not all sets in one call. This keeps each payload under 500KB.
- **Gate telemetry upload on WiFi/charging.** Rep telemetry is not needed for core sync (the portal displays rep summaries, not raw curves). Make telemetry upload opportunistic: only attempt on WiFi, and skip if total row count exceeds a threshold (e.g., 2,000 rows) without user consent.
- **Estimate before sending:** Count `repMetrics.sumOf { concentricTimestamps.size + eccentricTimestamps.size } × 2` before building the payload. If over 1,000 rows, switch to chunked upload mode.

**Warning signs:**
- `413 Request Entity Too Large` from Edge Function on large workouts
- Edge Function times out on workouts with many reps (>50 reps total)
- 2-second CPU limit hit during JSON deserialization in Deno

**Phase to address:** Phase 2 (Edge Function design) — architecture decision before writing the sync-push function. Enforce chunking from day one.

---

### Pitfall 6: Portal-Sourced Data Overwritten by Mobile Sync

**What goes wrong:**
The pull sync path (`pullRemoteChanges()` → `syncRepository.mergeSessions()`) will receive workout sessions, routines, and PRs from the portal. Some of this data was created via the portal UI (not the mobile app). If `mergeSessions` does a naive `INSERT OR REPLACE` (the SQLDelight pattern used by `upsertRoutine`), it will overwrite portal-created records with whatever the mobile has locally — potentially losing portal-sourced workout notes, session annotations, or PRs the user added via web.

Conversely, if the pull sync uses `INSERT OR IGNORE` semantics, mobile-updated records may fail to update portal-sourced records that the mobile has newer data for.

**Why it happens:**
The SyncManager was written assuming mobile is the authoritative source. In a true bidirectional sync, authority depends on which side has the newer timestamp, not which side pushed first.

**How to avoid:**
- Pull responses from the portal must include an `updated_at` timestamp for each record
- `mergeSessions()`, `mergeRoutines()`, etc. must implement timestamp-based conflict resolution: update the local record only if `portal.updatedAt > local.updatedAt`
- For records that only exist on the portal (no local counterpart), always insert
- For records the user deleted locally (soft-deleted with `deletedAt`), do not restore them from pull — check `local.deletedAt != null && portal.updatedAt < local.deletedAt` before merging
- Portal schema: `workout_sessions`, `routines`, `personal_records` tables need `updated_at` columns if not already present (check: `workout_sessions` base schema does NOT have `updated_at`)

**Warning signs:**
- Portal workout notes disappear after mobile sync
- PRs set in the portal web UI revert to mobile values after the next mobile sync
- Routines created in portal get local mobile defaults (e.g., mode reverted to `OLD_SCHOOL`)

**Phase to address:** Phase 3 (Pull sync implementation) — define the merge strategy in writing before implementing `mergeXxx()` functions.

---

### Pitfall 7: SyncManager Sends Legacy Flat DTOs in Parallel with Portal Format

**What goes wrong:**
`SyncManager.pushLocalChanges()` constructs a `SyncPushRequest` using `WorkoutSessionSyncDto`, `RoutineSyncDto`, etc. — the legacy flat format from `SyncModels.kt`. `PortalSyncAdapter` and `PortalSyncPayload` are implemented but **not wired into `SyncManager`**. If the migration is done incrementally — wiring up the new Edge Function endpoint but leaving `pushLocalChanges()` untouched — the Edge Function will receive the legacy flat format and either reject it or silently misparse it.

The `PortalApiClient` hardcodes the Railway URL in `DEFAULT_PORTAL_URL`. If that URL is changed to the Supabase Edge Function URL without also updating the request type, the body format mismatch will produce `400 Bad Request` or a silent deserialization failure on the Deno side.

**Why it happens:**
Two parallel DTO systems exist in the codebase. `SyncManager` was written before `PortalSyncAdapter` was added in v0.5.1. The wiring was explicitly deferred ("v0.5.1 — not yet wired into SyncManager").

**How to avoid:**
- In the same commit that changes `DEFAULT_PORTAL_URL` to the Supabase Edge Function URL, replace `SyncPushRequest` in `pushLocalChanges()` with `PortalSyncPayload`
- Do not leave `SyncPushRequest` as the active push format for any duration — the two endpoints are not format-compatible
- Write a compile-time check or unit test that constructs a `PortalSyncPayload` from test data and verifies it serializes to the expected JSON shape

**Warning signs:**
- Sync returns 400 after URL change but auth is confirmed working
- Edge Function logs show unexpected field names in the request body
- Sync appears to succeed (200) but portal shows no new data

**Phase to address:** Phase 2 (SyncManager wiring) — atomic change: URL + request format swap must happen in one PR, not separately.

---

### Pitfall 8: Schema Migration Fails on NOT NULL Column with Existing Data

**What goes wrong:**
Adding new columns (`superset_id`, `superset_color`, `superset_order`, `per_set_weights`, `per_set_rest`, `is_amrap`, etc.) to `routine_exercises` as `NOT NULL` without a default will fail if any rows exist in that table. Supabase applies migrations against the live database; unlike a local `db reset`, there is data already present from portal users who created routines via the web UI.

The safe migration pattern is always: add as `NULL` → backfill → set `NOT NULL`. The `20260228_rls_denormalization.sql` migration demonstrates this correctly for `user_id`. But new migrations adding the advanced `routine_exercises` columns must follow the same 3-step pattern.

**Why it happens:**
Developers often write migrations assuming an empty database (matching local dev). Supabase's `supabase db diff` will generate `ALTER TABLE ADD COLUMN ... NOT NULL DEFAULT 'x'` which works for new tables but can cause a table rewrite on large tables, acquiring an `ACCESS EXCLUSIVE` lock.

**How to avoid:**
- All new columns on `routine_exercises`: add as nullable with a sensible default, then set NOT NULL only after backfill
- For boolean columns like `is_amrap`, use `NOT NULL DEFAULT false` — PostgreSQL can add this without a table rewrite (it stores the default in the catalog for existing rows)
- For text/JSONB columns (`per_set_weights`, `superset_color`): add as nullable, do not set NOT NULL — these are genuinely optional fields

**Warning signs:**
- Migration fails in production with `ERROR: column "x" of relation "routine_exercises" contains null values`
- Supabase dashboard shows migration in "failed" state
- Local `supabase db reset` succeeds but production apply fails (data present in prod)

**Phase to address:** Phase 4 (Portal DB schema changes) — review every new column for nullable/default correctness before applying.

---

### Pitfall 9: Edge Function Deployed Before Mobile Update — Format Mismatch Window

**What goes wrong:**
In a cross-repo project, the portal Edge Functions are deployed independently from the mobile app. If the Edge Function is deployed first (enforcing the new `PortalSyncPayload` format) and a user runs the old mobile app (which sends `SyncPushRequest`), the Edge Function will reject their sync with 400. Conversely, if the mobile is updated first but the Edge Function isn't deployed, the new app will fail to reach the new endpoint.

This is specifically risky because:
- This is a community project — users may not update the mobile app promptly
- The old `DEFAULT_PORTAL_URL` points to a non-existent Railway backend (already failing), so there's no "current working state" to protect

**Why it happens:**
Two repos, no synchronized deployment pipeline, no API versioning strategy.

**How to avoid:**
- Deploy the Edge Function with **`ignoreUnknownKeys = true`** semantics: accept both the legacy flat fields and the new hierarchical fields, route on payload shape
- Or: deploy the Edge Function with a version prefix (`/v1/sync-push`) and have the old mobile URL point to a different (or no) endpoint
- Since old mobile is already broken (Railway URL doesn't exist), the priority is: deploy Edge Function first, then ship mobile update — there's no regression risk because the old path is already broken
- Document the deployment order explicitly in the milestone

**Warning signs:**
- Users on old app version report sync errors after portal deploy
- New app version fails to sync until Edge Function is deployed

**Phase to address:** Phase 5 (Integration testing) — write an explicit deployment runbook. Clarify that deploy order is: portal schema → Edge Functions → mobile release.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Skip refresh token storage | Faster auth migration | Users "logged out" after 1 hour | Never — access tokens expire |
| Use `service_role` key in Edge Function for all DB operations | Bypasses RLS, simpler code | All users can access each other's data if auth check is wrong | Never for user-data writes |
| Send full telemetry in main sync payload | Single HTTP call | 413 errors on large workouts, 2s CPU timeout | Never — separate telemetry from summary |
| `INSERT OR REPLACE` in pull merge | Simpler merge code | Portal-sourced data silently overwritten | Only if mobile is sole data source (not this project) |
| Hardcode Supabase project URL in `PortalApiClient` | Simpler setup | Cannot switch between staging/prod, secrets in source | Never — use BuildConfig or Settings |
| Skip `updated_at` on `workout_sessions` in portal schema | One less column | Cannot do timestamp-based conflict resolution in pull sync | Never if bidirectional sync is required |

---

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Supabase Edge Functions (Deno) | Importing entire `@supabase/supabase-js` npm package causes bundle size to approach 10MB limit | Use JSR import: `import { createClient } from 'jsr:@supabase/supabase-js@2'` (as process-sync-queue already does) |
| Supabase Edge Functions auth check | Using `service_role` key in an Edge Function called by mobile | Use the user's bearer token: `createClient(url, anonKey, { global: { headers: { Authorization: req.headers.get('Authorization') } } })` so `auth.uid()` resolves correctly and RLS applies |
| Supabase GoTrue (KMP) | Storing only `access_token` | Store both `access_token` and `refresh_token`; refresh on 401 |
| Supabase GoTrue (KMP) | Not implementing token refresh on 401 | Wrap `authenticatedRequest` with a retry-after-refresh interceptor |
| `russhwolf/settings` (KMP) | Storing Supabase JWT in SharedPreferences (Android) | For higher security, use Android Keystore via an expect/actual wrapper; for MVP, SharedPreferences is acceptable since token is bearer-only and rotatable |
| Ktor HTTP client | Sending multiplatform Kotlin serialized JSON with `Double` instead of `Float` | Supabase Postgres `NUMERIC` accepts both, but verify `weightKg: Float` does not serialize as `4.5E0` — use `encodeDefaults = true` and `isLenient = true` in the JSON config (already set in `PortalApiClient`) |
| Supabase RLS + Edge Function | Calling `supabase.from('sets').insert(...)` with service role bypasses RLS silently | Always call `getUser()` first and verify the user matches the data being inserted |
| Supabase `exercises` table INSERT | No INSERT policy defined in base schema | Add explicit INSERT WITH CHECK policy before writing any data via Edge Function |

---

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Sending all rep telemetry in one sync payload | `413 Request Entity Too Large` or 2s CPU timeout | Separate telemetry into chunked per-set uploads via a separate Edge Function | A workout with >8 reps per set across 3+ exercises (common use case) |
| Multi-hop RLS JOINs on `sets`/`rep_summaries`/`rep_telemetry` | Portal query latency spikes, slow dashboard load | Use denormalized `user_id` column with `(select auth.uid())` wrapper — the denormalization migration already handles this | Any table with >10K rows per user |
| Syncing all data since epoch on first sync | First sync never completes, times out | Use `lastSync` timestamp (already in `SyncPushRequest`) — ensure server-side Edge Function respects it with `WHERE updated_at > lastSync` filter | First sync for active users with months of data |
| `UPDATE sets SET user_id = ...` backfill inside migration transaction | Migration times out or blocks on large tables | Use a separate backfill script outside the transaction, then apply constraints | Tables with >100K rows |
| Edge Function cold start on first sync of the day | 400–870ms added to first sync (pre-2025 improvement) | Accept cold starts — recent Supabase improvements bring average cold start to ~42ms, P99 under 500ms. Don't add keep-alive pings. | Always (but now manageable) |

---

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Storing `service_role` key in mobile app | Full database access for anyone who decompiles the APK/IPA | Never. Mobile must use `anon_key` + GoTrue user JWT only. Edge Functions hold `service_role` server-side via Supabase Secrets |
| Trusting `user_id` from mobile DTO body | Any user can sync data for another user's UUID | In Edge Function, always derive `user_id` from `auth.getUser()` on the verified JWT, never from request body |
| Not verifying JWT in Edge Function | Any caller with the anon key can sync arbitrary data | Use `createClient` with the user's bearer token (not service role) so `auth.uid()` is the authenticated user's ID |
| Using `INSERT OR REPLACE` without checking ownership | User A's sync could update User B's record if IDs collide | Use upsert with `WHERE user_id = auth.uid()` in the ON CONFLICT clause |
| Logging full sync payloads | PII (weight, exercise names, timestamps) in Edge Function logs | Do not log request bodies. Log only row counts and status. |

---

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| Re-login prompt on every access token expiry (1 hour) | User feels logged out constantly despite being "premium" | Silent refresh via refresh token; only prompt re-login if refresh token is also expired (>1 week by default) |
| Sync failure during workout due to token expiry | User finishes workout, hits "sync" button, gets auth error | Proactively refresh token at app foreground; always queue sync locally first, upload second |
| Sync blocks main thread during rep telemetry upload | UI freezes or BLE disconnects during upload | Telemetry upload must be off-device-thread, post-workout, opportunistic |
| No feedback when sync partially succeeds | User sees "synced" but half their data is missing | Distinguish between "all records saved", "sessions saved, telemetry queued", and "sync failed" |
| Pull sync overwrites user edits made offline | User edits routine on mobile while offline, next sync reverts it | Timestamp-based merge: local edit wins if `local.updatedAt > portal.updatedAt` |

---

## "Looks Done But Isn't" Checklist

- [ ] **Auth migration:** Verify `getToken()` returns a valid GoTrue JWT (3-segment, `sub` claim = Supabase user UUID) — not a legacy custom token from a prior session
- [ ] **RLS INSERT policies:** Confirm `exercises`, `sets`, `rep_summaries`, `rep_telemetry` all have `FOR INSERT WITH CHECK` policies — base schema only has SELECT policies on child tables
- [ ] **Token refresh:** Verify a sync triggered 90 minutes after login succeeds without re-auth prompt (requires refresh token storage and retry logic)
- [ ] **Telemetry chunking:** Confirm a large workout (10 reps × 5 sets × 3 exercises) does not trigger a 413 or CPU timeout error
- [ ] **Portal-sourced data survival:** Create a routine in the portal web UI, then sync from mobile, confirm the routine is pulled without being overwritten by a mobile-only version
- [ ] **`user_id` injection in Edge Function:** Verify `sets.user_id` is populated from JWT (not from DTO) by checking the DB after a sync and confirming the value matches `auth.uid()` for the test user
- [ ] **Schema migration safety:** Apply the `routine_exercises` advanced columns migration against a database with existing rows (not `db reset`) — confirm no constraint failures
- [ ] **Soft delete survival:** Delete a session locally on mobile, sync, confirm the portal does not re-push it during the next pull
- [ ] **`exercises` table INSERT policy:** This table has only a SELECT policy via JOIN in base schema — confirm INSERT works in Edge Function without 42501 error
- [ ] **Supabase `anon_key` in mobile:** Confirm the mobile app embeds the `anon_key` (not `service_role` key) in `BuildConfig` or equivalent

---

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Sent legacy flat DTO to new Edge Function | LOW | Deploy corrected mobile build; re-sync |
| Auth token mismatch (custom vs GoTrue JWT) | LOW | `clearAuth()` on all users' devices on next app launch; require re-login |
| Missing INSERT RLS policy on `exercises`/`sets` | LOW | Add policy in a new migration; no data loss |
| Telemetry payload too large (413 error) | MEDIUM | Split into per-set upload calls; existing telemetry in SQLDelight is still on device |
| Portal-sourced data overwritten by mobile sync | HIGH | Requires point-in-time recovery from Supabase backup if data was important; prevention is the only reliable strategy |
| NOT NULL migration failure in production | MEDIUM | Roll back migration file, re-apply as nullable with default, re-deploy |
| Service role key exposed in mobile app binary | HIGH | Rotate key immediately in Supabase dashboard (all active sessions invalidated); audit logs for unauthorized access |

---

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| CORS blocks mobile client | Phase 1 — Edge Function scaffolding | Call sync-push from Android with no Origin header; verify 200 response |
| Custom JWT vs GoTrue JWT | Phase 1 — Auth migration | Log `PortalTokenStorage.getToken()` and verify it decodes as a GoTrue JWT |
| No refresh token / silent expiry | Phase 1 — Auth migration | Wait 65 minutes after login, trigger sync, confirm no re-auth prompt |
| Missing `user_id` on nested DTO inserts | Phase 2 — Edge Function implementation | Insert a workout session with exercises, query `sets.user_id` directly |
| Telemetry payload size | Phase 2 — Edge Function design decision | Benchmark: serialize a 10-rep × 3-set workout to JSON, check byte count |
| Portal data overwritten by pull sync | Phase 3 — Pull sync implementation | Create portal-only record, pull sync, verify record unchanged |
| Legacy flat DTO vs PortalSyncPayload | Phase 2 — SyncManager wiring | Unit test: verify `pushLocalChanges()` uses `PortalSyncPayload`, not `SyncPushRequest` |
| NOT NULL migration failure | Phase 4 — Schema migration | Apply migration against a non-empty test DB (not `db reset`) |
| Deployment order mismatch | Phase 5 — Integration | Write and follow a deployment runbook; test with old mobile against new Edge Function |

---

## Sources

- [Supabase Edge Functions Limits](https://supabase.com/docs/guides/functions/limits) — official; CPU 2s, wall clock 150s/400s, memory 256MB, bundle 20MB
- [Persistent Storage and 97% Faster Cold Starts](https://supabase.com/blog/persistent-storage-for-faster-edge-functions) — cold start median now ~42ms
- [Edge Functions request entity too large discussion](https://github.com/orgs/supabase/discussions/20864) — practical body size limit ~10MB
- [Supabase RLS Performance and Best Practices](https://supabase.com/docs/guides/troubleshooting/rls-performance-and-best-practices-Z5Jjwv) — multi-hop JOIN anti-pattern
- [Supabase CORS for Edge Functions](https://supabase.com/docs/guides/functions/cors) — manual CORS required
- [GoTrue token refresh on mobile sleep](https://github.com/supabase/supabase/issues/6464) — timer doesn't survive sleep mode
- [Supabase Auth Sessions](https://supabase.com/docs/guides/auth/sessions) — 1-hour access token, refresh token mechanics
- [Supabase API Keys — service role security](https://supabase.com/docs/guides/api/api-keys) — never embed in mobile
- [Supabase security retro 2025](https://supabase.com/blog/supabase-security-2025-retro) — RLS bypass patterns
- [supabase-kt GitHub](https://github.com/supabase-community/supabase-kt) — KMP Supabase client; available alternative to raw Ktor
- [Offline-first sync pitfalls (PowerSync analysis)](https://www.powersync.com/blog/bringing-offline-first-to-supabase) — conflict resolution, tombstones
- [Handling deletions in bidirectional sync](https://www.datasyncbook.com/content/handling-deletions/) — soft delete / tombstone patterns
- Direct codebase analysis: `SyncManager.kt`, `PortalApiClient.kt`, `PortalTokenStorage.kt`, `PortalSyncDtos.kt`, `PortalSyncAdapter.kt`, `SyncModels.kt`
- Direct portal analysis: `supabase/migrations/00002_base_schema.sql`, `20260228_rls_denormalization.sql`, `supabase/functions/_shared/cors.ts`

---
*Pitfalls research for: KMP mobile ↔ Supabase bidirectional sync (v0.6.0 Portal Sync Compatibility)*
*Researched: 2026-03-02*
