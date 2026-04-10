package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis

/**
 * Converts portal pull response DTOs (camelCase) to domain objects and legacy merge DTOs
 * (used by SyncRepository merge methods).
 *
 * This is the inverse of PortalSyncAdapter (which converts mobile → portal for push).
 * Converts:
 *   - Sessions (with exercises/sets) → WorkoutSession domain objects
 *   - Routines (with exercises) → RoutineSyncDto
 *   - Badges → EarnedBadgeSyncDto
 *   - Gamification stats → GamificationStatsSyncDto
 *
 * RPG attributes are handled directly via GamificationRepository (no legacy DTO needed).
 */
object PortalPullAdapter {

    /**
     * Convert a portal workout session (1 workout with N exercises) to N mobile
     * WorkoutSession rows (1 per exercise). This is the reverse of the push
     * adapter's grouping logic.
     *
     * Weight convention: the Supabase DB stores per-cable weight values. The portal
     * UI multiplies by WEIGHT_MULTIPLIER (2) for display only. The pull Edge Function
     * returns raw DB values, so PullSetDto.weightKg is already per-cable — no division needed.
     *
     * @param portalSession The pulled workout session from the portal
     * @param profileId The local profile to assign these sessions to
     * @return List of WorkoutSession domain objects, one per exercise
     */
    fun toWorkoutSessions(portalSession: PullWorkoutSessionDto, profileId: String): List<WorkoutSession> {
        if (portalSession.exercises.isEmpty()) return emptyList()

        val timestamp = try {
            kotlin.time.Instant.parse(portalSession.startedAt ?: return emptyList())
                .toEpochMilliseconds()
        } catch (_: Exception) {
            return emptyList()
        }

        val mobileMode = portalModeToMobileMode(portalSession.workoutMode ?: "OLD_SCHOOL")
        val exerciseCount = maxOf(portalSession.exerciseCount, portalSession.exercises.size, 1)

        return portalSession.exercises.map { exercise ->
            val totalReps = exercise.sets.sumOf { it.actualReps }
            val maxWeight = exercise.sets.maxOfOrNull { it.weightKg } ?: 0f

            WorkoutSession(
                id = exercise.id,
                timestamp = timestamp,
                mode = mobileMode,
                reps = exercise.sets.firstOrNull()?.targetReps ?: totalReps / maxOf(exercise.sets.size, 1),
                weightPerCableKg = maxWeight, // Already per-cable from DB
                duration = (portalSession.durationSeconds * 1000L) / exerciseCount, // seconds → ms
                totalReps = totalReps,
                warmupReps = 0, // Portal doesn't distinguish warmup vs working
                workingReps = totalReps,
                exerciseId = null, // No catalog ID from portal; requires local catalog lookup
                exerciseName = exercise.name,
                routineSessionId = portalSession.id,
                routineName = portalSession.routineName,
                heaviestLiftKg = maxWeight,
                totalVolumeKg = null, // Let effectiveTotalVolumeKg() compute from weightPerCableKg * cableCount * totalReps
                cableCount = null, // Let effectiveTotalVolumeKg() use session-level cableCount if available
                profileId = profileId,
            )
        }
    }

    /**
     * Convert portal routine DTO to legacy RoutineSyncDto for merge.
     * Note: routine exercises are NOT part of RoutineSyncDto — they're handled
     * separately by SyncRepository.mergePortalRoutines().
     */
    fun toRoutineSyncDto(routine: PullRoutineDto): RoutineSyncDto {
        val now = currentTimeMillis()
        return RoutineSyncDto(
            clientId = routine.id,
            serverId = routine.id, // Portal ID IS the server ID
            name = routine.name,
            description = routine.description,
            deletedAt = null,
            createdAt = now, // Portal doesn't track created_at on routines
            updatedAt = now, // Portal doesn't track updated_at on routines
        )
    }

    /**
     * Convert portal badge DTO to legacy EarnedBadgeSyncDto for merge.
     */
    fun toBadgeSyncDto(badge: PullBadgeDto): EarnedBadgeSyncDto {
        val earnedAtEpoch = try {
            kotlin.time.Instant.parse(badge.earnedAt).toEpochMilliseconds()
        } catch (_: Exception) {
            currentTimeMillis()
        }
        val now = currentTimeMillis()
        return EarnedBadgeSyncDto(
            clientId = badge.badgeId, // Use badgeId as clientId (badges are identified by badgeId)
            serverId = badge.badgeId,
            badgeId = badge.badgeId,
            earnedAt = earnedAtEpoch,
            deletedAt = null,
            createdAt = earnedAtEpoch,
            updatedAt = now,
        )
    }

    /**
     * Convert portal gamification stats DTO to legacy GamificationStatsSyncDto for merge.
     */
    fun toGamificationStatsSyncDto(stats: PullGamificationStatsDto): GamificationStatsSyncDto {
        val now = currentTimeMillis()
        return GamificationStatsSyncDto(
            clientId = "gamification_stats_1", // Singleton row
            totalWorkouts = stats.totalWorkouts,
            totalReps = stats.totalReps,
            totalVolumeKg = stats.totalVolumeKg,
            longestStreak = stats.longestStreak,
            currentStreak = stats.currentStreak,
            updatedAt = now,
        )
    }

    /**
     * Convert portal SCREAMING_SNAKE mode string to mobile DB format.
     * Portal sends "OLD_SCHOOL", mobile stores "OldSchool".
     */
    fun portalModeToMobileMode(portalMode: String): String = when (ProgramMode.fromSyncString(portalMode)) {
        ProgramMode.OldSchool -> "OldSchool"
        ProgramMode.Pump -> "Pump"
        ProgramMode.TUT -> "TUT"
        ProgramMode.TUTBeast -> "TUTBeast"
        ProgramMode.EccentricOnly -> "EccentricOnly"
        ProgramMode.Echo -> "Echo"
        null -> "OldSchool"
    }

    /**
     * Convert a pulled personal record DTO to the legacy PersonalRecordSyncDto
     * used by SyncRepository merge methods.
     *
     * Weight convention: value is stored per-cable in DB. No multiplication needed.
     */
    fun toPersonalRecordSyncDto(pr: PullPersonalRecordDto): PersonalRecordSyncDto {
        val now = currentTimeMillis()
        return PersonalRecordSyncDto(
            clientId = pr.id,
            serverId = pr.id,
            exerciseId = pr.id, // PR records don't have a separate exerciseId
            exerciseName = pr.exerciseName,
            weight = pr.value.toFloat(),
            reps = pr.reps ?: 0,
            oneRepMax = 0f, // Portal doesn't send computed 1RM
            achievedAt = pr.achievedAt?.let {
                try {
                    kotlinx.datetime.Instant.parse(it).toEpochMilliseconds()
                } catch (_: Exception) { now }
            } ?: now,
            workoutMode = pr.recordType,
            prType = pr.recordType,
            phase = pr.workoutPhase ?: "COMBINED",
            volume = pr.value.toFloat(),
            deletedAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Parse portal eccentricLoad string to integer percentage.
     * Portal sends enum names like "LOAD_100", "LOAD_150", or null.
     */
    fun parseEccentricLoad(portalValue: String?): Long {
        if (portalValue == null) return 100L
        // Try parsing "LOAD_XXX" format
        val numericPart = portalValue.removePrefix("LOAD_").toLongOrNull()
        if (numericPart != null) return numericPart
        // Try direct numeric
        return portalValue.toLongOrNull() ?: 100L
    }

    /**
     * Parse portal echoLevel string to integer index.
     * Portal sends enum names like "HARD", "HARDER", "HARDEST", "EPIC", or null.
     */
    fun parseEchoLevel(portalValue: String?): Long = when (portalValue?.uppercase()) {
        "HARD" -> 0L
        "HARDER" -> 1L
        "HARDEST" -> 2L
        "EPIC" -> 3L
        else -> 1L // Default HARDER
    }
}
