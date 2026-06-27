# Data - Sync & Portal Review

Scope: portal sync, API client, token management, and sync triggers.

Reviewed files:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ErrorClassifier.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/IntegrationDtos.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClientImpl.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalMappings.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapter.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenRefresh.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorage.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncTriggerManager.kt`

Summary:
- Findings count: 19
- Severity breakdown: critical 1, high 7, medium 7, low 4

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ErrorClassifier.kt`

### Finding 1
- Category: failure-point
- Severity: low
- Line numbers: N/A
- Description: The assigned file does not exist at the requested path. Error classification code appears to have been consolidated into `PortalApiClient.kt`, but the stale file path means this scope cannot be reviewed as written and future reviewers/build tooling may miss the actual implementation.
- Suggested fix direction: Update the task/file inventory and any build/docs references to point at the live classifier implementation, or restore a thin `ErrorClassifier.kt` wrapper if that filename is part of the intended module structure.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/IntegrationDtos.kt`

No concrete bugs found in this DTO-only file. The DTOs are permissive by design; validation appears to be expected at call sites or server-side.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt`

### Finding 2
- Category: bug
- Severity: high
- Line numbers: 568-585, 603-619
- Description: Token refresh failures clear local auth even when the failure is recoverable. Both `ensureValidToken()` and `forceRefresh()` classify 401/403 as permanent but call `tokenStorage.clearAuthWithEvent(...)` for every other failure too, including transient network/timeout/server errors. That deletes the access token and refresh token, so a temporary outage during refresh can force a user out and prevent a later successful refresh.
- Suggested fix direction: Preserve stored tokens for retryable refresh failures. Emit a recoverable auth event and return a classified transient/network failure without clearing storage; only clear tokens on definitive invalid-refresh-token/auth responses.

### Finding 3
- Category: bug
- Severity: medium
- Line numbers: 77, 83-87, 192-199
- Description: `ClassifiedSyncError.toException()` drops the original error category, and `classifyError()` reclassifies any `PortalApiException` only by HTTP status. Network exceptions converted to `PortalApiException` with `statusCode == null` become `TRANSIENT` instead of `NETWORK`, so `SyncTriggerManager` will back off by time instead of waiting for connectivity restoration.
- Suggested fix direction: Preserve `SyncErrorCategory` on the exception type or avoid re-wrapping classified network errors. At minimum, teach `classifyError(PortalApiException)` to recover network/auth/permanent categories from structured fields instead of status alone.

### Finding 4
- Category: error
- Severity: medium
- Line numbers: 238-241, 256-259, 276-279, 290-293, 319-322, 374-378, 424-427, 649-652
- Description: Broad `catch (e: Exception)` blocks swallow coroutine `CancellationException` and convert cancellation into ordinary `Result.failure`. That can make sign-in, token refresh, subscription checks, and authenticated sync requests continue cleanup/retry flows after their parent coroutine has been cancelled.
- Suggested fix direction: Add `catch (e: CancellationException) { throw e }` before broad exception handlers in suspend functions and shared request helpers.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClientImpl.kt`

### Finding 5
- Category: failure-point
- Severity: low
- Line numbers: N/A
- Description: The assigned file does not exist at the requested path. The live implementation is the `open class PortalApiClient` in `PortalApiClient.kt`, so this review scope contains a stale implementation filename.
- Suggested fix direction: Remove `PortalApiClientImpl.kt` from file inventories or split implementation/interface files intentionally so reviews and ownership match the current code.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalMappings.kt`

### Finding 6
- Category: failure-point
- Severity: low
- Line numbers: 56-76
- Description: Muscle group category mapping is exact-case and whitespace sensitive. Inputs such as `"quads"`, `"Quads "`, or localized/case-normalized strings bypass aggregation and are sent through unchanged, which can fragment portal analytics categories.
- Suggested fix direction: Normalize with `trim()` plus case-insensitive matching, and keep the original string only as raw display data if no normalized mapping exists.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapter.kt`

### Finding 7
- Category: failure-point
- Severity: medium
- Line numbers: 46-50, 197-202
- Description: A missing or unparsable `startedAt` silently drops an entire pulled portal workout session by returning `emptyList()`. One malformed timestamp from the portal can therefore hide all exercises in that workout without surfacing an error, retry signal, or quarantine path.
- Suggested fix direction: Return a recoverable merge error, log structured telemetry with the portal session id, or preserve the row using a server `updatedAt`/sync time fallback so data is not silently lost.

### Finding 8
- Category: bug
- Severity: high
- Line numbers: 308-329
- Description: Pulled personal records are mapped with `exerciseId = pr.id`, even though `pr.id` is the personal-record row id, not the exercise/catalog id. `SqlDelightSyncRepository.mergePersonalRecords()` inserts that value into the PR `exerciseId` column, breaking exercise linkage and conflict keys; additionally `oneRepMax` is hardcoded to `0f`, which can erase useful PR semantics for pulled records.
- Suggested fix direction: Extend `PullPersonalRecordDto`/server projection to include the real exercise id (or resolve by name/muscle group before merge), and map record type/value/one-rep-max fields according to the PR kind instead of substituting the PR row id.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt`

### Finding 9
- Category: bug
- Severity: high
- Line numbers: 268, 365, 409, 437, 462, 490
- Description: Set, rep-summary, and telemetry ids are generated fresh on every push. If the server persists a batch but the client times out before receiving the response, or if a manual/full retry resends the same local session, the retry has different child ids and is not idempotent. This can duplicate sets, rep summaries, and force-curve telemetry for the same workout.
- Suggested fix direction: Derive stable child ids from the local session id, rep number, phase, cable, and sample index, or persist generated ids locally before first push so retries reuse the same keys.

### Finding 10
- Category: bug
- Severity: high
- Line numbers: 216-222, 690-692
- Description: Outgoing workout sessions and training cycles use `currentTimeMillis()` as `updatedAt` instead of the row's last-modified timestamp. A stale local row can therefore appear newer than the portal copy at push time and win last-write-wins conflict resolution, especially during full resyncs, retries after long offline periods, or cycle pushes where all cycles are sent every sync.
- Suggested fix direction: Use domain/database `updatedAt` values for all LWW entities. If an entity lacks a reliable modified timestamp, either omit `updatedAt` so the server can avoid client-wins LWW, or add the missing column before enabling bidirectional overwrites.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenRefresh.kt`

### Finding 11
- Category: failure-point
- Severity: low
- Line numbers: N/A
- Description: The assigned file does not exist at the requested path. Refresh logic currently lives in `PortalApiClient.refreshToken()`, `ensureValidToken()`, and `forceRefresh()`.
- Suggested fix direction: Update review/build ownership metadata to remove this stale path or introduce a dedicated token-refresh component if that separation is desired.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorage.kt`

### Finding 12
- Category: bug
- Severity: high
- Line numbers: 132-147
- Description: `saveGoTrueAuth()` preserves the existing premium flag without checking that the new auth response belongs to the same user. `SyncManager.login()` compensates afterward, but other live callers (`PortalAuthRepository` email/OAuth sign-in and refresh paths) call `saveGoTrueAuth()` directly, so switching accounts through those flows can briefly or persistently inherit another user's premium entitlement until a separate status refresh corrects it.
- Suggested fix direction: Make `saveGoTrueAuth()` fail closed on account changes by comparing the previous user id to `response.user.id`, or require the caller to pass an explicit entitlement preservation policy.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt`

### Finding 13
- Category: bug
- Severity: critical
- Line numbers: 408-423, 1372-1390
- Description: `sync()` stamps pushed sessions with `currentTimeMillis()` before pulling and applying server LWW rows. If the server rejected a stale local push because it already had a newer row, the local stamp can make the stale local row appear newer than the server row during the immediate pull merge, preventing `mergeSessionsLww()` from repairing the conflict. This creates a real data-loss/convergence failure mode in server-newer conflicts.
- Suggested fix direction: Defer local sync stamping until after pull conflict repair, and never stamp rows that the push response reports as LWW rejected. Prefer server-acknowledged timestamps per accepted row over client wall-clock stamps.

### Finding 14
- Category: failure-point
- Severity: high
- Line numbers: 903-905, 954-955, 1014-1047
- Description: Push LWW rejections (`PortalSyncPushResponse.rejections`) are decoded in the DTO layer but never inspected in `SyncManager`. The code treats a response with rejected entities as a normal successful push, then proceeds with stamping and external-activity acknowledgement handling. Rejected local changes are therefore not surfaced to UI/retry logic and can be hidden by later timestamp changes.
- Suggested fix direction: Inspect `pushResponse.rejections` after every push batch, log and expose conflict counts, avoid marking rejected rows as synced, and force/preserve a pull repair path for those ids.

### Finding 15
- Category: failure-point
- Severity: medium
- Line numbers: 1091-1095, 1192-1198
- Description: Pull requests send `profileId = activeProfileId`, which can be null, while the local merge defaults to `mergeProfileId = "default"`. If the active profile is unavailable during startup, the server may return unscoped/all-profile data (or no profile-scoped data) that is then merged into the default local profile.
- Suggested fix direction: Resolve and send the same non-null profile id used for merge (`mergeProfileId`), or block pull until an active/default profile is definitely loaded.

### Finding 16
- Category: bug
- Severity: medium
- Line numbers: 1340-1352
- Description: Session notes LWW metadata uses `ps.startedAt` as the notes `updatedAtMillis`, despite the comment saying it should fall back only when `updatedAt` is unavailable. A note edited on the portal after the workout start can be merged with an artificially old timestamp and lose future LWW comparisons.
- Suggested fix direction: Parse `ps.updatedAt` first, then fall back to `ps.startedAt`, then current time only if neither parses.

### Finding 17
- Category: failure-point
- Severity: medium
- Line numbers: 1449-1502
- Description: RPG attributes and external activities are merged after the core transaction, but their failures are not caught. A repository exception after core data commits escapes as a pull failure, leaving local core data changed while `lastSyncTimestamp` is not advanced; the next sync will reprocess the same page and can repeat side effects.
- Suggested fix direction: Either include these merges in the same transactional boundary where possible, or catch/report them as explicit partial non-core failures with idempotent retry behavior and clear sync state.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncTriggerManager.kt`

### Finding 18
- Category: error
- Severity: high
- Line numbers: 330-348
- Description: `attemptSync()` assumes `syncManager.sync()` always returns a `Result`, but repository/API code inside `sync()` can still throw. `onAppForeground()` catches this at the outer layer, but `onWorkoutCompleted()` and `onConnectivityRestored()` call `attemptSync()` directly; an exception in those paths can escape without updating retry state/backoff and may crash the caller coroutine.
- Suggested fix direction: Wrap the `syncManager.sync()` call inside `attemptSync()` with cancellation-aware try/catch, call `onSyncFailure()` for non-cancellation exceptions, and return without throwing for trigger-driven sync attempts.

### Finding 19
- Category: bug
- Severity: medium
- Line numbers: 55, 216-222
- Description: The documented retry-storm guard says max 3 consecutive retries require manual intervention, but `MAX_CONSECUTIVE_FAILURES` is unused and the implementation explicitly no longer latches persistent errors for transient storms. This mismatch can mislead UI/ops expectations and allows indefinite transient retry cycles under the normal backoff schedule.
- Suggested fix direction: Either remove/update the stale constant and documentation, or reintroduce an actionable cap with clear UI state when transient failures exceed the intended threshold.
