package com.devil.phoenixproject.data.sync

import kotlinx.serialization.Serializable

// === Push Request/Response ===

@Serializable
data class SyncPushRequest(
    val deviceId: String,
    val deviceName: String? = null,
    val platform: String,
    val lastSync: Long,
    val sessions: List<WorkoutSessionSyncDto> = emptyList(),
    val records: List<PersonalRecordSyncDto> = emptyList(),
    val routines: List<RoutineSyncDto> = emptyList(),
    val exercises: List<CustomExerciseSyncDto> = emptyList(),
    val badges: List<EarnedBadgeSyncDto> = emptyList(),
    val gamificationStats: GamificationStatsSyncDto? = null
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
    val routines: Map<String, String> = emptyMap(),
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
    val sessions: List<WorkoutSessionSyncDto> = emptyList(),
    val records: List<PersonalRecordSyncDto> = emptyList(),
    val routines: List<RoutineSyncDto> = emptyList(),
    val exercises: List<CustomExerciseSyncDto> = emptyList(),
    val badges: List<EarnedBadgeSyncDto> = emptyList(),
    val gamificationStats: GamificationStatsSyncDto? = null
)

// === Auth DTOs ===

@Serializable
data class PortalLoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class PortalAuthResponse(
    val token: String,
    val user: PortalUser
)

@Serializable
data class PortalUser(
    val id: String,
    val email: String,
    val displayName: String?,
    val isPremium: Boolean
)

// === Entity DTOs ===

@Serializable
data class WorkoutSessionSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val timestamp: Long,
    val mode: String,
    val targetReps: Int,
    val weightPerCableKg: Float,
    val duration: Int = 0,
    val totalReps: Int = 0,
    val exerciseId: String? = null,
    val exerciseName: String? = null,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class PersonalRecordSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val exerciseId: String,
    val exerciseName: String,
    val weight: Float,
    val reps: Int,
    val oneRepMax: Float,
    val achievedAt: Long,
    val workoutMode: String,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class RoutineSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val name: String,
    val description: String = "",
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class CustomExerciseSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val name: String,
    val muscleGroup: String,
    val equipment: String,
    val defaultCableConfig: String,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class EarnedBadgeSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val badgeId: String,
    val earnedAt: Long,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class GamificationStatsSyncDto(
    val clientId: String,
    val totalWorkouts: Int = 0,
    val totalReps: Int = 0,
    val totalVolumeKg: Float = 0f,
    val longestStreak: Int = 0,
    val currentStreak: Int = 0,
    val updatedAt: Long
)

// === Error Response ===

@Serializable
data class PortalErrorResponse(
    val error: String
)
