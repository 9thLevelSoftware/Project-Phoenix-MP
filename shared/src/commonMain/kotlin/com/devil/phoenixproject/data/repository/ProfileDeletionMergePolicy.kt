package com.devil.phoenixproject.data.repository

data class ProfileMergePersonalRecord(
    val id: Long,
    val profileId: String,
    val exerciseId: String,
    val exerciseName: String,
    val weight: Double,
    val reps: Long,
    val oneRepMax: Double,
    val achievedAt: Long,
    val workoutMode: String,
    val prType: String,
    val volume: Double,
    val phase: String,
    val updatedAt: Long?,
    val serverId: String?,
    val deletedAt: Long?,
    val cableCount: Long?,
    val uuid: String?,
)

data class ProfileMergeEarnedBadge(
    val id: Long,
    val profileId: String,
    val badgeId: String,
    val earnedAt: Long,
    val celebratedAt: Long?,
    val updatedAt: Long?,
    val serverId: String?,
    val deletedAt: Long?,
)

data class ProfileMergeExerciseMvt(
    val exerciseId: String,
    val profileId: String,
    val personalMvtMs: Double,
    val sampleCount: Long,
    val updatedAt: Long,
)

object ProfileDeletionMergePolicy {
    val directProfileOwnedTables: Set<String> = setOf(
        "AssessmentResult",
        "EarnedBadge",
        "ExerciseMvt",
        "ExternalActivity",
        "ExternalBodyMeasurement",
        "ExternalExerciseTemplate",
        "ExternalExerciseTemplateMapping",
        "ExternalProgram",
        "ExternalRoutine",
        "ExternalRoutineFolder",
        "GamificationStats",
        "IntegrationStatus",
        "IntegrationSyncCursor",
        "PendingProfileLocalCleanup",
        "PersonalRecord",
        "ProgressionEvent",
        "Routine",
        "RoutineGroup",
        "RpgAttributes",
        "StreakHistory",
        "TrainingCycle",
        "UserProfilePreferences",
        "VelocityOneRepMaxEstimate",
        "WorkoutSession",
    )

    fun mergePersonalRecordGroup(
        records: List<ProfileMergePersonalRecord>,
        targetProfileId: String,
    ): ProfileMergePersonalRecord {
        require(records.isNotEmpty())
        val normalized = records
            .map { it.copy(workoutMode = normalizeWorkoutModeKey(it.workoutMode)) }
            .sortedWith(
                compareByDescending<ProfileMergePersonalRecord> { it.profileId == targetProfileId }
                    .thenBy { it.id },
            )
        val winner = normalized.reduce { current, candidate ->
            if (personalRecordBeats(candidate, current, targetProfileId)) candidate else current
        }
        val retainedId = normalized
            .filter { it.profileId == targetProfileId }
            .minOfOrNull { it.id }
            ?: normalized.minOf { it.id }
        val fallbackName = normalized.firstOrNull { it.exerciseName.isNotBlank() }?.exerciseName.orEmpty()
        val fallbackUuid = normalized.firstOrNull {
            (it.id != winner.id || it.profileId != winner.profileId) && it.uuid != null
        }?.uuid
        return winner.copy(
            id = retainedId,
            profileId = targetProfileId,
            exerciseName = winner.exerciseName.ifBlank { fallbackName },
            uuid = winner.uuid ?: fallbackUuid,
        )
    }

    fun mergeEarnedBadgeGroup(
        badges: List<ProfileMergeEarnedBadge>,
        targetProfileId: String,
    ): ProfileMergeEarnedBadge {
        require(badges.isNotEmpty())
        val ordered = badges.sortedWith(
            compareByDescending<ProfileMergeEarnedBadge> { it.profileId == targetProfileId }
                .thenBy { it.id },
        )
        val donor = ordered.reduce { current, candidate ->
            if (badgeMetadataBeats(candidate, current, targetProfileId)) candidate else current
        }
        val retainedId = badges
            .filter { it.profileId == targetProfileId }
            .minOfOrNull { it.id }
            ?: badges.minOf { it.id }
        return donor.copy(
            id = retainedId,
            profileId = targetProfileId,
            earnedAt = badges.minOf { it.earnedAt },
            celebratedAt = badges.mapNotNull { it.celebratedAt }.minOrNull(),
        )
    }

    fun mergeExerciseMvtGroup(
        rows: List<ProfileMergeExerciseMvt>,
        targetProfileId: String,
    ): ProfileMergeExerciseMvt {
        require(rows.isNotEmpty())
        val ordered = rows.sortedWith(
            compareByDescending<ProfileMergeExerciseMvt> { it.profileId == targetProfileId }
                .thenBy { it.profileId }
                .thenBy { it.personalMvtMs }
                .thenBy { it.sampleCount }
                .thenBy { it.updatedAt },
        )
        val normalizedCounts = ordered.map { it to it.sampleCount.coerceAtLeast(0) }
        val totalCount = normalizedCounts.sumOf { it.second }
        val newest = ordered.reduce { current, candidate ->
            when {
                candidate.updatedAt > current.updatedAt -> candidate
                candidate.updatedAt < current.updatedAt -> current
                candidate.profileId == targetProfileId -> candidate
                else -> current
            }
        }
        val mergedMvt = if (totalCount == 0L) {
            newest.personalMvtMs
        } else {
            normalizedCounts.sumOf { (row, count) -> row.personalMvtMs * count } / totalCount
        }
        return newest.copy(
            profileId = targetProfileId,
            personalMvtMs = mergedMvt,
            sampleCount = totalCount,
            updatedAt = rows.maxOf { it.updatedAt },
        )
    }

    private fun personalRecordBeats(
        candidate: ProfileMergePersonalRecord,
        current: ProfileMergePersonalRecord,
        targetProfileId: String,
    ): Boolean {
        val candidateLive = candidate.deletedAt == null
        val currentLive = current.deletedAt == null
        if (candidateLive != currentLive) return candidateLive

        val comparison = if (candidate.prType == "MAX_VOLUME") {
            compareValuesBy(candidate, current, { it.volume }, { it.weight }, { it.achievedAt })
        } else {
            compareValuesBy(candidate, current, { it.weight }, { it.oneRepMax }, { it.achievedAt })
        }
        return when {
            comparison > 0 -> true
            comparison < 0 -> false
            else -> candidate.profileId == targetProfileId && current.profileId != targetProfileId
        }
    }

    private fun badgeMetadataBeats(
        candidate: ProfileMergeEarnedBadge,
        current: ProfileMergeEarnedBadge,
        targetProfileId: String,
    ): Boolean {
        val candidateLive = candidate.deletedAt == null
        val currentLive = current.deletedAt == null
        return when {
            candidateLive != currentLive -> candidateLive
            candidate.earnedAt < current.earnedAt -> true
            candidate.earnedAt > current.earnedAt -> false
            else -> candidate.profileId == targetProfileId && current.profileId != targetProfileId
        }
    }
}
