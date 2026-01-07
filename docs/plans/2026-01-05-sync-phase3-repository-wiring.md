# Sync Phase 3: Repository Wiring Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Wire SyncManager to actual repositories for push/pull operations with proper delta sync tracking.

**Architecture:** Add sync fields (updatedAt, serverId, deletedAt) to key tables via SQLDelight migration. Extend repositories with sync-specific methods. Wire SyncManager to collect local changes and merge remote changes.

**Tech Stack:** SQLDelight migrations, Kotlin Coroutines, Koin DI

**Reference:** Phase 2 plan at `docs/plans/2026-01-04-sync-phase2-mobile-foundation.md`

---

## Task 1: Create Migration 6 for Sync Fields

**Files:**
- Create: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/6.sqm`

**Step 1: Create the migration file**

```sql
-- Migration 6: Add sync tracking fields for cloud sync
-- Adds updatedAt, serverId, deletedAt to sync-enabled tables

-- WorkoutSession sync fields
ALTER TABLE WorkoutSession ADD COLUMN updatedAt INTEGER;
ALTER TABLE WorkoutSession ADD COLUMN serverId TEXT;
ALTER TABLE WorkoutSession ADD COLUMN deletedAt INTEGER;

-- PersonalRecord sync fields
ALTER TABLE PersonalRecord ADD COLUMN updatedAt INTEGER;
ALTER TABLE PersonalRecord ADD COLUMN serverId TEXT;
ALTER TABLE PersonalRecord ADD COLUMN deletedAt INTEGER;

-- Routine sync fields
ALTER TABLE Routine ADD COLUMN updatedAt INTEGER;
ALTER TABLE Routine ADD COLUMN serverId TEXT;
ALTER TABLE Routine ADD COLUMN deletedAt INTEGER;

-- Exercise sync fields (for custom exercises only)
ALTER TABLE Exercise ADD COLUMN updatedAt INTEGER;
ALTER TABLE Exercise ADD COLUMN serverId TEXT;
ALTER TABLE Exercise ADD COLUMN deletedAt INTEGER;

-- EarnedBadge sync fields
ALTER TABLE EarnedBadge ADD COLUMN updatedAt INTEGER;
ALTER TABLE EarnedBadge ADD COLUMN serverId TEXT;
ALTER TABLE EarnedBadge ADD COLUMN deletedAt INTEGER;

-- GamificationStats sync fields
ALTER TABLE GamificationStats ADD COLUMN updatedAt INTEGER;
ALTER TABLE GamificationStats ADD COLUMN serverId TEXT;

-- Backfill updatedAt with existing timestamps where available
UPDATE WorkoutSession SET updatedAt = timestamp WHERE updatedAt IS NULL;
UPDATE PersonalRecord SET updatedAt = achievedAt WHERE updatedAt IS NULL;
UPDATE Routine SET updatedAt = createdAt WHERE updatedAt IS NULL;
UPDATE Exercise SET updatedAt = created WHERE updatedAt IS NULL;
UPDATE EarnedBadge SET updatedAt = earnedAt WHERE updatedAt IS NULL;
UPDATE GamificationStats SET updatedAt = lastUpdated WHERE updatedAt IS NULL;
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/6.sqm
git commit -m "feat(sync): add migration 6 for sync tracking fields"
```

---

## Task 2: Add Sync Columns to Main Schema

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`

**Step 1: Add sync columns to WorkoutSession table definition**

Find the WorkoutSession CREATE TABLE and add after `rpe INTEGER`:
```sql
    rpe INTEGER,
    -- Sync fields (added in migration 6)
    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER
```

**Step 2: Add sync columns to PersonalRecord table definition**

Find the PersonalRecord CREATE TABLE and add after `volume REAL`:
```sql
    volume REAL NOT NULL DEFAULT 0.0,
    -- Sync fields (added in migration 6)
    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER
```

**Step 3: Add sync columns to Routine table definition**

Find the Routine CREATE TABLE and add after `useCount INTEGER`:
```sql
    useCount INTEGER NOT NULL DEFAULT 0,
    -- Sync fields (added in migration 6)
    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER
```

**Step 4: Add sync columns to Exercise table definition**

Find the Exercise CREATE TABLE and add after `one_rep_max_kg REAL`:
```sql
    one_rep_max_kg REAL DEFAULT NULL,
    -- Sync fields (added in migration 6)
    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER
```

**Step 5: Add sync columns to EarnedBadge table definition**

Find the EarnedBadge CREATE TABLE and add after `celebratedAt INTEGER`:
```sql
    celebratedAt INTEGER,
    -- Sync fields (added in migration 6)
    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER
```

**Step 6: Add sync columns to GamificationStats table definition**

Find the GamificationStats CREATE TABLE and add after `lastUpdated INTEGER`:
```sql
    lastUpdated INTEGER NOT NULL,
    -- Sync fields (added in migration 6)
    updatedAt INTEGER,
    serverId TEXT
```

**Step 7: Add sync queries at end of file**

Add before the closing of file:
```sql
-- ==================== SYNC QUERIES ====================

-- Get sessions modified since last sync (for push)
selectSessionsModifiedSince:
SELECT * FROM WorkoutSession
WHERE (updatedAt > ? OR updatedAt IS NULL) AND deletedAt IS NULL
ORDER BY timestamp DESC;

-- Get soft-deleted sessions (for push)
selectDeletedSessionsSince:
SELECT id, serverId, deletedAt FROM WorkoutSession
WHERE deletedAt IS NOT NULL AND deletedAt > ?;

-- Update session server ID after push
updateSessionServerId:
UPDATE WorkoutSession SET serverId = ? WHERE id = ?;

-- Soft delete session
softDeleteSession:
UPDATE WorkoutSession SET deletedAt = ?, updatedAt = ? WHERE id = ?;

-- Upsert session from sync (for pull)
upsertSyncSession:
INSERT OR REPLACE INTO WorkoutSession (
    id, timestamp, mode, targetReps, weightPerCableKg, progressionKg,
    duration, totalReps, warmupReps, workingReps,
    isJustLift, stopAtTop, eccentricLoad, echoLevel,
    exerciseId, exerciseName, routineSessionId, routineName,
    safetyFlags, deloadWarningCount, romViolationCount, spotterActivations,
    peakForceConcentricA, peakForceConcentricB, peakForceEccentricA, peakForceEccentricB,
    avgForceConcentricA, avgForceConcentricB, avgForceEccentricA, avgForceEccentricB,
    heaviestLiftKg, totalVolumeKg, estimatedCalories,
    warmupAvgWeightKg, workingAvgWeightKg, burnoutAvgWeightKg, peakWeightKg, rpe,
    updatedAt, serverId, deletedAt
)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

-- Get PRs modified since last sync
selectPRsModifiedSince:
SELECT * FROM PersonalRecord
WHERE (updatedAt > ? OR updatedAt IS NULL) AND deletedAt IS NULL;

-- Update PR server ID
updatePRServerId:
UPDATE PersonalRecord SET serverId = ? WHERE id = ?;

-- Get routines modified since last sync
selectRoutinesModifiedSince:
SELECT * FROM Routine
WHERE (updatedAt > ? OR updatedAt IS NULL) AND deletedAt IS NULL;

-- Update routine server ID
updateRoutineServerId:
UPDATE Routine SET serverId = ? WHERE id = ?;

-- Get custom exercises modified since last sync
selectCustomExercisesModifiedSince:
SELECT * FROM Exercise
WHERE isCustom = 1 AND (updatedAt > ? OR updatedAt IS NULL) AND deletedAt IS NULL;

-- Update exercise server ID
updateExerciseServerId:
UPDATE Exercise SET serverId = ? WHERE id = ?;

-- Get badges modified since last sync
selectBadgesModifiedSince:
SELECT * FROM EarnedBadge
WHERE (updatedAt > ? OR updatedAt IS NULL) AND deletedAt IS NULL;

-- Update badge server ID
updateBadgeServerId:
UPDATE EarnedBadge SET serverId = ? WHERE id = ?;

-- Get gamification stats
selectGamificationStatsForSync:
SELECT * FROM GamificationStats WHERE id = 1;

-- Update gamification stats server ID
updateGamificationStatsServerId:
UPDATE GamificationStats SET serverId = ? WHERE id = 1;

-- Find session by server ID (for conflict resolution)
selectSessionByServerId:
SELECT * FROM WorkoutSession WHERE serverId = ?;

-- Find PR by server ID
selectPRByServerId:
SELECT * FROM PersonalRecord WHERE serverId = ?;

-- Find routine by server ID
selectRoutineByServerId:
SELECT * FROM Routine WHERE serverId = ?;

-- Mark session updatedAt on save
updateSessionTimestamp:
UPDATE WorkoutSession SET updatedAt = ? WHERE id = ?;
```

**Step 8: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 9: Commit**

```bash
git add shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq
git commit -m "feat(sync): add sync columns and queries to schema"
```

---

## Task 3: Create SyncRepository Interface

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SyncRepository.kt`

**Step 1: Create the sync repository interface**

```kotlin
package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.*
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for sync operations.
 * Provides methods to get local changes for push and merge remote changes from pull.
 */
interface SyncRepository {

    // === Push Operations (get local changes) ===

    /**
     * Get workout sessions modified since the given timestamp
     */
    suspend fun getSessionsModifiedSince(timestamp: Long): List<WorkoutSessionSyncDto>

    /**
     * Get personal records modified since the given timestamp
     */
    suspend fun getPRsModifiedSince(timestamp: Long): List<PersonalRecordSyncDto>

    /**
     * Get routines modified since the given timestamp
     */
    suspend fun getRoutinesModifiedSince(timestamp: Long): List<RoutineSyncDto>

    /**
     * Get custom exercises modified since the given timestamp
     */
    suspend fun getCustomExercisesModifiedSince(timestamp: Long): List<CustomExerciseSyncDto>

    /**
     * Get earned badges modified since the given timestamp
     */
    suspend fun getBadgesModifiedSince(timestamp: Long): List<EarnedBadgeSyncDto>

    /**
     * Get current gamification stats for sync
     */
    suspend fun getGamificationStatsForSync(): GamificationStatsSyncDto?

    // === ID Mapping (after push) ===

    /**
     * Update server IDs after successful push
     */
    suspend fun updateServerIds(mappings: IdMappings)

    // === Pull Operations (merge remote changes) ===

    /**
     * Merge sessions from server (upsert with conflict resolution)
     */
    suspend fun mergeSessions(sessions: List<WorkoutSessionSyncDto>)

    /**
     * Merge personal records from server
     */
    suspend fun mergePRs(records: List<PersonalRecordSyncDto>)

    /**
     * Merge routines from server
     */
    suspend fun mergeRoutines(routines: List<RoutineSyncDto>)

    /**
     * Merge custom exercises from server
     */
    suspend fun mergeCustomExercises(exercises: List<CustomExerciseSyncDto>)

    /**
     * Merge badges from server
     */
    suspend fun mergeBadges(badges: List<EarnedBadgeSyncDto>)

    /**
     * Merge gamification stats from server
     */
    suspend fun mergeGamificationStats(stats: GamificationStatsSyncDto?)
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SyncRepository.kt
git commit -m "feat(sync): add SyncRepository interface"
```

---

## Task 4: Implement SqlDelightSyncRepository

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightSyncRepository.kt`

**Step 1: Create the implementation**

```kotlin
package com.devil.phoenixproject.data.repository

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.data.sync.*
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class SqlDelightSyncRepository(
    private val db: VitruvianDatabase
) : SyncRepository {

    private val queries = db.vitruvianDatabaseQueries

    // === Push Operations ===

    override suspend fun getSessionsModifiedSince(timestamp: Long): List<WorkoutSessionSyncDto> {
        return withContext(Dispatchers.IO) {
            queries.selectSessionsModifiedSince(timestamp).executeAsList().map { row ->
                WorkoutSessionSyncDto(
                    clientId = row.id,
                    serverId = row.serverId,
                    timestamp = row.timestamp,
                    mode = row.mode,
                    targetReps = row.targetReps.toInt(),
                    weightPerCableKg = row.weightPerCableKg.toFloat(),
                    duration = row.duration.toInt(),
                    totalReps = row.totalReps.toInt(),
                    exerciseId = row.exerciseId,
                    exerciseName = row.exerciseName,
                    deletedAt = row.deletedAt,
                    createdAt = row.timestamp, // Use timestamp as createdAt
                    updatedAt = row.updatedAt ?: row.timestamp
                )
            }
        }
    }

    override suspend fun getPRsModifiedSince(timestamp: Long): List<PersonalRecordSyncDto> {
        return withContext(Dispatchers.IO) {
            queries.selectPRsModifiedSince(timestamp).executeAsList().map { row ->
                PersonalRecordSyncDto(
                    clientId = row.id.toString(),
                    serverId = row.serverId,
                    exerciseId = row.exerciseId,
                    exerciseName = row.exerciseName,
                    weight = row.weight.toFloat(),
                    reps = row.reps.toInt(),
                    oneRepMax = row.oneRepMax.toFloat(),
                    achievedAt = row.achievedAt,
                    workoutMode = row.workoutMode,
                    deletedAt = row.deletedAt,
                    createdAt = row.achievedAt,
                    updatedAt = row.updatedAt ?: row.achievedAt
                )
            }
        }
    }

    override suspend fun getRoutinesModifiedSince(timestamp: Long): List<RoutineSyncDto> {
        return withContext(Dispatchers.IO) {
            queries.selectRoutinesModifiedSince(timestamp).executeAsList().map { row ->
                RoutineSyncDto(
                    clientId = row.id,
                    serverId = row.serverId,
                    name = row.name,
                    description = row.description,
                    deletedAt = row.deletedAt,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt ?: row.createdAt
                )
            }
        }
    }

    override suspend fun getCustomExercisesModifiedSince(timestamp: Long): List<CustomExerciseSyncDto> {
        return withContext(Dispatchers.IO) {
            queries.selectCustomExercisesModifiedSince(timestamp).executeAsList().map { row ->
                CustomExerciseSyncDto(
                    clientId = row.id,
                    serverId = row.serverId,
                    name = row.name,
                    muscleGroup = row.muscleGroup,
                    equipment = row.equipment,
                    defaultCableConfig = row.defaultCableConfig,
                    deletedAt = row.deletedAt,
                    createdAt = row.created,
                    updatedAt = row.updatedAt ?: row.created
                )
            }
        }
    }

    override suspend fun getBadgesModifiedSince(timestamp: Long): List<EarnedBadgeSyncDto> {
        return withContext(Dispatchers.IO) {
            queries.selectBadgesModifiedSince(timestamp).executeAsList().map { row ->
                EarnedBadgeSyncDto(
                    clientId = row.id.toString(),
                    serverId = row.serverId,
                    badgeId = row.badgeId,
                    earnedAt = row.earnedAt,
                    deletedAt = row.deletedAt,
                    createdAt = row.earnedAt,
                    updatedAt = row.updatedAt ?: row.earnedAt
                )
            }
        }
    }

    override suspend fun getGamificationStatsForSync(): GamificationStatsSyncDto? {
        return withContext(Dispatchers.IO) {
            queries.selectGamificationStatsForSync().executeAsOneOrNull()?.let { row ->
                GamificationStatsSyncDto(
                    clientId = row.id.toString(),
                    totalWorkouts = row.totalWorkouts.toInt(),
                    totalReps = row.totalReps.toInt(),
                    totalVolumeKg = row.totalVolumeKg.toInt(),
                    longestStreak = row.longestStreak.toInt(),
                    currentStreak = row.currentStreak.toInt(),
                    updatedAt = row.updatedAt ?: row.lastUpdated
                )
            }
        }
    }

    // === ID Mapping ===

    override suspend fun updateServerIds(mappings: IdMappings) {
        withContext(Dispatchers.IO) {
            db.transaction {
                mappings.sessions.forEach { (clientId, serverId) ->
                    queries.updateSessionServerId(serverId, clientId)
                }
                mappings.records.forEach { (clientId, serverId) ->
                    queries.updatePRServerId(serverId, clientId.toLongOrNull() ?: return@forEach)
                }
                mappings.routines.forEach { (clientId, serverId) ->
                    queries.updateRoutineServerId(serverId, clientId)
                }
                mappings.exercises.forEach { (clientId, serverId) ->
                    queries.updateExerciseServerId(serverId, clientId)
                }
                mappings.badges.forEach { (clientId, serverId) ->
                    queries.updateBadgeServerId(serverId, clientId.toLongOrNull() ?: return@forEach)
                }
            }
            Logger.d { "Updated server IDs: ${mappings.sessions.size} sessions, ${mappings.records.size} PRs, ${mappings.routines.size} routines" }
        }
    }

    // === Pull Operations ===

    override suspend fun mergeSessions(sessions: List<WorkoutSessionSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                sessions.forEach { dto ->
                    // Check if we have this session locally (by serverId or clientId)
                    val existingByServer = dto.serverId?.let {
                        queries.selectSessionByServerId(it).executeAsOneOrNull()
                    }

                    val localId = existingByServer?.id ?: dto.clientId

                    // Server wins for conflict resolution (last-write-wins)
                    queries.upsertSyncSession(
                        id = localId,
                        timestamp = dto.timestamp,
                        mode = dto.mode,
                        targetReps = dto.targetReps.toLong(),
                        weightPerCableKg = dto.weightPerCableKg.toDouble(),
                        progressionKg = 0.0,
                        duration = dto.duration.toLong(),
                        totalReps = dto.totalReps.toLong(),
                        warmupReps = 0L,
                        workingReps = dto.totalReps.toLong(),
                        isJustLift = 0L,
                        stopAtTop = 0L,
                        eccentricLoad = 100L,
                        echoLevel = 1L,
                        exerciseId = dto.exerciseId,
                        exerciseName = dto.exerciseName,
                        routineSessionId = null,
                        routineName = null,
                        safetyFlags = 0L,
                        deloadWarningCount = 0L,
                        romViolationCount = 0L,
                        spotterActivations = 0L,
                        peakForceConcentricA = null,
                        peakForceConcentricB = null,
                        peakForceEccentricA = null,
                        peakForceEccentricB = null,
                        avgForceConcentricA = null,
                        avgForceConcentricB = null,
                        avgForceEccentricA = null,
                        avgForceEccentricB = null,
                        heaviestLiftKg = null,
                        totalVolumeKg = null,
                        estimatedCalories = null,
                        warmupAvgWeightKg = null,
                        workingAvgWeightKg = null,
                        burnoutAvgWeightKg = null,
                        peakWeightKg = null,
                        rpe = null,
                        updatedAt = dto.updatedAt,
                        serverId = dto.serverId,
                        deletedAt = dto.deletedAt
                    )
                }
            }
            Logger.d { "Merged ${sessions.size} sessions from server" }
        }
    }

    override suspend fun mergePRs(records: List<PersonalRecordSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                records.forEach { dto ->
                    // For PRs, we upsert by exerciseId + workoutMode (unique key)
                    // Server data wins in conflicts
                    queries.upsertPR(
                        exerciseId = dto.exerciseId,
                        exerciseName = dto.exerciseName,
                        weight = dto.weight.toDouble(),
                        reps = dto.reps.toLong(),
                        oneRepMax = dto.oneRepMax.toDouble(),
                        achievedAt = dto.achievedAt,
                        workoutMode = dto.workoutMode,
                        prType = "MAX_WEIGHT",
                        volume = (dto.weight * dto.reps).toDouble()
                    )
                }
            }
            Logger.d { "Merged ${records.size} PRs from server" }
        }
    }

    override suspend fun mergeRoutines(routines: List<RoutineSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                routines.forEach { dto ->
                    val existingByServer = dto.serverId?.let {
                        queries.selectRoutineByServerId(it).executeAsOneOrNull()
                    }

                    val localId = existingByServer?.id ?: dto.clientId

                    queries.upsertRoutine(
                        id = localId,
                        name = dto.name,
                        description = dto.description,
                        createdAt = dto.createdAt,
                        lastUsed = null,
                        useCount = 0L
                    )

                    // Update sync fields
                    if (dto.serverId != null) {
                        queries.updateRoutineServerId(dto.serverId, localId)
                    }
                }
            }
            Logger.d { "Merged ${routines.size} routines from server" }
        }
    }

    override suspend fun mergeCustomExercises(exercises: List<CustomExerciseSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                exercises.forEach { dto ->
                    // Custom exercises - upsert by clientId
                    queries.insertExercise(
                        id = dto.clientId,
                        name = dto.name,
                        description = null,
                        created = dto.createdAt,
                        muscleGroup = dto.muscleGroup,
                        muscleGroups = dto.muscleGroup,
                        muscles = null,
                        equipment = dto.equipment,
                        movement = null,
                        sidedness = null,
                        grip = null,
                        gripWidth = null,
                        minRepRange = null,
                        popularity = 0.0,
                        archived = 0L,
                        isFavorite = 0L,
                        isCustom = 1L,
                        timesPerformed = 0L,
                        lastPerformed = null,
                        aliases = null,
                        defaultCableConfig = dto.defaultCableConfig,
                        one_rep_max_kg = null
                    )

                    if (dto.serverId != null) {
                        queries.updateExerciseServerId(dto.serverId, dto.clientId)
                    }
                }
            }
            Logger.d { "Merged ${exercises.size} custom exercises from server" }
        }
    }

    override suspend fun mergeBadges(badges: List<EarnedBadgeSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                badges.forEach { dto ->
                    queries.insertEarnedBadge(dto.badgeId, dto.earnedAt)
                }
            }
            Logger.d { "Merged ${badges.size} badges from server" }
        }
    }

    override suspend fun mergeGamificationStats(stats: GamificationStatsSyncDto?) {
        if (stats == null) return

        withContext(Dispatchers.IO) {
            val now = currentTimeMillis()
            queries.upsertGamificationStats(
                totalWorkouts = stats.totalWorkouts.toLong(),
                totalReps = stats.totalReps.toLong(),
                totalVolumeKg = stats.totalVolumeKg.toLong(),
                longestStreak = stats.longestStreak.toLong(),
                currentStreak = stats.currentStreak.toLong(),
                uniqueExercisesUsed = 0L,
                prsAchieved = 0L,
                lastWorkoutDate = null,
                streakStartDate = null,
                lastUpdated = now
            )
            Logger.d { "Merged gamification stats from server" }
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightSyncRepository.kt
git commit -m "feat(sync): implement SqlDelightSyncRepository"
```

---

## Task 5: Wire SyncRepository to SyncManager

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt`

**Step 1: Update SyncManager constructor to include SyncRepository**

Replace the class definition and constructor:

```kotlin
package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.SyncRepository
import com.devil.phoenixproject.getPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
    data class Success(val syncTime: Long) : SyncState()
    data class Error(val message: String) : SyncState()
    object NotAuthenticated : SyncState()
    object NotPremium : SyncState()
}

class SyncManager(
    private val apiClient: PortalApiClient,
    private val tokenStorage: PortalTokenStorage,
    private val syncRepository: SyncRepository
) {
```

**Step 2: Update pushLocalChanges to use SyncRepository**

Replace the `pushLocalChanges` method:

```kotlin
    private suspend fun pushLocalChanges(): Result<SyncPushResponse> {
        val deviceId = tokenStorage.getDeviceId()
        val lastSync = tokenStorage.getLastSyncTimestamp()
        val platform = getPlatformName()

        // Gather local changes from repositories
        val sessions = syncRepository.getSessionsModifiedSince(lastSync)
        val records = syncRepository.getPRsModifiedSince(lastSync)
        val routines = syncRepository.getRoutinesModifiedSince(lastSync)
        val exercises = syncRepository.getCustomExercisesModifiedSince(lastSync)
        val badges = syncRepository.getBadgesModifiedSince(lastSync)
        val gamificationStats = syncRepository.getGamificationStatsForSync()

        val request = SyncPushRequest(
            deviceId = deviceId,
            deviceName = getDeviceName(),
            platform = platform,
            lastSync = lastSync,
            sessions = sessions,
            records = records,
            routines = routines,
            exercises = exercises,
            badges = badges,
            gamificationStats = gamificationStats
        )

        return apiClient.pushChanges(request).also { result ->
            // Update server IDs on success
            result.onSuccess { response ->
                syncRepository.updateServerIds(response.idMappings)
            }
        }
    }
```

**Step 3: Update pullRemoteChanges to use SyncRepository**

Replace the `pullRemoteChanges` method:

```kotlin
    private suspend fun pullRemoteChanges(): Result<Long> {
        val deviceId = tokenStorage.getDeviceId()
        val lastSync = tokenStorage.getLastSyncTimestamp()

        val request = SyncPullRequest(
            deviceId = deviceId,
            lastSync = lastSync
        )

        return apiClient.pullChanges(request).map { response ->
            // Merge pulled data into local repositories
            syncRepository.mergeSessions(response.sessions)
            syncRepository.mergePRs(response.records)
            syncRepository.mergeRoutines(response.routines)
            syncRepository.mergeCustomExercises(response.exercises)
            syncRepository.mergeBadges(response.badges)
            syncRepository.mergeGamificationStats(response.gamificationStats)

            response.syncTime
        }
    }
```

**Step 4: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt
git commit -m "feat(sync): wire SyncManager to SyncRepository for real data sync"
```

---

## Task 6: Update Koin DI to Include SyncRepository

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt`

**Step 1: Add SyncRepository imports**

Add with other imports:
```kotlin
import com.devil.phoenixproject.data.repository.SyncRepository
import com.devil.phoenixproject.data.repository.SqlDelightSyncRepository
```

**Step 2: Update SyncManager binding to include SyncRepository**

Find the existing sync bindings and update:

```kotlin
// Portal Sync
single { PortalTokenStorage(get()) }
single {
    PortalApiClient(
        tokenProvider = { get<PortalTokenStorage>().getToken() }
    )
}
single<SyncRepository> { SqlDelightSyncRepository(get()) }
single { SyncManager(get(), get(), get()) }
```

**Step 3: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt
git commit -m "feat(sync): add SyncRepository to Koin DI"
```

---

## Task 7: Build and Test Full App

**Step 1: Build full Android app**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: Verify database version updated**

Check that `shared/build.gradle.kts` has schemaVersion set appropriately (if not auto-detected).

**Step 3: Final commit**

```bash
git add .
git commit -m "feat(sync): Phase 3 complete - repository wiring for cloud sync"
```

---

## Summary

Phase 3 wires SyncManager to actual data:

| Component | Purpose |
|-----------|---------|
| Migration 6.sqm | Adds updatedAt, serverId, deletedAt to sync tables |
| SyncRepository interface | Contract for sync data operations |
| SqlDelightSyncRepository | Implementation with push/pull/merge logic |
| SyncManager updates | Uses SyncRepository instead of empty stubs |
| Koin DI updates | Wires SyncRepository into dependency graph |

**Next Phase:** Phase 4 will add automatic sync triggers (on workout complete, on app foreground) and background periodic sync.
