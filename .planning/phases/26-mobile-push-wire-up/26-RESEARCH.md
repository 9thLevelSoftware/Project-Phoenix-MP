# Phase 26: Mobile Push Wire-Up - Research

**Researched:** 2026-03-02
**Domain:** Kotlin Multiplatform — Ktor HTTP client, Koin DI, SQLDelight, Supabase Edge Functions
**Confidence:** HIGH

## Summary

Phase 26 is a wiring phase, not a greenfield build. All the constituent pieces exist and have been built correctly in prior phases. `PortalSyncDtos.kt` and `PortalSyncAdapter.kt` were added in commit 3737fa73 but are not yet connected to `SyncManager`. `PortalApiClient` still calls the legacy Railway URL (`https://phoenix-portal-backend.up.railway.app/api/sync/push`) using the legacy flat `SyncPushRequest`. The task is to atomically cut `SyncManager.pushLocalChanges()` over from the legacy flow to the portal flow in a single commit.

The deployed `mobile-sync-push` Edge Function (in phoenix-portal) accepts a `PortalSyncPayload`-shaped camelCase JSON body. It handles `exercise_progress` and `personal_records` server-side — the mobile client does not need to compute or transmit them as separate fields. The Edge Function extracts `user_id` from the JWT, so the mobile app should never trust or pass its own `user_id` in the DTO body (the Edge Function overwrites it anyway). PUSH-03 and PUSH-04 are satisfied server-side.

The `PortalSyncPayload` already has gamification fields (`rpgAttributes`, `badges`, `gamificationStats`). The mobile side needs to fetch these from `GamificationRepository` and the SQLDelight `RpgAttributes` table and map them to the portal DTOs. Rep telemetry is already fully excluded from `PortalSyncPayload` — `PortalSyncAdapter.toRepTelemetry()` exists but is not referenced in the sync path, satisfying PUSH-05 by omission.

The `SyncManager.sync()` flow calls `getSyncStatus()` first (Railway), then push, then pull. The status check and pull also need to move to Supabase Edge Function URLs as part of the cutover, or the status check must be removed / made non-fatal when the Railway backend is gone.

**Primary recommendation:** Replace `SyncManager.pushLocalChanges()` body entirely in one atomic commit: build `PortalSyncPayload` via `PortalSyncAdapter`, add a `pushPortalPayload(PortalSyncPayload)` method to `PortalApiClient` that POSTs to `{supabaseUrl}/functions/v1/mobile-sync-push` with `Authorization: Bearer {token}`, and remove or bypass the Railway status check that currently gates the push.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| PUSH-01 | SyncManager.pushLocalChanges() uses PortalSyncAdapter to build hierarchical PortalSyncPayload instead of legacy flat SyncPushRequest | PortalSyncAdapter.toPortalWorkoutSessions() + toPortalRoutine() already produce the right DTOs; SyncManager just needs to call them |
| PUSH-02 | Push sync includes user_id on all nested DTOs or Edge Function injects it server-side | Edge Function overwrites user_id from JWT on every row insert — mobile DTOs can include userId from tokenStorage.currentUser but the server value is authoritative |
| PUSH-03 | Push sync includes exercise_progress records computed from session/rep data | Fully handled server-side by the Edge Function (Brzycki 1RM, max_weight, total_volume, max_reps, set_count). Mobile sends nothing extra |
| PUSH-04 | Push sync includes personal_records matching portal schema | Handled server-side: Edge Function extracts is_pr=true sets and inserts personal_records rows. Mobile sets PortalSetDto.isPr = true where appropriate |
| PUSH-05 | Rep telemetry wired into sync payload and chunked per-set to stay within body size limits | Decision from STATE.md: rep telemetry EXCLUDED from main sync payload — deferred to v0.6.1. PortalSyncPayload has no telemetry field; PUSH-05 is satisfied by explicit exclusion |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Ktor Client | 3.x (project-configured) | HTTP POST to Edge Function | Already in use in PortalApiClient |
| kotlinx.serialization | 1.x (project-configured) | JSON encode PortalSyncPayload | Already configured with encodeDefaults=true, ignoreUnknownKeys=true |
| Koin | 4.1.1 | DI wiring — inject GamificationRepository into SyncManager | Already project-standard |
| SQLDelight | 2.2.1 | Read RPG attributes, badges, gamification stats for sync | Already project-standard |
| kotlinx.coroutines | 1.10.2 | IO dispatcher for DB reads | Already project-standard |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| co.touchlab.kermit | project-configured | Debug logging of push payload | Add log lines for the payload size and counts |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Manual PortalSyncPayload assembly in SyncManager | Move assembly to PortalSyncAdapter | PortalSyncAdapter is already the canonical builder — keep assembly there, SyncManager orchestrates only |

**Installation:** No new dependencies required. All needed libraries are already in the project.

## Architecture Patterns

### Recommended Project Structure

No new files. Changes are confined to:
```
shared/src/commonMain/.../data/sync/
├── PortalApiClient.kt       — add pushPortalPayload(), remove/replace getSyncStatus() path
├── SyncManager.kt           — rewrite pushLocalChanges(), remove getSyncStatus() gate
└── PortalSyncAdapter.kt     — add buildPayload() convenience method (or inline in SyncManager)

shared/src/commonMain/.../di/
└── SyncModule.kt            — inject GamificationRepository into SyncManager
```

### Pattern 1: Atomic Cutover (Legacy to Portal)

**What:** Replace the legacy `pushLocalChanges()` body in a single commit. Change both the URL and the DTO format at the same time. Never have a state where the URL points to the Edge Function but the body is the old flat format, or vice versa.

**When to use:** Required by STATE.md architectural decision: "Legacy SyncPushRequest cutover must be atomic: URL change + format change in ONE commit."

**Example — new pushLocalChanges() skeleton:**
```kotlin
private suspend fun pushLocalChanges(): Result<PortalSyncPushResponse> {
    val userId = tokenStorage.currentUser.value?.id
        ?: return Result.failure(PortalApiException("Not authenticated", null, 401))
    val deviceId = tokenStorage.getDeviceId()
    val lastSync = tokenStorage.getLastSyncTimestamp()
    val platform = getPlatformName()

    // Gather raw data
    val sessions = syncRepository.getRawSessionsModifiedSince(lastSync)  // WorkoutSession
    val routines = gamificationRepository.getRoutinesForSync()            // Routine
    val earnedBadges = gamificationRepository.getEarnedBadgesForSync()    // EarnedBadge
    val gamStats = syncRepository.getGamificationStatsForSync()           // GamificationStatsSyncDto
    val rpgInput = gamificationRepository.getRpgInput()
    val rpgProfile = RpgAttributeEngine.computeProfile(rpgInput)

    // Build portal payload using PortalSyncAdapter
    val payload = PortalSyncPayload(
        deviceId = deviceId,
        platform = platform,
        lastSync = lastSync,
        sessions = PortalSyncAdapter.toPortalWorkoutSessions(
            sessionsWithReps = sessions.map { PortalSyncAdapter.SessionWithReps(it) },
            userId = userId
        ),
        routines = routines.map { PortalSyncAdapter.toPortalRoutine(it, userId) },
        rpgAttributes = rpgProfile.toPortalDto(userId),
        badges = earnedBadges.toPortalDtos(userId),
        gamificationStats = gamStats?.toPortalDto(userId)
    )

    return apiClient.pushPortalPayload(payload)
}
```

### Pattern 2: PortalApiClient New Endpoint Method

**What:** Add `pushPortalPayload()` to `PortalApiClient` targeting the Supabase Edge Function URL. Keep the existing `pushChanges()` method temporarily (delete in follow-up) so nothing else breaks.

**Edge Function URL pattern:**
```kotlin
// In PortalApiClient companion object
const val EDGE_FUNCTION_PUSH = "/functions/v1/mobile-sync-push"
const val EDGE_FUNCTION_PULL = "/functions/v1/mobile-sync-pull"

// supabaseConfig.url is already "https://ilzlswmatadlnsuxatcv.supabase.co"
// Full URL: "${supabaseConfig.url}/functions/v1/mobile-sync-push"
```

**New method:**
```kotlin
suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
    return authenticatedRequest {
        httpClient.post("${supabaseConfig.url}${EDGE_FUNCTION_PUSH}") {
            bearerAuth(it)
            header("apikey", supabaseConfig.anonKey)   // Supabase requires apikey header
            setBody(payload)
        }
    }
}
```

**Critical:** The `apikey` header (anon key) must be sent alongside the `Authorization: Bearer {access_token}` header. Supabase Edge Functions require both.

### Pattern 3: SyncManager Status Check Removal

**What:** `SyncManager.sync()` calls `apiClient.getSyncStatus()` first, which targets the Railway URL. That backend is being abandoned. The status check must either be removed or replaced with a lightweight probe against Supabase.

**Simplest approach:** Remove the status check call from `sync()` entirely in Phase 26. The `SyncState.NotPremium` state was gated on the Railway 403 response — with the Edge Function, premium gating is not implemented server-side. The check becomes a no-op.

**Alternative:** Replace with a `getUser()` call on PortalApiClient (already exists) as a connectivity/auth probe.

### Pattern 4: Gamification Data Assembly for Push

**What:** `PortalSyncPayload.rpgAttributes` requires a `PortalRpgAttributesSyncDto`. `PortalSyncPayload.badges` requires `List<PortalEarnedBadgeSyncDto>`. Neither is currently assembled in SyncManager. SyncManager needs access to `GamificationRepository`.

**Badge DTO mapping:**
The mobile `EarnedBadge` has `badgeId` and `earnedAt` (epoch millis). The portal `PortalEarnedBadgeSyncDto` needs `badgeName`, `badgeDescription`, `badgeTier`. These require looking up the `Badge` definition by `badgeId`. The `BadgeRegistry` (or equivalent badge definition list) must be consulted.

```kotlin
// EarnedBadge → PortalEarnedBadgeSyncDto
fun EarnedBadge.toPortalDto(userId: String, allBadges: List<Badge>): PortalEarnedBadgeSyncDto {
    val badgeDef = allBadges.find { it.id == badgeId }
    return PortalEarnedBadgeSyncDto(
        userId = userId,
        badgeId = badgeId,
        badgeName = badgeDef?.name ?: badgeId,
        badgeDescription = badgeDef?.description,
        badgeTier = badgeDef?.tier?.name?.lowercase() ?: "bronze",
        earnedAt = epochToIso8601(earnedAt)
    )
}
```

**RPG DTO mapping:**
```kotlin
fun RpgProfile.toPortalDto(userId: String): PortalRpgAttributesSyncDto {
    return PortalRpgAttributesSyncDto(
        userId = userId,
        strength = strength,
        power = power,
        stamina = stamina,
        consistency = consistency,
        mastery = mastery,
        characterClass = characterClass.name,  // e.g. "POWERLIFTER"
        level = 1,              // no level system yet; hardcode 1
        experiencePoints = 0    // no XP system yet; hardcode 0
    )
}
```

**GamificationStats DTO mapping:**
`GamificationStatsSyncDto` (legacy) has `totalVolumeKg: Int`. `PortalGamificationStatsSyncDto` has `totalVolumeKg: Float` and adds `totalTimeSeconds: Int`. `totalTimeSeconds` is not in the mobile DB schema — it should default to 0.

### Pattern 5: SyncPushResponse Replacement

**What:** The legacy `SyncPushResponse` has `syncTime: Long` and `idMappings: IdMappings`. The Edge Function response is:
```json
{
  "syncTime": "2026-03-02T12:00:00.000Z",
  "sessionsInserted": 2,
  "exercisesInserted": 4,
  "setsInserted": 4,
  ...
}
```

The `syncTime` is now an ISO 8601 string, not epoch millis. `idMappings` does not exist in the Edge Function response — the portal uses the client-provided IDs (UUIDs generated by `generateUUID()` in PortalSyncAdapter). `updateServerIds()` in SyncRepository becomes a no-op for the portal push path.

**New response DTO:**
```kotlin
@Serializable
data class PortalSyncPushResponse(
    val syncTime: String,           // ISO 8601 from Edge Function
    val sessionsInserted: Int = 0,
    val exercisesInserted: Int = 0,
    val setsInserted: Int = 0,
    val repSummariesInserted: Int = 0,
    val routinesUpserted: Int = 0,
    val badgesUpserted: Int = 0,
    val exerciseProgressInserted: Int = 0,
    val personalRecordsInserted: Int = 0
)
```

### Pattern 6: SyncRepository — Raw Session Access

**What:** The current `SyncRepository.getSessionsModifiedSince()` returns `List<WorkoutSessionSyncDto>` (legacy flat DTO). `PortalSyncAdapter` needs `List<WorkoutSession>` (domain model with `routineSessionId` for grouping). The SyncRepository needs either a new method or the existing SyncRepository must be extended.

**Options:**
- Option A: Add `getWorkoutSessionsModifiedSince(timestamp: Long): List<WorkoutSession>` to `SyncRepository` and `SqlDelightSyncRepository`. HIGH confidence this is the right approach.
- Option B: Map from `WorkoutSessionSyncDto` back to a SessionWithReps-like struct — lossy and fragile.

**Use Option A.** The planner must add this method to both `SyncRepository` interface and `SqlDelightSyncRepository`. The DB query `selectSessionsModifiedSince` already exists and has all columns needed for `WorkoutSession`.

### Pattern 7: RepMetricData Access for PortalSyncAdapter

**What:** `PortalSyncAdapter.SessionWithReps` accepts `repMetrics: List<RepMetricData>` and `repBiomechanics: List<RepBiomechanicsData>`. For Phase 26, rep summaries are included in the push payload (they are in `PortalSetDto.repSummaries`). This is distinct from telemetry.

The question is: does `SqlDelightSyncRepository` need to fetch `RepMetricData` per session for the Phase 26 push? Looking at the Edge Function, `repSummaries` inside sets ARE included in the push payload and ARE inserted into `rep_summaries` table. So yes, rep metric data should be fetched.

However, `RepMetricData` is fetched from a different table (`RepMetric`). The `SyncRepository` does not currently expose rep metric data. A new method is needed, or SyncManager must inject `WorkoutRepository` to access rep metrics.

**Pragmatic approach for Phase 26:** Fetch rep metrics via an injected repository. `RepMetricRepository` or equivalent likely exists. Check if there is a `selectRepMetricsForSession` query.

### Anti-Patterns to Avoid

- **Partial cutover:** Never change the URL without changing the DTO format (or vice versa). One commit for both.
- **Trusting client user_id:** The Edge Function ignores the `userId` in the DTO body — it uses `auth.uid()` from JWT. Don't spend time ensuring user_id is perfectly populated in every nested DTO for security reasons; it's cosmetic at best.
- **Sending telemetry:** `PortalSyncAdapter.toRepTelemetry()` exists but must NOT be called in the push path. PUSH-05 is satisfied by explicit exclusion, not by chunked upload (that's v0.6.1).
- **Keeping Railway status check:** `getSyncStatus()` calls the Railway backend that is being abandoned. It must be removed from the `sync()` gate in Phase 26.
- **Leaking service_role key:** The Edge Function uses service_role server-side. Mobile only ever sends the anon key (`apikey` header) and the GoTrue access_token (`Authorization` header). Never put service_role in `BuildConfig`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| HTTP auth + token refresh | Custom retry logic | `authenticatedRequest {}` in PortalApiClient | Already implements double-check mutex refresh and 401 retry |
| JSON serialization | Manual JSON string building | kotlinx.serialization @Serializable DTOs | Already configured on HttpClient with encodeDefaults=true |
| UUID generation for DTO IDs | Random string | `generateUUID()` from domain model | Already multiplatform, already used in PortalSyncAdapter |
| ISO 8601 timestamp formatting | String formatting | `kotlinx.datetime.Instant.fromEpochMilliseconds(ms).toString()` | Already used in PortalSyncAdapter.epochToIso8601() |
| PR detection logic | Re-implement in mobile | Edge Function handles is_pr=true sets | Server-side computation already in mobile-sync-push index.ts |
| exercise_progress computation | Compute on mobile | Edge Function computes Brzycki 1RM server-side | Already in mobile-sync-push step 5 |

**Key insight:** The Edge Function server-side computation (steps 5 and 6 in index.ts) means PUSH-03 and PUSH-04 require zero new mobile logic beyond correctly setting `isPr = true` on the `PortalSetDto` when the mobile session represents a PR.

## Common Pitfalls

### Pitfall 1: Missing `apikey` Header on Edge Function Calls
**What goes wrong:** Supabase Edge Functions require both `Authorization: Bearer {token}` AND `apikey: {anon_key}` headers. Without the `apikey` header, the Edge Function returns 401 even with a valid token.
**Why it happens:** The GoTrue auth endpoints only need `apikey`. Developers assume Edge Functions work the same way.
**How to avoid:** Add `header("apikey", supabaseConfig.anonKey)` to `pushPortalPayload()` and `pullPortalChanges()` request builders.
**Warning signs:** HTTP 401 from Edge Function when token is clearly valid.

### Pitfall 2: SyncTime Format Mismatch
**What goes wrong:** Legacy `SyncPushResponse.syncTime` is `Long` (epoch millis). The Edge Function returns `syncTime` as ISO 8601 string. If the planner reuses `SyncPushResponse`, deserialization will fail.
**Why it happens:** The response DTO was designed for Railway, not Supabase Edge Functions.
**How to avoid:** Create a new `PortalSyncPushResponse` data class with `syncTime: String`. Parse to epoch millis for `tokenStorage.setLastSyncTimestamp()` using `kotlinx.datetime.Instant.parse(syncTime).toEpochMilliseconds()`.
**Warning signs:** Kotlinx.serialization deserialization exception on push success path.

### Pitfall 3: GamificationRepository Not in SyncManager DI
**What goes wrong:** `SyncManager` currently receives only `PortalApiClient`, `PortalTokenStorage`, `SyncRepository`. To build the gamification payload, it also needs `GamificationRepository`. Koin will throw at runtime if the constructor signature is expanded without updating `SyncModule.kt`.
**Why it happens:** The gamification data was not in scope when SyncManager was originally wired.
**How to avoid:** Add `GamificationRepository` to `SyncManager` constructor AND update `SyncModule.kt` to inject it. Both changes must be in the same commit.
**Warning signs:** Koin injection failure at app startup: "No definition found for type GamificationRepository".

### Pitfall 4: Badge Definition Lookup Gap
**What goes wrong:** `EarnedBadge` only stores `badgeId`. `PortalEarnedBadgeSyncDto` needs `badgeName`, `badgeDescription`, `badgeTier`. The badge catalog (definitions) exists as a static object in the mobile codebase but is not currently referenced from the sync path.
**Why it happens:** Mobile's gamification system was built independently from the sync system.
**How to avoid:** Find the badge definition registry (likely `BadgeDefinitions` or similar object in the domain layer) and use it to look up badge metadata during DTO construction.
**Warning signs:** Badges appear in portal with `badgeName = badgeId` and no tier information.

### Pitfall 5: WorkoutSession vs WorkoutSessionSyncDto Mismatch
**What goes wrong:** `SyncRepository.getSessionsModifiedSince()` returns `List<WorkoutSessionSyncDto>` which lacks `routineSessionId`, `routineName`, `totalVolumeKg`, and other fields needed by `PortalSyncAdapter`. Passing `WorkoutSessionSyncDto` to `PortalSyncAdapter.toPortalWorkoutSessions()` won't compile.
**Why it happens:** The legacy sync DTO was stripped to the minimum needed for the old flat format.
**How to avoid:** Add `getWorkoutSessionsModifiedSince(timestamp: Long): List<WorkoutSession>` to `SyncRepository` and `SqlDelightSyncRepository`. The DB query already returns all columns.

### Pitfall 6: Rep Metrics Fetch Missing
**What goes wrong:** `PortalSyncAdapter.SessionWithReps` has `repMetrics: List<RepMetricData>`. If these are not fetched, rep_summaries will be empty in the payload — Phase 26 success criterion 1 requires sets to appear in portal, and rep_summaries are inserted by the Edge Function.
**Why it happens:** SyncRepository does not currently expose RepMetricData. The planner might miss this additional data fetch.
**How to avoid:** Add `getRepMetricsForSession(sessionId: String): List<RepMetricData>` to the data access layer and call it for each session during payload assembly.

### Pitfall 7: GamificationStats totalTimeSeconds Missing
**What goes wrong:** `PortalGamificationStatsSyncDto` has `totalTimeSeconds: Int`. The mobile `GamificationStats` domain model and the `GamificationStatsSyncDto` do not include total time. The Edge Function expects this field.
**Why it happens:** The field was added to the portal schema but not the mobile model.
**How to avoid:** Default `totalTimeSeconds = 0` in the DTO mapping. The portal Edge Function will accept 0; it can be computed from WorkoutSessions in a future phase.

## Code Examples

Verified patterns from official project source:

### Adding apikey Header to Edge Function Request
```kotlin
// Source: PortalApiClient.kt (existing GoTrue pattern) + Edge Function requirement
suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
    return authenticatedRequest { token ->
        httpClient.post("${supabaseConfig.url}/functions/v1/mobile-sync-push") {
            bearerAuth(token)
            header("apikey", supabaseConfig.anonKey)
            setBody(payload)
        }
    }
}
```

### Converting SyncTime ISO String to Epoch
```kotlin
// Source: kotlinx-datetime (already in project via PortalSyncAdapter.epochToIso8601)
val epochMs = kotlinx.datetime.Instant.parse(syncPushResponse.syncTime).toEpochMilliseconds()
tokenStorage.setLastSyncTimestamp(epochMs)
```

### PortalSyncPushResponse New DTO
```kotlin
// Source: mobile-sync-push/index.ts response shape
@Serializable
data class PortalSyncPushResponse(
    val syncTime: String,
    val sessionsInserted: Int = 0,
    val exercisesInserted: Int = 0,
    val setsInserted: Int = 0,
    val repSummariesInserted: Int = 0,
    val routinesUpserted: Int = 0,
    val badgesUpserted: Int = 0,
    val exerciseProgressInserted: Int = 0,
    val personalRecordsInserted: Int = 0
)
```

### isPr Detection for PortalSetDto
```kotlin
// Source: Models.kt PersonalRecord + SqlDelightSyncRepository.getPRsModifiedSince pattern
// A set is a PR if there exists a PersonalRecord for the same exerciseId achieved at the
// same session timestamp. Check against PRs fetched alongside sessions.
val isPr = personalRecords.any {
    it.exerciseId == session.exerciseId &&
    it.achievedAt == session.timestamp
}
```

### RpgProfile → PortalRpgAttributesSyncDto
```kotlin
// Source: RpgModels.kt, PortalSyncDtos.kt
fun RpgProfile.toPortalSyncDto(userId: String): PortalRpgAttributesSyncDto =
    PortalRpgAttributesSyncDto(
        userId = userId,
        strength = strength,
        power = power,
        stamina = stamina,
        consistency = consistency,
        mastery = mastery,
        characterClass = characterClass.name,   // "POWERLIFTER", "ATHLETE", etc.
        level = 1,
        experiencePoints = 0
    )
```

### SyncModule.kt Update Pattern (Koin)
```kotlin
// Source: SyncModule.kt (existing pattern)
single {
    SyncManager(
        apiClient = get(),
        tokenStorage = get(),
        syncRepository = get(),
        gamificationRepository = get()  // new injection
    )
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Railway backend (flat SyncPushRequest) | Supabase Edge Function (PortalSyncPayload) | Phase 26 cutover | Atomic URL + format change required |
| exercise_progress computed mobile-side | exercise_progress computed server-side by Edge Function | Phase 25 Edge Function design | Mobile sends no exercise_progress field; PUSH-03 is free |
| personal_records extracted mobile-side | personal_records extracted by Edge Function from is_pr sets | Phase 25 Edge Function design | Mobile sets isPr=true on sets; PUSH-04 is free |
| SyncPushResponse with idMappings | PortalSyncPushResponse with counts only | Phase 26 | Portal uses client-provided UUIDs; no ID remapping needed |

**Deprecated/outdated:**
- `SyncPushRequest`: Replace entirely. No longer used after Phase 26.
- `getSyncStatus()` call in `SyncManager.sync()`: Remove. Railway backend is abandoned.
- `apiClient.pushChanges(request: SyncPushRequest)` in `PortalApiClient`: Mark for deletion after Phase 26.

## Open Questions

1. **Rep metrics access pattern**
   - What we know: `RepMetricData` is stored in a `RepMetric` table, accessible via `VitruvianDatabaseQueries`
   - What's unclear: Is there an existing repository method to get rep metrics by session ID? Check `WorkoutRepository` or `SqlDelightSyncRepository` for a `selectRepMetricsForSession` or similar query
   - Recommendation: The planner should check `VitruvianDatabase.sq` for `selectRepMetricsForSession` before adding a new query

2. **Badge catalog registry location**
   - What we know: Badge definitions exist as static data somewhere (the `BadgeEvaluator` or `BadgeRegistry` must define all badges including name, description, tier)
   - What's unclear: The exact class name and location of the all-badges list
   - Recommendation: Search for `BadgeDefinitions`, `BadgeRegistry`, `BadgeCatalog`, or similar before implementing badge DTO mapping. If none exists, create a `BadgeCatalog` object in the domain layer.

3. **SyncManager.sync() — status check replacement**
   - What we know: `getSyncStatus()` calls the Railway backend which is being abandoned; the 403 "not premium" gate won't apply to the Edge Function
   - What's unclear: Should sync be unconditional (no status check), or should a lightweight auth probe replace it?
   - Recommendation: Remove the status check entirely. Unauthenticated users are already blocked by `tokenStorage.hasToken()` check. `SyncState.NotPremium` can be kept in the sealed class but will never be emitted in Phase 26.

## Sources

### Primary (HIGH confidence)
- `shared/src/commonMain/.../data/sync/SyncManager.kt` — current push flow, legacy SyncPushRequest usage
- `shared/src/commonMain/.../data/sync/PortalSyncDtos.kt` — all portal DTO shapes
- `shared/src/commonMain/.../data/sync/PortalSyncAdapter.kt` — complete adapter implementation
- `shared/src/commonMain/.../data/sync/PortalApiClient.kt` — HTTP client, authenticatedRequest pattern, existing endpoints
- `shared/src/commonMain/.../data/sync/PortalTokenStorage.kt` — token access, currentUser, deviceId
- `shared/src/commonMain/.../data/sync/SyncModels.kt` — legacy request/response DTOs, legacy SyncPushRequest
- `shared/src/commonMain/.../di/SyncModule.kt` — current Koin DI wiring
- `phoenix-portal/supabase/functions/mobile-sync-push/index.ts` — deployed Edge Function, exact request/response shape

### Secondary (MEDIUM confidence)
- `shared/src/commonMain/.../data/repository/GamificationRepository.kt` — interface for RPG, badges, stats access
- `shared/src/commonMain/.../data/repository/SqlDelightGamificationRepository.kt` — getRpgInput(), saveRpgProfile(), getEarnedBadges() implementations
- `shared/src/commonMain/.../domain/premium/RpgAttributeEngine.kt` — computeProfile() takes RpgInput, returns RpgProfile

### Tertiary (LOW confidence)
- Badge catalog location — not directly observed; assumed to exist based on badge system being functional
- RepMetricData access by session ID — query existence not confirmed; needs verification against .sq file

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in use, no new dependencies
- Architecture: HIGH — Edge Function source read, all existing mobile files read, exact wire format confirmed
- Pitfalls: HIGH — derived from direct code inspection and architectural decisions in STATE.md

**Research date:** 2026-03-02
**Valid until:** 2026-04-02 (stable phase; Edge Function is deployed and locked)
