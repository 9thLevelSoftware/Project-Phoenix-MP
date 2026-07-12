package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.EarnedBadge
import com.devil.phoenixproject.database.ExerciseMvt
import com.devil.phoenixproject.database.PersonalRecord
import com.devil.phoenixproject.database.VitruvianDatabase

class ProfileScopedDataMerger(
    database: VitruvianDatabase,
) {
    private val queries = database.vitruvianDatabaseQueries

    fun mergeForProfileDeletion(sourceProfileId: String, targetProfileId: String) {
        mergePersonalRecords(sourceProfileId, targetProfileId)
        mergeEarnedBadges(sourceProfileId, targetProfileId)
        mergeExerciseMvt(sourceProfileId, targetProfileId)
        resolveExternalConflicts(sourceProfileId, targetProfileId)
    }

    fun mergePersonalRecords(sourceProfileId: String, targetProfileId: String) {
        val records = queries.selectAllRecords(sourceProfileId).executeAsList() +
            queries.selectAllRecords(targetProfileId).executeAsList()
        records
            .groupBy { it.normalizedMergeKey() }
            .values
            .filter { group -> group.any { it.profile_id == sourceProfileId } }
            .forEach { group -> mergePersonalRecordGroup(group, targetProfileId) }
        queries.reassignPRProfile(targetProfileId, sourceProfileId)
    }

    /** Normalizes raw workout-mode aliases without replacing the retained database row. */
    fun normalizePersonalRecordModes(profileId: String): Int {
        val groups = queries.selectAllRecords(profileId)
            .executeAsList()
            .groupBy { it.normalizedMergeKey() }
            .values
            .filter { group ->
                group.size > 1 || group.any {
                    normalizeWorkoutModeKey(it.workoutMode) != it.workoutMode
                }
            }
        groups.forEach { group -> mergePersonalRecordGroup(group, profileId) }
        return groups.sumOf { it.size }
    }

    fun mergeEarnedBadges(sourceProfileId: String, targetProfileId: String) {
        val badges = queries.selectAllEarnedBadges(sourceProfileId).executeAsList() +
            queries.selectAllEarnedBadges(targetProfileId).executeAsList()
        badges
            .groupBy { it.badgeId }
            .values
            .filter { group -> group.any { it.profile_id == sourceProfileId } }
            .forEach { group ->
                val merged = ProfileDeletionMergePolicy.mergeEarnedBadgeGroup(
                    group.map { it.toProfileMergeRow() },
                    targetProfileId,
                )
                group.filter { it.id != merged.id }.forEach { duplicate ->
                    queries.deleteEarnedBadgeById(duplicate.id)
                }
                queries.updateEarnedBadgeForProfileMerge(
                    earnedAt = merged.earnedAt,
                    celebratedAt = merged.celebratedAt,
                    updatedAt = merged.updatedAt,
                    serverId = merged.serverId,
                    deletedAt = merged.deletedAt,
                    targetId = merged.id,
                )
            }
        queries.reassignBadgeProfile(targetProfileId, sourceProfileId)
    }

    fun mergeExerciseMvt(sourceProfileId: String, targetProfileId: String) {
        val rows = queries.selectAllExerciseMvtByProfile(sourceProfileId).executeAsList() +
            queries.selectAllExerciseMvtByProfile(targetProfileId).executeAsList()
        rows
            .groupBy { it.exerciseId }
            .values
            .filter { group -> group.any { it.profile_id == sourceProfileId } }
            .forEach { group ->
                val merged = ProfileDeletionMergePolicy.mergeExerciseMvtGroup(
                    group.map { it.toProfileMergeRow() },
                    targetProfileId,
                )
                val retained = group
                    .firstOrNull { it.profile_id == targetProfileId }
                    ?: group.first { it.profile_id == sourceProfileId }
                group.filter { it !== retained }.forEach { duplicate ->
                    queries.deleteExerciseMvt(duplicate.exerciseId, duplicate.profile_id)
                }
                queries.updateExerciseMvtForProfileMerge(
                    targetProfileId = targetProfileId,
                    personalMvtMs = merged.personalMvtMs,
                    sampleCount = merged.sampleCount,
                    updatedAt = merged.updatedAt,
                    exerciseId = retained.exerciseId,
                    retainedProfileId = retained.profile_id,
                )
            }
        queries.reassignExerciseMvtProfile(targetProfileId, sourceProfileId)
    }

    private fun resolveExternalConflicts(sourceProfileId: String, targetProfileId: String) {
        queries.deleteConflictingSourceExternalActivities(sourceProfileId, targetProfileId)
        queries.reassignExternalActivityProfile(targetProfileId, sourceProfileId)

        queries.deleteConflictingSourceExternalRoutineSets(sourceProfileId, targetProfileId)
        queries.deleteConflictingSourceExternalRoutineExercises(sourceProfileId, targetProfileId)
        queries.deleteConflictingSourceExternalRoutines(sourceProfileId, targetProfileId)
        queries.reassignExternalRoutineProfile(targetProfileId, sourceProfileId)

        queries.deleteConflictingSourceExternalRoutineFolders(sourceProfileId, targetProfileId)
        queries.reassignExternalRoutineFolderProfile(targetProfileId, sourceProfileId)

        queries.deleteConflictingSourceExternalProgramStats(sourceProfileId, targetProfileId)
        queries.deleteConflictingSourceExternalPrograms(sourceProfileId, targetProfileId)
        queries.reassignExternalProgramProfile(targetProfileId, sourceProfileId)

        queries.deleteConflictingSourceExternalExerciseTemplates(sourceProfileId, targetProfileId)
        queries.reassignExternalExerciseTemplateProfile(targetProfileId, sourceProfileId)

        queries.deleteConflictingSourceExternalExerciseTemplateMappings(sourceProfileId, targetProfileId)
        queries.reassignExternalExerciseTemplateMappingProfile(targetProfileId, sourceProfileId)

        queries.deleteConflictingSourceExternalBodyMeasurements(sourceProfileId, targetProfileId)
        queries.reassignExternalBodyMeasurementProfile(targetProfileId, sourceProfileId)
    }

    private fun mergePersonalRecordGroup(
        group: List<PersonalRecord>,
        targetProfileId: String,
    ) {
        val merged = ProfileDeletionMergePolicy.mergePersonalRecordGroup(
            group.map { it.toProfileMergeRow() },
            targetProfileId,
        )
        group.filter { it.id != merged.id }.forEach { duplicate ->
            queries.deletePersonalRecordById(duplicate.id)
        }
        queries.updatePersonalRecordForProfileMerge(
            exerciseName = merged.exerciseName,
            weight = merged.weight,
            reps = merged.reps,
            oneRepMax = merged.oneRepMax,
            achievedAt = merged.achievedAt,
            workoutMode = merged.workoutMode,
            prType = merged.prType,
            volume = merged.volume,
            phase = merged.phase,
            updatedAt = merged.updatedAt,
            serverId = merged.serverId,
            deletedAt = merged.deletedAt,
            cableCount = merged.cableCount,
            uuid = merged.uuid,
            targetProfileId = targetProfileId,
            targetId = merged.id,
        )
    }

    private fun PersonalRecord.normalizedMergeKey() = listOf(
        exerciseId,
        normalizeWorkoutModeKey(workoutMode),
        prType,
        phase,
    ).joinToString("\u001f")

    private fun PersonalRecord.toProfileMergeRow() = ProfileMergePersonalRecord(
        id = id,
        profileId = profile_id,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        weight = weight,
        reps = reps,
        oneRepMax = oneRepMax,
        achievedAt = achievedAt,
        workoutMode = workoutMode,
        prType = prType,
        volume = volume,
        phase = phase,
        updatedAt = updatedAt,
        serverId = serverId,
        deletedAt = deletedAt,
        cableCount = cable_count,
        uuid = uuid,
    )

    private fun EarnedBadge.toProfileMergeRow() = ProfileMergeEarnedBadge(
        id = id,
        profileId = profile_id,
        badgeId = badgeId,
        earnedAt = earnedAt,
        celebratedAt = celebratedAt,
        updatedAt = updatedAt,
        serverId = serverId,
        deletedAt = deletedAt,
    )

    private fun ExerciseMvt.toProfileMergeRow() = ProfileMergeExerciseMvt(
        exerciseId = exerciseId,
        profileId = profile_id,
        personalMvtMs = personalMvtMs,
        sampleCount = sampleCount,
        updatedAt = updatedAt,
    )
}
