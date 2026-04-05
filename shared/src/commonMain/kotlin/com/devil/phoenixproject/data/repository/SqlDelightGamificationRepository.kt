package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.model.RpgInput
import com.devil.phoenixproject.domain.model.RpgProfile
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * SQLDelight implementation of GamificationRepository.
 * All methods are profile-scoped to support multi-profile isolation.
 */
class SqlDelightGamificationRepository(db: VitruvianDatabase) : GamificationRepository {
    private val queries = db.vitruvianDatabaseQueries

    override fun getEarnedBadges(profileId: String): Flow<List<EarnedBadge>> = queries.selectAllEarnedBadges(profileId = profileId)
        .asFlow()
        .mapToList(Dispatchers.IO)
        .map { badges ->
            badges.map { db ->
                EarnedBadge(
                    id = db.id,
                    badgeId = db.badgeId,
                    earnedAt = db.earnedAt,
                    celebratedAt = db.celebratedAt,
                )
            }
        }

    override fun getStreakInfo(profileId: String): Flow<StreakInfo> = queries.selectGamificationStats(profileId = profileId)
        .asFlow()
        .mapToOneOrNull(Dispatchers.IO)
        .map { stats ->
            if (stats == null) {
                StreakInfo.EMPTY
            } else {
                val lastWorkoutDate = stats.lastWorkoutDate
                val today = Clock.System.now().toLocalDateTime(
                    TimeZone.currentSystemDefault(),
                ).date
                val isAtRisk = if (lastWorkoutDate != null) {
                    val lastDate = Instant.fromEpochMilliseconds(lastWorkoutDate)
                        .toLocalDateTime(TimeZone.currentSystemDefault()).date
                    lastDate < today && stats.currentStreak > 0
                } else {
                    false
                }

                StreakInfo(
                    currentStreak = stats.currentStreak.toInt(),
                    longestStreak = stats.longestStreak.toInt(),
                    streakStartDate = stats.streakStartDate,
                    lastWorkoutDate = stats.lastWorkoutDate,
                    isAtRisk = isAtRisk,
                )
            }
        }

    override fun getGamificationStats(profileId: String): Flow<GamificationStats> = queries.selectGamificationStats(profileId = profileId)
        .asFlow()
        .mapToOneOrNull(Dispatchers.IO)
        .map { stats ->
            if (stats == null) {
                GamificationStats.EMPTY
            } else {
                GamificationStats(
                    totalWorkouts = stats.totalWorkouts.toInt(),
                    totalReps = stats.totalReps.toInt(),
                    totalVolumeKg = stats.totalVolumeKg,
                    longestStreak = stats.longestStreak.toInt(),
                    currentStreak = stats.currentStreak.toInt(),
                    uniqueExercisesUsed = stats.uniqueExercisesUsed.toInt(),
                    prsAchieved = stats.prsAchieved.toInt(),
                    lastUpdated = stats.lastUpdated,
                )
            }
        }

    override fun getUncelebratedBadges(profileId: String): Flow<List<EarnedBadge>> = queries.selectUncelebratedBadges(profileId = profileId)
        .asFlow()
        .mapToList(Dispatchers.IO)
        .map { badges ->
            badges.map { db ->
                EarnedBadge(
                    id = db.id,
                    badgeId = db.badgeId,
                    earnedAt = db.earnedAt,
                    celebratedAt = db.celebratedAt,
                )
            }
        }

    override suspend fun isBadgeEarned(badgeId: String, profileId: String): Boolean = withContext(Dispatchers.IO) {
        queries.selectEarnedBadgeById(badgeId, profileId = profileId).executeAsOneOrNull() !=
            null
    }

    override suspend fun awardBadge(badgeId: String, profileId: String): Boolean = withContext(Dispatchers.IO) {
        val existing = queries.selectEarnedBadgeById(
            badgeId,
            profileId = profileId,
        ).executeAsOneOrNull()
        if (existing != null) {
            false // Already earned
        } else {
            val now = Clock.System.now().toEpochMilliseconds()
            queries.insertEarnedBadge(badgeId, now, profileId = profileId)
            Logger.d { "Badge awarded: $badgeId (profile=$profileId)" }
            true
        }
    }

    override suspend fun markBadgeCelebrated(badgeId: String, profileId: String) {
        withContext(Dispatchers.IO) {
            val now = Clock.System.now().toEpochMilliseconds()
            queries.markBadgeCelebrated(now, badgeId, profileId = profileId)
        }
    }

    override suspend fun markBadgesCelebrated(badgeIds: List<String>, profileId: String) {
        if (badgeIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            val now = Clock.System.now().toEpochMilliseconds()
            queries.transaction {
                queries.markBadgesCelebrated(now, badgeIds, profileId = profileId)
            }
        }
    }

    override suspend fun updateStats(profileId: String) {
        withContext(Dispatchers.IO) {
            try {
                val totalWorkouts = queries.countTotalWorkouts(profileId = profileId).executeAsOne()
                val totalReps =
                    queries.countTotalReps(profileId = profileId).executeAsOneOrNull()?.SUM ?: 0L
                val totalVolume =
                    queries.countTotalVolume(profileId = profileId).executeAsOneOrNull()?.SUM ?: 0.0
                val uniqueExercises = queries.countUniqueExercises(
                    profileId = profileId,
                ).executeAsOne()
                val prsAchieved = queries.countPersonalRecords(profileId = profileId).executeAsOne()

                // Calculate streak - selectWorkoutDates returns List<String> directly
                val workoutDates = queries.selectWorkoutDates(profileId = profileId).executeAsList()
                val (currentStreak, longestStreak, streakStart, lastWorkout) = calculateStreaks(
                    workoutDates,
                )

                val now = Clock.System.now().toEpochMilliseconds()

                // Use profileId hashCode as stable integer id for INSERT OR REPLACE
                val stableId = profileId.hashCode().toLong()

                queries.upsertGamificationStats(
                    id = stableId,
                    totalWorkouts = totalWorkouts,
                    totalReps = totalReps,
                    totalVolumeKg = totalVolume.toLong(),
                    longestStreak = longestStreak.toLong(),
                    currentStreak = currentStreak.toLong(),
                    uniqueExercisesUsed = uniqueExercises,
                    prsAchieved = prsAchieved,
                    lastWorkoutDate = lastWorkout,
                    streakStartDate = streakStart,
                    lastUpdated = now,
                    profileId = profileId,
                )

                Logger.d {
                    "Gamification stats updated (profile=$profileId): workouts=$totalWorkouts, reps=$totalReps, streak=$currentStreak"
                }
            } catch (e: Exception) {
                Logger.e(e) { "Error updating gamification stats" }
            }
        }
    }

    /**
     * Calculate current and longest streaks from workout dates
     */
    private fun calculateStreaks(workoutDates: List<String>): StreakData {
        if (workoutDates.isEmpty()) {
            return StreakData(0, 0, null, null)
        }

        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val dates = workoutDates.mapNotNull { dateStr ->
            try {
                LocalDate.parse(dateStr)
            } catch (_: Exception) {
                null
            }
        }.sortedDescending()

        if (dates.isEmpty()) {
            return StreakData(0, 0, null, null)
        }

        val lastWorkoutDate = dates.first()
        val lastWorkoutMs = lastWorkoutDate.atStartOfDayIn(
            TimeZone.currentSystemDefault(),
        ).toEpochMilliseconds()

        // Check if streak is still active (last workout was today or yesterday)
        val daysSinceLastWorkout = today.toEpochDays() - lastWorkoutDate.toEpochDays()
        if (daysSinceLastWorkout > 1) {
            // Streak is broken
            return StreakData(0, calculateLongestStreak(dates), null, lastWorkoutMs)
        }

        // Calculate current streak
        var currentStreak = 1
        var streakStartDate = lastWorkoutDate
        var previousDate = lastWorkoutDate

        for (i in 1 until dates.size) {
            val currentDate = dates[i]
            val dayDiff = previousDate.toEpochDays() - currentDate.toEpochDays()

            if (dayDiff == 1L) {
                currentStreak++
                streakStartDate = currentDate
                previousDate = currentDate
            } else if (dayDiff > 1L) {
                break // Streak broken
            }
            // dayDiff == 0 means same day, skip
        }

        val longestStreak = maxOf(currentStreak, calculateLongestStreak(dates))
        val streakStartMs = streakStartDate.atStartOfDayIn(
            TimeZone.currentSystemDefault(),
        ).toEpochMilliseconds()

        return StreakData(currentStreak, longestStreak, streakStartMs, lastWorkoutMs)
    }

    private fun calculateLongestStreak(dates: List<LocalDate>): Int {
        if (dates.isEmpty()) return 0

        var longest = 1
        var current = 1
        val sortedDates = dates.distinct().sortedDescending()

        for (i in 1 until sortedDates.size) {
            val dayDiff = sortedDates[i - 1].toEpochDays() - sortedDates[i].toEpochDays()
            if (dayDiff == 1L) {
                current++
                longest = maxOf(longest, current)
            } else if (dayDiff > 1L) {
                current = 1
            }
        }

        return longest
    }

    private data class StreakData(val currentStreak: Int, val longestStreak: Int, val streakStartMs: Long?, val lastWorkoutMs: Long?)

    /**
     * Count workouts completed in the current week (Monday to Sunday)
     */
    private fun countWorkoutsInCurrentWeek(profileId: String): Int {
        val now = Clock.System.now()
        val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date

        // Find start of current week (Monday)
        val dayOfWeek = today.dayOfWeek.ordinal // Monday = 0, Sunday = 6
        val weekStart = LocalDate.fromEpochDays(today.toEpochDays() - dayOfWeek)
        val weekStartMs = weekStart.atStartOfDayIn(
            TimeZone.currentSystemDefault(),
        ).toEpochMilliseconds()

        // Count sessions with timestamp >= weekStartMs
        val sessions = queries.selectAllSessions(profileId = profileId).executeAsList()
        return sessions.count { it.timestamp >= weekStartMs }
    }

    /**
     * Get the maximum volume (kg) lifted in any single workout session
     * Prefer measured totalVolumeKg when available (v0.2.1+), otherwise fallback using stored cableCount.
     * Legacy rows without cable metadata default conservatively to single-cable volume.
     */
    private fun getMaxSingleSessionVolume(profileId: String): Int {
        val sessions = queries.selectAllSessions(profileId = profileId).executeAsList()
        if (sessions.isEmpty()) return 0

        return sessions.maxOfOrNull { session ->
            (
                session.totalVolumeKg
                    ?: (
                        session.totalReps * session.weightPerCableKg *
                            (session.cableCount ?: 1L).toDouble()
                        )
                ).toInt()
        } ?: 0
    }

    /**
     * Check if any workout was completed within the specified hour range
     * @param hourStart Start hour (0-23, inclusive)
     * @param hourEnd End hour (0-23, inclusive)
     */
    private fun hasWorkoutAtTime(hourStart: Int, hourEnd: Int, profileId: String): Boolean {
        val sessions = queries.selectAllSessions(profileId = profileId).executeAsList()

        return sessions.any { session ->
            val sessionTime = Instant.fromEpochMilliseconds(session.timestamp)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val hour = sessionTime.hour

            if (hourStart <= hourEnd) {
                // Normal range (e.g., 6 to 9)
                hour in hourStart..hourEnd
            } else {
                // Wrapping range (e.g., 22 to 5 for late night/early morning)
                hour >= hourStart || hour <= hourEnd
            }
        }
    }

    /**
     * Count workouts completed within a specific time range
     */
    private fun countWorkoutsAtTime(hourStart: Int, hourEnd: Int, profileId: String): Int {
        val sessions = queries.selectAllSessions(profileId = profileId).executeAsList()

        return sessions.count { session ->
            val sessionTime = Instant.fromEpochMilliseconds(session.timestamp)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val hour = sessionTime.hour

            if (hourStart <= hourEnd) {
                hour in hourStart until hourEnd
            } else {
                hour >= hourStart || hour < hourEnd
            }
        }
    }

    /**
     * Get the count of workouts in a specific mode
     */
    private fun getWorkoutCountByMode(modeName: String, profileId: String): Int {
        val modeCounts = queries.countWorkoutsByMode(profileId = profileId).executeAsList()
        // Handle Echo mode specially - it's stored as "Echo" in DB
        return modeCounts.find {
            it.mode.equals(modeName, ignoreCase = true) ||
                (modeName == "Echo" && it.mode.startsWith("Echo", ignoreCase = true))
        }?.count?.toInt() ?: 0
    }

    /**
     * Get the count of unique workout modes used
     */
    private fun getUniqueWorkoutModesCount(profileId: String): Int {
        val modes = queries.selectUniqueWorkoutModes(profileId = profileId).executeAsList()
        // Count distinct base modes (Echo variants count as 1)
        val baseModes = modes.map { mode ->
            when {
                mode.startsWith("Echo", ignoreCase = true) -> "Echo"
                mode.startsWith("TUT Beast", ignoreCase = true) -> "TUT Beast"
                else -> mode
            }
        }.distinct()
        return baseModes.size
    }

    /**
     * Check if all 6 workout modes have been used
     */
    private fun hasUsedAllWorkoutModes(profileId: String): Boolean {
        val modes = queries.selectUniqueWorkoutModes(profileId = profileId).executeAsList()
        val baseModes = modes.map { mode ->
            when {
                mode.startsWith("Echo", ignoreCase = true) -> "Echo"
                mode.startsWith("TUT Beast", ignoreCase = true) -> "TUT Beast"
                else -> mode
            }
        }.distinct()

        val requiredModes =
            setOf("Old School", "Pump", "TUT", "TUT Beast", "Eccentric Only", "Echo")
        return requiredModes.all { required ->
            baseModes.any { it.equals(required, ignoreCase = true) }
        }
    }

    /**
     * Get peak power from all workouts
     */
    private fun getPeakPower(): Int {
        val result = queries.selectPeakPower().executeAsOneOrNull()
        return result?.peakPower?.toInt() ?: 0
    }

    /**
     * Get count of unique muscle groups trained
     */
    private fun getUniqueMuscleGroupsCount(profileId: String): Int {
        val muscleGroups = queries.selectUniqueMuscleGroupsFromWorkouts(
            profileId = profileId,
        ).executeAsList()
        return muscleGroups.size
    }

    /**
     * Get count of weekend workouts
     */
    private fun getWeekendWorkoutsCount(profileId: String): Int = queries.countWeekendWorkouts(profileId = profileId).executeAsOne().toInt()

    /**
     * Get count of completed routine sessions
     */
    private fun getCompletedRoutinesCount(profileId: String): Int = queries.countCompletedRoutineSessions(profileId = profileId).executeAsOne().toInt()

    /**
     * Get count of created routines
     */
    private fun getCreatedRoutinesCount(profileId: String): Int = queries.countCreatedRoutines(profileId = profileId).executeAsOne().toInt()

    /**
     * Check if user came back after a break of specified days
     * This is tracked by checking if there was a gap >= breakDays between any two workouts
     */
    private fun hasComebackAfterBreak(breakDays: Int, profileId: String): Boolean {
        val sessions = queries.selectAllSessions(profileId = profileId).executeAsList()
        if (sessions.size < 2) return false

        val sortedSessions = sessions.sortedBy { it.timestamp }

        for (i in 1 until sortedSessions.size) {
            val prevDate = Instant.fromEpochMilliseconds(sortedSessions[i - 1].timestamp)
                .toLocalDateTime(TimeZone.currentSystemDefault()).date
            val currDate = Instant.fromEpochMilliseconds(sortedSessions[i].timestamp)
                .toLocalDateTime(TimeZone.currentSystemDefault()).date

            val daysBetween = currDate.toEpochDays() - prevDate.toEpochDays()
            if (daysBetween >= breakDays) {
                return true
            }
        }
        return false
    }

    /**
     * Check if user saved their streak (workout when at risk)
     * This happens when user works out on the same day their streak would break
     */
    private fun hasSavedStreak(profileId: String): Boolean {
        // Check if there's at least one streak entry in history and current streak > 0
        val streakHistory = queries.selectStreakBreakCount(profileId = profileId).executeAsOne()
        val stats =
            queries.selectGamificationStats(profileId = profileId).executeAsOneOrNull()
                ?: return false

        // If we have streak history and a current streak, user has saved streaks before
        return streakHistory > 0 && stats.currentStreak > 0
    }

    /**
     * Check if user has rebuilt a streak after losing one
     */
    private fun hasRebuiltStreak(requiredDays: Int, profileId: String): Boolean {
        val streakBreaks = queries.selectStreakBreakCount(profileId = profileId).executeAsOne()
        val stats =
            queries.selectGamificationStats(profileId = profileId).executeAsOneOrNull()
                ?: return false

        // User must have had a previous streak break and rebuilt to required length
        return streakBreaks > 0 && stats.currentStreak >= requiredDays
    }

    override suspend fun getRpgInput(profileId: String): RpgInput = withContext(Dispatchers.IO) {
        val totalWorkouts = queries.countTotalWorkouts(
            profileId = profileId,
        ).executeAsOne().toInt()
        val totalReps = (
            queries.countTotalReps(profileId = profileId).executeAsOneOrNull()?.SUM
                ?: 0L
            ).toInt()
        val totalVolumeKg =
            queries.countTotalVolume(profileId = profileId).executeAsOneOrNull()?.SUM ?: 0.0
        val uniqueExercises = queries.countUniqueExercises(
            profileId = profileId,
        ).executeAsOne().toInt()
        val personalRecords = queries.countPersonalRecords(
            profileId = profileId,
        ).executeAsOne().toInt()
        val badgesEarned = queries.countEarnedBadges(
            profileId = profileId,
        ).executeAsOne().toInt()

        val maxWeightLiftedKg =
            queries.selectMaxWeightLifted(profileId = profileId).executeAsOneOrNull()?.MAX
                ?: 0.0
        val avgWorkingWeightKg =
            queries.selectAvgWorkingWeight(profileId = profileId).executeAsOneOrNull()?.AVG
                ?: 0.0

        // Peak power: try RepMetric first, fall back to MetricSample
        val peakRepPower = try {
            queries.selectPeakRepPower().executeAsOneOrNull()?.MAX
        } catch (_: Exception) {
            null // RepMetric table may not exist due to migration gap
        }
        val peakPowerWatts = peakRepPower
            ?: queries.selectPeakPower().executeAsOneOrNull()?.peakPower
            ?: 0.0

        val trainingDays = queries.countTrainingDays(
            profileId = profileId,
        ).executeAsOne().toInt()

        // Streak data from GamificationStats
        val stats = queries.selectGamificationStats(profileId = profileId).executeAsOneOrNull()
        val currentStreak = stats?.currentStreak?.toInt() ?: 0
        val longestStreak = stats?.longestStreak?.toInt() ?: 0

        RpgInput(
            maxWeightLiftedKg = maxWeightLiftedKg,
            totalVolumeKg = totalVolumeKg,
            totalWorkouts = totalWorkouts,
            totalReps = totalReps,
            uniqueExercises = uniqueExercises,
            personalRecords = personalRecords,
            peakPowerWatts = peakPowerWatts,
            avgWorkingWeightKg = avgWorkingWeightKg,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            trainingDays = trainingDays,
            badgesEarned = badgesEarned,
        )
    }

    override suspend fun saveRpgProfile(profile: RpgProfile, profileId: String) {
        withContext(Dispatchers.IO) {
            // Use profileId hashCode as stable integer id for INSERT OR REPLACE
            val stableId = profileId.hashCode().toLong()
            queries.upsertRpgAttributes(
                id = stableId,
                strength = profile.strength.toLong(),
                power = profile.power.toLong(),
                stamina = profile.stamina.toLong(),
                consistency = profile.consistency.toLong(),
                mastery = profile.mastery.toLong(),
                characterClass = profile.characterClass.name,
                lastComputed = profile.lastComputed,
                profileId = profileId,
            )
        }
    }

    override suspend fun checkAndAwardBadges(profileId: String): List<Badge> {
        return withContext(Dispatchers.IO) {
            val newlyAwarded = mutableListOf<Badge>()

            // Get current stats
            val stats =
                queries.selectGamificationStats(profileId = profileId).executeAsOneOrNull()
                    ?: return@withContext emptyList()

            // Check each badge
            for (badge in BadgeDefinitions.allBadges) {
                if (isBadgeEarned(badge.id, profileId)) continue

                val isEarned = checkBadgeRequirement(badge, stats, profileId)
                if (isEarned) {
                    val awarded = awardBadge(badge.id, profileId)
                    if (awarded) {
                        newlyAwarded.add(badge)
                        Logger.d { "New badge earned: ${badge.name} (profile=$profileId)" }
                    }
                }
            }

            newlyAwarded
        }
    }

    private fun checkBadgeRequirement(
        badge: Badge,
        stats: com.devil.phoenixproject.database.GamificationStats,
        profileId: String,
    ): Boolean = when (val req = badge.requirement) {
        is BadgeRequirement.StreakDays ->
            stats.currentStreak >= req.days ||
                stats.longestStreak >= req.days

        is BadgeRequirement.TotalWorkouts -> stats.totalWorkouts >= req.count

        is BadgeRequirement.TotalReps -> stats.totalReps >= req.count

        is BadgeRequirement.PRsAchieved -> stats.prsAchieved >= req.count

        is BadgeRequirement.UniqueExercises -> stats.uniqueExercisesUsed >= req.count

        is BadgeRequirement.TotalVolume -> stats.totalVolumeKg >= req.kgLifted

        is BadgeRequirement.ConsecutiveWeeks -> {
            // Would need more complex calculation
            stats.longestStreak >= (req.weeks * 7)
        }

        is BadgeRequirement.WorkoutsInWeek -> {
            val workoutsThisWeek = countWorkoutsInCurrentWeek(profileId)
            workoutsThisWeek >= req.count
        }

        is BadgeRequirement.SingleWorkoutVolume -> {
            val maxSessionVolume = getMaxSingleSessionVolume(profileId)
            maxSessionVolume >= req.kgLifted
        }

        is BadgeRequirement.WorkoutAtTime -> {
            hasWorkoutAtTime(req.hourStart, req.hourEnd, profileId)
        }

        is BadgeRequirement.WorkoutsAtTimeCount -> {
            countWorkoutsAtTime(req.hourStart, req.hourEnd, profileId) >= req.count
        }

        is BadgeRequirement.WorkoutModeCount -> {
            getWorkoutCountByMode(req.modeName, profileId) >= req.count
        }

        is BadgeRequirement.AllWorkoutModes -> {
            hasUsedAllWorkoutModes(profileId)
        }

        is BadgeRequirement.PeakPower -> {
            getPeakPower() >= req.watts
        }

        is BadgeRequirement.UniqueMuscleGroups -> {
            getUniqueMuscleGroupsCount(profileId) >= req.count
        }

        is BadgeRequirement.ComebackAfterBreak -> {
            hasComebackAfterBreak(req.breakDays, profileId)
        }

        is BadgeRequirement.StreakSaved -> {
            hasSavedStreak(profileId)
        }

        is BadgeRequirement.StreakRebuilt -> {
            hasRebuiltStreak(req.days, profileId)
        }

        is BadgeRequirement.WeekendWorkouts -> {
            getWeekendWorkoutsCount(profileId) >= req.count
        }

        is BadgeRequirement.RoutinesCompleted -> {
            getCompletedRoutinesCount(profileId) >= req.count
        }

        is BadgeRequirement.RoutinesCreated -> {
            getCreatedRoutinesCount(profileId) >= req.count
        }

        is BadgeRequirement.QualityStreak -> {
            // Quality streak badges are awarded directly by GamificationManager.processSetQualityEvent()
            // which tracks session-scoped consecutive quality sets. Not evaluated via DB stats.
            false
        }
    }

    override suspend fun getBadgeProgress(badgeId: String, profileId: String): Pair<Int, Int>? {
        return withContext(Dispatchers.IO) {
            val badge = BadgeDefinitions.getBadgeById(badgeId) ?: return@withContext null
            val stats = queries.selectGamificationStats(profileId = profileId).executeAsOneOrNull()
                ?: return@withContext Pair(0, badge.getTargetValue())

            val current = when (val req = badge.requirement) {
                is BadgeRequirement.StreakDays -> maxOf(
                    stats.currentStreak.toInt(),
                    stats.longestStreak.toInt(),
                )

                is BadgeRequirement.TotalWorkouts -> stats.totalWorkouts.toInt()

                is BadgeRequirement.TotalReps -> stats.totalReps.toInt()

                is BadgeRequirement.PRsAchieved -> stats.prsAchieved.toInt()

                is BadgeRequirement.UniqueExercises -> stats.uniqueExercisesUsed.toInt()

                is BadgeRequirement.TotalVolume -> stats.totalVolumeKg.toInt()

                is BadgeRequirement.ConsecutiveWeeks -> stats.longestStreak.toInt() / 7

                is BadgeRequirement.WorkoutsInWeek -> countWorkoutsInCurrentWeek(profileId)

                is BadgeRequirement.SingleWorkoutVolume -> getMaxSingleSessionVolume(profileId)

                is BadgeRequirement.WorkoutAtTime -> if (hasWorkoutAtTime(
                        req.hourStart,
                        req.hourEnd,
                        profileId,
                    )
                ) {
                    1
                } else {
                    0
                }

                is BadgeRequirement.WorkoutsAtTimeCount -> countWorkoutsAtTime(
                    req.hourStart,
                    req.hourEnd,
                    profileId,
                )

                is BadgeRequirement.WorkoutModeCount -> getWorkoutCountByMode(
                    req.modeName,
                    profileId,
                )

                is BadgeRequirement.AllWorkoutModes -> getUniqueWorkoutModesCount(profileId)

                is BadgeRequirement.PeakPower -> getPeakPower()

                is BadgeRequirement.UniqueMuscleGroups -> getUniqueMuscleGroupsCount(profileId)

                is BadgeRequirement.ComebackAfterBreak -> if (hasComebackAfterBreak(
                        req.breakDays,
                        profileId,
                    )
                ) {
                    1
                } else {
                    0
                }

                is BadgeRequirement.StreakSaved -> if (hasSavedStreak(profileId)) 1 else 0

                is BadgeRequirement.StreakRebuilt -> if (hasRebuiltStreak(
                        req.days,
                        profileId,
                    )
                ) {
                    req.days
                } else {
                    stats.currentStreak.toInt()
                }

                is BadgeRequirement.WeekendWorkouts -> getWeekendWorkoutsCount(profileId)

                is BadgeRequirement.RoutinesCompleted -> getCompletedRoutinesCount(profileId)

                is BadgeRequirement.RoutinesCreated -> getCreatedRoutinesCount(profileId)

                is BadgeRequirement.QualityStreak -> 0 // Session-scoped, not tracked in DB
            }

            Pair(current, badge.getTargetValue())
        }
    }

    override suspend fun getAllBadgesWithProgress(profileId: String): List<BadgeWithProgress> = withContext(Dispatchers.IO) {
        val earnedBadges = queries.selectAllEarnedBadges(profileId = profileId).executeAsList()
            .associateBy { it.badgeId }
        val stats = queries.selectGamificationStats(profileId = profileId).executeAsOneOrNull()

        BadgeDefinitions.allBadges.map { badge ->
            val earned = earnedBadges[badge.id]
            val (current, target) = if (stats != null) {
                val progress =
                    getBadgeProgress(badge.id, profileId) ?: Pair(0, badge.getTargetValue())
                progress
            } else {
                Pair(0, badge.getTargetValue())
            }

            BadgeWithProgress(
                badge = badge,
                isEarned = earned != null,
                earnedAt = earned?.earnedAt,
                currentProgress = current,
                targetProgress = target,
            )
        }
    }
}
