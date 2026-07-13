package com.devil.phoenixproject.qa

import com.devil.phoenixproject.data.repository.AssessmentRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.JustLiftDefaultsDocument
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.SingleExerciseDefaultsDocument
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProfileQaSeedResult(
    val marker: String,
    val profileAId: String,
    val profileBId: String,
    val sessionCount: Int,
)

interface ProfileQaFixtureRowCleanup {
    fun deletePersonalRecord(id: Long)
    fun deleteVelocityOneRepMax(id: Long)
}

private class DatabaseProfileQaFixtureRowCleanup(
    database: VitruvianDatabase,
) : ProfileQaFixtureRowCleanup {
    private val queries = database.vitruvianDatabaseQueries

    override fun deletePersonalRecord(id: Long) {
        queries.deletePersonalRecordById(id)
    }

    override fun deleteVelocityOneRepMax(id: Long) {
        queries.deleteVelocityOneRepMaxById(id)
    }
}

class ProfileQaSeeder(
    private val userProfileRepository: UserProfileRepository,
    private val exerciseRepository: ExerciseRepository,
    private val workoutRepository: WorkoutRepository,
    private val repMetricRepository: RepMetricRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val assessmentRepository: AssessmentRepository,
    private val velocityOneRepMaxRepository: VelocityOneRepMaxRepository,
    database: VitruvianDatabase,
    private val fixtureRowCleanup: ProfileQaFixtureRowCleanup =
        DatabaseProfileQaFixtureRowCleanup(database),
) {
    private val seedMutex = Mutex()

    suspend fun seed(): ProfileQaSeedResult = seedMutex.withLock {
        val exercise = requireNotNull(exerciseRepository.findByName(EXERCISE_NAME)) {
            "Catalog exercise missing: $EXERCISE_NAME"
        }
        val exerciseId = requireNotNull(exercise.id) {
            "Catalog exercise has no stable ID: $EXERCISE_NAME"
        }
        val profileA = findOrCreateProfile(PROFILE_A_NAME, PROFILE_A_COLOR)
        val profileB = findOrCreateProfile(PROFILE_B_NAME, PROFILE_B_COLOR)

        seedProfile(
            profileA,
            exerciseId,
            profileKey = "a",
            originalCatalogOneRepMaxKg = exercise.oneRepMaxKg,
        )
        seedProfile(
            profileB,
            exerciseId,
            profileKey = "b",
            originalCatalogOneRepMaxKg = exercise.oneRepMaxKg,
        )
        userProfileRepository.setActiveProfile(profileA.id)

        return ProfileQaSeedResult(
            marker = RESULT_OK,
            profileAId = profileA.id,
            profileBId = profileB.id,
            sessionCount = SESSION_TIMESTAMPS.size * 2,
        )
    }

    private suspend fun findOrCreateProfile(name: String, colorIndex: Int): UserProfile {
        val existing = userProfileRepository.allProfiles.value.firstOrNull { it.name == name }
        return existing ?: userProfileRepository.createProfile(name, colorIndex)
    }

    private suspend fun seedProfile(
        profile: UserProfile,
        exerciseId: String,
        profileKey: String,
        originalCatalogOneRepMaxKg: Float?,
    ) {
        val isProfileA = profileKey == "a"
        val rackItemId = rackItemId(profileKey)
        userProfileRepository.setActiveProfile(profile.id)
        userProfileRepository.updateProfile(
            id = profile.id,
            name = if (isProfileA) PROFILE_A_NAME else PROFILE_B_NAME,
            colorIndex = if (isProfileA) PROFILE_A_COLOR else PROFILE_B_COLOR,
        )
        userProfileRepository.updateCore(profile.id, corePreferences(isProfileA))
        userProfileRepository.updateRack(profile.id, rackPreferences(profileKey, isProfileA))
        userProfileRepository.updateWorkout(
            profile.id,
            workoutPreferences(exerciseId, rackItemId, isProfileA),
        )
        userProfileRepository.updateLed(profile.id, ledPreferences(isProfileA))
        userProfileRepository.updateLocalSafety(profile.id, localSafetyPreferences(isProfileA))
        userProfileRepository.updateVbt(profile.id, vbtPreferences(isProfileA))

        cleanupFixtureRows(profile.id, exerciseId, profileKey)
        seedSessions(profile.id, exerciseId, profileKey, rackItemId, isProfileA)
        seedPersonalRecords(profile.id, exerciseId, originalCatalogOneRepMaxKg)
        assessmentRepository.saveAssessment(
            exerciseId = exerciseId,
            estimatedOneRepMaxKg = ASSESSMENT_TOTAL_KG,
            loadVelocityDataJson = ASSESSMENT_DATA_JSON,
            sessionId = null,
            userOverrideKg = null,
            profileId = profile.id,
        )
        velocityOneRepMaxRepository.insert(
            result = VelocityOneRepMaxResult(
                estimatedPerCableKg = VELOCITY_ONE_REP_MAX_PER_CABLE_KG,
                mvtUsedMs = VELOCITY_MVT_MS,
                r2 = VELOCITY_R2,
                distinctLoads = VELOCITY_DISTINCT_LOADS,
                passedQualityGate = true,
            ),
            exerciseId = exerciseId,
            computedAt = VELOCITY_COMPUTED_AT,
            profileId = profile.id,
        )
    }

    private suspend fun cleanupFixtureRows(profileId: String, exerciseId: String, profileKey: String) {
        sessionIds(profileKey).forEach { sessionId ->
            repMetricRepository.deleteRepMetrics(sessionId)
            workoutRepository.deleteSession(sessionId)
        }
        personalRecordRepository.getAllPRsForExercise(exerciseId, profileId)
            .filter { it.workoutMode == WORKOUT_MODE }
            .forEach { fixtureRowCleanup.deletePersonalRecord(it.id) }
        assessmentRepository.getAssessmentsByExercise(exerciseId, profileId).first()
            .filter {
                it.assessmentSessionId == null &&
                    it.estimatedOneRepMaxKg == ASSESSMENT_TOTAL_KG &&
                    it.loadVelocityData == ASSESSMENT_DATA_JSON
            }
            .forEach { assessmentRepository.deleteAssessment(it.id) }
        velocityOneRepMaxRepository.getHistory(exerciseId, profileId).first()
            .filter {
                it.computedAt == VELOCITY_COMPUTED_AT &&
                    it.estimatedPerCableKg == VELOCITY_ONE_REP_MAX_PER_CABLE_KG &&
                    it.mvtUsedMs == VELOCITY_MVT_MS &&
                    it.r2 == VELOCITY_R2 &&
                    it.distinctLoads == VELOCITY_DISTINCT_LOADS &&
                    it.passedQualityGate
            }
            .forEach { fixtureRowCleanup.deleteVelocityOneRepMax(it.id) }
    }

    private suspend fun seedSessions(
        profileId: String,
        exerciseId: String,
        profileKey: String,
        rackItemId: String,
        isProfileA: Boolean,
    ) {
        SESSION_TIMESTAMPS.indices.forEach { index ->
            val session = workoutSession(
                profileId = profileId,
                exerciseId = exerciseId,
                profileKey = profileKey,
                index = index,
                isProfileA = isProfileA,
            )
            workoutRepository.saveSession(session)
            repMetricRepository.saveRepMetrics(
                session.id,
                (1..session.workingReps).map { rep -> repMetric(session, rep) },
            )
        }
    }

    private suspend fun seedPersonalRecords(
        profileId: String,
        exerciseId: String,
        originalCatalogOneRepMaxKg: Float?,
    ) {
        try {
            personalRecordRepository.updatePRsIfBetter(
                exerciseId = exerciseId,
                weightPRWeightPerCableKg = 50f,
                volumePRWeightPerCableKg = 50f,
                reps = 3,
                workoutMode = WORKOUT_MODE,
                timestamp = SESSION_TIMESTAMPS.last(),
                profileId = profileId,
                cableCount = 2,
            ).getOrThrow()
            personalRecordRepository.updatePRsIfBetter(
                exerciseId = exerciseId,
                weightPRWeightPerCableKg = 40f,
                volumePRWeightPerCableKg = 40f,
                reps = 12,
                workoutMode = WORKOUT_MODE,
                timestamp = SESSION_TIMESTAMPS[2],
                profileId = profileId,
                cableCount = 2,
            ).getOrThrow()
        } finally {
            exerciseRepository.updateOneRepMax(exerciseId, originalCatalogOneRepMaxKg)
        }
    }

    private fun workoutSession(
        profileId: String,
        exerciseId: String,
        profileKey: String,
        index: Int,
        isProfileA: Boolean,
    ): WorkoutSession {
        val load = SESSION_LOADS[index]
        val reps = SESSION_REPS[index]
        val timestamp = SESSION_TIMESTAMPS[index]
        return WorkoutSession(
            id = sessionIds(profileKey)[index],
            timestamp = timestamp,
            mode = WORKOUT_MODE,
            reps = reps,
            weightPerCableKg = load,
            duration = 30_000L + index * 1_000L,
            totalReps = reps,
            warmupReps = 0,
            workingReps = reps,
            stopAtTop = isProfileA,
            exerciseId = exerciseId,
            exerciseName = EXERCISE_NAME,
            routineSessionId = "profile-qa-$profileKey-routine-session",
            routineName = if (isProfileA) "[QA] Strength A" else "[QA] Strength B",
            peakForceConcentricA = load + 6f,
            peakForceConcentricB = load + 5f,
            peakForceEccentricA = load + 4f,
            peakForceEccentricB = load + 3f,
            avgForceConcentricA = load + 2f,
            avgForceConcentricB = load + 1f,
            avgForceEccentricA = load,
            avgForceEccentricB = load - 1f,
            heaviestLiftKg = load,
            totalVolumeKg = load * reps * 2f,
            cableCount = 2,
            displayMultiplier = 2,
            externalAddedLoadKg = if (isProfileA) 10f else 0f,
            counterweightKg = if (isProfileA) 0f else 7.5f,
            rackItemsJson = rackSnapshotJson(profileKey, isProfileA),
            estimatedCalories = 5f + index,
            workingAvgWeightKg = load,
            peakWeightKg = load,
            avgMcvMmS = SESSION_MCV_MM_S[index],
            avgAsymmetryPercent = if (isProfileA) 2f else 8f,
            totalVelocityLossPercent = 5f + index,
            dominantSide = if (isProfileA) "BALANCED" else "RIGHT",
            strengthProfile = if (isProfileA) "STRENGTH" else "ENDURANCE",
            profileId = profileId,
        )
    }

    private fun repMetric(session: WorkoutSession, repNumber: Int): RepMetricData {
        val start = session.timestamp + (repNumber - 1) * 1_000L
        val load = session.weightPerCableKg
        return RepMetricData(
            repNumber = repNumber,
            isWarmup = false,
            startTimestamp = start,
            endTimestamp = start + 900L,
            durationMs = 900L,
            concentricDurationMs = 400L,
            concentricPositions = floatArrayOf(0f, 250f, 500f),
            concentricLoadsA = floatArrayOf(load, load + 1f, load + 2f),
            concentricLoadsB = floatArrayOf(load, load + 0.5f, load + 1.5f),
            concentricVelocities = floatArrayOf(400f, 600f, 500f),
            concentricTimestamps = longArrayOf(0L, 200L, 400L),
            eccentricDurationMs = 500L,
            eccentricPositions = floatArrayOf(500f, 250f, 0f),
            eccentricLoadsA = floatArrayOf(load + 2f, load + 1f, load),
            eccentricLoadsB = floatArrayOf(load + 1.5f, load + 0.5f, load),
            eccentricVelocities = floatArrayOf(-300f, -450f, -350f),
            eccentricTimestamps = longArrayOf(400L, 650L, 900L),
            peakForceA = load + 6f,
            peakForceB = load + 5f,
            avgForceConcentricA = load + 2f,
            avgForceConcentricB = load + 1f,
            avgForceEccentricA = load + 1f,
            avgForceEccentricB = load,
            peakVelocity = 600f,
            avgVelocityConcentric = 500f,
            avgVelocityEccentric = -375f,
            rangeOfMotionMm = 500f,
            peakPowerWatts = 300f + load,
            avgPowerWatts = 220f + load,
        )
    }

    private fun corePreferences(isProfileA: Boolean) = if (isProfileA) {
        CoreProfilePreferences(bodyWeightKg = 82.5f, weightUnit = WeightUnit.KG, weightIncrement = 2.5f)
    } else {
        CoreProfilePreferences(bodyWeightKg = 165f, weightUnit = WeightUnit.LB, weightIncrement = 5f)
    }

    private fun rackPreferences(profileKey: String, isProfileA: Boolean): RackPreferences {
        val createdAt = if (isProfileA) 1_700_000_001_000L else 1_700_000_002_000L
        return RackPreferences(
            items = listOf(
                RackItem(
                    id = rackItemId(profileKey),
                    name = if (isProfileA) "QA Weighted Vest A" else "QA Assistance B",
                    category = if (isProfileA) RackItemCategory.WEIGHTED_VEST else RackItemCategory.ASSISTANCE,
                    weightKg = if (isProfileA) 10f else 7.5f,
                    behavior = if (isProfileA) RackItemBehavior.ADDED_RESISTANCE else RackItemBehavior.COUNTERWEIGHT,
                    enabled = true,
                    sortOrder = if (isProfileA) 1 else 2,
                    createdAt = createdAt,
                    updatedAt = createdAt + 100L,
                ),
            ),
        )
    }

    private fun workoutPreferences(
        exerciseId: String,
        rackItemId: String,
        isProfileA: Boolean,
    ): WorkoutPreferences = if (isProfileA) {
        WorkoutPreferences(
            stopAtTop = true,
            beepsEnabled = true,
            stallDetectionEnabled = true,
            audioRepCountEnabled = true,
            repCountTiming = RepCountTiming.TOP,
            summaryCountdownSeconds = 15,
            autoStartCountdownSeconds = 7,
            gamificationEnabled = true,
            autoStartRoutine = true,
            countdownBeepsEnabled = true,
            repSoundEnabled = true,
            motionStartEnabled = true,
            weightSuggestionsEnabled = true,
            defaultRoutineExerciseUsePercentOfPR = true,
            defaultRoutineExerciseWeightPercentOfPR = 75,
            voiceStopEnabled = true,
            justLiftDefaults = JustLiftDefaultsDocument(
                workoutModeId = 0,
                weightPerCableKg = 25f,
                weightChangePerRep = 2.5f,
                eccentricLoadPercentage = 125,
                echoLevelValue = 3,
                stallDetectionEnabled = true,
                repCountTimingName = RepCountTiming.TOP.name,
                restSeconds = 45,
            ),
            singleExerciseDefaults = mapOf(
                exerciseId to SingleExerciseDefaultsDocument(
                    exerciseId = exerciseId,
                    setReps = listOf(8, 10, 12),
                    weightPerCableKg = 40f,
                    setWeightsPerCableKg = listOf(35f, 40f, 45f),
                    progressionKg = 2.5f,
                    setRestSeconds = listOf(45, 45, 60),
                    workoutModeId = 0,
                    eccentricLoadPercentage = 125,
                    echoLevelValue = 3,
                    duration = 30,
                    isAMRAP = true,
                    perSetRestTime = true,
                    defaultRackItemIds = listOf(rackItemId),
                ),
            ),
        )
    } else {
        WorkoutPreferences(
            stopAtTop = false,
            beepsEnabled = false,
            stallDetectionEnabled = false,
            audioRepCountEnabled = false,
            repCountTiming = RepCountTiming.BOTTOM,
            summaryCountdownSeconds = 5,
            autoStartCountdownSeconds = 3,
            gamificationEnabled = false,
            autoStartRoutine = false,
            countdownBeepsEnabled = false,
            repSoundEnabled = false,
            motionStartEnabled = false,
            weightSuggestionsEnabled = false,
            defaultRoutineExerciseUsePercentOfPR = false,
            defaultRoutineExerciseWeightPercentOfPR = 60,
            voiceStopEnabled = false,
            justLiftDefaults = JustLiftDefaultsDocument(
                workoutModeId = 10,
                weightPerCableKg = 15f,
                weightChangePerRep = -0.5f,
                eccentricLoadPercentage = 75,
                echoLevelValue = 0,
                stallDetectionEnabled = false,
                repCountTimingName = RepCountTiming.BOTTOM.name,
                restSeconds = 90,
            ),
            singleExerciseDefaults = mapOf(
                exerciseId to SingleExerciseDefaultsDocument(
                    exerciseId = exerciseId,
                    setReps = listOf(12, 10, 8),
                    weightPerCableKg = 30f,
                    setWeightsPerCableKg = listOf(30f, 27.5f, 25f),
                    progressionKg = -2.5f,
                    setRestSeconds = listOf(90, 75, 60),
                    workoutModeId = 10,
                    eccentricLoadPercentage = 75,
                    echoLevelValue = 0,
                    duration = 45,
                    isAMRAP = false,
                    perSetRestTime = false,
                    defaultRackItemIds = listOf(rackItemId),
                ),
            ),
        )
    }

    private fun ledPreferences(isProfileA: Boolean) = if (isProfileA) {
        LedPreferences(colorScheme = 1, discoModeUnlocked = false)
    } else {
        LedPreferences(colorScheme = 4, discoModeUnlocked = true)
    }

    private fun vbtPreferences(isProfileA: Boolean) = if (isProfileA) {
        VbtPreferences(
            enabled = true,
            velocityLossThresholdPercent = 20,
            autoEndOnVelocityLoss = true,
            defaultScalingBasis = ScalingBasis.ESTIMATED_1RM,
            verbalEncouragementEnabled = true,
            vulgarModeEnabled = true,
            vulgarTier = VulgarTier.MIX,
            dominatrixModeUnlocked = true,
            dominatrixModeActive = true,
        )
    } else {
        VbtPreferences(
            enabled = false,
            velocityLossThresholdPercent = 35,
            autoEndOnVelocityLoss = false,
            defaultScalingBasis = ScalingBasis.MAX_VOLUME_PR,
            verbalEncouragementEnabled = false,
            vulgarModeEnabled = false,
            vulgarTier = VulgarTier.MILD,
            dominatrixModeUnlocked = false,
            dominatrixModeActive = false,
        )
    }

    private fun localSafetyPreferences(isProfileA: Boolean) = if (isProfileA) {
        ProfileLocalSafetyPreferences(
            safeWord = "Phoenix Alpha",
            safeWordCalibrated = true,
            adultsOnlyConfirmed = true,
            adultsOnlyPrompted = true,
        )
    } else {
        ProfileLocalSafetyPreferences(
            safeWord = "Phoenix Bravo",
            safeWordCalibrated = false,
            adultsOnlyConfirmed = false,
            adultsOnlyPrompted = false,
        )
    }

    private fun rackItemId(profileKey: String) = if (profileKey == "a") {
        "profile-qa-a-rack-vest"
    } else {
        "profile-qa-b-rack-assist"
    }

    private fun rackSnapshotJson(profileKey: String, isProfileA: Boolean): String {
        val id = rackItemId(profileKey)
        return if (isProfileA) {
            "[{\"id\":\"$id\",\"name\":\"QA Weighted Vest A\",\"category\":\"WEIGHTED_VEST\",\"weightKg\":10.0,\"behavior\":\"ADDED_RESISTANCE\",\"enabled\":true,\"sortOrder\":1,\"createdAt\":1700000001000,\"updatedAt\":1700000001100}]"
        } else {
            "[{\"id\":\"$id\",\"name\":\"QA Assistance B\",\"category\":\"ASSISTANCE\",\"weightKg\":7.5,\"behavior\":\"COUNTERWEIGHT\",\"enabled\":true,\"sortOrder\":2,\"createdAt\":1700000002000,\"updatedAt\":1700000002100}]"
        }
    }

    private fun sessionIds(profileKey: String) = (1..SESSION_TIMESTAMPS.size).map {
        "profile-qa-$profileKey-session-$it"
    }

    companion object {
        const val RESULT_OK = "PROFILE_QA_SEED_OK"
        const val PROFILE_A_NAME = "[QA] Profile A"
        const val PROFILE_B_NAME = "[QA] Profile B"
        private const val PROFILE_A_COLOR = 2
        private const val PROFILE_B_COLOR = 7
        private const val EXERCISE_NAME = "Bench Press"
        private const val WORKOUT_MODE = "QA Fixture"
        private const val ASSESSMENT_TOTAL_KG = 120f
        private const val ASSESSMENT_DATA_JSON =
            "[{\"loadKg\":\"80.0\",\"velocityMs\":\"0.75\"},{\"loadKg\":\"100.0\",\"velocityMs\":\"0.55\"},{\"loadKg\":\"120.0\",\"velocityMs\":\"0.40\"}]"
        private const val VELOCITY_ONE_REP_MAX_PER_CABLE_KG = 77f
        private const val VELOCITY_MVT_MS = 0.30f
        private const val VELOCITY_R2 = 0.97f
        private const val VELOCITY_DISTINCT_LOADS = 3
        private const val VELOCITY_COMPUTED_AT = 4_102_444_800_000L
        private val SESSION_TIMESTAMPS = listOf(
            1_700_100_000_000L,
            1_700_200_000_000L,
            1_700_300_000_000L,
            1_700_400_000_000L,
            1_700_500_000_000L,
        )
        private val SESSION_LOADS = listOf(30f, 35f, 40f, 45f, 50f)
        private val SESSION_REPS = listOf(10, 8, 12, 5, 3)
        private val SESSION_MCV_MM_S = listOf(750f, 680f, 600f, 520f, 450f)
    }
}
