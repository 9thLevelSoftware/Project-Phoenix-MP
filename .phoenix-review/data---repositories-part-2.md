# Data - Repositories Part 2 Review

Scope reviewed:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/RepMetricRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SmartSuggestionsRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SyncRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/TrainingCycleRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/VelocityOneRepMaxRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt`

Notes: this review did not change application code. Related SQLDelight queries and concrete implementations were read where needed to validate repository-level failure points.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/RepMetricRepository.kt

### Finding RM-1
- Category: bug
- Severity: high
- Line numbers: 31-68
- Description: `saveRepMetrics()` always inserts every metric row but never removes existing rows for the same session and the `RepMetric` table/query set has only indexes, not a uniqueness constraint on `(sessionId, repNumber)`. Retrying a save or saving an updated session appends duplicate per-rep rows, inflating `getRepMetricCount()` and causing sync/export/UI consumers to read repeated reps.
- Suggested fix direction: Make save idempotent: either wrap a `deleteRepMetricsBySession(sessionId)` plus inserts in one transaction, or add a unique key for `(sessionId, repNumber)` and use an upsert/replace query.

### Finding RM-2
- Category: failure-point
- Severity: medium
- Line numbers: 31-69
- Description: Multi-row metric persistence is not wrapped in a transaction. If any insert fails halfway through a large metric list, earlier rows remain committed and later rows are missing, leaving a partially persisted rep stream for the session.
- Suggested fix direction: Enclose the whole save operation in `db.transaction { ... }` and fail atomically, or delete/retry atomically with an explicit recovery path.

### Finding RM-3
- Category: error
- Severity: medium
- Line numbers: 72-111, 120-126
- Description: `getRepMetrics()` and `getRepMetricCount()` catch all `Exception`s and return empty results. This swallows data corruption, SQL/migration errors, JSON parse errors, and coroutine cancellation, making missing rep telemetry indistinguishable from a valid empty session.
- Suggested fix direction: Catch only the expected migration/table-missing exception if that compatibility path is still required, rethrow `CancellationException`, log the failure, and surface corrupt data errors instead of silently dropping telemetry.

### Finding RM-4
- Category: failure-point
- Severity: medium
- Line numbers: 156-169
- Description: The manual JSON parsers assume perfectly formatted numeric arrays and use `toFloat()`/`toLong()` directly. Any malformed value in one persisted column throws and, because of RM-3, currently causes the entire session's rep metrics to disappear as an empty list.
- Suggested fix direction: Use a real JSON parser for primitive arrays or parse defensively with per-column validation and clear logging; quarantine only the bad field/row rather than hiding the whole session.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SmartSuggestionsRepository.kt

### Finding SS-1
- Category: bug
- Severity: medium
- Line numbers: 49-64
- Description: `getSessionSummariesSince()` maps `selectSessionSummariesSince`, whose SQL does not filter `WorkoutSession.deletedAt IS NULL` and does not exclude zero-rep ghost rows. Smart suggestions can therefore count soft-deleted or incomplete sessions in volume, balance, and time-of-day analysis.
- Suggested fix direction: Align the underlying query with history/export eligibility: filter `deletedAt IS NULL` and require `(workingReps > 0 OR totalReps > 0)` before mapping to `SessionSummary`.

### Finding SS-2
- Category: bug
- Severity: high
- Line numbers: 23-27, 67-78
- Description: The contract says `getExerciseLastPerformed()` returns last-performed dates for all active exercises, but the backing query uses an inner join from `Exercise` to `WorkoutSession`. Active exercises that have never been performed are omitted entirely, so neglect detection cannot recommend never-used movements.
- Suggested fix direction: Use `Exercise` as the driving table with a left join to eligible sessions, keep active exercises with `lastPerformed = null/0`, and let the suggestion engine treat them as never performed.

### Finding SS-3
- Category: bug
- Severity: medium
- Line numbers: 67-78
- Description: The last-performed query also lacks deleted-row and completed-row filters. A soft-deleted or zero-rep ghost workout can become the `MAX(ws.timestamp)`, making an exercise look recently trained when it was not.
- Suggested fix direction: Add `ws.deletedAt IS NULL` and completed-session predicates to the join condition, not the WHERE clause if SS-2 is fixed with a left join.

### Finding SS-4
- Category: bug
- Severity: medium
- Line numbers: 81-93
- Description: `getExerciseWeightHistory()` maps a query that filters only `ws.totalReps > 0` and omits `deletedAt IS NULL`. That excludes valid rows where only `workingReps` is populated and includes soft-deleted rows, skewing plateau detection.
- Suggested fix direction: Use the common completed-session predicate `(workingReps > 0 OR totalReps > 0)` and filter out soft-deleted rows before ordering the weight history.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SyncRepository.kt

### Finding SY-1
- Category: stub
- Severity: medium
- Line numbers: 104-107
- Description: `backfillPhaseSpecificPRs()` has a default implementation that always returns `PhasePRBackfillResult(changedRows = 0)`. Any fake, alternate, or future repository implementation that forgets to override this method will silently skip phase-specific PR backfill while reporting success.
- Suggested fix direction: Remove the default body or make it throw `NotImplementedError` outside explicitly documented test fakes; require production-capable implementations to provide real behavior.

### Finding SY-2
- Category: stub
- Severity: high
- Line numbers: 113-116
- Description: `findSessionIdsForPersonalRecords()` defaults to `emptyMap()`. The portal push path uses this helper to resolve source session IDs for PR rows; a missed override silently drops session links for all records instead of failing loudly.
- Suggested fix direction: Make this abstract or fail-fast by default. If tests need no-op behavior, implement it only in the test fake and name that behavior explicitly.

### Finding SY-3
- Category: stub
- Severity: medium
- Line numbers: 329-333
- Description: `mergeSessionNotes()` defaults to a no-op even though the method persists pulled portal session notes. Implementations that do not override it will acknowledge pull data but lose every note.
- Suggested fix direction: Make note merge support abstract/fail-fast, or gate call sites on a capability flag rather than silently ignoring pulled data.

### Finding SY-4
- Category: stub
- Severity: high
- Line numbers: 353-358
- Description: `mergeSessionsLww()` defaults to a no-op for the LWW pull merge that replaced `INSERT OR IGNORE`. If a concrete repository is missing the override, pulled session updates are discarded with no exception, leaving local state stale after sync.
- Suggested fix direction: Remove the default implementation or throw an explicit unsupported-operation exception so missing LWW support fails visibly during integration.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/TrainingCycleRepository.kt

### Finding TC-1
- Category: failure-point
- Severity: medium
- Line numbers: 115-117
- Description: `jumpToDay(cycleId, dayNumber)` exposes no valid range contract and accepts any `Int`. The concrete implementation writes the requested value directly to progress, so callers can persist day 0, negative days, or days beyond the cycle length and strand the active cycle on a non-existent day.
- Suggested fix direction: Define and enforce bounds in the repository contract and implementation: load the cycle day count, require `dayNumber in 1..totalDays`, and return an error/result for invalid input.

### Finding TC-2
- Category: failure-point
- Severity: medium
- Line numbers: 49-51
- Description: `setActiveCycle(cycleId, profileId)` does not specify what happens when `cycleId` does not exist, is soft-deleted, or belongs to another profile. The SQL implementation deactivates all cycles for the profile and then initializes progress for the supplied ID, so a bad ID can leave the profile with no valid active cycle.
- Suggested fix direction: Require repository implementations to verify that the target cycle exists, is not deleted, and belongs to `profileId` before deactivating existing cycles.

### Finding TC-3
- Category: failure-point
- Severity: low
- Line numbers: 58-61
- Description: The interface says `deleteCycle()` deletes a training cycle and all related data, while the sync-aware implementation soft-deletes only the cycle row and leaves days/progress/progression attached. This mismatch can lead callers to assume related local data is gone when it is only hidden by filters.
- Suggested fix direction: Update the contract to describe tombstone semantics, or provide separate `softDeleteCycle` and `hardDeleteCycle` methods with clear caller expectations.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt

### Finding UP-1
- Category: failure-point
- Severity: medium
- Line numbers: 73-89, 101-112
- Description: Repository initialization and `ensureDefaultProfileSync()` perform database reads/writes synchronously from the constructor and from suspend functions without switching to `Dispatchers.IO`. Instantiating this repository on the UI thread can block startup and profile refresh work can run on the caller's dispatcher.
- Suggested fix direction: Move initialization behind an explicit suspend factory/start method or dispatch all database work through `withContext(Dispatchers.IO)`.

### Finding UP-2
- Category: bug
- Severity: high
- Line numbers: 177-180
- Description: `setActiveProfile(id)` accepts any ID and then refreshes. The SQL update sets `isActive = 1` only for a matching row and `0` for all others, so passing a missing profile ID clears the active profile entirely.
- Suggested fix direction: Verify the target profile exists before calling `setActiveProfile`; return a Boolean/Result or throw for an invalid ID instead of deactivating every profile.

### Finding UP-3
- Category: bug
- Severity: medium
- Line numbers: 128-174
- Description: `deleteProfile(id)` returns `true` even when `id` is not an existing non-default profile. It reassigns zero rows, deletes zero rows, recomputes gamification, refreshes, and reports success, which can make UI or sync flows believe a deletion occurred.
- Suggested fix direction: Check profile existence and affected rows before the cascade, and return `false` or an error when there is nothing to delete.

### Finding UP-4
- Category: failure-point
- Severity: medium
- Line numbers: 136-151
- Description: Profile data reassignment and the final profile deletion are not in the same transaction. If `queries.deleteProfile(id)` fails after reassignment succeeds, the profile can remain while its data has already been moved to the target profile.
- Suggested fix direction: Include reassignment and profile deletion in one database transaction, then run best-effort gamification recompute after the atomic data move commits.

### Finding UP-5
- Category: failure-point
- Severity: low
- Line numbers: 217-221
- Description: `getActiveProfileSubscriptionStatus()` returns a cold one-shot `flow { emit(...) }`, not a reactive stream tied to profile or subscription updates. Callers collecting it long-term will not observe later `setActiveProfile()` or `updateSubscriptionStatus()` changes unless they re-call the method.
- Suggested fix direction: Either expose this as a suspend getter or back it with `activeProfile`/database query invalidation so subscription status changes emit continuously.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/VelocityOneRepMaxRepository.kt

### Finding V1RM-1
- Category: failure-point
- Severity: medium
- Line numbers: 47-60
- Description: `insert()` persists estimator output without validating numeric fields. NaN, infinite, negative, or otherwise nonsensical `estimatedPerCableKg`, `mvtUsedMs`, or `r2` values can be stored and later displayed or synced as passing estimates if the upstream result says `passedQualityGate`.
- Suggested fix direction: Validate finite positive load/MVT values and valid `r2` range before insert; reject or mark failing-quality rows when numeric invariants are broken.

### Finding V1RM-2
- Category: failure-point
- Severity: low
- Line numbers: 12-22, 37-45
- Description: The table includes sync/tombstone columns (`updatedAt`, `serverId`, `deletedAt`), but `VelocityOneRepMaxEntity` drops them and the mapper ignores them. Future sync or admin/debug flows cannot distinguish local-only, server-backed, or tombstoned estimate state through the repository entity.
- Suggested fix direction: Either remove unused sync columns from this repository's surface area until sync is implemented, or carry them in the entity so repository consumers can reason about sync state correctly.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt

### Finding WR-1
- Category: bug
- Severity: high
- Line numbers: 29-30
- Description: `deleteAllSessions()` has no `profileId` parameter even though most session reads are profile-scoped. The concrete SQL implementation deletes every `WorkoutSession` row across all profiles, so a profile-level cleanup or test helper call in production code can erase other users' workouts.
- Suggested fix direction: Replace or supplement it with `deleteAllSessions(profileId)` for user-facing flows, and reserve global deletion for clearly named backup/test/admin paths.

### Finding WR-2
- Category: failure-point
- Severity: medium
- Line numbers: 29, 32-41
- Description: `deleteSession(sessionId)` and `deleteSessionsByRoutineSessionId(routineSessionId)` are also unscoped. If a stale ID, imported collision, or routine-session ID mix-up crosses profile boundaries, the repository has no way to enforce that only the active profile's rows are removed.
- Suggested fix direction: Add `profileId` to destructive session methods and include it in the SQL WHERE clauses, or require callers to prove ownership before deletion.

### Finding WR-3
- Category: bug
- Severity: medium
- Line numbers: 43-48
- Description: `getRecentSessions(profileId, limit)` maps to `selectRecentSessions`, which filters only by profile and timestamp. It does not exclude soft-deleted rows, so recently deleted sessions can reappear in recent-history UI/export consumers that use this repository method.
- Suggested fix direction: Update the backing query to include `deletedAt IS NULL`; if the caller wants user-visible recent sessions, also use the completed-session filter used by `getHistoryVisibleSessions()`.

### Finding WR-4
- Category: bug
- Severity: medium
- Line numbers: 65-68
- Description: `getSession(sessionId)` maps to `selectSessionById`, which does not filter soft-deleted rows or profile ownership. Callers that fetch a session by ID after deletion can still receive a tombstoned row and act on it as if it were live.
- Suggested fix direction: Add an explicit live-session getter that filters `deletedAt IS NULL` and profile ID, and keep a separately named sync/admin getter for raw tombstone access.

### Finding WR-5
- Category: failure-point
- Severity: medium
- Line numbers: 11-18, 93-95
- Description: `PersonalRecordEntity` preserves only exercise ID, weight, reps, timestamp, and mode even though the table/repository now supports exercise name, PR type, phase, volume, cable count, and sync state. `getAllPersonalRecords()` consumers cannot distinguish max-weight vs max-volume or combined vs phase-specific PRs through this interface.
- Suggested fix direction: Expand the entity or return the richer domain `PersonalRecord` model so PR consumers can handle phase-specific and cable-aware records without lossy assumptions.

## Summary

Findings count: 27

Severity breakdown:
- Critical: 0
- High: 6
- Medium: 18
- Low: 3
