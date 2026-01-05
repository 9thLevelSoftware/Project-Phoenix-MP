# Sync Phase 1: Backend Schema & API Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Expand the portal backend to support full mobile data sync with push/pull/status API endpoints.

**Architecture:** Exposed ORM tables expanded to match mobile schema with sync metadata fields. RESTful API endpoints for push (upload changes), pull (download changes), and status (check sync state). JWT auth required on all sync endpoints.

**Tech Stack:** Kotlin, Ktor, Exposed ORM, PostgreSQL, kotlinx.serialization

**Reference:** Design doc at `docs/plans/2026-01-04-mobile-portal-sync-design.md`

---

## Task 1: Add Sync Metadata Fields to Users Table

**Files:**
- Modify: `portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Tables.kt`

**Step 1: Add subscription and sync fields to Users table**

Add after `lastLoginAt`:
```kotlin
val lastSyncAt = timestamp("last_sync_at").nullable()
val subscriptionStatus = varchar("subscription_status", 50).default("free")
val subscriptionExpiresAt = timestamp("subscription_expires_at").nullable()
```

**Step 2: Verify compilation**

Run: `cd portal/apps/backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Tables.kt
git commit -m "feat(portal): add sync and subscription fields to Users table"
```

---

## Task 2: Expand WorkoutSessions Table to Full Schema

**Files:**
- Modify: `portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Tables.kt`

**Step 1: Replace WorkoutSessions with full schema**

Replace the existing `WorkoutSessions` object with:
```kotlin
object WorkoutSessions : UUIDTable("workout_sessions") {
    val userId = reference("user_id", Users)

    // Sync metadata
    val clientId = uuid("client_id").uniqueIndex()
    val deviceId = uuid("device_id")
    val deletedAt = timestamp("deleted_at").nullable()

    // Core workout data
    val timestamp = long("timestamp")
    val mode = varchar("mode", 50)
    val targetReps = integer("target_reps")
    val weightPerCableKg = float("weight_per_cable_kg")
    val progressionKg = float("progression_kg").default(0f)
    val duration = integer("duration").default(0)
    val totalReps = integer("total_reps").default(0)
    val warmupReps = integer("warmup_reps").default(0)
    val workingReps = integer("working_reps").default(0)
    val isJustLift = bool("is_just_lift").default(false)
    val stopAtTop = bool("stop_at_top").default(false)
    val eccentricLoad = integer("eccentric_load").default(100)
    val echoLevel = integer("echo_level").default(1)

    // Exercise reference
    val exerciseId = varchar("exercise_id", 255).nullable()
    val exerciseName = varchar("exercise_name", 255).nullable()
    val routineSessionId = varchar("routine_session_id", 255).nullable()
    val routineName = varchar("routine_name", 255).nullable()

    // Safety tracking
    val safetyFlags = integer("safety_flags").default(0)
    val deloadWarningCount = integer("deload_warning_count").default(0)
    val romViolationCount = integer("rom_violation_count").default(0)
    val spotterActivations = integer("spotter_activations").default(0)

    // Force metrics
    val peakForceConcentricA = float("peak_force_concentric_a").nullable()
    val peakForceConcentricB = float("peak_force_concentric_b").nullable()
    val peakForceEccentricA = float("peak_force_eccentric_a").nullable()
    val peakForceEccentricB = float("peak_force_eccentric_b").nullable()
    val avgForceConcentricA = float("avg_force_concentric_a").nullable()
    val avgForceConcentricB = float("avg_force_concentric_b").nullable()
    val avgForceEccentricA = float("avg_force_eccentric_a").nullable()
    val avgForceEccentricB = float("avg_force_eccentric_b").nullable()

    // Summary metrics
    val heaviestLiftKg = float("heaviest_lift_kg").nullable()
    val totalVolumeKg = float("total_volume_kg").nullable()
    val estimatedCalories = float("estimated_calories").nullable()
    val warmupAvgWeightKg = float("warmup_avg_weight_kg").nullable()
    val workingAvgWeightKg = float("working_avg_weight_kg").nullable()
    val burnoutAvgWeightKg = float("burnout_avg_weight_kg").nullable()
    val peakWeightKg = float("peak_weight_kg").nullable()
    val rpe = integer("rpe").nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
```

**Step 2: Verify compilation**

Run: `cd portal/apps/backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Tables.kt
git commit -m "feat(portal): expand WorkoutSessions table to full mobile schema"
```

---

## Task 3: Expand PersonalRecords and Add PhaseStatistics Table

**Files:**
- Modify: `portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Tables.kt`

**Step 1: Update PersonalRecords with sync metadata and full fields**

Replace the existing `PersonalRecords` object:
```kotlin
object PersonalRecords : UUIDTable("personal_records") {
    val userId = reference("user_id", Users)

    // Sync metadata
    val clientId = uuid("client_id").uniqueIndex()
    val deviceId = uuid("device_id")
    val deletedAt = timestamp("deleted_at").nullable()

    // PR data
    val exerciseId = varchar("exercise_id", 255)
    val exerciseName = varchar("exercise_name", 255)
    val weight = float("weight")
    val reps = integer("reps")
    val oneRepMax = float("one_rep_max")
    val achievedAt = long("achieved_at")
    val workoutMode = varchar("workout_mode", 50)
    val prType = varchar("pr_type", 50).default("MAX_WEIGHT")
    val volume = float("volume").default(0f)
    val sessionId = uuid("session_id").nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
```

**Step 2: Add PhaseStatistics table after PersonalRecords**

```kotlin
object PhaseStatistics : UUIDTable("phase_statistics") {
    val userId = reference("user_id", Users)
    val sessionId = reference("session_id", WorkoutSessions)

    // Sync metadata
    val clientId = uuid("client_id").uniqueIndex()
    val deviceId = uuid("device_id")
    val deletedAt = timestamp("deleted_at").nullable()

    // Concentric phase
    val concentricKgAvg = float("concentric_kg_avg")
    val concentricKgMax = float("concentric_kg_max")
    val concentricVelAvg = float("concentric_vel_avg")
    val concentricVelMax = float("concentric_vel_max")
    val concentricWattAvg = float("concentric_watt_avg")
    val concentricWattMax = float("concentric_watt_max")

    // Eccentric phase
    val eccentricKgAvg = float("eccentric_kg_avg")
    val eccentricKgMax = float("eccentric_kg_max")
    val eccentricVelAvg = float("eccentric_vel_avg")
    val eccentricVelMax = float("eccentric_vel_max")
    val eccentricWattAvg = float("eccentric_watt_avg")
    val eccentricWattMax = float("eccentric_watt_max")

    // Timestamps
    val timestamp = long("timestamp")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
```

**Step 3: Verify compilation**

Run: `cd portal/apps/backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Tables.kt
git commit -m "feat(portal): expand PersonalRecords and add PhaseStatistics table"
```

---

## Task 4: Add Routines and RoutineExercises Tables

**Files:**
- Modify: `portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Tables.kt`

**Step 1: Add Routines table**

```kotlin
object Routines : UUIDTable("routines") {
    val userId = reference("user_id", Users)

    // Sync metadata
    val clientId = uuid("client_id").uniqueIndex()
    val deviceId = uuid("device_id")
    val deletedAt = timestamp("deleted_at").nullable()

    // Routine data
    val name = varchar("name", 255)
    val description = text("description").default("")
    val lastUsed = long("last_used").nullable()
    val useCount = integer("use_count").default(0)

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
```

**Step 2: Add Supersets table**

```kotlin
object Supersets : UUIDTable("supersets") {
    val userId = reference("user_id", Users)
    val routineId = reference("routine_id", Routines)

    // Sync metadata
    val clientId = uuid("client_id").uniqueIndex()
    val deviceId = uuid("device_id")
    val deletedAt = timestamp("deleted_at").nullable()

    // Superset data
    val name = varchar("name", 255)
    val colorIndex = integer("color_index").default(0)
    val restBetweenSeconds = integer("rest_between_seconds").default(10)
    val orderIndex = integer("order_index")

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
```

**Step 3: Add RoutineExercises table**

```kotlin
object RoutineExercises : UUIDTable("routine_exercises") {
    val userId = reference("user_id", Users)
    val routineId = reference("routine_id", Routines)
    val supersetId = reference("superset_id", Supersets).nullable()

    // Sync metadata
    val clientId = uuid("client_id").uniqueIndex()
    val deviceId = uuid("device_id")
    val deletedAt = timestamp("deleted_at").nullable()

    // Exercise reference
    val exerciseId = varchar("exercise_id", 255).nullable()
    val exerciseName = varchar("exercise_name", 255)
    val exerciseMuscleGroup = varchar("exercise_muscle_group", 100).default("")
    val exerciseEquipment = varchar("exercise_equipment", 100).default("")
    val exerciseDefaultCableConfig = varchar("exercise_default_cable_config", 50).default("DOUBLE")

    // Configuration
    val cableConfig = varchar("cable_config", 50).default("DOUBLE")
    val orderIndex = integer("order_index")
    val setReps = varchar("set_reps", 255).default("10,10,10")
    val weightPerCableKg = float("weight_per_cable_kg").default(0f)
    val setWeights = varchar("set_weights", 500).default("")
    val mode = varchar("mode", 50).default("OldSchool")
    val eccentricLoad = integer("eccentric_load").default(100)
    val echoLevel = integer("echo_level").default(1)
    val progressionKg = float("progression_kg").default(0f)
    val restSeconds = integer("rest_seconds").default(60)
    val duration = integer("duration").nullable()
    val setRestSeconds = varchar("set_rest_seconds", 255).default("[]")
    val perSetRestTime = integer("per_set_rest_time").default(0)
    val isAMRAP = bool("is_amrap").default(false)
    val orderInSuperset = integer("order_in_superset").default(0)

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
```

**Step 4: Verify compilation**

Run: `cd portal/apps/backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Tables.kt
git commit -m "feat(portal): add Routines, Supersets, and RoutineExercises tables"
```

---

## Task 5: Add CustomExercises and MetricSamples Tables

**Files:**
- Modify: `portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Tables.kt`

**Step 1: Add CustomExercises table**

```kotlin
object CustomExercises : UUIDTable("custom_exercises") {
    val userId = reference("user_id", Users)

    // Sync metadata
    val clientId = uuid("client_id").uniqueIndex()
    val deviceId = uuid("device_id")
    val deletedAt = timestamp("deleted_at").nullable()

    // Exercise data
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val muscleGroup = varchar("muscle_group", 100)
    val muscleGroups = varchar("muscle_groups", 500)
    val muscles = varchar("muscles", 500).nullable()
    val equipment = varchar("equipment", 100)
    val movement = varchar("movement", 100).nullable()
    val sidedness = varchar("sidedness", 50).nullable()
    val grip = varchar("grip", 50).nullable()
    val gripWidth = varchar("grip_width", 50).nullable()
    val minRepRange = float("min_rep_range").nullable()
    val aliases = text("aliases").nullable()
    val defaultCableConfig = varchar("default_cable_config", 50)
    val oneRepMaxKg = float("one_rep_max_kg").nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
```

**Step 2: Add MetricSamples table (for lazy-loaded data)**

```kotlin
object MetricSamples : UUIDTable("metric_samples") {
    val userId = reference("user_id", Users)
    val sessionId = reference("session_id", WorkoutSessions)

    // No sync metadata - these are bulk uploaded per session

    // Sample data
    val timestamp = long("timestamp")
    val position = float("position").nullable()
    val positionB = float("position_b").nullable()
    val velocity = float("velocity").nullable()
    val velocityB = float("velocity_b").nullable()
    val load = float("load").nullable()
    val loadB = float("load_b").nullable()
    val power = float("power").nullable()
    val status = integer("status").default(0)
}
```

**Step 3: Verify compilation**

Run: `cd portal/apps/backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Tables.kt
git commit -m "feat(portal): add CustomExercises and MetricSamples tables"
```

---

## Task 6: Add Gamification Tables

**Files:**
- Modify: `portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Tables.kt`

**Step 1: Add EarnedBadges table**

```kotlin
object EarnedBadges : UUIDTable("earned_badges") {
    val userId = reference("user_id", Users)

    // Sync metadata
    val clientId = uuid("client_id").uniqueIndex()
    val deviceId = uuid("device_id")
    val deletedAt = timestamp("deleted_at").nullable()

    // Badge data
    val badgeId = varchar("badge_id", 100)
    val earnedAt = long("earned_at")
    val celebratedAt = long("celebrated_at").nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
```

**Step 2: Add GamificationStats table**

```kotlin
object GamificationStats : UUIDTable("gamification_stats") {
    val userId = reference("user_id", Users).uniqueIndex()

    // Sync metadata
    val clientId = uuid("client_id").uniqueIndex()
    val deviceId = uuid("device_id")

    // Stats
    val totalWorkouts = integer("total_workouts").default(0)
    val totalReps = integer("total_reps").default(0)
    val totalVolumeKg = integer("total_volume_kg").default(0)
    val longestStreak = integer("longest_streak").default(0)
    val currentStreak = integer("current_streak").default(0)
    val uniqueExercisesUsed = integer("unique_exercises_used").default(0)
    val prsAchieved = integer("prs_achieved").default(0)
    val lastWorkoutDate = long("last_workout_date").nullable()
    val streakStartDate = long("streak_start_date").nullable()

    // Timestamps
    val updatedAt = timestamp("updated_at")
}
```

**Step 3: Add SyncDevices table**

```kotlin
object SyncDevices : UUIDTable("sync_devices") {
    val userId = reference("user_id", Users)
    val deviceId = uuid("device_id").uniqueIndex()
    val deviceName = varchar("device_name", 255).nullable()
    val platform = varchar("platform", 50) // "android" or "ios"
    val lastSyncAt = timestamp("last_sync_at")
    val createdAt = timestamp("created_at")
}
```

**Step 4: Verify compilation**

Run: `cd portal/apps/backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Tables.kt
git commit -m "feat(portal): add gamification and sync device tables"
```

---

## Task 7: Update Database Initialization

**Files:**
- Modify: `portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Database.kt`

**Step 1: Update SchemaUtils to include all new tables**

Find line with `SchemaUtils.createMissingTablesAndColumns` and replace with:
```kotlin
transaction {
    SchemaUtils.createMissingTablesAndColumns(
        Users,
        WorkoutSessions,
        PersonalRecords,
        PhaseStatistics,
        Routines,
        Supersets,
        RoutineExercises,
        CustomExercises,
        MetricSamples,
        EarnedBadges,
        GamificationStats,
        SyncDevices
    )
}
```

**Step 2: Verify compilation**

Run: `cd portal/apps/backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/db/Database.kt
git commit -m "feat(portal): register all new tables in database initialization"
```

---

## Task 8: Create Sync DTOs

**Files:**
- Create: `portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/models/SyncModels.kt`

**Step 1: Create the sync models file**

```kotlin
package com.devil.phoenixproject.portal.models

import kotlinx.serialization.Serializable

// === Push Request/Response ===

@Serializable
data class SyncPushRequest(
    val deviceId: String,
    val deviceName: String? = null,
    val platform: String, // "android" or "ios"
    val lastSync: Long,
    val sessions: List<WorkoutSessionDto> = emptyList(),
    val records: List<PersonalRecordDto> = emptyList(),
    val phaseStats: List<PhaseStatisticsDto> = emptyList(),
    val routines: List<RoutineDto> = emptyList(),
    val routineExercises: List<RoutineExerciseDto> = emptyList(),
    val supersets: List<SupersetDto> = emptyList(),
    val exercises: List<CustomExerciseDto> = emptyList(),
    val badges: List<EarnedBadgeDto> = emptyList(),
    val gamificationStats: GamificationStatsDto? = null
)

@Serializable
data class SyncPushResponse(
    val syncTime: Long,
    val idMappings: IdMappings
)

@Serializable
data class IdMappings(
    val sessions: Map<String, String> = emptyMap(),
    val records: Map<String, String> = emptyMap(),
    val phaseStats: Map<String, String> = emptyMap(),
    val routines: Map<String, String> = emptyMap(),
    val routineExercises: Map<String, String> = emptyMap(),
    val supersets: Map<String, String> = emptyMap(),
    val exercises: Map<String, String> = emptyMap(),
    val badges: Map<String, String> = emptyMap()
)

// === Pull Request/Response ===

@Serializable
data class SyncPullRequest(
    val deviceId: String,
    val lastSync: Long
)

@Serializable
data class SyncPullResponse(
    val syncTime: Long,
    val sessions: List<WorkoutSessionDto> = emptyList(),
    val records: List<PersonalRecordDto> = emptyList(),
    val phaseStats: List<PhaseStatisticsDto> = emptyList(),
    val routines: List<RoutineDto> = emptyList(),
    val routineExercises: List<RoutineExerciseDto> = emptyList(),
    val supersets: List<SupersetDto> = emptyList(),
    val exercises: List<CustomExerciseDto> = emptyList(),
    val badges: List<EarnedBadgeDto> = emptyList(),
    val gamificationStats: GamificationStatsDto? = null
)

// === Status Response ===

@Serializable
data class SyncStatusResponse(
    val lastSync: Long?,
    val pendingChanges: Int,
    val subscriptionStatus: String,
    val subscriptionExpiresAt: String?
)

// === Entity DTOs ===

@Serializable
data class WorkoutSessionDto(
    val clientId: String,
    val serverId: String? = null,
    val timestamp: Long,
    val mode: String,
    val targetReps: Int,
    val weightPerCableKg: Float,
    val progressionKg: Float = 0f,
    val duration: Int = 0,
    val totalReps: Int = 0,
    val warmupReps: Int = 0,
    val workingReps: Int = 0,
    val isJustLift: Boolean = false,
    val stopAtTop: Boolean = false,
    val eccentricLoad: Int = 100,
    val echoLevel: Int = 1,
    val exerciseId: String? = null,
    val exerciseName: String? = null,
    val routineSessionId: String? = null,
    val routineName: String? = null,
    val safetyFlags: Int = 0,
    val deloadWarningCount: Int = 0,
    val romViolationCount: Int = 0,
    val spotterActivations: Int = 0,
    val peakForceConcentricA: Float? = null,
    val peakForceConcentricB: Float? = null,
    val peakForceEccentricA: Float? = null,
    val peakForceEccentricB: Float? = null,
    val avgForceConcentricA: Float? = null,
    val avgForceConcentricB: Float? = null,
    val avgForceEccentricA: Float? = null,
    val avgForceEccentricB: Float? = null,
    val heaviestLiftKg: Float? = null,
    val totalVolumeKg: Float? = null,
    val estimatedCalories: Float? = null,
    val warmupAvgWeightKg: Float? = null,
    val workingAvgWeightKg: Float? = null,
    val burnoutAvgWeightKg: Float? = null,
    val peakWeightKg: Float? = null,
    val rpe: Int? = null,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class PersonalRecordDto(
    val clientId: String,
    val serverId: String? = null,
    val exerciseId: String,
    val exerciseName: String,
    val weight: Float,
    val reps: Int,
    val oneRepMax: Float,
    val achievedAt: Long,
    val workoutMode: String,
    val prType: String = "MAX_WEIGHT",
    val volume: Float = 0f,
    val sessionId: String? = null,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class PhaseStatisticsDto(
    val clientId: String,
    val serverId: String? = null,
    val sessionId: String,
    val concentricKgAvg: Float,
    val concentricKgMax: Float,
    val concentricVelAvg: Float,
    val concentricVelMax: Float,
    val concentricWattAvg: Float,
    val concentricWattMax: Float,
    val eccentricKgAvg: Float,
    val eccentricKgMax: Float,
    val eccentricVelAvg: Float,
    val eccentricVelMax: Float,
    val eccentricWattAvg: Float,
    val eccentricWattMax: Float,
    val timestamp: Long,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class RoutineDto(
    val clientId: String,
    val serverId: String? = null,
    val name: String,
    val description: String = "",
    val lastUsed: Long? = null,
    val useCount: Int = 0,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class SupersetDto(
    val clientId: String,
    val serverId: String? = null,
    val routineId: String,
    val name: String,
    val colorIndex: Int = 0,
    val restBetweenSeconds: Int = 10,
    val orderIndex: Int,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class RoutineExerciseDto(
    val clientId: String,
    val serverId: String? = null,
    val routineId: String,
    val supersetId: String? = null,
    val exerciseId: String? = null,
    val exerciseName: String,
    val exerciseMuscleGroup: String = "",
    val exerciseEquipment: String = "",
    val exerciseDefaultCableConfig: String = "DOUBLE",
    val cableConfig: String = "DOUBLE",
    val orderIndex: Int,
    val setReps: String = "10,10,10",
    val weightPerCableKg: Float = 0f,
    val setWeights: String = "",
    val mode: String = "OldSchool",
    val eccentricLoad: Int = 100,
    val echoLevel: Int = 1,
    val progressionKg: Float = 0f,
    val restSeconds: Int = 60,
    val duration: Int? = null,
    val setRestSeconds: String = "[]",
    val perSetRestTime: Int = 0,
    val isAMRAP: Boolean = false,
    val orderInSuperset: Int = 0,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class CustomExerciseDto(
    val clientId: String,
    val serverId: String? = null,
    val name: String,
    val description: String? = null,
    val muscleGroup: String,
    val muscleGroups: String,
    val muscles: String? = null,
    val equipment: String,
    val movement: String? = null,
    val sidedness: String? = null,
    val grip: String? = null,
    val gripWidth: String? = null,
    val minRepRange: Float? = null,
    val aliases: String? = null,
    val defaultCableConfig: String,
    val oneRepMaxKg: Float? = null,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class EarnedBadgeDto(
    val clientId: String,
    val serverId: String? = null,
    val badgeId: String,
    val earnedAt: Long,
    val celebratedAt: Long? = null,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class GamificationStatsDto(
    val clientId: String,
    val totalWorkouts: Int = 0,
    val totalReps: Int = 0,
    val totalVolumeKg: Int = 0,
    val longestStreak: Int = 0,
    val currentStreak: Int = 0,
    val uniqueExercisesUsed: Int = 0,
    val prsAchieved: Int = 0,
    val lastWorkoutDate: Long? = null,
    val streakStartDate: Long? = null,
    val updatedAt: Long
)

// === MetricSample DTOs (for lazy load) ===

@Serializable
data class MetricSampleDto(
    val timestamp: Long,
    val position: Float? = null,
    val positionB: Float? = null,
    val velocity: Float? = null,
    val velocityB: Float? = null,
    val load: Float? = null,
    val loadB: Float? = null,
    val power: Float? = null,
    val status: Int = 0
)

@Serializable
data class MetricSamplesUploadRequest(
    val deviceId: String,
    val samples: List<MetricSampleDto>
)

@Serializable
data class MetricSamplesResponse(
    val sessionId: String,
    val samples: List<MetricSampleDto>
)
```

**Step 2: Verify compilation**

Run: `cd portal/apps/backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/models/SyncModels.kt
git commit -m "feat(portal): add sync DTOs for push/pull API"
```

---

## Task 9: Create SyncService

**Files:**
- Create: `portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/sync/SyncService.kt`

**Step 1: Create the sync service**

```kotlin
package com.devil.phoenixproject.portal.sync

import com.devil.phoenixproject.portal.db.*
import com.devil.phoenixproject.portal.models.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class SyncService {

    fun getStatus(userId: UUID): SyncStatusResponse = transaction {
        val user = Users.select { Users.id eq userId }.singleOrNull()
            ?: throw IllegalStateException("User not found")

        val lastSync = user[Users.lastSyncAt]?.toEpochMilliseconds()
        val subscriptionStatus = user[Users.subscriptionStatus]
        val subscriptionExpires = user[Users.subscriptionExpiresAt]?.toString()

        // Count pending changes (simplified - just count total records)
        val pendingChanges = 0 // TODO: implement actual pending count

        SyncStatusResponse(
            lastSync = lastSync,
            pendingChanges = pendingChanges,
            subscriptionStatus = subscriptionStatus,
            subscriptionExpiresAt = subscriptionExpires
        )
    }

    fun push(userId: UUID, request: SyncPushRequest): SyncPushResponse = transaction {
        val now = Clock.System.now()
        val deviceUuid = UUID.fromString(request.deviceId)

        // Register/update device
        registerDevice(userId, deviceUuid, request.deviceName, request.platform, now)

        val idMappings = IdMappings(
            sessions = pushSessions(userId, deviceUuid, request.sessions, now),
            records = pushRecords(userId, deviceUuid, request.records, now),
            phaseStats = pushPhaseStats(userId, deviceUuid, request.phaseStats, now),
            routines = pushRoutines(userId, deviceUuid, request.routines, now),
            supersets = pushSupersets(userId, deviceUuid, request.supersets, now),
            routineExercises = pushRoutineExercises(userId, deviceUuid, request.routineExercises, now),
            exercises = pushExercises(userId, deviceUuid, request.exercises, now),
            badges = pushBadges(userId, deviceUuid, request.badges, now)
        )

        // Update gamification stats if provided
        request.gamificationStats?.let { pushGamificationStats(userId, deviceUuid, it, now) }

        // Update user's last sync time
        Users.update({ Users.id eq userId }) {
            it[lastSyncAt] = now
        }

        SyncPushResponse(
            syncTime = now.toEpochMilliseconds(),
            idMappings = idMappings
        )
    }

    fun pull(userId: UUID, request: SyncPullRequest): SyncPullResponse = transaction {
        val since = Instant.fromEpochMilliseconds(request.lastSync)
        val now = Clock.System.now()
        val deviceUuid = UUID.fromString(request.deviceId)

        SyncPullResponse(
            syncTime = now.toEpochMilliseconds(),
            sessions = pullSessions(userId, deviceUuid, since),
            records = pullRecords(userId, deviceUuid, since),
            phaseStats = pullPhaseStats(userId, deviceUuid, since),
            routines = pullRoutines(userId, deviceUuid, since),
            routineExercises = pullRoutineExercises(userId, deviceUuid, since),
            supersets = pullSupersets(userId, deviceUuid, since),
            exercises = pullExercises(userId, deviceUuid, since),
            badges = pullBadges(userId, deviceUuid, since),
            gamificationStats = pullGamificationStats(userId)
        )
    }

    // === Private helper functions ===

    private fun registerDevice(userId: UUID, deviceId: UUID, name: String?, platform: String, now: Instant) {
        val existing = SyncDevices.select { SyncDevices.deviceId eq deviceId }.singleOrNull()
        if (existing == null) {
            SyncDevices.insert {
                it[SyncDevices.userId] = userId
                it[SyncDevices.deviceId] = deviceId
                it[deviceName] = name
                it[SyncDevices.platform] = platform
                it[lastSyncAt] = now
                it[createdAt] = now
            }
        } else {
            SyncDevices.update({ SyncDevices.deviceId eq deviceId }) {
                it[lastSyncAt] = now
                if (name != null) it[deviceName] = name
            }
        }
    }

    private fun pushSessions(userId: UUID, deviceId: UUID, sessions: List<WorkoutSessionDto>, now: Instant): Map<String, String> {
        val mappings = mutableMapOf<String, String>()

        for (session in sessions) {
            val clientUuid = UUID.fromString(session.clientId)
            val existing = WorkoutSessions.select { WorkoutSessions.clientId eq clientUuid }.singleOrNull()

            if (existing == null) {
                val serverId = UUID.randomUUID()
                WorkoutSessions.insert {
                    it[id] = serverId
                    it[WorkoutSessions.userId] = userId
                    it[WorkoutSessions.clientId] = clientUuid
                    it[WorkoutSessions.deviceId] = deviceId
                    it[timestamp] = session.timestamp
                    it[mode] = session.mode
                    it[targetReps] = session.targetReps
                    it[weightPerCableKg] = session.weightPerCableKg
                    it[progressionKg] = session.progressionKg
                    it[duration] = session.duration
                    it[totalReps] = session.totalReps
                    it[warmupReps] = session.warmupReps
                    it[workingReps] = session.workingReps
                    it[isJustLift] = session.isJustLift
                    it[stopAtTop] = session.stopAtTop
                    it[eccentricLoad] = session.eccentricLoad
                    it[echoLevel] = session.echoLevel
                    it[exerciseId] = session.exerciseId
                    it[exerciseName] = session.exerciseName
                    it[routineSessionId] = session.routineSessionId
                    it[routineName] = session.routineName
                    it[safetyFlags] = session.safetyFlags
                    it[deloadWarningCount] = session.deloadWarningCount
                    it[romViolationCount] = session.romViolationCount
                    it[spotterActivations] = session.spotterActivations
                    it[peakForceConcentricA] = session.peakForceConcentricA
                    it[peakForceConcentricB] = session.peakForceConcentricB
                    it[peakForceEccentricA] = session.peakForceEccentricA
                    it[peakForceEccentricB] = session.peakForceEccentricB
                    it[avgForceConcentricA] = session.avgForceConcentricA
                    it[avgForceConcentricB] = session.avgForceConcentricB
                    it[avgForceEccentricA] = session.avgForceEccentricA
                    it[avgForceEccentricB] = session.avgForceEccentricB
                    it[heaviestLiftKg] = session.heaviestLiftKg
                    it[totalVolumeKg] = session.totalVolumeKg
                    it[estimatedCalories] = session.estimatedCalories
                    it[warmupAvgWeightKg] = session.warmupAvgWeightKg
                    it[workingAvgWeightKg] = session.workingAvgWeightKg
                    it[burnoutAvgWeightKg] = session.burnoutAvgWeightKg
                    it[peakWeightKg] = session.peakWeightKg
                    it[rpe] = session.rpe
                    it[deletedAt] = session.deletedAt?.let { ts -> Instant.fromEpochMilliseconds(ts) }
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                mappings[session.clientId] = serverId.toString()
            } else {
                // Update existing - only if updatedAt is newer
                val existingUpdatedAt = existing[WorkoutSessions.updatedAt]
                val incomingUpdatedAt = Instant.fromEpochMilliseconds(session.updatedAt)

                if (incomingUpdatedAt > existingUpdatedAt) {
                    WorkoutSessions.update({ WorkoutSessions.clientId eq clientUuid }) {
                        it[deletedAt] = session.deletedAt?.let { ts -> Instant.fromEpochMilliseconds(ts) }
                        it[updatedAt] = now
                    }
                }
                mappings[session.clientId] = existing[WorkoutSessions.id].toString()
            }
        }

        return mappings
    }

    // Placeholder implementations for other entity types
    private fun pushRecords(userId: UUID, deviceId: UUID, records: List<PersonalRecordDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushPhaseStats(userId: UUID, deviceId: UUID, stats: List<PhaseStatisticsDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushRoutines(userId: UUID, deviceId: UUID, routines: List<RoutineDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushSupersets(userId: UUID, deviceId: UUID, supersets: List<SupersetDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushRoutineExercises(userId: UUID, deviceId: UUID, exercises: List<RoutineExerciseDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushExercises(userId: UUID, deviceId: UUID, exercises: List<CustomExerciseDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushBadges(userId: UUID, deviceId: UUID, badges: List<EarnedBadgeDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushGamificationStats(userId: UUID, deviceId: UUID, stats: GamificationStatsDto, now: Instant) {} // TODO

    private fun pullSessions(userId: UUID, deviceId: UUID, since: Instant): List<WorkoutSessionDto> = emptyList() // TODO
    private fun pullRecords(userId: UUID, deviceId: UUID, since: Instant): List<PersonalRecordDto> = emptyList() // TODO
    private fun pullPhaseStats(userId: UUID, deviceId: UUID, since: Instant): List<PhaseStatisticsDto> = emptyList() // TODO
    private fun pullRoutines(userId: UUID, deviceId: UUID, since: Instant): List<RoutineDto> = emptyList() // TODO
    private fun pullRoutineExercises(userId: UUID, deviceId: UUID, since: Instant): List<RoutineExerciseDto> = emptyList() // TODO
    private fun pullSupersets(userId: UUID, deviceId: UUID, since: Instant): List<SupersetDto> = emptyList() // TODO
    private fun pullExercises(userId: UUID, deviceId: UUID, since: Instant): List<CustomExerciseDto> = emptyList() // TODO
    private fun pullBadges(userId: UUID, deviceId: UUID, since: Instant): List<EarnedBadgeDto> = emptyList() // TODO
    private fun pullGamificationStats(userId: UUID): GamificationStatsDto? = null // TODO
}
```

**Step 2: Verify compilation**

Run: `cd portal/apps/backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/sync/SyncService.kt
git commit -m "feat(portal): add SyncService with push/pull/status logic"
```

---

## Task 10: Create Sync API Routes

**Files:**
- Modify: `portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/routes/SyncRoutes.kt`

**Step 1: Check existing SyncRoutes file and replace with full implementation**

```kotlin
package com.devil.phoenixproject.portal.routes

import com.devil.phoenixproject.portal.auth.AuthService
import com.devil.phoenixproject.portal.models.*
import com.devil.phoenixproject.portal.sync.SyncService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.syncRoutes(authService: AuthService, syncService: SyncService) {
    route("/api/sync") {

        // Get sync status
        get("/status") {
            val userId = authService.extractUserId(call) ?: run {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing token"))
                return@get
            }

            try {
                val status = syncService.getStatus(userId)
                call.respond(status)
            } catch (e: Exception) {
                call.application.log.error("Sync status error", e)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to get sync status"))
            }
        }

        // Push changes to server
        post("/push") {
            val userId = authService.extractUserId(call) ?: run {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing token"))
                return@post
            }

            try {
                val request = call.receive<SyncPushRequest>()
                val response = syncService.push(userId, request)
                call.respond(response)
            } catch (e: Exception) {
                call.application.log.error("Sync push error", e)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to push sync data"))
            }
        }

        // Pull changes from server
        post("/pull") {
            val userId = authService.extractUserId(call) ?: run {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing token"))
                return@post
            }

            try {
                val request = call.receive<SyncPullRequest>()
                val response = syncService.pull(userId, request)
                call.respond(response)
            } catch (e: Exception) {
                call.application.log.error("Sync pull error", e)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to pull sync data"))
            }
        }
    }

    // MetricSamples endpoints (lazy load)
    route("/api/sessions/{sessionId}/metrics") {

        get {
            val userId = authService.extractUserId(call) ?: run {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing token"))
                return@get
            }

            val sessionId = call.parameters["sessionId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing session ID"))
                return@get
            }

            // TODO: Implement metric sample retrieval
            call.respond(MetricSamplesResponse(sessionId, emptyList()))
        }

        post {
            val userId = authService.extractUserId(call) ?: run {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing token"))
                return@post
            }

            val sessionId = call.parameters["sessionId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing session ID"))
                return@post
            }

            try {
                val request = call.receive<MetricSamplesUploadRequest>()
                // TODO: Implement metric sample upload
                call.respond(HttpStatusCode.Created, mapOf("uploaded" to request.samples.size))
            } catch (e: Exception) {
                call.application.log.error("Metrics upload error", e)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to upload metrics"))
            }
        }
    }
}
```

**Step 2: Verify compilation**

Run: `cd portal/apps/backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/routes/SyncRoutes.kt
git commit -m "feat(portal): add sync API routes for push/pull/status/metrics"
```

---

## Task 11: Add extractUserId to AuthService

**Files:**
- Modify: `portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/auth/AuthService.kt`

**Step 1: Add extractUserId function**

Add this function to AuthService class:
```kotlin
fun extractUserId(call: ApplicationCall): UUID? {
    val authHeader = call.request.header("Authorization") ?: return null
    if (!authHeader.startsWith("Bearer ")) return null
    val token = authHeader.removePrefix("Bearer ")
    return verifyToken(token)
}
```

**Step 2: Add import for ApplicationCall**

Add at top of file:
```kotlin
import io.ktor.server.application.*
```

**Step 3: Verify compilation**

Run: `cd portal/apps/backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/auth/AuthService.kt
git commit -m "feat(portal): add extractUserId helper to AuthService"
```

---

## Task 12: Update Application.kt to Wire Everything Together

**Files:**
- Modify: `portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/Application.kt`

**Step 1: Add SyncService import and instantiation**

Add import:
```kotlin
import com.devil.phoenixproject.portal.sync.SyncService
```

Add after `val authService = AuthService()`:
```kotlin
val syncService = SyncService()
```

**Step 2: Update syncRoutes call**

Change:
```kotlin
syncRoutes()
```
to:
```kotlin
syncRoutes(authService, syncService)
```

**Step 3: Verify compilation**

Run: `cd portal/apps/backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add portal/apps/backend/src/main/kotlin/com/devil/phoenixproject/portal/Application.kt
git commit -m "feat(portal): wire SyncService into application"
```

---

## Task 13: Build and Test Locally

**Step 1: Build the fat JAR**

Run: `cd portal/apps/backend && ./gradlew buildFatJar`
Expected: BUILD SUCCESSFUL

**Step 2: Test locally (requires local PostgreSQL)**

If you have local PostgreSQL running:
```bash
cd portal/apps/backend
java -jar build/libs/phoenix-portal-backend-all.jar
```

**Step 3: Test endpoints with curl**

```bash
# Get status (requires valid JWT)
curl -X GET http://localhost:8080/api/sync/status \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# Push (requires valid JWT)
curl -X POST http://localhost:8080/api/sync/push \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"test-device","platform":"android","lastSync":0,"sessions":[]}'
```

**Step 4: Commit all remaining changes and push**

```bash
git add -A
git commit -m "feat(portal): complete Phase 1 sync backend API"
git push origin premium_features
```

---

## Summary

This plan implements:
1. Expanded database schema matching mobile app (12 tables)
2. Sync metadata fields on all syncable entities
3. DTOs for push/pull API requests/responses
4. SyncService with push/pull/status logic (sessions implemented, others stubbed)
5. API routes at `/api/sync/push`, `/api/sync/pull`, `/api/sync/status`
6. MetricSamples lazy-load endpoints at `/api/sessions/{id}/metrics`

Next phase will implement the remaining push/pull entity handlers and the mobile client.
