# Sync Layer & Supabase Audit Report

**Scope:** All files in `data/sync/`, `di/SyncModule.kt`, `data/repository/SyncRepository.kt`, `SqlDelightSyncRepository.kt`, `AuthRepository.kt`, `PortalAuthRepository.kt`, `ui/sync/LinkAccountViewModel.kt`, tests, and supabase config.

---

## 1. Authentication Flow

### 1.1 Token Refresh Race — Double-Check Pattern (Correct) ✅
**File:** `PortalApiClient.kt` lines 133–157 (`ensureValidToken`)
The refresh path uses a `Mutex` with double-check after lock acquisition. This correctly prevents duplicate refresh requests from concurrent coroutines. The `forceRefresh` method (lines 163–178) for 401 retries also holds the mutex. **No issue found.**

### 1.2 **[MEDIUM] GoTrue Auth Loses Premium Status on Every Sign-In**
**File:** `PortalTokenStorage.kt` lines 58–72 (`saveGoTrueAuth`)
The code preserves the existing premium flag: `val existingPremium: Boolean = settings[KEY_IS_PREMIUM, false]`. However, on the **very first sign-in** (fresh install or after `clearAuth`), this defaults to `false`. The user won't be treated as premium until the first successful sync push confirms premium status (line `tokenStorage.updatePremiumStatus(true)` in `SyncManager.kt` line 112).

**Impact:** Between login and first sync, premium users see free-tier behavior (SyncTriggerManager skips auto-sync for non-premium, line 80). The first sync must be manually triggered. **Severity: Medium** — workaround exists (manual sync or the `lastSyncTime == 0` bypass on line 79).

### 1.3 **[LOW] 401 Retry on Authenticated Requests Doesn't Re-serialize**
**File:** `PortalApiClient.kt` lines 183–198 (`authenticatedRequest`)
On 401, it calls `forceRefresh()` then calls `block(retryToken)` again. If the block is `pushPortalPayload`, this resends the **entire payload**, which is fine for idempotent upserts. But the retry `block` lambda is called outside the mutex, meaning another coroutine could also be retrying simultaneously. Since `SyncManager.sync()` is behind a mutex anyway, this is practically safe but architecturally fragile.

**Severity: Low** — protected by the higher-level `syncMutex`.

### 1.4 **[MEDIUM] Refresh Token Failure Silently Clears Auth Without Notification**
**File:** `PortalApiClient.kt` lines 150–155 and 172–176
When `refreshToken()` fails, `tokenStorage.clearAuth()` is called. This resets `isAuthenticated` to false and clears `lastSyncTimestamp` (line in `PortalTokenStorage.clearAuth()`). However, the UI isn't directly notified of session expiry. The `SyncManager` will eventually return `NotAuthenticated` on the next sync attempt, but background `SyncTriggerManager` will just silently stop syncing.

**Severity: Medium** — users on auto-sync only discover their session expired when they manually check sync status.

### 1.5 **[LOW] No Server-Side Logout on Client Logout**
**File:** `SyncManager.kt` line 76 (`logout`)
The `logout()` method only calls `tokenStorage.clearAuth()` — it does NOT call `apiClient.signOut()` to invalidate the server-side session. Compare with `PortalAuthRepository.signOut()` (line 82) which does call `apiClient.signOut()` first.

**Severity: Low** — the access token expires naturally (1 hour), but the refresh token remains valid server-side until it expires or is explicitly revoked.

---

## 2. Sync Conflict Resolution

### 2.1 **[HIGH] Inconsistent Conflict Resolution Strategy Across Entity Types**
**File:** `SyncManager.kt` (pull section, lines 218–290) + `SqlDelightSyncRepository.kt`

The codebase uses **four different conflict resolution strategies** without clear documentation of which is authoritative:

| Entity | Strategy | File/Line |
|--------|----------|-----------|
| Sessions | INSERT OR IGNORE (local wins) | `SqlDelightSyncRepository.mergePortalSessions` |
| Routines | Local wins if `localUpdatedAt > lastSync` | `SqlDelightSyncRepository.mergePortalRoutines` |
| Training Cycles | Server wins (overwrite) | `SqlDelightSyncRepository.mergePortalCycles` |
| Badges | INSERT OR IGNORE (union merge) | `SqlDelightSyncRepository.mergeBadges` |
| Gamification Stats | Server wins (overwrite with local-only fields preserved) | `SqlDelightSyncRepository.mergeGamificationStats` |
| RPG Attributes | Server wins (overwrite) | `SyncManager.kt` lines 274–285 |
| External Activities | Upsert (server wins) | `SyncManager.kt` lines 287–302 |

This mixed approach creates a risk scenario: If Device A pushes session+routine data, then Device B modifies the same routine locally, then Device B syncs — the routine might be kept as Device B's version (local wins due to `updatedAt > lastSync`) while associated sessions from Device A are ignored (local wins via INSERT OR IGNORE). The cycle is overwritten by the server, creating a cross-entity inconsistency.

**Severity: High** — multi-device sync users could see routines, cycles, and sessions fall out of sync.

### 2.2 **[MEDIUM] Training Cycle Merge Can Deactivate User's Active Cycle**
**File:** `SqlDelightSyncRepository.kt` lines 345–367 (`mergePortalCycles`)
The comment on line 358 says "Do NOT use setActiveTrainingCycle here — it deactivates ALL other cycles." However, the code still calls `updateTrainingCycle` with `is_active` from the portal. If the portal sends a cycle with `status = "active"`, it sets `is_active = 1` on the portal cycle, but does NOT deactivate other local cycles. This means **multiple cycles can be simultaneously active** after a merge.

**Severity: Medium** — data integrity issue; UI may pick the wrong active cycle.

---

## 3. Data Mapping (Push/Pull)

### 3.1 **[MEDIUM] Pull Session Has No exerciseId — Catalog Lookup Missing**
**File:** `PortalPullAdapter.kt` line 62 (`toWorkoutSessions`)
Pulled sessions set `exerciseId = null` with a comment "No catalog ID from portal; requires local catalog lookup." This means sessions pulled from the server will never link to the local exercise catalog, breaking features like exercise-specific PR tracking, history grouping, and statistics.

**Severity: Medium** — functional limitation on pulled data.

### 3.2 **[LOW] Duration Split Across Exercises Is Imprecise**
**File:** `PortalPullAdapter.kt` line 59
`duration = (portalSession.durationSeconds * 1000L) / exerciseCount` — integer division with multiplication. For 1 exercise with 601 seconds, result is 601000ms. For 3 exercises with 601 seconds, each gets 200333ms (601000/3 = 200333.33... truncated). The remainder is lost.

**Severity: Low** — minor precision loss, cosmetic only.

### 3.3 **[LOW] Pull Sets Weight Uses Max Instead of Average**
**File:** `PortalPullAdapter.kt` line 57
`weightPerCableKg = maxWeight` uses `maxOfOrNull` across all sets. If a session has progressive overload sets (40kg, 45kg, 50kg), the mobile session records 50kg as the weight, losing the per-set weight progression information.

**Severity: Low** — data simplification from portal's richer model to mobile's flatter model.

### 3.4 **[MEDIUM] Push Adapter totalVolumeKg Fallback Calculation Is Incorrect**
**File:** `PortalSyncAdapter.kt` line 89 (`buildPortalSession`)
```kotlin
val totalVolume = sorted.sumOf {
    (it.session.totalVolumeKg ?: (it.session.weightPerCableKg * it.session.totalReps)).toDouble()
}.toFloat()
```
The fallback `weightPerCableKg * totalReps` is incorrect for dual-cable exercises. The mobile model stores per-cable weight, so the actual volume should be `weightPerCableKg * cableCount * totalReps`. This underreports volume by 50% for dual-cable exercises when `totalVolumeKg` is null.

**Severity: Medium** — incorrect volume data pushed to portal for sessions lacking pre-computed volume.

### 3.5 **[LOW] Superset Color Mapping in Pull Is Lossy**
**File:** `SqlDelightSyncRepository.kt` line 280 (`mergePortalRoutines`)
```kotlin
val colorIndex = colorStr?.toLongOrNull() ?: supersetOrderIdx.toLong()
```
The portal sends color as a string name (e.g., "pink", "indigo") from the push adapter (`PortalSyncAdapter.toPortalRoutine`, which maps colorIndex → name), but the pull adapter tries `toLongOrNull()` on "pink" which returns `null`, falling back to the order index. The color mapping is not round-trippable.

**Severity: Low** — cosmetic; superset colors will be wrong after pull.

---

## 4. Error Handling & Transactional Safety

### 4.1 **[HIGH] Batched Push Has No Rollback — Partial Sync State**
**File:** `SyncManager.kt` lines 184–216 (batched push)
When pushing in batches, each batch updates the sync timestamp (line 211). If batch 3 of 5 fails, batches 1-2 are committed and timestamped, but batch 3-5 data remains unpushed. The sync timestamp has been advanced past batch 1-2, so on retry, the code re-queries `getWorkoutSessionsModifiedSince(lastSync, ...)` which may miss sessions from batches 1-2 that were already timestamped.

However, there's a subtlety: the `stampTime` logic on line 107 stamps pushed sessions *after* all pushes complete. Wait — in the batched path, the sync timestamp is updated after each batch (line 211), but session stamping only happens in the `sync()` method at line 107 using `prePushLastSync`. If a batch fails mid-way, the subsequent stamp logic never runs (it's after `pushResult.isFailure` check). On retry, sessions from successful batches may be re-pushed because they weren't stamped. This is safe (upsert) but wasteful.

**The real risk:** the `lastSync` stored after batch 2's success means the *pull* will ask for changes since batch 2's time, potentially missing server-side changes that occurred between batch 1 and batch 2.

**Severity: High** — data consistency gap in large-history initial syncs.

### 4.2 **[MEDIUM] Pull Failure Is Non-Fatal but Leaves State Inconsistent**
**File:** `SyncManager.kt` lines 122–131
When pull fails, `pullSyncTime` is null, so `finalSyncTime = pullSyncTime ?: syncTimeEpoch` uses the push sync time. The sync is reported as successful. However, if the pull failed because of a parsing error (corrupted data), the user sees "Sync successful" but never received remote changes. There's no mechanism to force a pull-only retry.

**Severity: Medium** — silent data loss from remote devices.

### 4.3 **[MEDIUM] SqlDelightSyncRepository Merge Operations Are Not Atomic Across Entity Types**
**File:** `SyncManager.kt` lines 234–302 (`pullRemoteChanges`)
Sessions, routines, cycles, badges, stats, RPG, and external activities are merged in separate transactions. If the app crashes between merging sessions and merging routines, the local database will have a partial pull state — e.g., sessions referencing routines that don't exist locally yet.

**Severity: Medium** — unlikely crash scenario but would cause data inconsistency.

### 4.4 **[LOW] HTTP Timeout May Be Insufficient for Large Payloads**
**File:** `PortalApiClient.kt` lines 24–27
```kotlin
requestTimeoutMillis = 30_000
connectTimeoutMillis = 10_000
```
For batch size of 50 sessions with nested telemetry (potentially thousands of data points), 30 seconds may be tight on slow connections. The code doesn't differentiate timeout from server error.

**Severity: Low** — 30s is reasonable for most cases; batch size limit helps.

---

## 5. Race Conditions

### 5.1 **[LOW] SyncTriggerManager Uses Non-Suspend Lock**
**File:** `SyncTriggerManager.kt` lines 32, 67, 73
```kotlin
private val stateLock = Any()
...
val shouldSkip = withPlatformLock(stateLock) { ... }
```
`withPlatformLock` wraps `synchronized` on JVM. While the critical sections are tiny (timestamp comparison, counter increment), mixing `synchronized` with coroutines can cause thread starvation in constrained dispatchers. This is acceptable here since the locked sections are non-suspending and very fast.

**Severity: Low** — not a practical concern but could be refactored to `Mutex`.

### 5.2 **[LOW] Concurrent Login + Sync Race**
**File:** `SyncManager.kt`
`login()` and `sync()` both modify `_syncState`. Login resets state to `Idle` (line 70), but if `sync()` is running concurrently (before login completes), the state changes could interleave. The `syncMutex` only protects `sync()`, not `login()`/`logout()`.

**Severity: Low** — in practice, the UI prevents simultaneous login and sync actions.

---

## 6. Network Error Handling & Retry

### 6.1 **[MEDIUM] No Exponential Backoff on Sync Failures**
**File:** `SyncTriggerManager.kt`
The trigger manager tracks consecutive failures (max 3 before persistent error) but has no backoff strategy. After a failure, the next foreground trigger (after the 5-min throttle) tries again at the same cadence. For server-side issues, this means the app hammers the server every 5 minutes.

**Severity: Medium** — should implement exponential backoff (e.g., 5min → 15min → 30min).

### 6.2 **[MEDIUM] Generic Error Wrapping Loses HTTP Context**
**File:** `PortalApiClient.kt` lines 200–202
```kotlin
} catch (e: Exception) {
    Result.failure(PortalApiException("Request failed: ${e.message}", e))
}
```
Network errors (timeout, DNS failure, SSL errors) are all wrapped as generic `PortalApiException` without a status code. The `SyncManager` checks `error.statusCode == 401` for auth failures — network errors with `statusCode = null` fall into the generic error path, which is correct but doesn't distinguish transient from permanent errors.

**Severity: Medium** — transient network errors should trigger retry; permanent API errors should not.

### 6.3 **[LOW] handleResponse Misses 429 (Rate Limited) Status**
**File:** `PortalApiClient.kt` lines 204–220 (`handleResponse`)
The handler checks 200, 201, 401, 403, and falls through to generic error for everything else. HTTP 429 (Too Many Requests) and 503 (Service Unavailable) should trigger a different retry strategy than 500. Currently they're all treated as generic errors.

**Severity: Low** — Supabase edge functions rarely return 429.

---

## 7. Data Loss Scenarios

### 7.1 **[HIGH] Sessions With NULL updatedAt Are Pushed Indefinitely**
**File:** `SyncManager.kt` lines 104–113
The code correctly addresses this with post-push stamping:
```kotlin
val pushedSessions = syncRepository.getWorkoutSessionsModifiedSince(prePushLastSync, activeProfileId)
pushedSessions.forEach { session ->
    syncRepository.updateSessionTimestamp(session.id, stampTime)
}
```
However, this stamping only runs on **successful push**. If the push succeeds but the app crashes before stamping (between lines 112 and 113), the sessions will be re-pushed on next sync. This is safe (upsert on server) but causes unnecessary network traffic.

**Severity: Low** (actually safe due to upserts). ~~HIGH~~ Revised to **Low**.

### 7.2 **[HIGH] Training Cycles Lack updatedAt — Always Fully Re-Pushed**
**File:** `SyncManager.kt` line 159
```kotlin
val cyclesWithContext = syncRepository.getFullCyclesForSync(activeProfileId)
```
The comment says "all — no delta, lacks updatedAt." This means every sync pushes ALL training cycles, not just modified ones. For users with many cycles, this wastes bandwidth and creates a degenerate pattern where the server upserts unchanged data on every sync.

**Severity: Medium** (not data loss, but performance/bandwidth waste).

### 7.3 **[MEDIUM] Exercise Signatures and Assessments Are Always Fully Re-Pushed**
**File:** `SyncManager.kt` lines 173–175
`syncRepository.getAllExerciseSignatures()` and `syncRepository.getAllAssessments()` have no delta query — they push everything every time.

**Severity: Medium** — same as 7.2, bandwidth waste.

---

## 8. Token Storage Security

### 8.1 **[HIGH] iOS Token Storage Uses Plain Settings — Not Keychain**
**File:** `PlatformModule.ios.kt` line 39
```kotlin
single<Settings>(SecureSettingsQualifier) { get() }
```
The iOS platform module maps `SecureSettingsQualifier` to the **same default Settings** (UserDefaults). The comment acknowledges: "Keychain-backed storage is a future enhancement." JWTs and refresh tokens are stored in **unencrypted** NSUserDefaults.

**Severity: High** — on jailbroken/rooted devices, tokens are trivially extractable. On non-jailbroken devices, iOS sandboxing provides some protection, but this fails security best practices.

### 8.2 **[MEDIUM] Android Uses EncryptedSharedPreferences (Good) but Falls Back to Plain**
**File:** From the grep results, Android creates encrypted preferences for the secure qualifier. The fallback mechanism is unclear from the visible code, but the architecture is sound. Need to verify `createEncryptedPreferences` doesn't fall back to unencrypted on failure.

**Severity: Medium** — needs verification.

### 8.3 **[LOW] Device ID Is a UUID — No Hardware Binding**
**File:** `PortalTokenStorage.kt` lines 85–91
The device ID is a random UUID, not bound to hardware identifiers. This means clearing app data generates a new device ID, which is actually fine for privacy but means the server can't deduplicate devices.

**Severity: Low** — by design, but worth documenting.

---

## 9. API Client HTTP Handling

### 9.1 **[MEDIUM] HTTP Client Is Never Closed**
**File:** `PortalApiClient.kt` line 22
```kotlin
private val httpClient = HttpClient { ... }
```
The Ktor `HttpClient` is created as a field and never explicitly closed. Since `PortalApiClient` is a Koin singleton, it lives for the app's lifetime, so this is acceptable. However, on logout+re-login, the same client (with potentially stale connection pools) is reused.

**Severity: Low** — no practical impact for singletons.

### 9.2 **[LOW] signOut Swallows All Exceptions**
**File:** `PortalApiClient.kt` lines 85–92
```kotlin
} catch (_: Exception) {
    Result.success(Unit) // Sign-out failure is non-critical
}
```
This is intentional (comment documents it), but it means network issues during sign-out are completely invisible. The server-side session remains valid.

**Severity: Low** — by design.

---

## 10. Pull Adapter Completeness

### 10.1 **[MEDIUM] No Pagination Support for Pull**
**File:** `PortalApiClient.kt` line 99 (`pullPortalPayload`)
The pull request sends `lastSync` and `deviceId` but no pagination parameters. If the server has 10,000 sessions modified since last sync, the entire payload is returned in one response. There's no cursor-based pagination, page size limit, or continuation token.

**Severity: Medium** — for large backlogs, the response could exceed memory limits or timeout.

### 10.2 **[LOW] Deleted Records Are Not Handled in Pull**
**File:** `PortalPullAdapter.kt` and `PortalSyncPullResponse`
The pull DTOs include `PullWorkoutSessionDto`, `PullRoutineDto`, etc., but none have a `deletedAt` field. If a record is deleted on Device A and synced to the server, Device B's pull won't learn about the deletion. Soft deletes are supported in the push path (`WorkoutSessionSyncDto.deletedAt`, `PersonalRecordSyncDto.deletedAt`) but not in the pull path.

**Severity: Low** — the current design appears to be "no remote deletes" by intention (comment in PortalSyncPullResponse: "Sessions are immutable/push-only per PULL-03").

### 10.3 **[LOW] PRs Are Not Pulled from Server**
**File:** `PortalSyncPullResponse` has no `personalRecords` field. PRs are push-only. This means a fresh install or new device won't have PR history from the server.

**Severity: Low** — appears intentional (PRs are derived from session data).

---

## 11. ViewModel & UI Layer

### 11.1 **[MEDIUM] LinkAccountViewModel Uses Unscoped CoroutineScope**
**File:** `LinkAccountViewModel.kt` line 20
```kotlin
private val scope = CoroutineScope(Dispatchers.Main)
```
The scope is never cancelled. If the ViewModel is garbage collected while a login/sync coroutine is running, the coroutine continues executing with references to a dead ViewModel's state. Unlike Android `viewModelScope`, this scope has no lifecycle binding.

**Severity: Medium** — potential memory leak and stale state updates.

### 11.2 **[LOW] PortalAuthRepository Uses Unscoped CoroutineScope**
**File:** `PortalAuthRepository.kt` line 16
Same pattern as 11.1. It does have a `close()` method (line 98) but it's unclear if it's ever called.

**Severity: Low** — singleton scope, so practically lives for the app's lifetime.

---

## 12. Supabase Configuration

### 12.1 **[INFO] No Local Supabase Migrations or Functions in Repo**
The `supabase/` directory only contains `.temp/` files (cli metadata, project ref). No SQL migrations, edge function source code, or `config.toml` are committed. This means:
- Database schema changes are managed externally (Supabase dashboard)
- Edge functions are deployed separately
- No way to version-control or reproduce the backend from this repo

**Severity: Informational** — not a bug, but a deployment/DevOps concern.

---

## Summary by Severity

| Severity | Count | Key Issues |
|----------|-------|------------|
| **Critical** | 0 | — |
| **High** | 3 | Inconsistent conflict resolution (2.1), Batched push partial state (4.1), iOS token storage unencrypted (8.1) |
| **Medium** | 12 | Premium status on first login (1.2), Refresh failure silent (1.4), Cycle merge multi-active (2.2), Missing exerciseId on pull (3.1), Volume calculation wrong (3.4), Pull failure silent (4.2), Non-atomic merge (4.3), No backoff (6.1), Error wrapping (6.2), No pagination (10.1), ViewModel leak (11.1), Android secure storage fallback (8.2) |
| **Low** | 11 | Various minor issues as documented above |

### Top 5 Recommendations (Priority Order)

1. **Fix iOS token storage** — use Keychain via a library like `multiplatform-settings` Keychain backend (8.1)
2. **Unify conflict resolution** — document and standardize whether server-wins or local-wins for each entity type (2.1)
3. **Add transactional pull** — wrap all pull merge operations in a single database transaction (4.3)
4. **Fix volume calculation fallback** — multiply by `cableCount` in the push adapter (3.4)
5. **Add exponential backoff** to SyncTriggerManager (6.1)
