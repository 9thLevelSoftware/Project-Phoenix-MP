# Data - Repositories Part 1 Review

Scope reviewed:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/AssessmentRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/BiomechanicsRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/CompletedSetRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ExerciseRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ExternalActivityRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/GamificationRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/PersonalRecordRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ProgressionRepository.kt`

Note: the assigned `data/repository/ExternalActivityRepository.kt` path does not exist in this checkout. I also inspected the relocated/similar file at `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/ExternalActivityRepository.kt` to preserve review coverage.

Summary:
- Findings: 23
- Critical: 0
- High: 5
- Medium: 16
- Low: 2

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/AssessmentRepository.kt

### Finding 1
- Category: failure-point
- Severity: high
- Line numbers: 85-95
- Description: `saveAssessmentSession` is documented as one logical operation: create an assessment `WorkoutSession`, insert an `AssessmentResult`, and update `Exercise.oneRepMaxKg`. The SQLDelight implementation performs those steps through multiple repositories/queries without one database transaction, so a failure after session creation can leave a workout session without an assessment result, or an assessment result without the matching 1RM update.
- Suggested fix direction: Make the implementation execute the whole assessment-session save in a single database transaction, or expose a lower-level transactional API that inserts the session/result and 1RM update atomically. If cross-repository transaction sharing is hard, return a `Result` and roll back/cleanup explicitly on partial failure.

### Finding 2
- Category: failure-point
- Severity: medium
- Line numbers: 38-45, 85-95
- Description: The save APIs accept `Float` and count/duration inputs with no contract-level validation for finite/non-negative values. NaN/infinite 1RM or negative reps/durations/weights can flow into the repository and corrupt assessment history or exercise 1RM state.
- Suggested fix direction: Validate `estimatedOneRepMaxKg`, `userOverrideKg`, `weightPerCableKg`, `totalReps`, and `durationMs` before persistence. Reject non-finite floats and impossible negative values with a typed failure.

### Finding 3
- Category: failure-point
- Severity: medium
- Line numbers: 63-67
- Description: `deleteAssessment(id: Long)` is not profile-scoped even though the rest of the read/write API and entity are profile-aware. A caller with a row id can delete an assessment without proving it belongs to the active profile.
- Suggested fix direction: Change deletion to include `profileId` and implement it with `DELETE ... WHERE id = ? AND profile_id = ?`, or resolve the row profile before deletion and reject mismatches.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/BiomechanicsRepository.kt

### Finding 4
- Category: failure-point
- Severity: medium
- Line numbers: 30-57
- Description: `saveRepBiomechanics` inserts each rep in a loop but does not wrap the batch in a transaction. If one row fails because of a constraint, serialization error, or database issue, earlier reps remain persisted and later reps are missing, producing an incomplete per-set biomechanics timeline.
- Suggested fix direction: Wrap the full `results.forEach` write in `queries.transaction { ... }` and consider validating every `BiomechanicsRepResult` before the transaction starts.

### Finding 5
- Category: error
- Severity: medium
- Line numbers: 60-101, especially 85-86
- Description: Reads deserialize force arrays with `toFloatArrayFromJson()` and do not catch malformed persisted data. That helper uses `String.toFloat()` per element, so one corrupt TEXT value can throw and fail the whole `getRepBiomechanics(sessionId)` call.
- Suggested fix direction: Add safe parsing for the array columns, either returning empty arrays with a logged warning or surfacing a typed repository failure. Consider schema/data repair for corrupted rows.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/CompletedSetRepository.kt

### Finding 6
- Category: bug
- Severity: high
- Line numbers: 51-72
- Description: Completed-set read methods have no `profileId`, and the SQLDelight implementation's exercise-based queries join `WorkoutSession` without filtering `profile_id` or `deletedAt`. In a multi-profile app this can leak another profile's completed sets into progression/history calculations and can include deleted workout sessions.
- Suggested fix direction: Add `profileId` to exercise/session list read methods where the caller operates in a profile context, and filter joined `WorkoutSession` rows with `ws.profile_id = :profileId` and `ws.deletedAt IS NULL`.

### Finding 7
- Category: failure-point
- Severity: medium
- Line numbers: 69-72
- Description: `getRecentCompletedSetsForExercise(exerciseId, limit)` does not define or enforce valid `limit` bounds. In SQLite, a negative limit can behave like unlimited, so a bad caller can accidentally load all historical sets instead of a bounded recent window.
- Suggested fix direction: Require `limit > 0` at the repository boundary and optionally cap it to a sane maximum for progression analysis.

### Finding 8
- Category: failure-point
- Severity: medium
- Line numbers: 27-29, 85-88
- Description: The bulk save methods promise to save multiple planned/completed sets, but the SQLDelight implementation loops inserts without a transaction. A mid-batch failure can leave partially saved routine templates or completed workout data.
- Suggested fix direction: Wrap `savePlannedSets` and `saveCompletedSets` in database transactions. For empty lists, return immediately before entering the transaction.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepository.kt

### Finding 9
- Category: failure-point
- Severity: medium
- Line numbers: 76-84
- Description: Corrupt rack JSON in settings is silently converted to `emptyList()`. The next successful save/upsert/delete will write that empty list back, turning a transient decode problem into permanent rack data loss.
- Suggested fix direction: Preserve the raw corrupt value and expose an error/recovery path. At minimum, log the decode failure and avoid overwriting the stored value until the user explicitly resets or repairs the rack.

### Finding 10
- Category: failure-point
- Severity: low
- Line numbers: 37, 45-48
- Description: `getItems()` and `saveItemsInternal()` expose/store the list instance directly. If the caller passes a mutable list typed as `List<RackItem>` and mutates it after saving, repository state can change without another settings write or an intentional StateFlow update.
- Suggested fix direction: Defensively copy lists at the repository boundary (`items.toList()`) and return copies from `getItems()`.

### Finding 11
- Category: failure-point
- Severity: low
- Line numbers: 39-48, 69-74
- Description: `saveItems` accepts arbitrary lists and does not enforce unique rack item IDs. `resolveActiveItems` then builds `associateBy { it.id }`, so duplicate IDs collapse to one item and selection behavior depends on list ordering.
- Suggested fix direction: Reject or normalize duplicate IDs during `saveItems`/`upsert`, and consider sorting by `sortOrder` before persistence/resolution.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ExerciseRepository.kt

### Finding 12
- Category: failure-point
- Severity: medium
- Line numbers: 153-159
- Description: `findByIdOrName` explicitly allows a fuzzy-search fallback but the contract has no ambiguity handling, score threshold, or way to return multiple candidates. The implementation takes the first search result, so template conversion can silently bind to the wrong exercise when names are similar or an ID is stale.
- Suggested fix direction: Make fuzzy resolution deterministic and auditable: use exact normalized aliases first, require a confidence threshold, and return an ambiguity error/list instead of silently choosing the first fuzzy result.

### Finding 13
- Category: failure-point
- Severity: high
- Line numbers: 79-84
- Description: The import contract says exercises should only be imported if the database is empty, but the SQLDelight implementation has a re-import branch when videos are missing that deletes all exercises and videos before importing. Because custom exercises and user metadata live in the same `Exercise` table, this can destroy user-created exercises/favorites/1RM values during a video backfill path.
- Suggested fix direction: Do not clear the whole exercise table for video backfills. Import videos separately, preserve custom/user fields, or perform a migration-style merge keyed by stable exercise IDs.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ExternalActivityRepository.kt

### Finding 14
- Category: error
- Severity: medium
- Line numbers: 1
- Description: The assigned file path does not exist in this checkout. The closest/current interface is `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/ExternalActivityRepository.kt`, and production code imports `com.devil.phoenixproject.data.integration.ExternalActivityRepository`.
- Suggested fix direction: Update review/task manifests and any stale package references to the `data/integration` path, or move the interface back if `data/repository` is the intended architecture.

### Finding 15
- Category: bug
- Severity: high
- Line numbers: relocated file `data/integration/ExternalActivityRepository.kt` lines 25-27 and 52-58
- Description: The interface can mark external activities as deleted with `needsSync = true`, but the unsynced read contract only describes normal unsynced activities. The SQL query behind `getUnsyncedActivities` filters `deletedAt IS NULL`, so tombstones created by `markDeletedByExternalIds(..., needsSync = true)` are never returned for portal push and remote deletes can be lost.
- Suggested fix direction: Decide whether deletion tombstones must sync. If yes, include `needsSync = 1` tombstones in the unsynced query/DTO path or add a separate `getUnsyncedDeletedActivities` API.

### Finding 16
- Category: failure-point
- Severity: medium
- Line numbers: relocated file `data/integration/ExternalActivityRepository.kt` lines 36-46
- Description: Two sync-ack APIs coexist: `markSynced(ids)` and provider/profile-scoped `markSyncedBySyncKeys(...)`. The older `markSynced(ids)` has no profile/provider guard, so any fallback path that receives stale or foreign IDs can clear `needsSync` on the wrong row.
- Suggested fix direction: Deprecate or remove `markSynced(ids)` once server acks always include provider-scoped keys, or add `profileId` to the ID-based method and enforce it in SQL.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/GamificationRepository.kt

### Finding 17
- Category: failure-point
- Severity: medium
- Line numbers: 55-59
- Description: `updateStats(profileId)` returns `Unit`, and the SQLDelight implementation catches/logs exceptions internally. Callers cannot tell when stats failed to recalculate, so badge/RPG/UI state can remain stale while the workflow continues as if successful.
- Suggested fix direction: Return `Result<Unit>` or let exceptions propagate to callers that can retry or surface an error. Avoid swallowing repository failures silently.

### Finding 18
- Category: bug
- Severity: high
- Line numbers: 67-76
- Description: `getRpgInput(profileId)` is documented as aggregating profile-scoped workout, rep metric, gamification, PR, and badge data. The implementation's peak-power inputs use global `RepMetric`/`MetricSample` queries with no profile/session filter, so one profile can influence another profile's RPG power attribute.
- Suggested fix direction: Add profile-scoped peak power queries by joining rep/metric rows to `WorkoutSession` and filtering `profile_id` plus `deletedAt IS NULL`.

### Finding 19
- Category: bug
- Severity: medium
- Line numbers: 38-42
- Description: `awardBadge` promises `true` only when a badge was newly awarded. The implementation checks for an existing badge, then performs `INSERT OR IGNORE`, and returns true without verifying the insert changed a row. Concurrent award attempts can both report newly awarded, causing duplicate celebrations even though the unique index keeps one database row.
- Suggested fix direction: Make badge award atomic and row-count based. Use an insert that reports whether it inserted, or re-query after `INSERT OR IGNORE` in the same transaction before returning the Boolean.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/PersonalRecordRepository.kt

### Finding 20
- Category: bug
- Severity: medium
- Line numbers: 247-260
- Description: `normalizeWorkoutModeKey` handles display names and compact camel-case names, but not the sync wire names used elsewhere (`OLD_SCHOOL`, `TUT_BEAST`, `ECCENTRIC_ONLY`). PRs saved or looked up with wire-format mode strings can be partitioned under different mode keys and missed by later display-name lookups.
- Suggested fix direction: Normalize all accepted mode encodings through `ProgramMode.fromSyncString`/`fromDisplayName` or add explicit snake-case branches before falling back to the trimmed input.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ProgressionRepository.kt

### Finding 21
- Category: bug
- Severity: medium
- Line numbers: 38-41
- Description: `hasPendingProgression` says it checks whether there is a pending progression for an exercise, but the implementation only inspects the most recent progression event. If the latest event has a response while an older event is still pending, this method returns false and can allow duplicate or conflicting suggestions.
- Suggested fix direction: Query for any `user_response IS NULL` row for the exercise/profile, preferably with `LIMIT 1`, instead of checking only the newest event.

### Finding 22
- Category: failure-point
- Severity: medium
- Line numbers: 49-59
- Description: `recordResponse(eventId, ...)` and `deleteProgressionEvent(eventId)` are not profile-scoped, unlike the read APIs. A stale ID or cross-profile collision can update/delete an event without verifying it belongs to the active profile.
- Suggested fix direction: Add `profileId` to response/delete methods and enforce it in `UPDATE`/`DELETE` statements.

### Finding 23
- Category: failure-point
- Severity: medium
- Line numbers: 43-46
- Description: `createProgressionSuggestion` has no duplicate/pending guard in its contract. Callers can create multiple pending suggestions for the same exercise/profile, which later makes `hasPendingProgression` and UI prompts ambiguous.
- Suggested fix direction: Enforce at most one pending progression per exercise/profile either with a repository precondition/transaction or with a partial unique index if supported by the target SQLite setup.
