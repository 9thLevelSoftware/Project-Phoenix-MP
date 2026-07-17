package com.devil.phoenixproject.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// === Shared Data Structures ===

data class ProfilePreferenceSectionKey(
    val localProfileId: String,
    val section: com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName,
)

data class ProfilePreferenceSectionSyncDto(
    val key: ProfilePreferenceSectionKey,
    val documentVersion: Int,
    val baseRevision: Long,
    val clientModifiedAtEpochMs: Long,
    val localGeneration: Long,
    val payload: kotlinx.serialization.json.JsonObject,
)

data class PreparedProfilePreferenceMutation(
    val wire: PortalProfilePreferenceSectionMutationDto,
    val key: ProfilePreferenceSectionKey,
    val sentLocalGeneration: Long,
)

data class CanonicalProfilePreferenceSection(
    val key: ProfilePreferenceSectionKey,
    val documentVersion: Int,
    val serverRevision: Long,
    val serverUpdatedAtEpochMs: Long,
    val payload: kotlinx.serialization.json.JsonObject,
)

data class ProfilePreferencePushOutcome(
    val key: ProfilePreferenceSectionKey,
    val sentLocalGeneration: Long,
    val serverRevision: Long,
    val canonical: CanonicalProfilePreferenceSection?,
    val rejectionReason: String?,
)

data class ProfilePreferenceSyncApplyReport(
    val applied: Int = 0,
    val preservedNewerLocal: Int = 0,
    val ignoredUnknownProfile: Int = 0,
    val invalid: Int = 0,
)

data class ProfilePreferenceSyncIssue(
    val key: ProfilePreferenceSectionKey,
    val localGeneration: Long,
    val reason: String,
)

data class ProfilePreferenceDirtySnapshot(
    val valid: List<ProfilePreferenceSectionSyncDto>,
    val unsyncable: List<ProfilePreferenceSyncIssue>,
)

sealed interface ProfilePreferenceCanonicalDecodeResult {
    data class Valid(val section: CanonicalProfilePreferenceSection) : ProfilePreferenceCanonicalDecodeResult

    data class Invalid(
        val localProfileId: String,
        val section: String,
        val reason: String,
    ) : ProfilePreferenceCanonicalDecodeResult
}

// IdMappings is used by SyncRepository / SqlDelightSyncRepository to stamp server-assigned
// UUIDs back onto locally-created rows after a successful push. It is NOT part of the wire
// format for the current Edge Functions (which use client-provided UUIDs); it exists to
// support legacy push flows and the updateServerIds() repository contract.
@Serializable
data class IdMappings(
    val sessions: Map<String, String> = emptyMap(),
    val records: Map<String, String> = emptyMap(),
    val routines: Map<String, String> = emptyMap(),
    val exercises: Map<String, String> = emptyMap(),
    val badges: Map<String, String> = emptyMap(),
)

// === Auth DTOs ===

@Serializable
data class PortalLoginRequest(val email: String, val password: String)

@Serializable
data class PortalAuthResponse(val token: String, val user: PortalUser)

@Serializable
data class PortalUser(val id: String, val email: String, val displayName: String?, val isPremium: Boolean)

// === Entity DTOs ===
// NOTE: These types are internal data-transfer objects used between the sync layer and the
// repository / merge logic. They are NOT wire-format types — the actual HTTP request/response
// bodies are defined in PortalSyncDtos.kt (PortalSyncPayload, PortalSyncPullResponse, etc.).

@Serializable
data class WorkoutSessionSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val timestamp: Long,
    val mode: String,
    val targetReps: Int,
    val weightPerCableKg: Float,
    /** Session duration in milliseconds. Matches [com.devil.phoenixproject.domain.model.WorkoutSession.duration]. */
    val duration: Long = 0L,
    val totalReps: Int = 0,
    val exerciseId: String? = null,
    val exerciseName: String? = null,
    val displayMultiplier: Int? = null,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class PersonalRecordSyncDto(
    val clientId: String,
    val serverId: String? = null,
    @Transient val localId: Long = -1L,
    val exerciseId: String,
    val exerciseName: String,
    val weight: Float,
    val reps: Int,
    val oneRepMax: Float,
    val achievedAt: Long,
    val workoutMode: String,
    val prType: String = "MAX_WEIGHT",
    val phase: String = "COMBINED",
    val volume: Float = 0f,
    val cableCount: Int? = null,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class RoutineSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val name: String,
    val description: String = "",
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class CustomExerciseSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val name: String,
    val displayName: String? = null, // Disambiguated name (#404)
    val muscleGroup: String,
    val equipment: String,
    val defaultCableConfig: String,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class EarnedBadgeSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val badgeId: String,
    val earnedAt: Long,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class GamificationStatsSyncDto(
    val clientId: String,
    val totalWorkouts: Int = 0,
    val totalReps: Int = 0,
    val totalVolumeKg: Float = 0f,
    val longestStreak: Int = 0,
    val currentStreak: Int = 0,
    // Received from mobile-sync-pull (total_time_seconds column on server).
    // Carried through the DTO chain but not yet persisted locally — the GamificationStats
    // SQLite table has no total_time_seconds column. Add a migration and wire it into
    // upsertGamificationStats / getGamificationStatsForSync once the column lands.
    val totalTimeSeconds: Long = 0L,
    val updatedAt: Long,
)

// === Error Response ===

@Serializable
data class PortalErrorResponse(val error: String)
