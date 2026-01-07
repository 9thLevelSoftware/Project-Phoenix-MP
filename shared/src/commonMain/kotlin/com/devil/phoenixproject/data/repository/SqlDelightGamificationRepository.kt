package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * SQLDelight implementation of GamificationRepository
 */
class SqlDelightGamificationRepository(
    db: VitruvianDatabase
) : GamificationRepository {
    private val queries = db.vitruvianDatabaseQueries

    override fun getEarnedBadges(): Flow<List<EarnedBadge>> {
        return queries.selectAllEarnedBadges()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { badges ->
                badges.map { db ->
                    EarnedBadge(
                        id = db.id,
                        badgeId = db.badgeId,
                        earnedAt = db.earnedAt,
                        celebratedAt = db.celebratedAt
                    )
                }
            }
    }

    override fun getStreakInfo(): Flow<StreakInfo> {
        return queries.selectGamificationStats()
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { stats ->
                if (stats == null) {
                    StreakInfo.EMPTY
                } else {
                    val lastWorkoutDate = stats.lastWorkoutDate
                    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                    val isAtRisk = if (lastWorkoutDate != null) {
                        val lastDate = Instant.fromEpochMilliseconds(lastWorkoutDate)
                            .toLocalDateTime(TimeZone.currentSystemDefault()).date
                        lastDate < today && stats.currentStreak > 0
                    } else false

                    StreakInfo(
                        currentStreak = stats.currentStreak.toInt(),
                        longestStreak = stats.longestStreak.toInt(),
                        streakStartDate = stats.streakStartDate,
                        lastWorkoutDate = stats.lastWorkoutDate,
                        isAtRisk = isAtRisk
                    )
                }
            }
    }

    override fun getGamificationStats(): Flow<GamificationStats> {
        return queries.selectGamificationStats()
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
                        lastUpdated = stats.lastUpdated
                    )
                }
            }
    }

    override fun getUncelebratedBadges(): Flow<List<EarnedBadge>> {
        return queries.selectUncelebratedBadges()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { badges ->
                badges.map { db ->
                    EarnedBadge(
                        id = db.id,
                        badgeId = db.badgeId,
                        earnedAt = db.earnedAt,
                        celebratedAt = db.celebratedAt
                    )
                }
            }
    }

    override suspend fun isBadgeEarned(badgeId: String): Boolean {
        return withContext(Dispatchers.IO) {
            queries.selectEarnedBadgeById(badgeId).executeAsOneOrNull() != null
        }
    }

    override suspend fun awardBadge(badgeId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val existing = queries.selectEarnedBadgeById(badgeId).executeAsOneOrNull()
            if (existing != null) {
                false // Already earned
            } else {
                val now = Clock.System.now().toEpochMilliseconds()
                queries.insertEarnedBadge(badgeId, now)
                Logger.d { "Badge awarded: $badgeId" }
                true
            }
        }
    }

    override suspend fun markBadgeCelebrated(badgeId: String) {
        withContext(Dispatchers.IO) {
            val now = Clock.System.now().toEpochMilliseconds()
            queries.markBadgeCelebrated(now, badgeId)
        }
    }

    override suspend fun markBadgesCelebrated(badgeIds: List<String>) {
        if (badgeIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            val now = Clock.System.now().toEpochMilliseconds()
            queries.transaction {
                queries.markBadgesCelebrated(now, badgeIds)
            }
        }
    }

    override suspend fun updateStats() {
        withContext(Dispatchers.IO) {
            try {
                val totalWorkouts = queries.countTotalWorkouts().executeAsOne()
                val totalReps = queries.countTotalReps().executeAsOneOrNull()?.SUM ?: 0L
                val totalVolume = queries.countTotalVolume().executeAsOneOrNull()?.SUM ?: 0.0
                val uniqueExercises = queries.countUniqueExercises().executeAsOne()
                val prsAchieved = queries.countPersonalRecords().executeAsOne()

                // Calculate streak - selectWorkoutDates returns List<String> directly
                val workoutDates = queries.selectWorkoutDates().executeAsList()
                val (currentStreak, longestStreak, streakStart, lastWorkout) = calculateStreaks(workoutDates)

                val now = Clock.System.now().toEpochMilliseconds()

                queries.upsertGamificationStats(
                    totalWorkouts = totalWorkouts,
                    totalReps = totalReps,
                    totalVolumeKg = totalVolume.toLong(),
                    longestStreak = longestStreak.toLong(),
                    currentStreak = currentStreak.toLong(),
                    uniqueExercisesUsed = uniqueExercises,
                    prsAchieved = prsAchieved,
                    lastWorkoutDate = lastWorkout,
                    streakStartDate = streakStart,
                    lastUpdated = now
                )

                Logger.d { "Gamification stats updated: workouts=$totalWorkouts, reps=$totalReps, streak=$currentStreak" }
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
            } catch (e: Exception) {
                null
            }
        }.sortedDescending()

        if (dates.isEmpty()) {
            return StreakData(0, 0, null, null)
        }

        val lastWorkoutDate = dates.first()
        val lastWorkoutMs = lastWorkoutDate.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

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
        val streakStartMs = streakStartDate.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

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

    private data class StreakData(
        val currentStreak: Int,
        val longestStreak: Int,
        val streakStartMs: Long?,
        val lastWorkoutMs: Long?
    )

    /**
     * Count workouts completed in the current week (Monday to Sunday)
     */
    private fun countWorkoutsInCurrentWeek(): Int {
        val now = Clock.System.now()
        val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date

        // Find start of current week (Monday)
        val dayOfWeek = today.dayOfWeek.ordinal // Monday = 0, Sunday = 6
        val weekStart = LocalDate.fromEpochDays(today.toEpochDays() - dayOfWeek)
        val weekStartMs = weekStart.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

        // Count sessions with timestamp >= weekStartMs
        val sessions = queries.selectAllSessions().executeAsList()
        return sessions.count { it.timestamp >= weekStartMs }
    }

    /**
     * Get the maximum volume (kg) lifted in any single workout session
     * Volume = totalReps * weightPerCableKg * 2 (for both cables)
     */
    private fun getMaxSingleSessionVolume(): Int {
        val sessions = queries.selectAllSessions().executeAsList()
        if (sessions.isEmpty()) return 0

        return sessions.maxOfOrNull { session ->
            (session.totalReps * session.weightPerCableKg * 2).toInt()
        } ?: 0
    }

    /**
     * Check if any workout was completed within the specified hour range
     * @param hourStart Start hour (0-23, inclusive)
     * @param hourEnd End hour (0-23, inclusive)
     */
    private fun hasWorkoutAtTime(hourStart: Int, hourEnd: Int): Boolean {
        val sessions = queries.selectAllSessions().executeAsList()

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
    private fun countWorkoutsAtTime(hourStart: Int, hourEnd: Int): Int {
        val sessions = queries.selectAllSessions().executeAsList()

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
    private fun getWorkoutCountByMode(modeName: String): Int {
        val modeCounts = queries.countWorkoutsByMode().executeAsList()
        // Handle Echo mode specially - it's stored as "Echo" in DB
        return modeCounts.find {
            it.mode.equals(modeName, ignoreCase = true) ||
            (modeName == "Echo" && it.mode.startsWith("Echo", ignoreCase = true))
        }?.count?.toInt() ?: 0
    }

    /**
     * Get the count of unique workout modes used
     */
    private fun getUniqueWorkoutModesCount(): Int {
        val modes = queries.selectUniqueWorkoutModes().executeAsList()
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
    private fun hasUsedAllWorkoutModes(): Boolean {
        val modes = queries.selectUniqueWorkoutModes().executeAsList()
        val baseModes = modes.map { mode ->
            when {
                mode.startsWith("Echo", ignoreCase = true) -> "Echo"
                mode.startsWith("TUT Beast", ignoreCase = true) -> "TUT Beast"
                else -> mode
            }
        }.distinct()

        val requiredModes = setOf("Old School", "Pump", "TUT", "TUT Beast", "Eccentric Only", "Echo")
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
    private fun getUniqueMuscleGroupsCount(): Int {
        val muscleGroups = queries.selectUniqueMuscleGroupsFromWorkouts().executeAsList()
        return muscleGroups.size
    }

    /**
     * Get count of weekend workouts
     */
    private fun getWeekendWorkoutsCount(): Int {
        return queries.countWeekendWorkouts().executeAsOne().toInt()
    }

    /**
     * Get count of completed routine sessions
     */
    private fun getCompletedRoutinesCount(): Int {
        return queries.countCompletedRoutineSessions().executeAsOne().toInt()
    }

    /**
     * Get count of created routines
     */
    private fun getCreatedRoutinesCount(): Int {
        return queries.countCreatedRoutines().executeAsOne().toInt()
    }

    /**
     * Check if user came back after a break of specified days
     * This is tracked by checking if there was a gap >= breakDays between any two workouts
     */
    private fun hasComebackAfterBreak(breakDays: Int): Boolean {
        val sessions = queries.selectAllSessions().executeAsList()
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
    private fun hasSavedStreak(): Boolean {
        // Check if there's at least one streak entry in history and current streak > 0
        val streakHistory = queries.selectStreakBreakCount().executeAsOne()
        val stats = queries.selectGamificationStats().executeAsOneOrNull() ?: return false

        // If we have streak history and a current streak, user has saved streaks before
        return streakHistory > 0 && stats.currentStreak > 0
    }

    /**
     * Check if user has rebuilt a streak after losing one
     */
    private fun hasRebuiltStreak(requiredDays: Int): Boolean {
        val streakBreaks = queries.selectStreakBreakCount().executeAsOne()
        val stats = queries.selectGamificationStats().executeAsOneOrNull() ?: return false

        // User must have had a previous streak break and rebuilt to required length
        return streakBreaks > 0 && stats.currentStreak >= requiredDays
    }

    override suspend fun checkAndAwardBadges(): List<Badge> {
        return withContext(Dispatchers.IO) {
            val newlyAwarded = mutableListOf<Badge>()

            // Get current stats
            val stats = queries.selectGamificationStats().executeAsOneOrNull() ?: return@withContext emptyList()

            // Check each badge
            for (badge in BadgeDefinitions.allBadges) {
                if (isBadgeEarned(badge.id)) continue

                val isEarned = checkBadgeRequirement(badge, stats)
                if (isEarned) {
                    val awarded = awardBadge(badge.id)
                    if (awarded) {
                        newlyAwarded.add(badge)
                        Logger.d { "New badge earned: ${badge.name}" }
                    }
                }
            }

            newlyAwarded
        }
    }

    private suspend fun checkBadgeRequirement(
        badge: Badge,
        stats: com.devil.phoenixproject.database.GamificationStats
    ): Boolean {
        return when (val req = badge.requirement) {
            is BadgeRequirement.StreakDays -> stats.currentStreak >= req.days || stats.longestStreak >= req.days
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
                val workoutsThisWeek = countWorkoutsInCurrentWeek()
                workoutsThisWeek >= req.count
            }
            is BadgeRequirement.SingleWorkoutVolume -> {
                val maxSessionVolume = getMaxSingleSessionVolume()
                maxSessionVolume >= req.kgLifted
            }
            is BadgeRequirement.WorkoutAtTime -> {
                hasWorkoutAtTime(req.hourStart, req.hourEnd)
            }
            is BadgeRequirement.WorkoutsAtTimeCount -> {
                countWorkoutsAtTime(req.hourStart, req.hourEnd) >= req.count
            }
            is BadgeRequirement.WorkoutModeCount -> {
                getWorkoutCountByMode(req.modeName) >= req.count
            }
            is BadgeRequirement.AllWorkoutModes -> {
                hasUsedAllWorkoutModes()
            }
            is BadgeRequirement.PeakPower -> {
                getPeakPower() >= req.watts
            }
            is BadgeRequirement.UniqueMuscleGroups -> {
                getUniqueMuscleGroupsCount() >= req.count
            }
            is BadgeRequirement.ComebackAfterBreak -> {
                hasComebackAfterBreak(req.breakDays)
            }
            is BadgeRequirement.StreakSaved -> {
                hasSavedStreak()
            }
            is BadgeRequirement.StreakRebuilt -> {
                hasRebuiltStreak(req.days)
            }
            is BadgeRequirement.WeekendWorkouts -> {
                getWeekendWorkoutsCount() >= req.count
            }
            is BadgeRequirement.RoutinesCompleted -> {
                getCompletedRoutinesCount() >= req.count
            }
            is BadgeRequirement.RoutinesCreated -> {
                getCreatedRoutinesCount() >= req.count
            }
        }
    }

    override suspend fun getBadgeProgress(badgeId: String): Pair<Int, Int>? {
        return withContext(Dispatchers.IO) {
            val badge = BadgeDefinitions.getBadgeById(badgeId) ?: return@withContext null
            val stats = queries.selectGamificationStats().executeAsOneOrNull()
                ?: return@withContext Pair(0, badge.getTargetValue())

            val current = when (val req = badge.requirement) {
                is BadgeRequirement.StreakDays -> maxOf(stats.currentStreak.toInt(), stats.longestStreak.toInt())
                is BadgeRequirement.TotalWorkouts -> stats.totalWorkouts.toInt()
                is BadgeRequirement.TotalReps -> stats.totalReps.toInt()
                is BadgeRequirement.PRsAchieved -> stats.prsAchieved.toInt()
                is BadgeRequirement.UniqueExercises -> stats.uniqueExercisesUsed.toInt()
                is BadgeRequirement.TotalVolume -> stats.totalVolumeKg.toInt()
                is BadgeRequirement.ConsecutiveWeeks -> stats.longestStreak.toInt() / 7
                is BadgeRequirement.WorkoutsInWeek -> countWorkoutsInCurrentWeek()
                is BadgeRequirement.SingleWorkoutVolume -> getMaxSingleSessionVolume()
                is BadgeRequirement.WorkoutAtTime -> if (hasWorkoutAtTime(req.hourStart, req.hourEnd)) 1 else 0
                is BadgeRequirement.WorkoutsAtTimeCount -> countWorkoutsAtTime(req.hourStart, req.hourEnd)
                is BadgeRequirement.WorkoutModeCount -> getWorkoutCountByMode(req.modeName)
                is BadgeRequirement.AllWorkoutModes -> getUniqueWorkoutModesCount()
                is BadgeRequirement.PeakPower -> getPeakPower()
                is BadgeRequirement.UniqueMuscleGroups -> getUniqueMuscleGroupsCount()
                is BadgeRequirement.ComebackAfterBreak -> if (hasComebackAfterBreak(req.breakDays)) 1 else 0
                is BadgeRequirement.StreakSaved -> if (hasSavedStreak()) 1 else 0
                is BadgeRequirement.StreakRebuilt -> if (hasRebuiltStreak(req.days)) req.days else stats.currentStreak.toInt()
                is BadgeRequirement.WeekendWorkouts -> getWeekendWorkoutsCount()
                is BadgeRequirement.RoutinesCompleted -> getCompletedRoutinesCount()
                is BadgeRequirement.RoutinesCreated -> getCreatedRoutinesCount()
            }

            Pair(current, badge.getTargetValue())
        }
    }

    override suspend fun getAllBadgesWithProgress(): List<BadgeWithProgress> {
        return withContext(Dispatchers.IO) {
            val earnedBadges = queries.selectAllEarnedBadges().executeAsList()
                .associateBy { it.badgeId }
            val stats = queries.selectGamificationStats().executeAsOneOrNull()

            BadgeDefinitions.allBadges.map { badge ->
                val earned = earnedBadges[badge.id]
                val (current, target) = if (stats != null) {
                    val progress = getBadgeProgress(badge.id) ?: Pair(0, badge.getTargetValue())
                    progress
                } else {
                    Pair(0, badge.getTargetValue())
                }

                BadgeWithProgress(
                    badge = badge,
                    isEarned = earned != null,
                    earnedAt = earned?.earnedAt,
                    currentProgress = current,
                    targetProgress = target
                )
            }
        }
    }
}
