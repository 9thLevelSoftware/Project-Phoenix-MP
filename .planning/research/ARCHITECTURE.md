# Architecture Research

**Domain:** KMP mobile app + Supabase portal bidirectional sync
**Researched:** 2026-03-02
**Confidence:** HIGH (based on direct code inspection of both repos)

---

## Standard Architecture

### System Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                         MOBILE APP (KMP)                              │
│                                                                        │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │                    UI Layer (Compose Multiplatform)               │ │
│  │  LinkAccountScreen ─── SyncStatusBanner ─── WorkoutCompleteHUD   │ │
│  └─────────────────────────────┬────────────────────────────────────┘ │
│                                 │ StateFlow                            │
│  ┌──────────────────────────────▼────────────────────────────────────┐ │
│  │                 ViewModel Layer (LinkAccountViewModel)             │ │
│  └──────────────────────────────┬────────────────────────────────────┘ │
│                                 │                                      │
│  ┌──────────────────────────────▼────────────────────────────────────┐ │
│  │  SyncTriggerManager  ───►  SyncManager                            │ │
│  │  (throttle/offline)        (orchestrates push+pull)               │ │
│  └──────────────────────────────┬────────────────────────────────────┘ │
│                                 │                                      │
│  ┌────────────────┬─────────────▼──────────────┬──────────────────┐  │
│  │ PortalApiClient│    PortalSyncAdapter        │ SqlDelightSync   │  │
│  │ (Ktor HTTP)    │    (data transformation)    │ Repository       │  │
│  │                │    • 3-tier grouping         │ (SQLDelight DB)  │  │
│  │                │    • unit conversion         │                  │  │
│  │                │    • DTO assembly            │                  │  │
│  └────────┬───────┴────────────────────────────┴──────────────────┘  │
│           │ HTTPS                                                      │
└───────────┼────────────────────────────────────────────────────────────┘
            │
            │  Bearer: Supabase JWT (access_token from auth.signIn)
            │
┌───────────┼────────────────────────────────────────────────────────────┐
│           ▼              PORTAL (Supabase)                              │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                  Edge Functions (Deno/TypeScript)                │   │
│  │                                                                  │   │
│  │  mobile-sync-push ──► validates JWT, verifies user_id claim     │   │
│  │  mobile-sync-pull ──► queries modified since lastSync           │   │
│  │                                                                  │   │
│  │  (existing: strava-sync, fitbit-sync, process-sync-queue)       │   │
│  └────────────────────────────┬────────────────────────────────────┘   │
│                               │ service_role (bypasses RLS)            │
│  ┌────────────────────────────▼────────────────────────────────────┐   │
│  │                    Supabase PostgreSQL + RLS                     │   │
│  │                                                                  │   │
│  │  workout_sessions  exercises  sets  rep_summaries  rep_telemetry │   │
│  │  routines  routine_exercises  personal_records  exercise_progress│   │
│  │  rpg_attributes  earned_badges  gamification_stats               │   │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │               React SPA (Vite + Supabase JS client)              │  │
│  │               AuthProvider ──► supabase.auth.onAuthStateChange   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Location | Current State | v0.6.0 Role |
|-----------|----------|---------------|-------------|
| `SyncManager` | `data/sync/SyncManager.kt` | Calls Railway backend via legacy SyncPushRequest | **Modified**: wire to PortalSyncAdapter + PortalSyncPayload; auth via Supabase JWT |
| `PortalApiClient` | `data/sync/PortalApiClient.kt` | Points to `phoenix-portal-backend.up.railway.app` | **Modified**: base URL → Supabase Edge Function URLs; auth header stays Bearer |
| `PortalSyncAdapter` | `data/sync/PortalSyncAdapter.kt` | Full 3-tier transformation logic complete | **Unchanged**: already correct; just needs to be called from SyncManager |
| `PortalSyncDtos` | `data/sync/PortalSyncDtos.kt` | Portal-format DTOs complete | **Unchanged**: wire format correct; add pull-side DTOs if missing |
| `PortalTokenStorage` | `data/sync/PortalTokenStorage.kt` | Stores custom JWT from Railway auth | **Modified**: store Supabase access_token + refresh_token; add user_id from JWT claims |
| `SyncTriggerManager` | `data/sync/SyncTriggerManager.kt` | Throttle + failure tracking | **Unchanged**: orchestration logic is correct |
| `PortalAuthRepository` | `data/repository/PortalAuthRepository.kt` | Delegates to PortalApiClient (Railway) | **Replaced or wrapper**: delegate to Supabase Auth REST instead of Railway |
| `SyncModule` (Koin) | `di/SyncModule.kt` | Wires existing components | **Modified**: add Supabase base URL config; no new Koin bindings needed |
| `mobile-sync-push` | `supabase/functions/` | Does not exist | **New**: Deno Edge Function in portal repo |
| `mobile-sync-pull` | `supabase/functions/` | Does not exist | **New**: Deno Edge Function in portal repo |
| Portal DB migrations | `supabase/migrations/` | Missing RPG tables, routine_exercises advanced cols | **New migrations**: 3 migration files |

---

## Recommended Project Structure

### Mobile (Project-Phoenix-MP) — Modified Files

```
shared/src/commonMain/kotlin/com/devil/phoenixproject/
├── data/sync/
│   ├── PortalApiClient.kt        MODIFY: base URL config, Supabase JWT auth
│   ├── PortalTokenStorage.kt     MODIFY: store Supabase tokens (access + refresh)
│   ├── SyncManager.kt            MODIFY: use PortalSyncAdapter for push, pull response mapping
│   ├── PortalSyncAdapter.kt      UNCHANGED (complete)
│   ├── PortalSyncDtos.kt         UNCHANGED push side; add pull response DTOs
│   ├── PortalMappings.kt         UNCHANGED
│   ├── SyncModels.kt             ADD: PortalPullResponse DTO; deprecate legacy flat DTOs
│   └── SyncTriggerManager.kt     UNCHANGED
├── data/repository/
│   ├── PortalAuthRepository.kt   MODIFY: swap Railway calls for Supabase Auth REST
│   └── SqlDelightSyncRepository  ADD: getRpgAttributesForSync(), getExerciseProgress()
└── di/
    └── SyncModule.kt             MODIFY: Supabase URL constant, no structural change
```

### Portal (phoenix-portal) — New Files

```
supabase/
├── functions/
│   ├── mobile-sync-push/
│   │   └── index.ts              NEW: accepts PortalSyncPayload, upserts all tables
│   └── mobile-sync-pull/
│       └── index.ts              NEW: queries all tables since lastSync, returns pull DTO
└── migrations/
    ├── 20260302_routine_exercises_advanced.sql   NEW: superset/per-set cols
    ├── 20260302_rpg_gamification_tables.sql      NEW: rpg_attributes, earned_badges, gamification_stats
    └── 20260302_workout_mode_display.sql         NEW: mode display mapping function/view (optional)
```

### Structure Rationale

- **No new Koin modules**: The sync subsystem already has a dedicated `SyncModule`. Auth changes are internal to `PortalAuthRepository` and `PortalTokenStorage`. Adding a new module would require changes in `appModule` and `KoinInit` with no benefit.
- **PortalApiClient stays**: It's a clean Ktor wrapper. The only change is the base URL (Railway → Supabase Edge Function URL) and ensuring the Bearer token is the Supabase `access_token`. The API surface is compatible.
- **Edge Functions split by direction**: `mobile-sync-push` and `mobile-sync-pull` are separate functions because they have very different RLS implications (push uses service_role, pull respects RLS), different payload sizes, and separate failure modes. One combined function creates a 30s timeout risk.
- **Migrations in portal repo**: Portal owns its schema. Mobile does not touch SQL migrations.

---

## Architectural Patterns

### Pattern 1: Supabase JWT as Mobile Bearer Token

**What:** Mobile calls `POST /auth/v1/token` (Supabase Auth REST) directly via Ktor. The returned `access_token` is a standard JWT signed by Supabase. This same JWT is sent as `Authorization: Bearer <token>` to Edge Functions, which validate it automatically via Supabase's built-in JWT verification.

**When to use:** This replaces the Railway custom token flow. The mobile never needs the Supabase JS SDK — raw Ktor HTTP calls to Supabase Auth REST endpoints are sufficient.

**Trade-offs:**
- Pro: No new dependency (Supabase KMP SDK is heavy; raw Ktor calls are already present)
- Pro: JWT automatically verified by Edge Function runtime (no custom auth middleware)
- Con: Must handle token refresh manually (store refresh_token, call `/auth/v1/token?grant_type=refresh_token` when 401 received)
- Con: JWT expiry is 1 hour by default; must implement refresh before expiry

**Example (Ktor call to Supabase Auth):**
```kotlin
// PortalApiClient: sign in
suspend fun signInWithSupabase(email: String, password: String): Result<SupabaseAuthResponse> {
    return try {
        val response = httpClient.post("$supabaseUrl/auth/v1/token?grant_type=password") {
            header("apikey", supabaseAnonKey)
            setBody(mapOf("email" to email, "password" to password))
        }
        handleResponse(response)
    } catch (e: Exception) {
        Result.failure(PortalApiException("Sign in failed: ${e.message}", e))
    }
}
```

```kotlin
// PortalTokenStorage: new fields needed
const val KEY_ACCESS_TOKEN = "supabase_access_token"
const val KEY_REFRESH_TOKEN = "supabase_refresh_token"
const val KEY_TOKEN_EXPIRY = "supabase_token_expiry_ms"
const val KEY_USER_ID = "supabase_user_id"     // auth.uid() — needed for RLS
```

### Pattern 2: PortalSyncAdapter as Push Transformer (Already Written)

**What:** `SyncManager.pushLocalChanges()` currently builds a legacy `SyncPushRequest` with flat `WorkoutSessionSyncDto` list. It must instead: (1) query sessions WITH rep metrics from repositories, (2) call `PortalSyncAdapter.toPortalWorkoutSessions()`, (3) build `PortalSyncPayload`, (4) POST to `mobile-sync-push` Edge Function.

**When to use:** Any time `SyncManager.pushLocalChanges()` is called. The adapter is already correct — the issue is that `SyncManager` never calls it.

**Trade-offs:**
- Pro: Adapter logic is fully tested by commit 3737fa73; no transformation code to write
- Con: `SyncRepository` interface needs new methods to supply `SessionWithReps` (currently returns flat `WorkoutSessionSyncDto`, not domain `WorkoutSession` with reps)

**Example refactored push:**
```kotlin
// SyncManager.pushLocalChanges() — new shape
private suspend fun pushLocalChanges(): Result<PortalSyncPushResponse> {
    val userId = tokenStorage.getUserId()  // NEW: Supabase auth.uid()
    val lastSync = tokenStorage.getLastSyncTimestamp()

    // Requires new repository method returning domain objects + rep data
    val sessionsWithReps = syncRepository.getSessionsWithRepsSince(lastSync)
    val routines = syncRepository.getRoutineDomainObjectsSince(lastSync)
    val rpgAttributes = syncRepository.getRpgAttributesForSync()
    val badges = syncRepository.getEarnedBadgesForSync(lastSync)
    val gamificationStats = syncRepository.getGamificationStatsForSync()

    val payload = PortalSyncPayload(
        deviceId = tokenStorage.getDeviceId(),
        platform = getPlatformName(),
        lastSync = lastSync,
        sessions = PortalSyncAdapter.toPortalWorkoutSessions(sessionsWithReps, userId),
        routines = routines.map { PortalSyncAdapter.toPortalRoutine(it, userId) },
        rpgAttributes = rpgAttributes?.toDto(userId),
        badges = badges.map { it.toPortalDto(userId) },
        gamificationStats = gamificationStats?.toPortalDto(userId)
    )

    return apiClient.pushPayload(payload)
}
```

### Pattern 3: Edge Function service_role for Upserts

**What:** `mobile-sync-push` uses the Supabase `SERVICE_ROLE_KEY` (not the anon key) to bypass RLS when writing data. It first validates the incoming JWT to extract the `user_id`, then injects that `user_id` into all insert/upsert payloads explicitly. This satisfies RLS because the data is written with the correct `user_id` even though the INSERT is done with service_role.

**When to use:** Any Edge Function that receives a mobile push payload. Never use anon key in Edge Functions that write data — it requires the user's session token for RLS compliance, which makes bulk upserts slower.

**Trade-offs:**
- Pro: RLS policies satisfied without multi-hop JOIN overhead
- Pro: Transactional batch insert possible (all tables in one function call)
- Con: service_role never leaves the server — correct, but requires careful JWT validation upfront
- Con: Must validate that `user_id` in payload matches JWT `sub` claim to prevent user A writing user B's data

**Example Edge Function structure:**
```typescript
// supabase/functions/mobile-sync-push/index.ts
Deno.serve(async (req) => {
  // 1. Validate JWT — built-in by Supabase runtime when verify_jwt = true (default)
  const authHeader = req.headers.get('Authorization');
  // Supabase runtime auto-rejects invalid JWTs before handler runs

  // 2. Extract user_id from JWT claims
  const supabaseAdmin = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
  );
  const { data: { user } } = await supabaseAdmin.auth.getUser(
    authHeader?.replace('Bearer ', '') ?? ''
  );
  const userId = user?.id;

  // 3. Parse payload and validate user_id claim
  const payload: PortalSyncPayload = await req.json();
  // Reject if payload user_id doesn't match JWT sub
  for (const session of payload.sessions) {
    if (session.user_id !== userId) return new Response('Forbidden', { status: 403 });
  }

  // 4. Upsert all tables using service_role client
  await upsertWorkoutSessions(supabaseAdmin, payload.sessions);
  await upsertRoutines(supabaseAdmin, payload.routines, userId);
  await upsertRpgAttributes(supabaseAdmin, payload.rpg_attributes, userId);
  // ...

  return new Response(JSON.stringify({ sync_time: Date.now() }), { status: 200 });
});
```

### Pattern 4: Pull Response as Portal-Native DTOs

**What:** `mobile-sync-pull` returns data in the portal's own schema format (matching column names). Mobile receives this and the reverse adapter maps portal DTOs back to `WorkoutSessionSyncDto` / `RoutineSyncDto` etc. for `SqlDelightSyncRepository.merge*()` methods. This avoids a separate mobile-specific pull format.

**When to use:** Pull direction only. Push uses `PortalSyncPayload` (mobile-native). Pull uses portal-native column names.

**Trade-offs:**
- Pro: Pull function simply queries tables with standard Supabase client; no custom serialization
- Con: Mobile needs a reverse adapter (portal → mobile DTOs) for merge operations
- Con: Adds `user_id` fields to pull responses which mobile already knows (redundant but harmless)

---

## Data Flow

### Push Sync: Mobile → Portal

```
WorkoutSession (SQLDelight DB)
    + RepMetricData (SQLDelight DB)
    + RepBiomechanics (SQLDelight DB)
    ↓
SqlDelightSyncRepository.getSessionsWithRepsSince(lastSync)
    ↓ SessionWithReps[]
PortalSyncAdapter.toPortalWorkoutSessions(sessionsWithReps, userId)
    ↓ groups by routineSessionId, converts units (mm/s→m/s, kg→N)
PortalSyncPayload (sessions + routines + rpg + badges + gamification)
    ↓ serialized JSON (kotlinx.serialization)
PortalApiClient.pushPayload(payload)
    POST /functions/v1/mobile-sync-push
    Authorization: Bearer <supabase_access_token>
    ↓
[Edge Function] validates JWT → extracts user_id → validates payload user_ids
    ↓ service_role Supabase client
Supabase PostgreSQL tables:
    workout_sessions (UPSERT ON CONFLICT id)
    exercises       (UPSERT ON CONFLICT id)
    sets            (UPSERT ON CONFLICT id) — must include user_id (denormalized)
    rep_summaries   (UPSERT ON CONFLICT id) — must include user_id (denormalized)
    rep_telemetry   (INSERT — no upsert, append-only)
    routines        (UPSERT ON CONFLICT id)
    routine_exercises (UPSERT ON CONFLICT id)
    rpg_attributes  (UPSERT ON CONFLICT user_id)
    earned_badges   (INSERT ON CONFLICT DO NOTHING)
    gamification_stats (UPSERT ON CONFLICT user_id)
    ↓ returns
{ sync_time: Long, id_mappings: {...} }
    ↓
PortalApiClient → SyncManager → tokenStorage.setLastSyncTimestamp()
```

### Pull Sync: Portal → Mobile

```
SyncManager.pullRemoteChanges()
    ↓
PortalApiClient.pullChanges(PortalPullRequest(deviceId, lastSync))
    POST /functions/v1/mobile-sync-pull
    Authorization: Bearer <supabase_access_token>
    ↓
[Edge Function] validates JWT → extracts user_id
    Supabase anon client with user JWT (respects RLS automatically)
    SELECT from workout_sessions WHERE user_id = auth.uid() AND updated_at > lastSync
    SELECT from routines WHERE user_id = auth.uid() AND updated_at > lastSync
    SELECT from personal_records WHERE user_id = auth.uid() AND updated_at > lastSync
    SELECT from rpg_attributes WHERE user_id = auth.uid()
    SELECT from gamification_stats WHERE user_id = auth.uid()
    ↓ returns PortalPullResponse (portal column names)
    ↓
PortalPullResponse → reverse-adapt → legacy merge DTOs
SqlDelightSyncRepository.mergeSessions() / mergeRoutines() / mergePRs() etc.
    (existing merge logic unchanged)
```

### Auth Flow: Supabase JWT Acquisition

```
User enters email + password in LinkAccountScreen
    ↓
LinkAccountViewModel.login()
    ↓
SyncManager.login() → PortalApiClient.signInWithSupabase()
    POST https://<project>.supabase.co/auth/v1/token?grant_type=password
    Headers: apikey: <anon_key>
    Body: { email, password }
    ↓
SupabaseAuthResponse { access_token, refresh_token, expires_in, user: { id, email } }
    ↓
PortalTokenStorage.saveAuth():
    • access_token  → KEY_ACCESS_TOKEN
    • refresh_token → KEY_REFRESH_TOKEN
    • expires_at    → KEY_TOKEN_EXPIRY (now + expires_in * 1000)
    • user.id       → KEY_USER_ID (critical for RLS payload stamping)
    • user.email    → KEY_USER_EMAIL
    ↓
PortalUser(id=user.id, email=user.email, isPremium=false)
```

### Token Refresh Flow

```
PortalApiClient.authenticatedRequest() detects 401 response
    OR PortalTokenStorage.getToken() checks expiry before returning token
    ↓
POST /auth/v1/token?grant_type=refresh_token
    Body: { refresh_token: stored_refresh_token }
    ↓
New { access_token, refresh_token, expires_in }
    ↓
PortalTokenStorage.saveAuth() with new tokens
    ↓
Retry original request with new access_token
```

---

## Integration Points

### Mobile Repo: Files That Change

| File | Change Type | Description |
|------|-------------|-------------|
| `data/sync/PortalApiClient.kt` | Modify | Change `DEFAULT_PORTAL_URL` to Supabase Edge Function base; change auth methods to call Supabase Auth REST; add `pushPayload(PortalSyncPayload)` method; add auto-refresh on 401 |
| `data/sync/PortalTokenStorage.kt` | Modify | Add `KEY_REFRESH_TOKEN`, `KEY_TOKEN_EXPIRY`, change `saveAuth()` to accept `SupabaseAuthResponse`; add `getUserId()` getter |
| `data/sync/SyncManager.kt` | Modify | Replace `pushLocalChanges()` to use adapter; require `userId` from tokenStorage; update response type from `SyncPushResponse` to `PortalSyncPushResponse` |
| `data/sync/SyncModels.kt` | Modify | Add `SupabaseAuthResponse` DTO, `PortalSyncPushResponse`, `PortalPullResponse`, `PortalPullRequest`; deprecate `SyncPushRequest`, `SyncPullRequest` (keep for existing merge compatibility) |
| `data/repository/PortalAuthRepository.kt` | Modify | Swap `apiClient.login()` for `apiClient.signInWithSupabase()`; update `PortalAuthResponse → SupabaseAuthResponse` |
| `data/repository/SyncRepository.kt` | Modify | Add `getSessionsWithRepsSince()`, `getRoutineDomainObjectsSince()`, `getRpgAttributesForSync()`, `getExerciseProgressForSync()` |
| `data/repository/SqlDelightSyncRepository.kt` | Modify | Implement new interface methods above |
| `di/SyncModule.kt` | Modify | Add Supabase URL + anon key constants or inject from config; no structural Koin changes |

### Mobile Repo: New Files

| File | Description |
|------|-------------|
| `data/sync/SupabaseAuthResponse.kt` | DTO for Supabase auth REST response (access_token, refresh_token, expires_in, user) |
| `data/sync/PortalPullResponseDtos.kt` | Pull-direction DTOs matching portal column names; reverse-adapter methods |

### Portal Repo: New Files

| File | Description |
|------|-------------|
| `supabase/functions/mobile-sync-push/index.ts` | Edge Function: receive PortalSyncPayload, validate JWT, upsert all tables |
| `supabase/functions/mobile-sync-pull/index.ts` | Edge Function: validate JWT, query all changed tables, return pull response |
| `supabase/migrations/20260302_routine_exercises_advanced.sql` | Add superset_id, superset_color, superset_order, per_set_weights, per_set_rest, is_amrap, pr_percentage, rep_count_timing, stop_at_position, stall_detection, eccentric_load, echo_level to routine_exercises |
| `supabase/migrations/20260302_rpg_gamification_tables.sql` | Create rpg_attributes, earned_badges, gamification_stats with RLS |
| `supabase/migrations/20260302_workout_sessions_routing_session_id.sql` | Add routine_session_id column to workout_sessions table |

### Portal Repo: Modified Files

| File | Change |
|------|--------|
| `supabase/functions/_shared/cors.ts` | Add Edge Function URL patterns for mobile (mobile doesn't send Origin header; CORS not needed for mobile clients — verify and document) |
| `supabase/config.toml` | Add `[functions.mobile-sync-push]` and `[functions.mobile-sync-pull]` entries (no JWT override needed — default verify_jwt = true is correct) |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| SyncManager ↔ PortalSyncAdapter | Direct call (object singleton) | No DI injection needed; adapter is stateless object |
| SyncManager ↔ SyncRepository | Koin-injected interface | Add new methods to interface + implementation; don't break existing `merge*()` callers |
| PortalApiClient ↔ PortalTokenStorage | tokenProvider lambda (already in place) | Extend to return `access_token` specifically; add expiry check |
| Edge Function ↔ Supabase DB | service_role client for push; anon+JWT client for pull | Push needs service_role to bypass RLS; pull can use user JWT for natural RLS filtering |
| Mobile app ↔ Edge Function | HTTPS POST via Ktor | No websocket, no polling — stateless request/response per sync |

---

## Build Order (Cross-Repo Dependencies)

The dependencies between mobile and portal changes create a strict ordering constraint:

**Phase A: Portal DB foundation (portal repo, unblocks everything)**
1. Run migration: `rpg_gamification_tables.sql` — creates tables that mobile push will write to
2. Run migration: `routine_exercises_advanced.sql` — adds columns mobile push sends
3. Run migration: `workout_sessions_routine_session_id.sql` — adds column for grouped sessions

**Phase B: Portal Edge Functions (portal repo, unblocks mobile push)**
4. Write and deploy `mobile-sync-push/index.ts` — mobile push target
5. Write and deploy `mobile-sync-pull/index.ts` — mobile pull source

**Phase C: Mobile auth migration (mobile repo, unblocks sync)**
6. Update `PortalTokenStorage` — new token fields (access_token, refresh_token, user_id)
7. Update `PortalApiClient` — Supabase Auth REST calls, new base URL
8. Update `PortalAuthRepository` — wire to new client methods
9. Update `SyncModels` — new DTOs (SupabaseAuthResponse, PortalSyncPushResponse, PortalPullResponse)

**Phase D: Mobile push wire-up (mobile repo, requires A+B+C)**
10. Update `SyncRepository` interface — add `getSessionsWithRepsSince()` etc.
11. Update `SqlDelightSyncRepository` — implement new interface methods
12. Update `SyncManager.pushLocalChanges()` — use adapter + new payload format

**Phase E: Mobile pull wire-up (mobile repo, requires B+C)**
13. Write `PortalPullResponseDtos.kt` — pull DTOs + reverse adapter
14. Update `SyncManager.pullRemoteChanges()` — use new pull endpoint + reverse adapt

**Phase F: End-to-end validation**
15. Integration test: push workout session → verify in Supabase dashboard
16. Integration test: pull from portal → verify merged into SQLDelight
17. Fix duration unit (ms → s already in adapter; verify SyncManager passes ms or s)
18. Fix mode display mapping on portal (query uses raw SCREAMING_SNAKE; add display label map)

**Parallelizable work:**
- Phase A migrations (1-3) can all run in one `supabase db push`
- Phase B functions (4-5) can be written in parallel; deployed together
- Phase C mobile auth (6-9) can be done before portal functions are deployed (just won't work end-to-end yet)

---

## Scaling Considerations

| Scale | Architecture Adjustments |
|-------|--------------------------|
| 0-1k users | Current approach is fine; Edge Functions cold start (~200ms) acceptable |
| 1k-10k users | Consider Supabase connection pooling (PgBouncer already enabled by default); rep_telemetry table will grow fast — add partition by user_id or month |
| 10k+ users | rep_telemetry needs TTL/archival policy; consider async push (queue-based) so mobile doesn't wait for telemetry insert |

### Scaling Priorities

1. **First bottleneck:** `rep_telemetry` table size. Each rep generates ~40 time-series points at 50Hz. One workout session can produce 5000+ rows. Add a 90-day retention policy or move to time-series storage (TimescaleDB extension on Supabase).
2. **Second bottleneck:** Edge Function cold starts under burst load. Supabase Edge Functions are Deno Deploy backed — warm instances exist after first call. Not a concern at community scale.

---

## Anti-Patterns

### Anti-Pattern 1: Calling Supabase JS Client from KMP

**What people do:** Add `io.github.jan-tennessen:supabase-kt` to the KMP module to get "native" Supabase support.

**Why it's wrong:** The Supabase KMP library is heavyweight (pulls in Ktor + Kotlin serialization already in the project, creating version conflicts), has iOS linking requirements (Supabase Swift SDK linkage for Apple auth flows), and adds ~3MB to the binary. The project already has Ktor with `ContentNegotiation` and JSON serialization. The Supabase REST API is just HTTP — raw Ktor calls are sufficient and already the established pattern.

**Do this instead:** Keep `PortalApiClient` as a plain Ktor client. Add two new endpoints: `POST /auth/v1/token` (sign in), `POST /auth/v1/token?grant_type=refresh_token` (refresh). This is ~30 lines of code total.

### Anti-Pattern 2: Sending rep_telemetry in Every Sync

**What people do:** Include all `rep_telemetry` points in every `PortalSyncPayload` push.

**Why it's wrong:** A single workout session can generate 2000-8000 telemetry data points. Including these in the sync payload balloons the request to 500KB-2MB, exceeding Edge Function payload limits (6MB default) under moderate use and creating timeout risk (30s Edge Function limit).

**Do this instead:** Make telemetry sync opt-in or separate. Push workout sessions, sets, and rep_summaries in the main sync payload. Push telemetry as a separate background job triggered only when on WiFi. The portal analytics already work with rep_summaries alone — telemetry is only needed for force curve visualization (premium feature).

### Anti-Pattern 3: Assuming User ID in Payload is Trusted

**What people do:** Trust `user_id` in the JSON payload from mobile without verifying against the JWT.

**Why it's wrong:** A malicious actor could send a valid JWT for user A but a payload with `user_id` set to user B, overwriting another user's data despite passing RLS checks (since the write uses service_role).

**Do this instead:** In `mobile-sync-push`, always extract `user_id` from `supabaseAdmin.auth.getUser(token)`, then overwrite or validate every `user_id` field in the payload against this value before any database write.

### Anti-Pattern 4: One Giant Edge Function for All Sync

**What people do:** Create a single `mobile-sync` function that handles auth, push, pull, and status in one handler.

**Why it's wrong:** Edge Functions have a 30-second execution limit. A large sync (many sessions, lots of rep data) can easily exceed this when doing sequential upserts. Combining push + pull also makes retries dangerous (push succeeds, pull fails, retry re-pushes data).

**Do this instead:** Separate functions for push and pull. Keep each idempotent (upsert, not insert). Mobile retries push independently of pull.

### Anti-Pattern 5: Legacy SyncPushRequest Mixed with New Flow

**What people do:** Keep `SyncManager.pushLocalChanges()` partially using the old `SyncPushRequest` and partially using `PortalSyncPayload`, bridging them in the method.

**Why it's wrong:** The two formats are fundamentally different (flat vs 3-tier hierarchy). Mixing them creates serialization confusion, partial data sync, and untestable code paths.

**Do this instead:** Make the cutover clean. `SyncManager.pushLocalChanges()` exclusively uses `PortalSyncPayload`. The old DTOs (`WorkoutSessionSyncDto`, `SyncPushRequest`, etc.) are kept only for the pull-side merge operations which they already service correctly.

---

## Sources

- Direct code inspection: `PortalApiClient.kt`, `SyncManager.kt`, `PortalSyncAdapter.kt`, `PortalSyncDtos.kt`, `SyncModels.kt`, `PortalTokenStorage.kt`, `SyncModule.kt` (HIGH confidence)
- Portal schema: `supabase/migrations/00002_base_schema.sql`, `20260217_phase10_tables.sql`, `20260228_rls_denormalization.sql` (HIGH confidence)
- Portal Edge Function pattern: `process-sync-queue/index.ts`, `_shared/cors.ts` (HIGH confidence)
- Portal auth: `src/providers/AuthProvider.tsx`, `src/lib/supabase.ts` (HIGH confidence)
- Koin DI graph: `di/AppModule.kt`, `di/SyncModule.kt`, `di/DataModule.kt`, `di/PlatformModule.android.kt` (HIGH confidence)
- Supabase Auth REST API format: standard Supabase JWT/token endpoint (MEDIUM confidence — verified against known Supabase API contract, not re-fetched)

---

*Architecture research for: Portal Sync Compatibility (v0.6.0)*
*Researched: 2026-03-02*
