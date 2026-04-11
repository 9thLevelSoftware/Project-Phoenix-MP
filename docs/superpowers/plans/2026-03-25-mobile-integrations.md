# Mobile Third-Party Integrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Hevy, Liftosaur, Strong CSV, Apple Health, and Google Health Connect integrations to the mobile app with local-first storage and server-side API key management.

**Architecture:** Hybrid — CSV export/import runs fully on-device from SQLite; health integrations use platform-native APIs (HealthKit/Health Connect) via expect/actual; API syncs (Hevy/Liftosaur) delegate to Edge Functions keeping secrets server-side. A new local `ExternalActivity` SQLite table stores imported/synced data; paid users get cloud sync to Supabase.

**Tech Stack:** Kotlin Multiplatform, SQLDelight, Koin, Ktor, Compose Multiplatform, HealthKit (iOS), Health Connect (Android), Supabase Edge Functions (TypeScript/Deno)

**Spec:** `docs/superpowers/specs/2026-03-25-mobile-integrations-design.md`

---

## File Map

### New Files — shared/src/commonMain

| File | Responsibility |
|------|---------------|
| `domain/model/ExternalActivity.kt` | `ExternalActivity` data class, `IntegrationProvider` enum, `ConnectionStatus` enum, `IntegrationStatus` data class |
| `data/integration/ExternalActivityRepository.kt` | Repository interface for external activities + integration status |
| `data/integration/SqlDelightExternalActivityRepository.kt` | SQLDelight implementation of the repository |
| `data/integration/CsvExporter.kt` | Generates Strong-format CSV from local WorkoutSession data |
| `data/integration/CsvImporter.kt` | Parses Strong and Hevy CSV formats into `ExternalActivity` list |
| `data/integration/IntegrationManager.kt` | Orchestrates API sync triggers via Edge Functions |
| `presentation/screen/IntegrationsScreen.kt` | Settings → Integrations UI (health toggles, Hevy/Liftosaur cards, CSV card) |
| `presentation/screen/ExternalActivitiesScreen.kt` | Read-only list of imported/synced external activities |
| `presentation/viewmodel/IntegrationsViewModel.kt` | State management for integrations screen |

### New Files — Platform-Specific

| File | Responsibility |
|------|---------------|
| `androidMain/.../data/integration/HealthIntegration.android.kt` | Health Connect `actual` implementation |
| `iosMain/.../data/integration/HealthIntegration.ios.kt` | HealthKit `actual` implementation |
| `commonMain/.../data/integration/HealthIntegration.kt` | `expect` class declaration |

### New Files — Portal Edge Functions

| File | Responsibility |
|------|---------------|
| `phoenix-portal/supabase/functions/mobile-integration-sync/index.ts` | API sync trigger, API key storage, provider dispatch |

### Modified Files

| File | Change |
|------|--------|
| `shared/src/commonMain/sqldelight/.../VitruvianDatabase.sq` | Add ExternalActivity + IntegrationStatus table definitions and queries |
| `shared/src/commonMain/sqldelight/.../migrations/23.sqm` | Migration to create ExternalActivity + IntegrationStatus tables |
| `shared/build.gradle.kts` | Bump SQLDelight version from 23 to 24; add Health Connect dependency on androidMain |
| `shared/src/commonMain/.../data/sync/PortalSyncDtos.kt` | Add `ExternalActivityDto`, extend `PortalSyncPayload` and `PortalSyncPullResponse` |
| `shared/src/commonMain/.../data/sync/SyncManager.kt` | Add external activity push/pull in `pushLocalChanges()` and `pullRemoteChanges()` |
| `shared/src/commonMain/.../data/sync/PortalApiClient.kt` | Add `callIntegrationSync()` method |
| `shared/src/commonMain/.../di/DataModule.kt` | Register `ExternalActivityRepository` |
| `shared/src/commonMain/.../di/SyncModule.kt` | Register `IntegrationManager` |
| `shared/src/commonMain/.../di/PresentationModule.kt` | Register `IntegrationsViewModel` |
| `shared/src/commonMain/.../presentation/screen/SettingsTab.kt` | Add "Integrations" navigation row |
| `shared/src/commonMain/.../presentation/manager/ActiveSessionEngine.kt` | Add health auto-push after workout completion |
| `phoenix-portal/supabase/functions/mobile-sync-push/index.ts` | Accept `externalActivities` array in push payload |
| `phoenix-portal/supabase/functions/mobile-sync-pull/index.ts` | Return `externalActivities` in pull response |

---

## Phase 1: Domain Models + Database Schema

### Task 1: Domain Models

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ExternalActivity.kt`
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/model/ExternalActivityTest.kt`

- [ ] **Step 1: Write the domain model file**

```kotlin
package com.devil.phoenixproject.domain.model

/**
 * Provider for third-party integrations.
 * The [key] matches the portal's `user_integrations.provider` column.
 */
enum class IntegrationProvider(val key: String, val displayName: String) {
    HEVY("hevy", "Hevy"),
    LIFTOSAUR("liftosaur", "Liftosaur"),
    STRONG("strong", "Strong"),
    APPLE_HEALTH("apple_health", "Apple Health"),
    GOOGLE_HEALTH("google_health", "Google Health Connect");

    companion object {
        fun fromKey(key: String): IntegrationProvider? = entries.find { it.key == key }
    }
}

/**
 * Connection status for an integration provider.
 */
enum class ConnectionStatus {
    CONNECTED, DISCONNECTED, ERROR, TOKEN_EXPIRED
}

/**
 * Tracks connection state per provider.
 */
data class IntegrationStatus(
    val provider: IntegrationProvider,
    val status: ConnectionStatus,
    val lastSyncAt: Long? = null,
    val errorMessage: String? = null,
    val profileId: String = "default"
)

/**
 * Normalized activity from an external source (Hevy, Liftosaur, Strong CSV, etc.).
 * Weights stored as-is from the source app (total weight, NOT per-cable).
 * This is distinct from WorkoutSession which uses per-cable convention.
 */
data class ExternalActivity(
    val id: String = generateUUID(),
    val externalId: String,
    val provider: IntegrationProvider,
    val name: String,
    val activityType: String = "strength",
    val startedAt: Long,
    val durationSeconds: Int = 0,
    val distanceMeters: Double? = null,
    val calories: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val elevationGainMeters: Double? = null,
    val rawData: String? = null,
    val syncedAt: Long = currentTimeMillis(),
    val profileId: String = "default",
    val needsSync: Boolean = true
)
```

- [ ] **Step 2: Write tests for IntegrationProvider.fromKey() and model defaults**

```kotlin
package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalActivityTest {

    @Test
    fun integrationProvider_fromKey_validKey_returnsProvider() {
        assertEquals(IntegrationProvider.HEVY, IntegrationProvider.fromKey("hevy"))
        assertEquals(IntegrationProvider.LIFTOSAUR, IntegrationProvider.fromKey("liftosaur"))
        assertEquals(IntegrationProvider.STRONG, IntegrationProvider.fromKey("strong"))
        assertEquals(IntegrationProvider.APPLE_HEALTH, IntegrationProvider.fromKey("apple_health"))
        assertEquals(IntegrationProvider.GOOGLE_HEALTH, IntegrationProvider.fromKey("google_health"))
    }

    @Test
    fun integrationProvider_fromKey_unknownKey_returnsNull() {
        assertNull(IntegrationProvider.fromKey("fitbit"))
        assertNull(IntegrationProvider.fromKey(""))
    }

    @Test
    fun externalActivity_defaults() {
        val activity = ExternalActivity(
            externalId = "hevy-chest-12345",
            provider = IntegrationProvider.HEVY,
            name = "Chest Day",
            startedAt = 1000L
        )
        assertEquals("strength", activity.activityType)
        assertEquals(0, activity.durationSeconds)
        assertNull(activity.calories)
        assertEquals("default", activity.profileId)
        assertEquals(true, activity.needsSync)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.devil.phoenixproject.domain.model.ExternalActivityTest" -q`
Expected: PASS (3 tests)

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ExternalActivity.kt \
       shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/model/ExternalActivityTest.kt
git commit -m "feat(integration): add ExternalActivity domain models and IntegrationProvider enum"
```

---

### Task 2: SQLDelight Migration + Schema

**Files:**
- Create: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/23.sqm`
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
- Modify: `shared/build.gradle.kts` (version 23 → 24)

- [ ] **Step 1: Create migration 23.sqm**

Create file at `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/23.sqm`:

```sql
-- Migration 23: Add ExternalActivity and IntegrationStatus tables for third-party integrations.
-- ExternalActivity stores normalized workout data from Hevy, Liftosaur, Strong CSV, etc.
-- IntegrationStatus tracks connection state per provider per profile.

CREATE TABLE IF NOT EXISTS ExternalActivity (
    id TEXT NOT NULL PRIMARY KEY,
    externalId TEXT NOT NULL,
    provider TEXT NOT NULL,
    name TEXT NOT NULL,
    activityType TEXT NOT NULL DEFAULT 'strength',
    startedAt INTEGER NOT NULL,
    durationSeconds INTEGER NOT NULL DEFAULT 0,
    distanceMeters REAL,
    calories INTEGER,
    avgHeartRate INTEGER,
    maxHeartRate INTEGER,
    elevationGainMeters REAL,
    rawData TEXT,
    syncedAt INTEGER NOT NULL,
    profileId TEXT NOT NULL DEFAULT 'default',
    needsSync INTEGER NOT NULL DEFAULT 1
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_external_activity_dedup ON ExternalActivity(externalId, provider);
CREATE INDEX IF NOT EXISTS idx_external_activity_profile ON ExternalActivity(profileId);
CREATE INDEX IF NOT EXISTS idx_external_activity_provider ON ExternalActivity(provider);
CREATE INDEX IF NOT EXISTS idx_external_activity_started ON ExternalActivity(startedAt DESC);

CREATE TABLE IF NOT EXISTS IntegrationStatus (
    provider TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'disconnected',
    lastSyncAt INTEGER,
    errorMessage TEXT,
    profileId TEXT NOT NULL DEFAULT 'default',
    PRIMARY KEY(provider, profileId)
);
```

- [ ] **Step 2: Add table definitions and queries to VitruvianDatabase.sq**

Append to the end of `VitruvianDatabase.sq` (before any trailing comments):

```sql
-- External Activities (Third-party integrations)
CREATE TABLE IF NOT EXISTS ExternalActivity (
    id TEXT NOT NULL PRIMARY KEY,
    externalId TEXT NOT NULL,
    provider TEXT NOT NULL,
    name TEXT NOT NULL,
    activityType TEXT NOT NULL DEFAULT 'strength',
    startedAt INTEGER NOT NULL,
    durationSeconds INTEGER NOT NULL DEFAULT 0,
    distanceMeters REAL,
    calories INTEGER,
    avgHeartRate INTEGER,
    maxHeartRate INTEGER,
    elevationGainMeters REAL,
    rawData TEXT,
    syncedAt INTEGER NOT NULL,
    profileId TEXT NOT NULL DEFAULT 'default',
    needsSync INTEGER NOT NULL DEFAULT 1
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_external_activity_dedup ON ExternalActivity(externalId, provider);
CREATE INDEX IF NOT EXISTS idx_external_activity_profile ON ExternalActivity(profileId);
CREATE INDEX IF NOT EXISTS idx_external_activity_provider ON ExternalActivity(provider);
CREATE INDEX IF NOT EXISTS idx_external_activity_started ON ExternalActivity(startedAt DESC);

-- Integration Status
CREATE TABLE IF NOT EXISTS IntegrationStatus (
    provider TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'disconnected',
    lastSyncAt INTEGER,
    errorMessage TEXT,
    profileId TEXT NOT NULL DEFAULT 'default',
    PRIMARY KEY(provider, profileId)
);

-- ExternalActivity queries
getAllExternalActivities:
SELECT * FROM ExternalActivity
WHERE profileId = ?
ORDER BY startedAt DESC;

getExternalActivitiesByProvider:
SELECT * FROM ExternalActivity
WHERE profileId = ? AND provider = ?
ORDER BY startedAt DESC;

getUnsyncedExternalActivities:
SELECT * FROM ExternalActivity
WHERE profileId = ? AND needsSync = 1;

upsertExternalActivity:
INSERT OR REPLACE INTO ExternalActivity(
    id, externalId, provider, name, activityType, startedAt,
    durationSeconds, distanceMeters, calories, avgHeartRate,
    maxHeartRate, elevationGainMeters, rawData, syncedAt, profileId, needsSync
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

markExternalActivitySynced:
UPDATE ExternalActivity SET needsSync = 0 WHERE id = ?;

deleteExternalActivitiesByProvider:
DELETE FROM ExternalActivity WHERE provider = ? AND profileId = ?;

-- IntegrationStatus queries
getIntegrationStatus:
SELECT * FROM IntegrationStatus
WHERE provider = ? AND profileId = ?;

getAllIntegrationStatuses:
SELECT * FROM IntegrationStatus
WHERE profileId = ?;

upsertIntegrationStatus:
INSERT OR REPLACE INTO IntegrationStatus(provider, status, lastSyncAt, errorMessage, profileId)
VALUES (?, ?, ?, ?, ?);
```

- [ ] **Step 3: Bump SQLDelight version in build.gradle.kts**

In `shared/build.gradle.kts`, change:
```
version = 23
```
to:
```
version = 24
```

- [ ] **Step 4: Verify the schema compiles**

Run: `./gradlew :shared:generateCommonMainVitruvianDatabaseInterface -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/23.sqm \
       shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq \
       shared/build.gradle.kts
git commit -m "feat(integration): add ExternalActivity and IntegrationStatus SQLDelight schema (migration 23)"
```

---

### Task 3: ExternalActivityRepository

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/ExternalActivityRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/SqlDelightExternalActivityRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`

- [ ] **Step 1: Write the repository interface**

```kotlin
package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.IntegrationStatus
import kotlinx.coroutines.flow.Flow

interface ExternalActivityRepository {
    fun getAll(profileId: String, provider: IntegrationProvider? = null): Flow<List<ExternalActivity>>
    suspend fun getUnsyncedActivities(profileId: String): List<ExternalActivity>
    suspend fun upsertActivities(activities: List<ExternalActivity>)
    suspend fun markSynced(ids: List<String>)
    suspend fun deleteActivities(provider: IntegrationProvider, profileId: String)

    fun getIntegrationStatus(provider: IntegrationProvider, profileId: String): Flow<IntegrationStatus?>
    fun getAllIntegrationStatuses(profileId: String): Flow<List<IntegrationStatus>>
    suspend fun updateIntegrationStatus(
        provider: IntegrationProvider,
        status: ConnectionStatus,
        profileId: String,
        lastSyncAt: Long? = null,
        errorMessage: String? = null
    )
}
```

- [ ] **Step 2: Write the SqlDelight implementation**

```kotlin
package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.IntegrationStatus
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SqlDelightExternalActivityRepository(
    private val database: VitruvianDatabase
) : ExternalActivityRepository {

    private val queries get() = database.vitruvianDatabaseQueries

    override fun getAll(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalActivity>> {
        val dbFlow = if (provider != null) {
            queries.getExternalActivitiesByProvider(profileId, provider.key)
        } else {
            queries.getAllExternalActivities(profileId)
        }
        return dbFlow.asFlow().mapToList(Dispatchers.IO).map { rows ->
            rows.map { it.toDomain() }
        }
    }

    override suspend fun getUnsyncedActivities(profileId: String): List<ExternalActivity> =
        withContext(Dispatchers.IO) {
            queries.getUnsyncedExternalActivities(profileId).executeAsList().map { it.toDomain() }
        }

    override suspend fun upsertActivities(activities: List<ExternalActivity>) =
        withContext(Dispatchers.IO) {
            database.transaction {
                activities.forEach { a ->
                    queries.upsertExternalActivity(
                        id = a.id,
                        externalId = a.externalId,
                        provider = a.provider.key,
                        name = a.name,
                        activityType = a.activityType,
                        startedAt = a.startedAt,
                        durationSeconds = a.durationSeconds.toLong(),
                        distanceMeters = a.distanceMeters,
                        calories = a.calories?.toLong(),
                        avgHeartRate = a.avgHeartRate?.toLong(),
                        maxHeartRate = a.maxHeartRate?.toLong(),
                        elevationGainMeters = a.elevationGainMeters,
                        rawData = a.rawData,
                        syncedAt = a.syncedAt,
                        profileId = a.profileId,
                        needsSync = if (a.needsSync) 1L else 0L
                    )
                }
            }
        }

    override suspend fun markSynced(ids: List<String>) = withContext(Dispatchers.IO) {
        database.transaction {
            ids.forEach { id -> queries.markExternalActivitySynced(id) }
        }
    }

    override suspend fun deleteActivities(provider: IntegrationProvider, profileId: String) =
        withContext(Dispatchers.IO) {
            queries.deleteExternalActivitiesByProvider(provider.key, profileId)
        }

    override fun getIntegrationStatus(
        provider: IntegrationProvider,
        profileId: String
    ): Flow<IntegrationStatus?> {
        return queries.getIntegrationStatus(provider.key, profileId)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { row -> row?.toStatusDomain() }
    }

    override fun getAllIntegrationStatuses(profileId: String): Flow<List<IntegrationStatus>> {
        return queries.getAllIntegrationStatuses(profileId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toStatusDomain() } }
    }

    override suspend fun updateIntegrationStatus(
        provider: IntegrationProvider,
        status: ConnectionStatus,
        profileId: String,
        lastSyncAt: Long?,
        errorMessage: String?
    ) = withContext(Dispatchers.IO) {
        queries.upsertIntegrationStatus(
            provider = provider.key,
            status = status.name,
            lastSyncAt = lastSyncAt,
            errorMessage = errorMessage,
            profileId = profileId
        )
    }

    // --- Row mappers ---

    private fun com.devil.phoenixproject.database.ExternalActivity.toDomain(): ExternalActivity {
        return ExternalActivity(
            id = id,
            externalId = externalId,
            provider = IntegrationProvider.fromKey(provider) ?: IntegrationProvider.STRONG,
            name = name,
            activityType = activityType,
            startedAt = startedAt,
            durationSeconds = durationSeconds.toInt(),
            distanceMeters = distanceMeters,
            calories = calories?.toInt(),
            avgHeartRate = avgHeartRate?.toInt(),
            maxHeartRate = maxHeartRate?.toInt(),
            elevationGainMeters = elevationGainMeters,
            rawData = rawData,
            syncedAt = syncedAt,
            profileId = profileId,
            needsSync = needsSync == 1L
        )
    }

    private fun com.devil.phoenixproject.database.IntegrationStatus.toStatusDomain(): IntegrationStatus {
        return IntegrationStatus(
            provider = IntegrationProvider.fromKey(provider) ?: IntegrationProvider.STRONG,
            status = try {
                ConnectionStatus.valueOf(status)
            } catch (_: IllegalArgumentException) {
                ConnectionStatus.DISCONNECTED
            },
            lastSyncAt = lastSyncAt,
            errorMessage = errorMessage,
            profileId = profileId
        )
    }
}
```

- [ ] **Step 3: Register in DataModule**

Add to `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`:

```kotlin
// After the existing repository registrations, add:
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.SqlDelightExternalActivityRepository

// Inside the module block:
single<ExternalActivityRepository> { SqlDelightExternalActivityRepository(get()) }
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/ExternalActivityRepository.kt \
       shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/SqlDelightExternalActivityRepository.kt \
       shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt
git commit -m "feat(integration): add ExternalActivityRepository with SqlDelight implementation"
```

---

## Phase 2: CSV Export

### Task 4: CsvExporter — Strong Format

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/CsvExporter.kt`
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/integration/CsvExporterTest.kt`

- [ ] **Step 1: Write CsvExporter**

```kotlin
package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * Generates Strong-format CSV from local WorkoutSession data.
 *
 * Strong CSV is the de facto standard accepted by Hevy, Strong, and analytics tools.
 * Columns: Date, Workout Name, Duration, Exercise Name, Set Order, Weight, Reps,
 *          Distance, Seconds, Notes, Workout Notes
 *
 * IMPORTANT: WorkoutSession stores weight as per-cable (kg). This exporter
 * multiplies by 2 (WEIGHT_MULTIPLIER) for total weight, then optionally
 * converts to lbs.
 */
class CsvExporter {

    companion object {
        /** Per-cable to total weight multiplier (matches portal's WEIGHT_MULTIPLIER) */
        const val WEIGHT_MULTIPLIER = 2

        private const val KG_TO_LBS = 2.20462f

        private val HEADER = "Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes"
    }

    /**
     * Generate a Strong-format CSV string from workout sessions.
     *
     * @param sessions List of WorkoutSession (from local SQLite)
     * @param weightUnit Target weight unit for the CSV
     * @return CSV string content ready to be written to file
     */
    fun generateStrongCsv(
        sessions: List<WorkoutSession>,
        weightUnit: WeightUnit = WeightUnit.KG
    ): String {
        if (sessions.isEmpty()) return HEADER

        val lines = mutableListOf(HEADER)

        // Group sessions by routineSessionId (or standalone)
        // Each group becomes one "workout" in Strong terms
        val grouped = groupSessionsIntoWorkouts(sessions)

        for ((workoutName, workoutSessions) in grouped) {
            val sorted = workoutSessions.sortedBy { it.timestamp }
            val startTime = sorted.first().timestamp
            val totalDurationSec = sorted.sumOf { it.duration }
            val durationStr = formatDuration(totalDurationSec)
            val dateStr = formatTimestamp(startTime)

            sorted.forEachIndexed { index, session ->
                val exerciseName = session.exerciseName ?: "Unknown Exercise"
                val totalWeightKg = session.weightPerCableKg * WEIGHT_MULTIPLIER
                val weight = if (weightUnit == WeightUnit.LB) {
                    totalWeightKg * KG_TO_LBS
                } else {
                    totalWeightKg
                }
                val weightStr = formatWeight(weight)
                val reps = session.totalReps
                val setOrder = index + 1

                lines.add(csvRow(
                    dateStr, workoutName, durationStr, exerciseName,
                    setOrder.toString(), weightStr, reps.toString(),
                    "", "", "", ""
                ))
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Group sessions into logical workouts.
     * Sessions with the same routineSessionId form one workout.
     * Standalone sessions (null routineSessionId) are individual workouts.
     */
    internal fun groupSessionsIntoWorkouts(
        sessions: List<WorkoutSession>
    ): Map<String, List<WorkoutSession>> {
        val result = mutableMapOf<String, MutableList<WorkoutSession>>()

        for (session in sessions) {
            val key = if (session.routineSessionId != null) {
                session.routineName ?: "Workout"
            } else {
                session.exerciseName ?: "Workout"
            }
            result.getOrPut(key) { mutableListOf() }.add(session)
        }

        return result
    }

    /** Format epoch millis to "YYYY-MM-DD HH:MM:SS" */
    internal fun formatTimestamp(epochMillis: Long): String {
        val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMillis)
        val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        return "${local.date} ${local.hour.toString().padStart(2, '0')}:" +
               "${local.minute.toString().padStart(2, '0')}:" +
               "${local.second.toString().padStart(2, '0')}"
    }

    /** Format seconds to "1h 23m" style */
    internal fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    /** Format weight to string, removing unnecessary decimals */
    private fun formatWeight(weight: Float): String {
        return if (weight == weight.toLong().toFloat()) {
            weight.toLong().toString()
        } else {
            "%.1f".format(weight)
        }
    }

    /** Build a properly escaped CSV row */
    private fun csvRow(vararg fields: String): String {
        return fields.joinToString(",") { field ->
            if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
                "\"${field.replace("\"", "\"\"")}\""
            } else {
                field
            }
        }
    }
}
```

- [ ] **Step 2: Write CsvExporter tests**

```kotlin
package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvExporterTest {

    private val exporter = CsvExporter()

    @Test
    fun generateStrongCsv_emptyList_returnsHeaderOnly() {
        val csv = exporter.generateStrongCsv(emptyList())
        assertEquals(
            "Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes",
            csv
        )
    }

    @Test
    fun generateStrongCsv_singleSession_correctColumns() {
        val session = WorkoutSession(
            id = "test-1",
            timestamp = 1711360200000, // 2024-03-25 10:30:00 UTC
            mode = "OldSchool",
            reps = 10,
            weightPerCableKg = 40f, // 40kg per cable = 80kg total
            duration = 120,
            totalReps = 10,
            exerciseName = "Bench Press"
        )
        val csv = exporter.generateStrongCsv(listOf(session), WeightUnit.KG)
        val lines = csv.lines()
        assertEquals(2, lines.size, "Should have header + 1 data row")
        val dataLine = lines[1]
        assertTrue(dataLine.contains("Bench Press"), "Should contain exercise name")
        assertTrue(dataLine.contains("80"), "Should contain total weight (40*2=80)")
        assertTrue(dataLine.contains("10"), "Should contain reps")
    }

    @Test
    fun generateStrongCsv_weightMultiplier_appliesCorrectly() {
        val session = WorkoutSession(
            id = "test-2",
            timestamp = 1711360200000,
            weightPerCableKg = 50f, // 50 per cable
            totalReps = 8,
            exerciseName = "Squat"
        )
        val csvKg = exporter.generateStrongCsv(listOf(session), WeightUnit.KG)
        assertTrue(csvKg.contains("100"), "50kg per cable * 2 = 100kg total")

        val csvLb = exporter.generateStrongCsv(listOf(session), WeightUnit.LB)
        // 50 * 2 * 2.20462 = 220.462, formatted as "220.5"
        assertTrue(csvLb.contains("220"), "Should convert to lbs")
    }

    @Test
    fun formatDuration_hoursAndMinutes() {
        assertEquals("1h 23m", exporter.formatDuration(4980))
    }

    @Test
    fun formatDuration_minutesOnly() {
        assertEquals("5m", exporter.formatDuration(300))
    }

    @Test
    fun formatDuration_zeroSeconds() {
        assertEquals("0m", exporter.formatDuration(0))
    }

    @Test
    fun groupSessionsIntoWorkouts_routineSessions_grouped() {
        val sessions = listOf(
            WorkoutSession(id = "1", routineSessionId = "r1", routineName = "Push Day", exerciseName = "Bench"),
            WorkoutSession(id = "2", routineSessionId = "r1", routineName = "Push Day", exerciseName = "OHP"),
            WorkoutSession(id = "3", exerciseName = "Squat") // standalone
        )
        val groups = exporter.groupSessionsIntoWorkouts(sessions)
        assertEquals(2, groups.size, "Should have 2 groups: routine + standalone")
        assertEquals(2, groups["Push Day"]?.size, "Push Day should have 2 sessions")
        assertEquals(1, groups["Squat"]?.size, "Squat should have 1 session")
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.devil.phoenixproject.data.integration.CsvExporterTest" -q`
Expected: PASS (6 tests)

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/CsvExporter.kt \
       shared/src/commonTest/kotlin/com/devil/phoenixproject/data/integration/CsvExporterTest.kt
git commit -m "feat(integration): add CsvExporter for Strong-format CSV generation"
```

---

## Phase 3: CSV Import

### Task 5: CsvImporter — Strong + Hevy Formats

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/CsvImporter.kt`
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/integration/CsvImporterTest.kt`

- [ ] **Step 1: Write CsvImporter**

```kotlin
package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.util.CsvParser

/**
 * Detected CSV format based on column headers.
 */
enum class CsvFormat { STRONG, HEVY, UNKNOWN }

/**
 * Result of a CSV import parse operation.
 */
data class CsvImportPreview(
    val format: CsvFormat,
    val activities: List<ExternalActivity>,
    val workoutCount: Int,
    val dateRange: Pair<Long, Long>?, // earliest to latest startedAt
    val totalDurationSeconds: Long,
    val errors: List<String> = emptyList()
)

/**
 * Parses Strong and Hevy CSV formats into ExternalActivity lists.
 *
 * Strong CSV columns: Date, Workout Name, Duration, Exercise Name, Set Order,
 *                     Weight, Reps, Distance, Seconds, Notes, Workout Notes
 *
 * Hevy CSV columns: title, start_time, end_time, description, exercise_title,
 *                   superset_id, exercise_notes, set_index, set_type, weight_lbs,
 *                   reps, distance_miles, duration_seconds, rpe
 */
class CsvImporter {

    companion object {
        private const val LBS_TO_KG = 0.453592
        private const val MILES_TO_METERS = 1609.344

        private val STRONG_HEADERS = setOf("workout name", "exercise name", "set order")
        private val HEVY_HEADERS = setOf("title", "exercise_title", "set_index")
    }

    /**
     * Detect the CSV format from the header row.
     */
    fun detectFormat(content: String): CsvFormat {
        val firstLine = content.lineSequence().firstOrNull()?.lowercase() ?: return CsvFormat.UNKNOWN
        val headers = CsvParser.parseCsvRow(firstLine).map { it.trim().lowercase() }.toSet()

        return when {
            headers.containsAll(STRONG_HEADERS) -> CsvFormat.STRONG
            headers.containsAll(HEVY_HEADERS) -> CsvFormat.HEVY
            else -> CsvFormat.UNKNOWN
        }
    }

    /**
     * Parse a CSV string and return a preview with activities.
     * Auto-detects format. For Strong format, [weightUnit] indicates
     * what unit the weights are in (the Strong app lets users export in either).
     */
    fun parse(
        content: String,
        weightUnit: WeightUnit = WeightUnit.KG,
        profileId: String = "default",
        isPaidUser: Boolean = false
    ): CsvImportPreview {
        val format = detectFormat(content)
        return when (format) {
            CsvFormat.STRONG -> parseStrongCsv(content, weightUnit, profileId, isPaidUser)
            CsvFormat.HEVY -> parseHevyCsv(content, profileId, isPaidUser)
            CsvFormat.UNKNOWN -> CsvImportPreview(
                format = CsvFormat.UNKNOWN,
                activities = emptyList(),
                workoutCount = 0,
                dateRange = null,
                totalDurationSeconds = 0,
                errors = listOf("Unrecognized CSV format. Expected Strong or Hevy CSV headers.")
            )
        }
    }

    /**
     * Parse Strong CSV format. Groups rows by (Workout Name + Date) into activities.
     */
    internal fun parseStrongCsv(
        content: String,
        weightUnit: WeightUnit,
        profileId: String,
        isPaidUser: Boolean
    ): CsvImportPreview {
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyPreview(CsvFormat.STRONG, "CSV has no data rows")

        val headers = CsvParser.parseCsvRow(lines[0]).map { it.trim().lowercase() }
        val colIdx = headers.withIndex().associate { (i, h) -> h to i }

        val errors = mutableListOf<String>()
        // Group by workout name + date
        val workoutMap = mutableMapOf<String, MutableList<Map<String, String>>>()

        for (i in 1 until lines.size) {
            try {
                val fields = CsvParser.parseCsvRow(lines[i])
                val row = headers.zip(fields).toMap()
                val date = row["date"] ?: continue
                val workoutName = row["workout name"] ?: "Workout"
                val key = "$workoutName|$date"
                workoutMap.getOrPut(key) { mutableListOf() }.add(row)
            } catch (e: Exception) {
                errors.add("Row ${i + 1}: ${e.message}")
            }
        }

        val now = currentTimeMillis()
        val activities = workoutMap.map { (key, rows) ->
            val firstRow = rows.first()
            val workoutName = firstRow["workout name"] ?: "Workout"
            val dateStr = firstRow["date"] ?: ""
            val durationStr = firstRow["duration"] ?: "0m"
            val startedAt = parseStrongDate(dateStr)
            val durationSec = parseStrongDuration(durationStr)
            val epochSeconds = startedAt / 1000

            ExternalActivity(
                id = generateUUID(),
                externalId = "strong-${workoutName.lowercase().replace(" ", "_")}-$epochSeconds",
                provider = IntegrationProvider.STRONG,
                name = workoutName,
                activityType = "strength",
                startedAt = startedAt,
                durationSeconds = durationSec,
                rawData = null, // Could serialize exercise details here
                syncedAt = now,
                profileId = profileId,
                needsSync = isPaidUser
            )
        }

        val timestamps = activities.map { it.startedAt }.filter { it > 0 }
        return CsvImportPreview(
            format = CsvFormat.STRONG,
            activities = activities,
            workoutCount = activities.size,
            dateRange = if (timestamps.isNotEmpty()) timestamps.min() to timestamps.max() else null,
            totalDurationSeconds = activities.sumOf { it.durationSeconds.toLong() },
            errors = errors
        )
    }

    /**
     * Parse Hevy CSV format. Groups rows by (title + start_time) into activities.
     */
    internal fun parseHevyCsv(
        content: String,
        profileId: String,
        isPaidUser: Boolean
    ): CsvImportPreview {
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyPreview(CsvFormat.HEVY, "CSV has no data rows")

        val headers = CsvParser.parseCsvRow(lines[0]).map { it.trim().lowercase() }
        val errors = mutableListOf<String>()
        val workoutMap = mutableMapOf<String, MutableList<Map<String, String>>>()

        for (i in 1 until lines.size) {
            try {
                val fields = CsvParser.parseCsvRow(lines[i])
                val row = headers.zip(fields).toMap()
                val title = row["title"] ?: "Workout"
                val startTime = row["start_time"] ?: continue
                val key = "$title|$startTime"
                workoutMap.getOrPut(key) { mutableListOf() }.add(row)
            } catch (e: Exception) {
                errors.add("Row ${i + 1}: ${e.message}")
            }
        }

        val now = currentTimeMillis()
        val activities = workoutMap.map { (_, rows) ->
            val firstRow = rows.first()
            val title = firstRow["title"] ?: "Workout"
            val startTimeStr = firstRow["start_time"] ?: ""
            val endTimeStr = firstRow["end_time"] ?: ""
            val startedAt = parseHevyTimestamp(startTimeStr)
            val endedAt = parseHevyTimestamp(endTimeStr)
            val durationSec = if (endedAt > startedAt) ((endedAt - startedAt) / 1000).toInt() else 0
            val epochSeconds = startedAt / 1000

            ExternalActivity(
                id = generateUUID(),
                externalId = "hevy-${title.lowercase().replace(" ", "_")}-$epochSeconds",
                provider = IntegrationProvider.HEVY,
                name = title,
                activityType = "strength",
                startedAt = startedAt,
                durationSeconds = durationSec,
                rawData = null,
                syncedAt = now,
                profileId = profileId,
                needsSync = isPaidUser
            )
        }

        val timestamps = activities.map { it.startedAt }.filter { it > 0 }
        return CsvImportPreview(
            format = CsvFormat.HEVY,
            activities = activities,
            workoutCount = activities.size,
            dateRange = if (timestamps.isNotEmpty()) timestamps.min() to timestamps.max() else null,
            totalDurationSeconds = activities.sumOf { it.durationSeconds.toLong() },
            errors = errors
        )
    }

    // --- Date/time parsers ---

    /** Parse "YYYY-MM-DD HH:MM:SS" to epoch millis */
    internal fun parseStrongDate(dateStr: String): Long {
        return try {
            val trimmed = dateStr.trim()
            val localDateTime = kotlinx.datetime.LocalDateTime.parse(
                trimmed.replace(" ", "T")
            )
            localDateTime.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault())
                .toEpochMilliseconds()
        } catch (_: Exception) {
            0L
        }
    }

    /** Parse Strong duration "1h 23m" or "45m" to seconds */
    internal fun parseStrongDuration(durationStr: String): Int {
        val trimmed = durationStr.trim()
        var totalSeconds = 0
        val hourMatch = Regex("(\\d+)h").find(trimmed)
        val minMatch = Regex("(\\d+)m").find(trimmed)
        if (hourMatch != null) totalSeconds += hourMatch.groupValues[1].toInt() * 3600
        if (minMatch != null) totalSeconds += minMatch.groupValues[1].toInt() * 60
        return totalSeconds
    }

    /** Parse Hevy ISO-8601 timestamp to epoch millis */
    internal fun parseHevyTimestamp(timestamp: String): Long {
        return try {
            kotlinx.datetime.Instant.parse(timestamp.trim()).toEpochMilliseconds()
        } catch (_: Exception) {
            // Try without timezone (local time)
            try {
                val local = kotlinx.datetime.LocalDateTime.parse(timestamp.trim().replace(" ", "T"))
                local.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds()
            } catch (_: Exception) {
                0L
            }
        }
    }

    private fun emptyPreview(format: CsvFormat, error: String) = CsvImportPreview(
        format = format,
        activities = emptyList(),
        workoutCount = 0,
        dateRange = null,
        totalDurationSeconds = 0,
        errors = listOf(error)
    )
}
```

- [ ] **Step 2: Write CsvImporter tests**

```kotlin
package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvImporterTest {

    private val importer = CsvImporter()

    // --- Format detection ---

    @Test
    fun detectFormat_strongHeaders_returnsStrong() {
        val csv = "Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes\n"
        assertEquals(CsvFormat.STRONG, importer.detectFormat(csv))
    }

    @Test
    fun detectFormat_hevyHeaders_returnsHevy() {
        val csv = "title,start_time,end_time,description,exercise_title,superset_id,exercise_notes,set_index,set_type,weight_lbs,reps,distance_miles,duration_seconds,rpe\n"
        assertEquals(CsvFormat.HEVY, importer.detectFormat(csv))
    }

    @Test
    fun detectFormat_unknownHeaders_returnsUnknown() {
        val csv = "foo,bar,baz\n1,2,3"
        assertEquals(CsvFormat.UNKNOWN, importer.detectFormat(csv))
    }

    // --- Strong CSV parsing ---

    @Test
    fun parseStrongCsv_singleWorkout_parsedCorrectly() {
        val csv = """Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes
2026-03-25 14:30:00,Chest Day,1h 15m,Bench Press,1,80,10,,,,
2026-03-25 14:30:00,Chest Day,1h 15m,Bench Press,2,80,8,,,,"""

        val result = importer.parse(csv, WeightUnit.KG)
        assertEquals(CsvFormat.STRONG, result.format)
        assertEquals(1, result.workoutCount, "Two rows same workout = 1 activity")
        assertEquals("Chest Day", result.activities.first().name)
        assertEquals(IntegrationProvider.STRONG, result.activities.first().provider)
    }

    @Test
    fun parseStrongCsv_multipleWorkouts_groupedCorrectly() {
        val csv = """Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes
2026-03-25 14:30:00,Chest Day,1h 15m,Bench Press,1,80,10,,,,
2026-03-26 10:00:00,Leg Day,45m,Squat,1,100,5,,,,"""

        val result = importer.parse(csv, WeightUnit.KG)
        assertEquals(2, result.workoutCount)
    }

    @Test
    fun parseStrongCsv_externalId_isDeterministic() {
        val csv = """Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes
2026-03-25 14:30:00,Chest Day,1h 15m,Bench Press,1,80,10,,,,"""

        val result1 = importer.parse(csv, WeightUnit.KG)
        val result2 = importer.parse(csv, WeightUnit.KG)
        assertEquals(
            result1.activities.first().externalId,
            result2.activities.first().externalId,
            "Same input should produce same externalId for dedup"
        )
    }

    // --- Hevy CSV parsing ---

    @Test
    fun parseHevyCsv_singleWorkout_parsedCorrectly() {
        val csv = """title,start_time,end_time,description,exercise_title,superset_id,exercise_notes,set_index,set_type,weight_lbs,reps,distance_miles,duration_seconds,rpe
Chest Day,2026-03-25T14:30:00Z,2026-03-25T15:45:00Z,,Bench Press,,,,normal,176,10,,,"""

        val result = importer.parse(csv)
        assertEquals(CsvFormat.HEVY, result.format)
        assertEquals(1, result.workoutCount)
        assertEquals("Chest Day", result.activities.first().name)
        assertEquals(IntegrationProvider.HEVY, result.activities.first().provider)
        assertEquals(4500, result.activities.first().durationSeconds, "75 min = 4500 sec")
    }

    // --- Duration parsing ---

    @Test
    fun parseStrongDuration_hoursAndMinutes() {
        assertEquals(4980, importer.parseStrongDuration("1h 23m"))
    }

    @Test
    fun parseStrongDuration_minutesOnly() {
        assertEquals(2700, importer.parseStrongDuration("45m"))
    }

    @Test
    fun parseStrongDuration_zeroMinutes() {
        assertEquals(0, importer.parseStrongDuration("0m"))
    }

    // --- Date parsing ---

    @Test
    fun parseStrongDate_validDate_returnsNonZero() {
        val result = importer.parseStrongDate("2026-03-25 14:30:00")
        assertTrue(result > 0, "Should parse to a valid epoch millis")
    }

    @Test
    fun parseStrongDate_invalidDate_returnsZero() {
        assertEquals(0L, importer.parseStrongDate("not a date"))
    }

    // --- Empty/error cases ---

    @Test
    fun parse_emptyContent_returnsEmptyPreview() {
        val result = importer.parse("")
        assertEquals(CsvFormat.UNKNOWN, result.format)
        assertEquals(0, result.workoutCount)
    }

    @Test
    fun parse_headerOnly_returnsEmptyActivities() {
        val csv = "Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes"
        val result = importer.parse(csv)
        assertEquals(0, result.workoutCount)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.devil.phoenixproject.data.integration.CsvImporterTest" -q`
Expected: PASS (12 tests)

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/CsvImporter.kt \
       shared/src/commonTest/kotlin/com/devil/phoenixproject/data/integration/CsvImporterTest.kt
git commit -m "feat(integration): add CsvImporter with Strong and Hevy format parsing"
```

---

## Phase 4: Health Integration (expect/actual)

### Task 6: Health Integration Interface + Android Implementation

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.kt`
- Create: `shared/src/androidMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.android.kt`
- Create: `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.ios.kt`
- Modify: `shared/build.gradle.kts` (add Health Connect dependency)

- [ ] **Step 1: Write expect class in commonMain**

```kotlin
package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * Platform-specific health integration.
 * Android: Google Health Connect
 * iOS: Apple HealthKit
 *
 * Write-only: pushes Phoenix workout data to the platform health store
 * after each completed workout. Does not read external data.
 */
expect class HealthIntegration {
    /**
     * Whether the health platform is available on this device.
     * Android: Health Connect app installed. iOS: HealthKit available.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Request write permissions from the user.
     * Returns true if permissions were granted.
     */
    suspend fun requestPermissions(): Boolean

    /**
     * Check if write permissions are currently granted.
     */
    suspend fun hasPermissions(): Boolean

    /**
     * Write a completed workout session to the health store.
     * Weight is converted from per-cable to total (×2) for the health record.
     */
    suspend fun writeWorkout(session: WorkoutSession): Result<Unit>
}
```

- [ ] **Step 2: Add Health Connect dependency to build.gradle.kts**

In `shared/build.gradle.kts`, inside the `androidMain` dependencies block, add:

```kotlin
// Health Connect (Google Health)
implementation("androidx.health.connect:connect-client:1.1.0-alpha11")
```

- [ ] **Step 3: Write Android actual implementation**

```kotlin
package com.devil.phoenixproject.data.integration

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.units.Energy
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlinx.datetime.Instant
import java.time.ZoneOffset

actual class HealthIntegration(private val context: Context) {

    private val permissions = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
    )

    actual suspend fun isAvailable(): Boolean {
        return try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            Logger.w("HealthIntegration") { "Health Connect not available: ${e.message}" }
            false
        }
    }

    actual suspend fun requestPermissions(): Boolean {
        // Note: In production, this requires launching an Activity intent via
        // PermissionController.createRequestPermissionResultContract().
        // The actual permission request is handled in the Compose UI layer.
        // This method checks if permissions are already granted.
        return hasPermissions()
    }

    actual suspend fun hasPermissions(): Boolean {
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(permissions)
        } catch (e: Exception) {
            Logger.w("HealthIntegration") { "Failed to check permissions: ${e.message}" }
            false
        }
    }

    actual suspend fun writeWorkout(session: WorkoutSession): Result<Unit> {
        return try {
            val client = HealthConnectClient.getOrCreate(context)

            val startInstant = Instant.fromEpochMilliseconds(session.timestamp)
            val endInstant = Instant.fromEpochMilliseconds(
                session.timestamp + (session.duration * 1000)
            )
            val startJava = java.time.Instant.ofEpochMilli(startInstant.toEpochMilliseconds())
            val endJava = java.time.Instant.ofEpochMilli(endInstant.toEpochMilliseconds())

            val exerciseRecord = ExerciseSessionRecord(
                startTime = startJava,
                startZoneOffset = ZoneOffset.systemDefault().rules.getOffset(startJava),
                endTime = endJava,
                endZoneOffset = ZoneOffset.systemDefault().rules.getOffset(endJava),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
                title = session.exerciseName ?: "Vitruvian Workout"
            )

            val records = mutableListOf<androidx.health.connect.client.records.Record>(exerciseRecord)

            // Add calories if available
            session.estimatedCalories?.let { cal ->
                if (cal > 0f) {
                    records.add(
                        TotalCaloriesBurnedRecord(
                            startTime = startJava,
                            startZoneOffset = ZoneOffset.systemDefault().rules.getOffset(startJava),
                            endTime = endJava,
                            endZoneOffset = ZoneOffset.systemDefault().rules.getOffset(endJava),
                            energy = Energy.kilocalories(cal.toDouble())
                        )
                    )
                }
            }

            client.insertRecords(records)
            Logger.i("HealthIntegration") { "Wrote workout to Health Connect: ${session.exerciseName}" }
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("HealthIntegration") { "Failed to write to Health Connect: ${e.message}" }
            Result.failure(e)
        }
    }
}
```

- [ ] **Step 4: Write iOS stub actual implementation**

```kotlin
package com.devil.phoenixproject.data.integration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * iOS HealthKit integration.
 * TODO: Implement using platform.HealthKit Kotlin/Native interop.
 * For now, this is a stub that logs and succeeds silently.
 */
actual class HealthIntegration {

    actual suspend fun isAvailable(): Boolean {
        // HKHealthStore.isHealthDataAvailable()
        Logger.d("HealthIntegration") { "iOS HealthKit availability check - stub" }
        return false // Stub until HealthKit interop is implemented
    }

    actual suspend fun requestPermissions(): Boolean {
        Logger.d("HealthIntegration") { "iOS HealthKit permission request - stub" }
        return false
    }

    actual suspend fun hasPermissions(): Boolean {
        Logger.d("HealthIntegration") { "iOS HealthKit permission check - stub" }
        return false
    }

    actual suspend fun writeWorkout(session: WorkoutSession): Result<Unit> {
        Logger.d("HealthIntegration") { "iOS HealthKit write - stub for ${session.exerciseName}" }
        return Result.failure(UnsupportedOperationException("HealthKit not yet implemented"))
    }
}
```

- [ ] **Step 5: Build to verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.kt \
       shared/src/androidMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.android.kt \
       shared/src/iosMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.ios.kt \
       shared/build.gradle.kts
git commit -m "feat(integration): add HealthIntegration expect/actual for Health Connect and HealthKit"
```

---

## Phase 5: IntegrationManager + API Client Extension

### Task 7: PortalApiClient Extension + IntegrationManager

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/IntegrationManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt`

- [ ] **Step 1: Add callIntegrationSync() to PortalApiClient**

Add before the `// === Private Helpers ===` section in `PortalApiClient.kt`:

```kotlin
// === Integration Sync Endpoints ===

/**
 * Call the mobile-integration-sync Edge Function.
 * Used for Hevy/Liftosaur API key storage and sync triggering.
 */
open suspend fun callIntegrationSync(request: IntegrationSyncRequest): Result<IntegrationSyncResponse> {
    return authenticatedRequest { token ->
        httpClient.post("${supabaseConfig.url}/functions/v1/mobile-integration-sync") {
            bearerAuth(token)
            header("apikey", supabaseConfig.anonKey)
            setBody(request)
        }
    }
}
```

Also add these DTOs at the end of `PortalApiClient.kt` (before the closing `}`), or in a separate file:

```kotlin
@kotlinx.serialization.Serializable
data class IntegrationSyncRequest(
    val provider: String,  // "hevy" or "liftosaur"
    val action: String,    // "connect", "sync", or "disconnect"
    val apiKey: String? = null  // Only for "connect" action
)

@kotlinx.serialization.Serializable
data class IntegrationSyncResponse(
    val status: String,  // "connected", "synced", "disconnected", "error"
    val activities: List<IntegrationActivityDto> = emptyList(),
    val error: String? = null
)

@kotlinx.serialization.Serializable
data class IntegrationActivityDto(
    val externalId: String,
    val provider: String,
    val name: String,
    val activityType: String = "strength",
    val startedAt: String,  // ISO-8601
    val durationSeconds: Int = 0,
    val distanceMeters: Double? = null,
    val calories: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val elevationGainMeters: Double? = null,
    val rawData: String? = null
)
```

- [ ] **Step 2: Write IntegrationManager**

```kotlin
package com.devil.phoenixproject.data.integration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.sync.IntegrationActivityDto
import com.devil.phoenixproject.data.sync.IntegrationSyncRequest
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID

/**
 * Orchestrates third-party integration operations.
 * API sync delegates to Edge Functions (API keys stored server-side).
 * CSV export/import and health writes are handled locally (not through this class).
 */
class IntegrationManager(
    private val apiClient: PortalApiClient,
    private val repository: ExternalActivityRepository
) {
    /**
     * Connect a provider by storing the API key server-side and performing initial sync.
     * Returns the list of activities fetched from the provider.
     */
    suspend fun connectProvider(
        provider: IntegrationProvider,
        apiKey: String,
        profileId: String,
        isPaidUser: Boolean
    ): Result<List<ExternalActivity>> {
        return try {
            val response = apiClient.callIntegrationSync(
                IntegrationSyncRequest(
                    provider = provider.key,
                    action = "connect",
                    apiKey = apiKey
                )
            ).getOrThrow()

            if (response.status == "error") {
                repository.updateIntegrationStatus(
                    provider, ConnectionStatus.ERROR, profileId,
                    errorMessage = response.error
                )
                return Result.failure(Exception(response.error ?: "Connection failed"))
            }

            val activities = response.activities.map { it.toDomain(provider, profileId, isPaidUser) }
            repository.upsertActivities(activities)
            repository.updateIntegrationStatus(
                provider, ConnectionStatus.CONNECTED, profileId,
                lastSyncAt = currentTimeMillis()
            )

            Logger.i("IntegrationManager") { "Connected ${provider.key}: ${activities.size} activities synced" }
            Result.success(activities)
        } catch (e: Exception) {
            Logger.e("IntegrationManager") { "Failed to connect ${provider.key}: ${e.message}" }
            repository.updateIntegrationStatus(
                provider, ConnectionStatus.ERROR, profileId,
                errorMessage = e.message
            )
            Result.failure(e)
        }
    }

    /**
     * Trigger a sync for a connected provider. Uses stored server-side API key.
     */
    suspend fun syncProvider(
        provider: IntegrationProvider,
        profileId: String,
        isPaidUser: Boolean
    ): Result<List<ExternalActivity>> {
        return try {
            val response = apiClient.callIntegrationSync(
                IntegrationSyncRequest(provider = provider.key, action = "sync")
            ).getOrThrow()

            if (response.status == "error") {
                val errorMsg = response.error ?: "Sync failed"
                repository.updateIntegrationStatus(
                    provider, ConnectionStatus.ERROR, profileId,
                    errorMessage = errorMsg
                )
                return Result.failure(Exception(errorMsg))
            }

            val activities = response.activities.map { it.toDomain(provider, profileId, isPaidUser) }
            repository.upsertActivities(activities)
            repository.updateIntegrationStatus(
                provider, ConnectionStatus.CONNECTED, profileId,
                lastSyncAt = currentTimeMillis()
            )

            Logger.i("IntegrationManager") { "Synced ${provider.key}: ${activities.size} activities" }
            Result.success(activities)
        } catch (e: Exception) {
            Logger.e("IntegrationManager") { "Failed to sync ${provider.key}: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Disconnect a provider. Deletes server-side API key and local activities.
     */
    suspend fun disconnectProvider(
        provider: IntegrationProvider,
        profileId: String
    ): Result<Unit> {
        return try {
            apiClient.callIntegrationSync(
                IntegrationSyncRequest(provider = provider.key, action = "disconnect")
            ).getOrThrow()

            repository.deleteActivities(provider, profileId)
            repository.updateIntegrationStatus(
                provider, ConnectionStatus.DISCONNECTED, profileId
            )

            Logger.i("IntegrationManager") { "Disconnected ${provider.key}" }
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("IntegrationManager") { "Failed to disconnect ${provider.key}: ${e.message}" }
            Result.failure(e)
        }
    }

    /** Map Edge Function DTO to domain model */
    private fun IntegrationActivityDto.toDomain(
        provider: IntegrationProvider,
        profileId: String,
        isPaidUser: Boolean
    ): ExternalActivity {
        val startedAtMillis = try {
            kotlinx.datetime.Instant.parse(startedAt).toEpochMilliseconds()
        } catch (_: Exception) { 0L }

        return ExternalActivity(
            id = generateUUID(),
            externalId = externalId,
            provider = provider,
            name = name,
            activityType = activityType,
            startedAt = startedAtMillis,
            durationSeconds = durationSeconds,
            distanceMeters = distanceMeters,
            calories = calories,
            avgHeartRate = avgHeartRate,
            maxHeartRate = maxHeartRate,
            elevationGainMeters = elevationGainMeters,
            rawData = rawData,
            syncedAt = currentTimeMillis(),
            profileId = profileId,
            needsSync = isPaidUser
        )
    }
}
```

- [ ] **Step 3: Register IntegrationManager in SyncModule**

Add to `SyncModule.kt`:

```kotlin
import com.devil.phoenixproject.data.integration.IntegrationManager

// Inside the module block, after existing registrations:
single { IntegrationManager(get(), get()) }
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt \
       shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/IntegrationManager.kt \
       shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt
git commit -m "feat(integration): add IntegrationManager and PortalApiClient integration sync endpoint"
```

---

## Phase 6: Sync Extension

### Task 8: Extend SyncManager + PortalSyncDtos for External Activities

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt`

- [ ] **Step 1: Add ExternalActivityDto to PortalSyncDtos.kt**

Append before the `// ─── Pull Response DTOs` section:

```kotlin
// ─── External Activities (Integration sync) ──────────────────────────

@Serializable
data class ExternalActivitySyncDto(
    val id: String,
    val externalId: String,
    val provider: String,
    val name: String,
    val activityType: String = "strength",
    val startedAt: String,  // ISO-8601
    val durationSeconds: Int = 0,
    val distanceMeters: Double? = null,
    val calories: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val elevationGainMeters: Double? = null,
    val rawData: String? = null,
    val syncedAt: String  // ISO-8601
)
```

- [ ] **Step 2: Extend PortalSyncPayload**

Add to `PortalSyncPayload`:

```kotlin
val externalActivities: List<ExternalActivitySyncDto> = emptyList(),
```

- [ ] **Step 3: Extend PortalSyncPullResponse**

Add to `PortalSyncPullResponse`:

```kotlin
val externalActivities: List<ExternalActivitySyncDto> = emptyList(),
```

- [ ] **Step 4: Extend SyncManager.pushLocalChanges()**

In `SyncManager.kt`, add the external activity gathering and pushing. After the gamification stats gathering (around line 224), add:

```kotlin
// 8b. External activities (integration data — paid users only)
val externalActivityDtos = if (tokenStorage.isPremium()) {
    val externalActivityRepo: ExternalActivityRepository = // inject via constructor
    val unsynced = externalActivityRepo.getUnsyncedActivities(activeProfileId)
    unsynced.map { a ->
        ExternalActivitySyncDto(
            id = a.id,
            externalId = a.externalId,
            provider = a.provider.key,
            name = a.name,
            activityType = a.activityType,
            startedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(a.startedAt).toString(),
            durationSeconds = a.durationSeconds,
            distanceMeters = a.distanceMeters,
            calories = a.calories,
            avgHeartRate = a.avgHeartRate,
            maxHeartRate = a.maxHeartRate,
            elevationGainMeters = a.elevationGainMeters,
            rawData = a.rawData,
            syncedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(a.syncedAt).toString()
        )
    }
} else emptyList()
```

Add `externalActivities = externalActivityDtos` to the `PortalSyncPayload` constructor calls (both single-push and batched paths — include only in the final batch for batched push).

After a successful push, mark activities synced:

```kotlin
if (externalActivityDtos.isNotEmpty()) {
    externalActivityRepo.markSynced(externalActivityDtos.map { it.id })
}
```

- [ ] **Step 5: Extend SyncManager.pullRemoteChanges()**

After the RPG attributes merge (around line 462), add:

```kotlin
// 7. External activities — merge from portal (upsert, no conflict — portal-synced data)
if (pullResponse.externalActivities.isNotEmpty()) {
    val externalActivityRepo: ExternalActivityRepository = // inject via constructor
    val activities = pullResponse.externalActivities.map { dto ->
        ExternalActivity(
            id = dto.id,
            externalId = dto.externalId,
            provider = IntegrationProvider.fromKey(dto.provider) ?: IntegrationProvider.STRONG,
            name = dto.name,
            activityType = dto.activityType,
            startedAt = try {
                kotlinx.datetime.Instant.parse(dto.startedAt).toEpochMilliseconds()
            } catch (_: Exception) { 0L },
            durationSeconds = dto.durationSeconds,
            distanceMeters = dto.distanceMeters,
            calories = dto.calories,
            avgHeartRate = dto.avgHeartRate,
            maxHeartRate = dto.maxHeartRate,
            elevationGainMeters = dto.elevationGainMeters,
            rawData = dto.rawData,
            syncedAt = try {
                kotlinx.datetime.Instant.parse(dto.syncedAt).toEpochMilliseconds()
            } catch (_: Exception) { currentTimeMillis() },
            profileId = mergeProfileId,
            needsSync = false  // Came from portal, already synced
        )
    }
    externalActivityRepo.upsertActivities(activities)
    Logger.d("SyncManager") { "Merged ${activities.size} portal external activities" }
}
```

- [ ] **Step 6: Update SyncManager constructor to accept ExternalActivityRepository**

Add `private val externalActivityRepository: ExternalActivityRepository` as a constructor parameter. Update `SyncModule.kt` to pass it:

```kotlin
single {
    SyncManager(
        apiClient = get(),
        tokenStorage = get(),
        syncRepository = get(),
        gamificationRepository = get(),
        repMetricRepository = get(),
        userProfileRepository = get(),
        externalActivityRepository = get()
    )
}
```

- [ ] **Step 7: Build to verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt \
       shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt \
       shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt
git commit -m "feat(integration): extend sync push/pull to include external activities for paid users"
```

---

## Phase 7: UI

### Task 9: IntegrationsViewModel

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/IntegrationsViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt`

- [ ] **Step 1: Write IntegrationsViewModel**

This ViewModel manages state for the Integrations screen: provider statuses, CSV export/import, API sync triggers, and health integration toggle.

```kotlin
package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.*
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class IntegrationsUiState(
    val integrationStatuses: Map<IntegrationProvider, IntegrationStatus> = emptyMap(),
    val externalActivities: List<ExternalActivity> = emptyList(),
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val isSyncing: Boolean = false,
    val importPreview: CsvImportPreview? = null,
    val csvContent: String? = null, // Generated CSV ready for sharing
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class IntegrationsViewModel(
    private val externalActivityRepo: ExternalActivityRepository,
    private val integrationManager: IntegrationManager,
    private val workoutRepository: WorkoutRepository,
    private val csvExporter: CsvExporter,
    private val csvImporter: CsvImporter,
    private val healthIntegration: HealthIntegration,
    private val profileId: String = "default",
    private val isPaidUser: Boolean = false
) : ViewModel() {

    private val _uiState = MutableStateFlow(IntegrationsUiState())
    val uiState: StateFlow<IntegrationsUiState> = _uiState.asStateFlow()

    init {
        // Observe integration statuses
        viewModelScope.launch {
            externalActivityRepo.getAllIntegrationStatuses(profileId).collect { statuses ->
                _uiState.update { it.copy(
                    integrationStatuses = statuses.associateBy { s -> s.provider }
                )}
            }
        }

        // Observe external activities
        viewModelScope.launch {
            externalActivityRepo.getAll(profileId).collect { activities ->
                _uiState.update { it.copy(externalActivities = activities) }
            }
        }
    }

    fun exportCsv(weightUnit: WeightUnit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, errorMessage = null) }
            try {
                val sessions = workoutRepository.getAllSessions(profileId).first()
                val csv = csvExporter.generateStrongCsv(sessions, weightUnit)
                _uiState.update { it.copy(
                    isExporting = false,
                    csvContent = csv,
                    successMessage = "CSV generated with ${sessions.size} workouts"
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isExporting = false,
                    errorMessage = "Export failed: ${e.message}"
                )}
            }
        }
    }

    fun clearCsvContent() {
        _uiState.update { it.copy(csvContent = null) }
    }

    fun previewCsvImport(content: String, weightUnit: WeightUnit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null) }
            try {
                val preview = csvImporter.parse(content, weightUnit, profileId, isPaidUser)
                _uiState.update { it.copy(
                    isImporting = false,
                    importPreview = preview
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isImporting = false,
                    errorMessage = "Import failed: ${e.message}"
                )}
            }
        }
    }

    fun confirmImport() {
        val preview = _uiState.value.importPreview ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            try {
                externalActivityRepo.upsertActivities(preview.activities)
                _uiState.update { it.copy(
                    isImporting = false,
                    importPreview = null,
                    successMessage = "Imported ${preview.workoutCount} workouts"
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isImporting = false,
                    errorMessage = "Import failed: ${e.message}"
                )}
            }
        }
    }

    fun cancelImport() {
        _uiState.update { it.copy(importPreview = null) }
    }

    fun connectProvider(provider: IntegrationProvider, apiKey: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, errorMessage = null) }
            integrationManager.connectProvider(provider, apiKey, profileId, isPaidUser).fold(
                onSuccess = { activities ->
                    _uiState.update { it.copy(
                        isSyncing = false,
                        successMessage = "Connected! ${activities.size} activities synced"
                    )}
                },
                onFailure = { e ->
                    _uiState.update { it.copy(
                        isSyncing = false,
                        errorMessage = "Connection failed: ${e.message}"
                    )}
                }
            )
        }
    }

    fun syncProvider(provider: IntegrationProvider) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, errorMessage = null) }
            integrationManager.syncProvider(provider, profileId, isPaidUser).fold(
                onSuccess = { activities ->
                    _uiState.update { it.copy(
                        isSyncing = false,
                        successMessage = "Synced ${activities.size} activities"
                    )}
                },
                onFailure = { e ->
                    _uiState.update { it.copy(
                        isSyncing = false,
                        errorMessage = "Sync failed: ${e.message}"
                    )}
                }
            )
        }
    }

    fun disconnectProvider(provider: IntegrationProvider) {
        viewModelScope.launch {
            integrationManager.disconnectProvider(provider, profileId)
        }
    }

    fun toggleHealthIntegration(enable: Boolean) {
        viewModelScope.launch {
            val provider = if (getPlatform().name.lowercase().contains("ios")) {
                IntegrationProvider.APPLE_HEALTH
            } else {
                IntegrationProvider.GOOGLE_HEALTH
            }

            if (enable) {
                val available = healthIntegration.isAvailable()
                if (!available) {
                    _uiState.update { it.copy(
                        errorMessage = "Health app not available on this device"
                    )}
                    return@launch
                }
                val granted = healthIntegration.requestPermissions()
                if (granted) {
                    externalActivityRepo.updateIntegrationStatus(
                        provider, ConnectionStatus.CONNECTED, profileId
                    )
                } else {
                    _uiState.update { it.copy(
                        errorMessage = "Health permissions not granted"
                    )}
                }
            } else {
                externalActivityRepo.updateIntegrationStatus(
                    provider, ConnectionStatus.DISCONNECTED, profileId
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}

// Platform helper (already exists in project)
private fun getPlatform() = com.devil.phoenixproject.getPlatform()
```

- [ ] **Step 2: Register in PresentationModule**

Add to `PresentationModule.kt`:

```kotlin
import com.devil.phoenixproject.presentation.viewmodel.IntegrationsViewModel
import com.devil.phoenixproject.data.integration.CsvExporter
import com.devil.phoenixproject.data.integration.CsvImporter

// Inside the module block:
factory { CsvExporter() }
factory { CsvImporter() }
factory { IntegrationsViewModel(get(), get(), get(), get(), get(), get()) }
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/IntegrationsViewModel.kt \
       shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt
git commit -m "feat(integration): add IntegrationsViewModel with export, import, sync, and health state management"
```

---

### Task 10: IntegrationsScreen + SettingsTab Navigation

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/IntegrationsScreen.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExternalActivitiesScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt`

- [ ] **Step 1: Create IntegrationsScreen**

This is the main integrations management screen. Full Compose UI with health toggle, Hevy/Liftosaur API cards, and Strong CSV card. Due to the size of this composable, the implementation will follow the existing patterns in `SettingsTab.kt` (scrollable column with card sections).

Key sections:
- Health Apps section (toggle for Apple Health / Health Connect)
- Hevy card (connect/disconnect, API key input bottom sheet, sync button, CSV export)
- Liftosaur card (connect/disconnect, API key input, sync button)
- Strong / CSV card (export with weight unit toggle, import with file picker)
- External Activities navigation link

- [ ] **Step 2: Create ExternalActivitiesScreen**

Read-only list of external activities with provider filter chips and tap-to-expand detail.

- [ ] **Step 3: Add Integrations navigation to SettingsTab**

Add a new parameter to `SettingsTab`:
```kotlin
onNavigateToIntegrations: () -> Unit = {},
```

Add a settings row after the "Link Account" row:
```kotlin
SettingsRow(
    icon = Icons.Default.Sync,
    label = stringResource(Res.string.settings_integrations),
    subtitle = "Hevy, Liftosaur, Health Connect",
    onClick = onNavigateToIntegrations
)
```

- [ ] **Step 4: Wire navigation in App.kt / NavGraph**

Add the IntegrationsScreen and ExternalActivitiesScreen routes to the app navigation graph. Connect the SettingsTab callback.

- [ ] **Step 5: Build and verify on device**

Run: `./gradlew :androidApp:installDebug`
Expected: App installs, Settings → Integrations navigates to new screen

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/IntegrationsScreen.kt \
       shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExternalActivitiesScreen.kt \
       shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt
git commit -m "feat(integration): add IntegrationsScreen UI with health, API, and CSV sections"
```

---

## Phase 8: Edge Function + Auto-Push

### Task 11: mobile-integration-sync Edge Function

**Files:**
- Create: `phoenix-portal/supabase/functions/mobile-integration-sync/index.ts`

- [ ] **Step 1: Create the Edge Function**

This Edge Function handles connect/sync/disconnect for Hevy and Liftosaur. It reuses patterns from the existing `hevy-sync` and `liftosaur-sync` Edge Functions but skips subscription gating and returns activities directly.

Key behavior:
- `connect`: Store API key in `oauth_tokens`, call provider API, return activities
- `sync`: Use stored API key, fetch new activities, return them
- `disconnect`: Delete from `oauth_tokens`, update `user_integrations`
- For paid users: also persist activities to `external_activities` table
- For free users: return activities only, no Supabase persistence

- [ ] **Step 2: Test locally with Supabase CLI**

Run: `supabase functions serve mobile-integration-sync --no-verify-jwt`
Test with curl using a test API key.

- [ ] **Step 3: Commit**

```bash
git add phoenix-portal/supabase/functions/mobile-integration-sync/index.ts
git commit -m "feat(integration): add mobile-integration-sync Edge Function for Hevy/Liftosaur API sync"
```

---

### Task 12: Extend mobile-sync-push and mobile-sync-pull Edge Functions

**Files:**
- Modify: `phoenix-portal/supabase/functions/mobile-sync-push/index.ts`
- Modify: `phoenix-portal/supabase/functions/mobile-sync-pull/index.ts`

- [ ] **Step 1: Extend mobile-sync-push to accept externalActivities**

Add handling for an optional `externalActivities` array in the push payload. If present, upsert each activity into the Supabase `external_activities` table.

- [ ] **Step 2: Extend mobile-sync-pull to return externalActivities**

Query `external_activities` where `user_id = userId` and `synced_at > lastSync`. Include in the pull response.

- [ ] **Step 3: Test with existing sync flow**

Verify that existing sync (without external activities) still works — the new fields are optional.

- [ ] **Step 4: Commit**

```bash
git add phoenix-portal/supabase/functions/mobile-sync-push/index.ts \
       phoenix-portal/supabase/functions/mobile-sync-pull/index.ts
git commit -m "feat(integration): extend mobile-sync-push/pull Edge Functions to handle external activities"
```

---

### Task 13: Health Auto-Push After Workout

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`

- [ ] **Step 1: Add health auto-push hook**

In `ActiveSessionEngine`, after a workout completes (when transitioning to `WorkoutState.Completed` or `WorkoutState.ExerciseComplete`), add:

```kotlin
// Auto-push to health app if connected
viewModelScope.launch {
    try {
        val provider = if (getPlatform().name.lowercase().contains("ios")) {
            IntegrationProvider.APPLE_HEALTH
        } else {
            IntegrationProvider.GOOGLE_HEALTH
        }
        val status = externalActivityRepo.getIntegrationStatus(provider, profileId).first()
        if (status?.status == ConnectionStatus.CONNECTED) {
            healthIntegration.writeWorkout(completedSession)
            Logger.i("ActiveSessionEngine") { "Auto-pushed workout to ${provider.displayName}" }
        }
    } catch (e: Exception) {
        // Fire-and-forget — don't block workout completion
        Logger.w("ActiveSessionEngine") { "Health auto-push failed: ${e.message}" }
    }
}
```

- [ ] **Step 2: Build and test on device**

Run: `./gradlew :androidApp:installDebug`
Expected: Workout completes normally. If Health Connect is connected, workout appears in Health Connect.

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt
git commit -m "feat(integration): auto-push completed workouts to Health Connect / HealthKit"
```

---

## Phase 9: Final Verification

### Task 14: Integration Test + Full Build

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew :shared:testDebugUnitTest -q`
Expected: All tests pass (existing + new ExternalActivityTest, CsvExporterTest, CsvImporterTest)

- [ ] **Step 2: Run Android debug build**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual verification on device**

Install on device/emulator and verify:
1. Settings → Integrations screen loads
2. CSV Export generates file and opens share sheet
3. CSV Import picks file, shows preview, imports
4. Health Connect toggle requests permissions (if HC installed)
5. Hevy/Liftosaur cards show API key input
6. External Activities screen shows imported data

- [ ] **Step 4: Final commit (if any cleanup needed)**

```bash
git commit -m "test(integration): verify mobile integrations end-to-end"
```
