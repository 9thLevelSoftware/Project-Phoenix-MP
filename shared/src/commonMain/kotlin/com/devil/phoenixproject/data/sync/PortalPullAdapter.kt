package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.serialization.json.JsonPrimitive

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

    fun toCanonicalProfilePreferenceSection(
        dto: PortalProfilePreferenceSectionCanonicalDto,
    ): ProfilePreferenceCanonicalDecodeResult {
        fun invalid(reason: ProfilePreferenceSyncIssueReason) =
            ProfilePreferenceCanonicalDecodeResult.Invalid(
                localProfileId = dto.localProfileId,
                section = dto.section,
                reason = reason.name,
            )

        if (dto.localProfileId.isBlank() ||
            profilePreferenceWireSafetyViolation(JsonPrimitive(dto.localProfileId)) != null
        ) {
            return invalid(ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID)
        }
        val section = ProfilePreferenceSectionName.entries.firstOrNull { it.name == dto.section }
            ?: return invalid(ProfilePreferenceSyncIssueReason.UNSUPPORTED_SECTION)
        val updatedAt = ProfilePreferenceSyncCodec.parseStrictRfc3339EpochMilliseconds(
            dto.serverUpdatedAt,
        )
        if (updatedAt == null ||
            updatedAt !in ProfilePreferenceSyncCodec.MIN_RFC3339_EPOCH_MILLIS..
                ProfilePreferenceSyncCodec.MAX_RFC3339_EPOCH_MILLIS
        ) {
            return invalid(ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_TIMESTAMP)
        }

        val candidate = CanonicalProfilePreferenceSection(
            key = ProfilePreferenceSectionKey(dto.localProfileId, section),
            documentVersion = dto.documentVersion,
            serverRevision = dto.serverRevision,
            serverUpdatedAtEpochMs = updatedAt,
            payload = dto.payload,
        )
        return when (val decoded = ProfilePreferenceSyncCodec().decodeCanonical(candidate)) {
            is ProfilePreferenceCanonicalColumnsResult.Invalid -> invalid(decoded.reason)
            is ProfilePreferenceCanonicalColumnsResult.Valid ->
                ProfilePreferenceCanonicalDecodeResult.Valid(candidate)
        }
    }

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
     * @param exerciseLookup Optional function to resolve exerciseId from (name, muscleGroup).
     *                       When provided, sessions will be enriched with catalog links for
     *                       analytics, muscle group aggregation, and plateau detection.
     *                       When null, exerciseId remains null (legacy behavior).
     * @return List of WorkoutSession domain objects, one per exercise
     */
    suspend fun toWorkoutSessionsWithLookup(
        portalSession: PullWorkoutSessionDto,
        profileId: String,
        exerciseLookup: suspend (name: String, muscleGroup: String?, exerciseId: String?) -> String?,
    ): List<WorkoutSession> {
        if (portalSession.exercises.isEmpty()) return emptyList()

        val timestamp = try {
            kotlin.time.Instant.parse(portalSession.startedAt ?: return emptyList())
                .toEpochMilliseconds()
        } catch (_: Exception) {
            return emptyList()
        }

        val mobileMode = portalModeToMobileMode(portalSession.workoutMode ?: "OLD_SCHOOL")
        val exerciseCount = maxOf(portalSession.exerciseCount, portalSession.exercises.size, 1)

        // Build sessions with async exercise lookup
        return portalSession.exercises.map { exercise ->
            val totalReps = exercise.sets.sumOf { it.actualReps }
            val maxWeight = exercise.sets.maxOfOrNull { it.weightKg } ?: 0f

            // Attempt to resolve exerciseId from local catalog (ID-first, then name-based)
            val resolvedExerciseId = exerciseLookup(exercise.name, exercise.muscleGroup, exercise.exerciseId)

            // Issue #591: Hydrate detailed metric columns from rep-level
            // telemetry when the portal pull DTO carries them. Without this,
            // a freshly recorded local row would be overwritten by a pull
            // row whose peakForce* / avgForce* fields are null, and History
            // would render the stale "after v0.2.1" placeholder. Pull DTO
            // forces are in Newtons; mobile fields are in kg-load.
            val metricHydration = aggregateSetMetrics(exercise.sets)

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
                exerciseId = resolvedExerciseId,
                exerciseName = exercise.name,
                routineSessionId = portalSession.id,
                routineName = portalSession.routineName,
                heaviestLiftKg = maxWeight,
                totalVolumeKg = null, // Let effectiveTotalVolumeKg() compute from weightPerCableKg * cableCount * totalReps
                cableCount = null, // Let effectiveTotalVolumeKg() use session-level cableCount if available
                // Issue #591: best-effort hydration from per-set rep summaries.
                // Any field that the portal does not supply stays null and
                // falls back to the locally captured value at LWW merge time.
                peakForceConcentricA = metricHydration.peakForceConcentricA,
                peakForceConcentricB = metricHydration.peakForceConcentricB,
                peakForceEccentricA = metricHydration.peakForceEccentricA,
                peakForceEccentricB = metricHydration.peakForceEccentricB,
                avgForceConcentricA = metricHydration.avgForceConcentricA,
                avgForceConcentricB = metricHydration.avgForceConcentricB,
                avgForceEccentricA = metricHydration.avgForceEccentricA,
                avgForceEccentricB = metricHydration.avgForceEccentricB,
                avgAsymmetryPercent = metricHydration.avgAsymmetryPercent,
                profileId = profileId,
            )
        }
    }

    /**
     * Issue #591: Aggregate per-set rep summaries into the summary-level
     * peak/avg force fields stored on `WorkoutSession`.
     *
     * The portal stores per-rep telemetry in `PullRepSummaryDto`:
     *   - `leftForceAvg` / `rightForceAvg` (Newtons) → per-rep averages for
     *     cable A / cable B during the concentric or eccentric phase.
     *     Cable A maps to "left", cable B maps to "right"
     *     (see PortalSyncAdapter.kt:363-364).
     *   - `meanForceN` / `peakForceN` (Newtons, combined cables) — set-level
     *     aggregates already supplied by the portal set DTO.
     *
     * Mobile `peakForceConcentric*` / `avgForceConcentric*` columns store
     * kg-load (i.e., Newtons / 9.80665). Conversion here so the round trip
     * push → pull is unit-consistent with locally captured values.
     *
     * Eccentric peak/avg is harder to derive because the portal stores
     * `tutMs` and not a separate eccentric force aggregate, so this helper
     * conservatively keeps eccentric peak/avg as null unless a set DTO
     * provides them directly. Preservation at LWW merge still protects the
     * locally captured eccentric values when present.
     */
    private fun aggregateSetMetrics(sets: List<PullSetDto>): HydratedMetrics {
        if (sets.isEmpty()) return HydratedMetrics.EMPTY

        val allReps = sets.flatMap { it.repSummaries }
        if (allReps.isEmpty()) return HydratedMetrics.EMPTY

        // Single pass over allReps — avoid re-flatMapping `sets` again at
        // the call site to compute avgAsymmetryPercent (gemini-code-assist
        // medium-priority note). Cable A force: max of leftForceAvg across
        // all reps (peak proxy). Cable B force: max of rightForceAvg across
        // all reps. Rep averages: average across all reps that supplied a
        // value.
        val leftForces = allReps.mapNotNull { it.leftForceAvg }
        val rightForces = allReps.mapNotNull { it.rightForceAvg }
        val peakLeft = leftForces.maxOrNull()
        val peakRight = rightForces.maxOrNull()
        val avgLeft = leftForces.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val avgRight = rightForces.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val asymmetryPcts = allReps.mapNotNull { it.asymmetryPct }
        val avgAsymmetry = asymmetryPcts.takeIf { it.isNotEmpty() }?.average()?.toFloat()

        return HydratedMetrics(
            peakForceConcentricA = peakLeft?.let { PortalMappings.newtonsToLoadKg(it) },
            peakForceConcentricB = peakRight?.let { PortalMappings.newtonsToLoadKg(it) },
            peakForceEccentricA = null, // Portal does not surface per-cable eccentric peak; preserve local.
            peakForceEccentricB = null,
            avgForceConcentricA = avgLeft?.let { PortalMappings.newtonsToLoadKg(it) },
            avgForceConcentricB = avgRight?.let { PortalMappings.newtonsToLoadKg(it) },
            avgForceEccentricA = null, // Same — preserved locally if available.
            avgForceEccentricB = null,
            avgAsymmetryPercent = avgAsymmetry,
        )
    }

    private data class HydratedMetrics(
        val peakForceConcentricA: Float?,
        val peakForceConcentricB: Float?,
        val peakForceEccentricA: Float?,
        val peakForceEccentricB: Float?,
        val avgForceConcentricA: Float?,
        val avgForceConcentricB: Float?,
        val avgForceEccentricA: Float?,
        val avgForceEccentricB: Float?,
        val avgAsymmetryPercent: Float?,
    ) {
        companion object {
            val EMPTY = HydratedMetrics(null, null, null, null, null, null, null, null, null)
        }
    }

    /**
     * Convert a portal workout session (1 workout with N exercises) to N mobile
     * WorkoutSession rows (1 per exercise). This is the reverse of the push
     * adapter's grouping logic.
     *
     * Weight convention: the Supabase DB stores per-cable weight values. The portal
     * UI multiplies by WEIGHT_MULTIPLIER (2) for display only. The pull Edge Function
     * returns raw DB values, so PullSetDto.weightKg is already per-cable — no division needed.
     *
     * NOTE: This legacy method does NOT resolve exerciseId. Use toWorkoutSessionsWithLookup()
     * for full catalog integration.
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
            totalTimeSeconds = stats.totalTimeSeconds.toLong(),
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
    fun toPersonalRecordSyncDto(
        pr: PullPersonalRecordDto,
        resolvedExerciseId: String? = null,
    ): PersonalRecordSyncDto {
        val now = currentTimeMillis()
        fun parsePortalTimestamp(value: String?): Long? = value?.let {
            try {
                kotlinx.datetime.Instant.parse(it).toEpochMilliseconds()
            } catch (_: Exception) {
                null
            }
        }
        val achievedAt = parsePortalTimestamp(pr.achievedAt) ?: now
        val updatedAt = parsePortalTimestamp(pr.updatedAt) ?: achievedAt
        return PersonalRecordSyncDto(
            clientId = pr.id,
            serverId = pr.id,
            // Portal personal_records has no exercise_id column (only
            // exercise_name), so the catalog exercise id is resolved by
            // name/muscle group at the call site and passed in. Falling back to
            // pr.id (the PR row id) only when there is no catalog match — the
            // previous unconditional `exerciseId = pr.id` broke exercise linkage
            // and made every pulled PR a unique idx_pr_unique key that never
            // deduped against local PRs (audit F021).
            exerciseId = resolvedExerciseId?.takeIf { it.isNotBlank() } ?: pr.id,
            exerciseName = pr.exerciseName,
            weight = pr.value.toFloat(),
            reps = pr.reps ?: 0,
            oneRepMax = 0f, // Portal doesn't send computed 1RM
            achievedAt = achievedAt,
            workoutMode = pr.recordType,
            prType = pr.recordType,
            phase = pr.workoutPhase ?: "COMBINED",
            volume = pr.value.toFloat(),
            deletedAt = parsePortalTimestamp(pr.deletedAt),
            createdAt = achievedAt,
            updatedAt = updatedAt,
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
