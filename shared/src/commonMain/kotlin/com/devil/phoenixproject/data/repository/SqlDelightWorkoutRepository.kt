package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.database.Routine as RoutineRow
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineGroup
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.domain.onerepmax.WorkoutVelocityPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class SqlDelightWorkoutRepository(
    private val db: PhoenixDatabase,
    private val exerciseRepository: ExerciseRepository,
    private val routineRecoveryStore: RoutineRecoveryStore = RoutineRecoveryStore(db.phoenixDatabaseQueries),
    // Issue #1162 final audit R4: recovery access is authorized by the
    // AUTHENTICATED portal identity and the active profile, resolved per call at
    // execution time (the signed-in account and selected profile change over the
    // app's lifetime) — never by stored profile linkage or caller-supplied
    // strings alone. Defaults deny: a repository built without these providers
    // shows and restores nothing.
    private val signedInPortalUserId: () -> String? = { null },
    private val activeProfileId: () -> String? = { null },
) : WorkoutRepository {

    private val queries = db.phoenixDatabaseQueries
    private val routineIdentityResolver = RoutineIdentityResolver(queries, routineRecoveryStore)

    // Issue #1162: one-shot identity maintenance per selected profile, run before
    // the first routine-list emission (offline launch and profile switch included).
    private val identityMaintenanceMutex = Mutex()
    private val identityMaintainedProfiles = mutableSetOf<String>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun mapToSession(
        id: String,
        timestamp: Long,
        mode: String,
        targetReps: Long,
        weightPerCableKg: Double,
        progressionKg: Double,
        duration: Long,
        totalReps: Long,
        warmupReps: Long,
        workingReps: Long,
        isJustLift: Long,
        stopAtTop: Long,
        eccentricLoad: Long,
        echoLevel: Long,
        exerciseId: String?,
        exerciseName: String?,
        routineSessionId: String?,
        routineName: String?,
        routineId: String?,
        safetyFlags: Long,
        deloadWarningCount: Long,
        romViolationCount: Long,
        spotterActivations: Long,
        // New summary metrics
        peakForceConcentricA: Double?,
        peakForceConcentricB: Double?,
        peakForceEccentricA: Double?,
        peakForceEccentricB: Double?,
        avgForceConcentricA: Double?,
        avgForceConcentricB: Double?,
        avgForceEccentricA: Double?,
        avgForceEccentricB: Double?,
        heaviestLiftKg: Double?,
        totalVolumeKg: Double?,
        cableCount: Long?,
        estimatedCalories: Double?,
        warmupAvgWeightKg: Double?,
        workingAvgWeightKg: Double?,
        burnoutAvgWeightKg: Double?,
        peakWeightKg: Double?,
        rpe: Long?,
        // Biomechanics summary (migration 15 - Phase 13 v16)
        avgMcvMmS: Double?,
        avgAsymmetryPercent: Double?,
        totalVelocityLossPercent: Double?,
        dominantSide: String?,
        strengthProfile: String?,
        // Form Check score (migration 16 - Phase 19 CV-06)
        formScore: Long?,
        // Sync fields (migration 6)
        updatedAt: Long?,
        serverId: String?,
        deletedAt: Long?,
        // Multi-profile support (migration 21)
        profileId: String,
        // Equipment-aware weight display (migration 29)
        displayMultiplier: Long?,
        // Equipment rack context (migration 33)
        externalAddedLoadKg: Double,
        counterweightKg: Double,
        rackItemsJson: String,
        portalOrigin: Long,
        localSyncGeneration: Long,
        syncedSyncGeneration: Long,
    ): WorkoutSession = WorkoutSession(
        id = id,
        timestamp = timestamp,
        mode = mode,
        reps = targetReps.toInt(),
        weightPerCableKg = weightPerCableKg.toFloat(),
        progressionKg = progressionKg.toFloat(),
        duration = duration,
        totalReps = totalReps.toInt(),
        warmupReps = warmupReps.toInt(),
        workingReps = workingReps.toInt(),
        isJustLift = isJustLift == 1L,
        stopAtTop = stopAtTop == 1L,
        eccentricLoad = eccentricLoad.toInt(),
        echoLevel = echoLevel.toInt(),
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        routineSessionId = routineSessionId,
        routineName = routineName,
        routineId = routineId,
        safetyFlags = safetyFlags.toInt(),
        deloadWarningCount = deloadWarningCount.toInt(),
        romViolationCount = romViolationCount.toInt(),
        spotterActivations = spotterActivations.toInt(),
        // New summary metrics
        peakForceConcentricA = peakForceConcentricA?.toFloat(),
        peakForceConcentricB = peakForceConcentricB?.toFloat(),
        peakForceEccentricA = peakForceEccentricA?.toFloat(),
        peakForceEccentricB = peakForceEccentricB?.toFloat(),
        avgForceConcentricA = avgForceConcentricA?.toFloat(),
        avgForceConcentricB = avgForceConcentricB?.toFloat(),
        avgForceEccentricA = avgForceEccentricA?.toFloat(),
        avgForceEccentricB = avgForceEccentricB?.toFloat(),
        heaviestLiftKg = heaviestLiftKg?.toFloat(),
        totalVolumeKg = totalVolumeKg?.toFloat(),
        cableCount = cableCount?.toInt(),
        estimatedCalories = estimatedCalories?.toFloat(),
        warmupAvgWeightKg = warmupAvgWeightKg?.toFloat(),
        workingAvgWeightKg = workingAvgWeightKg?.toFloat(),
        burnoutAvgWeightKg = burnoutAvgWeightKg?.toFloat(),
        peakWeightKg = peakWeightKg?.toFloat(),
        rpe = rpe?.toInt(),
        // Biomechanics summary
        avgMcvMmS = avgMcvMmS?.toFloat(),
        avgAsymmetryPercent = avgAsymmetryPercent?.toFloat(),
        totalVelocityLossPercent = totalVelocityLossPercent?.toFloat(),
        dominantSide = dominantSide,
        strengthProfile = strengthProfile,
        // Form Check score
        formScore = formScore?.toInt(),
        // Multi-profile support
        profileId = profileId,
        // Equipment-aware weight display
        displayMultiplier = displayMultiplier?.toInt(),
        externalAddedLoadKg = externalAddedLoadKg.toFloat(),
        counterweightKg = counterweightKg.toFloat(),
        rackItemsJson = rackItemsJson,
        updatedAt = updatedAt,
    )

    private fun mapToRoutineBasic(
        id: String,
        name: String,
        description: String,
        createdAt: Long,
        lastUsed: Long?,
        useCount: Long,
        // Sync fields (migration 6)
        updatedAt: Long?,
        serverId: String?,
        deletedAt: Long?,
        // Multi-profile support (migration 21)
        profileId: String,
        // Routine group assignment (migration 27)
        groupId: String?,
    ): Routine = Routine(
        id = id,
        name = name,
        description = description,
        exercises = emptyList(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastUsed = lastUsed,
        useCount = useCount.toInt(),
        profileId = profileId,
        groupId = groupId,
    )

    private suspend fun loadRoutineWithExercises(
        routineId: String,
        name: String,
        createdAt: Long,
        lastUsed: Long? = null,
        useCount: Int = 0,
        profileId: String = "default",
        groupId: String? = null,
        updatedAt: Long? = null,
        description: String = "",
    ): Routine {
        val exerciseRows = queries.selectExercisesByRoutine(routineId).executeAsList()
        val supersetRows = queries.selectSupersetsByRoutine(routineId).executeAsList()

        // Load supersets from database
        val supersets = supersetRows.map { row ->
            Superset(
                id = row.id,
                routineId = row.routineId,
                name = row.name,
                colorIndex = row.colorIndex.toInt(),
                restBetweenSeconds = row.restBetweenSeconds.toInt(),
                orderIndex = row.orderIndex.toInt(),
            )
        }

        val exercises = exerciseRows.mapNotNull { row ->
            try {
                // Try to get exercise from library, or create from stored data
                val resolvedExercise = row.exerciseId?.let { exerciseId ->
                    exerciseRepository.getExerciseById(exerciseId)
                        ?: exerciseRepository.findByName(row.exerciseName)
                            ?.also { byName ->
                                Logger.w {
                                    "Routine exercise had stale exerciseId=$exerciseId; resolved by name '${row.exerciseName}' -> ${byName.id} and healing row ${row.id}"
                                }
                                healRoutineExerciseId(byName.id, row.id)
                            }
                } ?: exerciseRepository.findByName(row.exerciseName)
                    ?.also { byName ->
                        Logger.i {
                            "Routine exercise missing exerciseId for '${row.exerciseName}'; resolved to ${byName.id} and healing row ${row.id}"
                        }
                        healRoutineExerciseId(byName.id, row.id)
                    }

                val exercise = resolvedExercise ?: run {
                    // Issue #774: never mint an exercise with a blank name; it crashes the
                    // exercise picker. Fall back to the stored id, then a fixed label.
                    val healedName = healedExerciseName(row.exerciseName, row.exerciseId)
                    // Self-healing: auto-create a custom exercise so exerciseId is never null.
                    // A null exerciseId breaks the entire PR tracking pipeline (#319).
                    val autoCreated = exerciseRepository.createCustomExercise(
                        Exercise(
                            name = healedName,
                            muscleGroup = row.exerciseMuscleGroup,
                            equipment = row.exerciseEquipment,
                            isCustom = true,
                        ),
                    ).getOrNull()

                    if (autoCreated != null) {
                        Logger.i {
                            "HEAL: Auto-created custom exercise '${row.exerciseName}' -> id=${autoCreated.id} " +
                                "(was null in RoutineExercise ${row.id})"
                        }
                        // Heal the DB row so subsequent loads don't repeat this
                        healRoutineExerciseId(autoCreated.id!!, row.id)
                        autoCreated
                    } else {
                        // Last resort: generate a synthetic ID to prevent null propagation
                        val syntheticId = generateUUID()
                        Logger.w {
                            "HEAL: createCustomExercise failed for '${row.exerciseName}', " +
                                "using synthetic ID=$syntheticId"
                        }
                        Exercise(
                            id = syntheticId,
                            name = healedName,
                            muscleGroup = row.exerciseMuscleGroup,
                            muscleGroups = row.exerciseMuscleGroup,
                            equipment = row.exerciseEquipment,
                            isFavorite = false,
                            isCustom = true,
                        )
                    }
                }

                // #635: the per-routine-exercise flag (portal toggle / pull sync) overrides
                // the catalog classification when explicitly stored on the row.
                val exerciseWithBodyweightFlag = row.isBodyweight
                    ?.let { exercise.copy(isBodyweightOverride = it != 0L) }
                    ?: exercise

                // Parse comma-separated setReps (supports "AMRAP" as null marker)
                val setReps: List<Int?> = try {
                    row.setReps.split(",").map { value ->
                        val trimmed = value.trim()
                        if (trimmed.equals("AMRAP", ignoreCase = true)) null else trimmed.toIntOrNull()
                    }
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to parse setReps '${row.setReps}' for exercise ${row.exerciseName}, using default [10]" }
                    listOf(10)
                }

                val setWeights: List<Float> = try {
                    if (row.setWeights.isBlank()) {
                        emptyList()
                    } else {
                        row.setWeights.split(",").mapNotNull { it.trim().toFloatOrNull() }
                    }
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to parse setWeights '${row.setWeights}' for exercise ${row.exerciseName}, using empty list" }
                    emptyList()
                }

                val setRestSeconds: List<Int> = try {
                    json.decodeFromString<List<Int>>(row.setRestSeconds)
                } catch (e: Exception) {
                    Logger.w(e) {
                        "Failed to parse setRestSeconds '${row.setRestSeconds}' for exercise ${row.exerciseName}, using empty list"
                    }
                    emptyList()
                }

                val setEchoLevels: List<EchoLevel?> = try {
                    if (row.setEchoLevels.isBlank()) {
                        emptyList()
                    } else {
                        json.decodeFromString<List<Int?>>(row.setEchoLevels).map { ordinal ->
                            ordinal?.let { EchoLevel.entries.getOrNull(it) }
                        }
                    }
                } catch (e: Exception) {
                    Logger.w(e) {
                        "Failed to parse setEchoLevels '${row.setEchoLevels}' for exercise ${row.exerciseName}, using empty list"
                    }
                    emptyList()
                }

                val warmupSets: List<com.devil.phoenixproject.domain.model.WarmupSet> = try {
                    if (row.warmupSets.isBlank()) {
                        emptyList()
                    } else {
                        json.decodeFromString<List<com.devil.phoenixproject.domain.model.WarmupSet>>(row.warmupSets)
                    }
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to parse warmupSets '${row.warmupSets}' for exercise ${row.exerciseName}, using empty list" }
                    emptyList()
                }

                val defaultRackItemIds: List<String> = try {
                    if (row.defaultRackItemIds.isBlank()) {
                        emptyList()
                    } else {
                        json.decodeFromString<List<String>>(row.defaultRackItemIds)
                            .filter { it.isNotBlank() }
                            .distinct()
                    }
                } catch (e: Exception) {
                    Logger.w(e) {
                        "Failed to parse defaultRackItemIds '${row.defaultRackItemIds}' for exercise ${row.exerciseName}, using empty list"
                    }
                    emptyList()
                }

                val rackBehaviorOverrides: Map<String, RackItemBehavior> = try {
                    if (row.rackBehaviorOverrides.isBlank() || row.rackBehaviorOverrides == "{}") {
                        emptyMap()
                    } else {
                        json.decodeFromString<Map<String, RackItemBehavior>>(row.rackBehaviorOverrides)
                    }
                } catch (e: Exception) {
                    Logger.w(e) {
                        "Failed to parse rackBehaviorOverrides '${row.rackBehaviorOverrides}' for exercise ${row.exerciseName}, using empty map"
                    }
                    emptyMap()
                }

                val eccentricLoad = mapEccentricLoadFromDb(row.eccentricLoad)
                val echoLevel = EchoLevel.entries.getOrNull(row.echoLevel.toInt()) ?: EchoLevel.HARDER

                val programMode = parseProgramMode(row.mode)

                // Parse PR percentage scaling fields
                val prTypeForScaling = try {
                    PRType.valueOf(row.prTypeForScaling)
                } catch (e: Exception) {
                    Logger.w(e) { "Invalid prTypeForScaling '${row.prTypeForScaling}' for exercise ${row.exerciseName}, using MAX_WEIGHT" }
                    PRType.MAX_WEIGHT
                }

                val setWeightsPercentOfPR: List<Int> = try {
                    if (row.setWeightsPercentOfPR.isNullOrBlank()) {
                        emptyList()
                    } else {
                        json.decodeFromString<List<Int>>(row.setWeightsPercentOfPR)
                    }
                } catch (e: Exception) {
                    Logger.w(e) {
                        "Failed to parse setWeightsPercentOfPR '${row.setWeightsPercentOfPR}' for exercise ${row.exerciseName}, using empty list"
                    }
                    emptyList()
                }

                RoutineExercise(
                    id = row.id,
                    exercise = exerciseWithBodyweightFlag,
                    orderIndex = row.orderIndex.toInt(),
                    setReps = setReps,
                    weightPerCableKg = row.weightPerCableKg.toFloat(),
                    setWeightsPerCableKg = setWeights,
                    programMode = programMode,
                    eccentricLoad = eccentricLoad,
                    echoLevel = echoLevel,
                    progressionKg = row.progressionKg.toFloat(),
                    setRestSeconds = setRestSeconds,
                    setEchoLevels = setEchoLevels,
                    duration = row.duration?.toInt(),
                    isAMRAP = row.isAMRAP == 1L,
                    perSetRestTime = row.perSetRestTime == 1L,
                    // Per-exercise behavior overrides
                    stallDetectionEnabled = row.stallDetectionEnabled == 1L,
                    repCountTiming = try {
                        com.devil.phoenixproject.domain.model.RepCountTiming.valueOf(row.repCountTiming)
                    } catch (
                        _: Exception,
                    ) {
                        com.devil.phoenixproject.domain.model.RepCountTiming.TOP
                    },
                    stopAtTop = row.stopAtTop == 1L,
                    supersetId = row.supersetId,
                    orderInSuperset = row.orderInSuperset.toInt(),
                    // PR percentage scaling fields
                    usePercentOfPR = row.usePercentOfPR == 1L,
                    weightPercentOfPR = row.weightPercentOfPR.toInt(),
                    prTypeForScaling = prTypeForScaling,
                    setWeightsPercentOfPR = setWeightsPercentOfPR,
                    warmupSets = warmupSets,
                    defaultRackItemIds = defaultRackItemIds,
                    rackBehaviorOverrides = rackBehaviorOverrides,
                    scalingBasis = row.scalingBasis?.let { runCatching { ScalingBasis.valueOf(it) }.getOrNull() },
                    dropSetEnabled = row.dropSetEnabled == 1L,
                    dropSetMinWeightKg = row.dropSetMinWeightKg?.toFloat(),
                )
            } catch (e: Exception) {
                Logger.e(e) { "Failed to map routine exercise: ${row.exerciseId}" }
                null
            }
        }

        return Routine(
            id = routineId,
            name = name,
            description = description,
            exercises = exercises,
            supersets = supersets,
            createdAt = createdAt,
            lastUsed = lastUsed,
            useCount = useCount,
            profileId = profileId,
            groupId = groupId,
            updatedAt = updatedAt,
        )
    }

    private fun mapEccentricLoadFromDb(dbValue: Long): EccentricLoad {
        // Defensive clamping: Machine hardware limit is 150% eccentric load
        // Values > 150% can cause machine faults (yellow light)
        val safeValue = dbValue.toInt().coerceIn(0, 150)
        if (dbValue.toInt() != safeValue) {
            Logger.w { "DB eccentric load $dbValue% clamped to $safeValue% (hardware limit 150%)" }
        }

        return when (safeValue) {
            0 -> EccentricLoad.LOAD_0

            50 -> EccentricLoad.LOAD_50

            75 -> EccentricLoad.LOAD_75

            100 -> EccentricLoad.LOAD_100

            110 -> EccentricLoad.LOAD_110

            120 -> EccentricLoad.LOAD_120

            130 -> EccentricLoad.LOAD_130

            140 -> EccentricLoad.LOAD_140

            150 -> EccentricLoad.LOAD_150

            else -> {
                // Value is within 0-150 but not a standard enum value - find closest match
                Logger.w { "Non-standard eccentric load value: $safeValue%, finding closest match" }
                EccentricLoad.entries.minByOrNull { kotlin.math.abs(it.percentage - safeValue) }
                    ?: EccentricLoad.LOAD_100
            }
        }
    }

    private fun parseProgramMode(modeStr: String): ProgramMode = when {
        modeStr.startsWith("Program:") -> {
            val programModeName = modeStr.removePrefix("Program:")
            when (programModeName) {
                "OldSchool" -> ProgramMode.OldSchool
                "Pump" -> ProgramMode.Pump
                "TUT" -> ProgramMode.TUT
                "TUTBeast" -> ProgramMode.TUTBeast
                "EccentricOnly" -> ProgramMode.EccentricOnly
                "Echo" -> ProgramMode.Echo
                else -> ProgramMode.OldSchool
            }
        }

        modeStr == "Echo" || modeStr.startsWith("Echo") -> {
            ProgramMode.Echo
        }

        // Issue #7 Fix: Handle unprefixed legacy mode strings
        modeStr == "Pump" -> ProgramMode.Pump

        modeStr == "TUT" -> ProgramMode.TUT

        modeStr == "TUTBeast" -> ProgramMode.TUTBeast

        modeStr == "EccentricOnly" -> ProgramMode.EccentricOnly

        modeStr == "OldSchool" -> ProgramMode.OldSchool

        else -> ProgramMode.OldSchool
    }

    private fun serializeProgramMode(programMode: ProgramMode): String = when (programMode) {
        ProgramMode.OldSchool -> "Program:OldSchool"
        ProgramMode.Pump -> "Program:Pump"
        ProgramMode.TUT -> "Program:TUT"
        ProgramMode.TUTBeast -> "Program:TUTBeast"
        ProgramMode.EccentricOnly -> "Program:EccentricOnly"
        ProgramMode.Echo -> "Echo" // EchoLevel and EccentricLoad are stored separately in DB columns
    }

    override fun getAllSessions(profileId: String): Flow<List<WorkoutSession>> = queries.selectAllSessions(profileId = profileId, mapper = ::mapToSession)
        .asFlow()
        .mapToList(Dispatchers.IO)

    override fun getHistoryVisibleSessions(profileId: String): Flow<List<WorkoutSession>> =
        queries.selectHistoryVisibleSessions(profileId = profileId, mapper = ::mapToSession)
            .asFlow()
            .mapToList(Dispatchers.IO)

    override suspend fun getRecentCompletedSessionsForExercise(
        exerciseId: String,
        profileId: String,
        limit: Int,
    ): List<WorkoutSession> {
        require(exerciseId.isNotBlank()) { "exerciseId must not be blank" }
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(limit in 1..MAX_RECENT_EXERCISE_SESSIONS) {
            "limit must be in 1..$MAX_RECENT_EXERCISE_SESSIONS"
        }
        return withContext(Dispatchers.IO) {
            queries.selectRecentCompletedSessionsForExercise(
                profileId = profileId,
                exerciseId = exerciseId,
                limit = limit.toLong(),
                mapper = ::mapToSession,
            ).executeAsList()
        }
    }

    override suspend fun getMostRecentCompletedExerciseId(profileId: String): String? {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        return withContext(Dispatchers.IO) {
            queries.selectMostRecentCompletedExerciseId(profileId).executeAsOneOrNull()
        }
    }

    override suspend fun saveSession(session: WorkoutSession) {
        withContext(Dispatchers.IO) {
            insertSessionRow(session)
        }
    }

    /**
     * F-012: the whole completion in one transaction. See
     * [WorkoutRepository.commitCompletedSet] for the contract; the guards here
     * are the ones the former six-call sequence used, so a retry is a no-op.
     */
    override suspend fun commitCompletedSet(
        session: WorkoutSession,
        metrics: List<com.devil.phoenixproject.domain.model.WorkoutMetric>,
        completedSet: com.devil.phoenixproject.domain.model.CompletedSet?,
        repMetrics: List<com.devil.phoenixproject.domain.model.RepMetricData>,
        repBiomechanics: List<com.devil.phoenixproject.domain.model.BiomechanicsRepResult>,
    ) {
        withContext(Dispatchers.IO) {
            db.transaction {
                if (queries.selectSessionById(session.id).executeAsOneOrNull() == null) {
                    insertSessionRow(session)
                }
                if (metrics.isNotEmpty()) {
                    queries.deleteMetricsBySession(session.id)
                    metrics.forEach { metric -> insertMetricRow(session.id, metric) }
                }
                if (completedSet != null &&
                    queries.selectCompletedSetById(completedSet.id).executeAsOneOrNull() == null
                ) {
                    queries.insertCompletedSetRow(completedSet)
                }
                queries.deleteRepMetricsBySession(session.id)
                repMetrics.forEach { metric -> queries.insertRepMetricRow(session.id, metric) }
                queries.deleteRepBiomechanicsBySession(session.id)
                repBiomechanics.forEach { result -> queries.insertRepBiomechanicsRow(session.id, result) }
                // Main's newer sync-dirty contract: every child write marks its
                // session. The atomic commit writes those children in one
                // transaction, so it must dirty the session the same way the
                // old per-repo calls did — otherwise a committed set never pushes.
                queries.markWorkoutComponentDirty(session.id)
            }
        }
    }

    private fun insertSessionRow(session: WorkoutSession) {
        queries.insertSession(
            id = session.id,
            timestamp = session.timestamp,
            mode = session.mode,
            targetReps = session.reps.toLong(),
            weightPerCableKg = session.weightPerCableKg.toDouble(),
            progressionKg = session.progressionKg.toDouble(),
            duration = session.duration,
            totalReps = session.totalReps.toLong(),
            warmupReps = session.warmupReps.toLong(),
            workingReps = session.workingReps.toLong(),
            isJustLift = if (session.isJustLift) 1L else 0L,
            stopAtTop = if (session.stopAtTop) 1L else 0L,
            eccentricLoad = session.eccentricLoad.toLong(),
            echoLevel = session.echoLevel.toLong(),
            exerciseId = session.exerciseId,
            exerciseName = session.exerciseName,
            routineSessionId = session.routineSessionId,
            routineName = session.routineName,
            routineId = session.routineId,
            safetyFlags = session.safetyFlags.toLong(),
            deloadWarningCount = session.deloadWarningCount.toLong(),
            romViolationCount = session.romViolationCount.toLong(),
            spotterActivations = session.spotterActivations.toLong(),
            // New summary metrics
            peakForceConcentricA = session.peakForceConcentricA?.toDouble(),
            peakForceConcentricB = session.peakForceConcentricB?.toDouble(),
            peakForceEccentricA = session.peakForceEccentricA?.toDouble(),
            peakForceEccentricB = session.peakForceEccentricB?.toDouble(),
            avgForceConcentricA = session.avgForceConcentricA?.toDouble(),
            avgForceConcentricB = session.avgForceConcentricB?.toDouble(),
            avgForceEccentricA = session.avgForceEccentricA?.toDouble(),
            avgForceEccentricB = session.avgForceEccentricB?.toDouble(),
            heaviestLiftKg = session.heaviestLiftKg?.toDouble(),
            totalVolumeKg = session.totalVolumeKg?.toDouble(),
            cableCount = session.cableCount?.toLong(),
            estimatedCalories = session.estimatedCalories?.toDouble(),
            warmupAvgWeightKg = session.warmupAvgWeightKg?.toDouble(),
            workingAvgWeightKg = session.workingAvgWeightKg?.toDouble(),
            burnoutAvgWeightKg = session.burnoutAvgWeightKg?.toDouble(),
            peakWeightKg = session.peakWeightKg?.toDouble(),
            rpe = session.rpe?.toLong(),
            // Biomechanics summary
            avgMcvMmS = session.avgMcvMmS?.toDouble(),
            avgAsymmetryPercent = session.avgAsymmetryPercent?.toDouble(),
            totalVelocityLossPercent = session.totalVelocityLossPercent?.toDouble(),
            dominantSide = session.dominantSide,
            strengthProfile = session.strengthProfile,
            // Form Check score
            formScore = session.formScore?.toLong(),
            // Multi-profile support
            profile_id = session.profileId,
            // Equipment-aware weight display
            display_multiplier = session.displayMultiplier?.toLong(),
            externalAddedLoadKg = session.externalAddedLoadKg.toDouble(),
            counterweightKg = session.counterweightKg.toDouble(),
            rackItemsJson = session.rackItemsJson,
        )
    }

    private fun insertMetricRow(sessionId: String, metric: com.devil.phoenixproject.domain.model.WorkoutMetric) {
        // Calculate power: P = (loadA + loadB) × v (combined force × velocity for dual-cable)
        val power = (metric.loadA + metric.loadB) * metric.velocityA.toFloat()
        queries.insertMetric(
            sessionId = sessionId,
            timestamp = metric.timestamp,
            position = metric.positionA.toDouble(),
            positionB = metric.positionB.toDouble(),
            velocity = metric.velocityA,
            velocityB = metric.velocityB,
            load = metric.loadA.toDouble(),
            loadB = metric.loadB.toDouble(),
            power = power.toDouble(),
            status = metric.status.toLong(),
        )
    }

    override suspend fun updateSessionExerciseTag(sessionId: String, exerciseId: String, exerciseName: String) {
        withContext(Dispatchers.IO) {
            queries.updateSessionExerciseTag(
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                updatedAt = currentTimeMillis(),
                id = sessionId,
            )
        }
    }

    override suspend fun clearSessionExerciseTag(sessionId: String) {
        withContext(Dispatchers.IO) {
            queries.clearSessionExerciseTag(
                updatedAt = currentTimeMillis(),
                id = sessionId,
            )
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                val session = queries.selectSessionById(sessionId).executeAsOneOrNull()
                    ?: return@transaction
                val portalSessionId = session.routineSessionId
                    ?.takeIf { it.isNotBlank() }
                    ?: session.id
                val liveComponents = queries.selectLiveProfileWorkoutComponentsForDeletion(
                    profileId = session.profile_id,
                    portalSessionId = portalSessionId,
                ).executeAsList()
                val scope = if (session.routineSessionId.isNullOrBlank() || liveComponents.size <= 1) {
                    WorkoutDeletionScope.WORKOUT
                } else {
                    WorkoutDeletionScope.COMPONENT
                }
                insertLocalDeletion(
                    profileId = session.profile_id,
                    scope = scope,
                    portalSessionId = portalSessionId,
                    componentSessionId = session.id.takeIf { scope == WorkoutDeletionScope.COMPONENT },
                    deletedAt = currentTimeMillis(),
                )
                if (scope == WorkoutDeletionScope.WORKOUT) {
                    queries.hardDeleteProfileWorkoutPortalParent(
                        profileId = session.profile_id,
                        portalSessionId = portalSessionId,
                    )
                } else {
                    queries.hardDeleteWorkoutComponent(session.id)
                }
            }
        }
    }

    override suspend fun deleteSessionsByRoutineSessionId(profileId: String, routineSessionId: String) {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(routineSessionId.isNotBlank()) { "routineSessionId must not be blank" }
        withContext(Dispatchers.IO) {
            db.transaction {
                val components = queries.selectLiveProfileWorkoutComponentsForDeletion(
                    profileId = profileId,
                    portalSessionId = routineSessionId,
                ).executeAsList()
                if (components.isEmpty()) return@transaction
                insertLocalDeletion(
                    profileId = profileId,
                    scope = WorkoutDeletionScope.WORKOUT,
                    portalSessionId = routineSessionId,
                    componentSessionId = null,
                    deletedAt = currentTimeMillis(),
                )
                queries.hardDeleteProfileWorkoutPortalParent(
                    profileId = profileId,
                    portalSessionId = routineSessionId,
                )
            }
        }
    }

    override suspend fun deleteAllSessions(profileId: String) {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        withContext(Dispatchers.IO) {
            db.transaction {
                val deletedAt = currentTimeMillis()
                val portalSessionIds = queries
                    .selectDistinctLiveWorkoutPortalParentsForProfile(profileId)
                    .executeAsList()
                portalSessionIds.forEach { portalSessionId ->
                    insertLocalDeletion(
                        profileId = profileId,
                        scope = WorkoutDeletionScope.WORKOUT,
                        portalSessionId = portalSessionId,
                        componentSessionId = null,
                        deletedAt = deletedAt,
                    )
                }
                queries.hardDeleteAllWorkoutSessionsForProfile(profileId)
            }
        }
    }

    override suspend fun discardSessionInternal(sessionId: String) {
        withContext(Dispatchers.IO) {
            queries.hardDeleteWorkoutComponent(sessionId)
        }
    }

    private fun insertLocalDeletion(
        profileId: String,
        scope: WorkoutDeletionScope,
        portalSessionId: String,
        componentSessionId: String?,
        deletedAt: Long,
    ) {
        val ownerUserId = queries.getProfileById(profileId)
            .executeAsOneOrNull()
            ?.supabase_user_id
        queries.insertWorkoutDeletion(
            mutationId = generateUUID(),
            ownerUserId = ownerUserId,
            profileId = profileId,
            scope = scope.name,
            portalSessionId = portalSessionId,
            componentSessionId = componentSessionId,
            deletedAt = deletedAt,
            source = WorkoutDeletionSource.LOCAL.name,
        )
    }

    /**
     * Issue #1162: one-shot owner-scoped identity maintenance runs before the
     * first list emission for this profile (before the reactive query is even
     * subscribed — never inside its mapper), so already-split alias rows are one
     * card on an offline launch without any save or named pull. Maintenance
     * failure leaves the original rows visible and logs a diagnostic.
     */
    override fun getAllRoutines(profileId: String): Flow<List<Routine>> = flow {
        ensureRoutineIdentityMaintenance(profileId)
        emitAll(
            queries.selectAllRoutines(profileId = profileId, mapper = ::mapToRoutineBasic)
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { basicRoutines ->
                    // Load exercises for each routine
                    basicRoutines.map { routine ->
                        try {
                            loadRoutineWithExercises(
                                routine.id,
                                routine.name,
                                routine.createdAt,
                                routine.lastUsed,
                                routine.useCount,
                                routine.profileId,
                                routine.groupId,
                                updatedAt = routine.updatedAt,
                                description = routine.description,
                            )
                        } catch (e: Exception) {
                            Logger.e(e) { "Failed to load exercises for routine ${routine.id}" }
                            routine
                        }
                    }
                },
        )
    }

    /**
     * Issue #1162 merge gate R1: maintenance runs at most once per profile per
     * repository, serialized with its completion bookkeeping in one lock — every
     * first collector waits for the in-flight run, and the profile is marked
     * maintained ONLY after successful completion. A cancelled or failed run
     * leaves the profile retryable (the next collector runs maintenance again).
     */
    private suspend fun ensureRoutineIdentityMaintenance(profileId: String) {
        identityMaintenanceMutex.withLock {
            if (profileId in identityMaintainedProfiles) return
            val result = runRoutineIdentityMaintenance(profileId)
            if (!result.failed) {
                identityMaintainedProfiles += profileId
            }
        }
    }

    /**
     * Issue #1162 eager identity maintenance, run transactionally per selected
     * profile before the first routine-list emission (offline launch and profile
     * switch included), independent of named writes and network pulls.
     *
     * Each same-profile identity component (validated full UUID equivalence or a
     * genuine stored `serverId` edge, owner-scoped) is reconciled once: the full
     * source graph is retained in the local-only recovery store first, then the
     * aliases coalesce into the kept row with its stored primary key preserved. A
     * tombstoned component keeps the tombstoned row with `deletedAt` preserved.
     * Any failure rolls the whole run back (original rows stay visible) and is
     * reported here as a diagnostic.
     */
    override suspend fun runRoutineIdentityMaintenance(profileId: String): RoutineIdentityMaintenanceResult =
        withContext(Dispatchers.IO) {
            var result = RoutineIdentityMaintenanceResult()
            try {
                db.transaction {
                    val now = currentTimeMillis()
                    routineRecoveryStore.pruneExpired(now)
                    val rows = queries.selectAllRoutinesByProfileIncludingDeleted(profileId).executeAsList()
                    val visited = mutableSetOf<String>()
                    var components = 0
                    var reconciled = 0
                    var removed = 0
                    var snapshots = 0
                    var tombstones = 0
                    for (row in rows) {
                        if (row.id in visited) continue
                        val candidates = routineIdentityResolver.findCandidates(
                            incomingId = row.id,
                            scopeProfileId = profileId,
                        ) { it.profile_id == profileId }
                        candidates.forEach { visited += it.id }
                        if (candidates.size < 2) continue
                        components++
                        // The resolver retains the full source graph BEFORE the
                        // destructive coalesce, in this same transaction (merge
                        // gate R2). Failure throws and rolls everything back.
                        val reconciliation = routineIdentityResolver
                            .reconcileExistingComponent(candidates) ?: continue
                        reconciled++
                        snapshots++
                        removed += reconciliation.removedIds.size
                        if (reconciliation.tombstonePreserved) tombstones++
                        Logger.d("RoutineIdentity") {
                            "Issue #1162 maintenance (profile=$profileId): kept '${reconciliation.keeperId}', " +
                                "removed aliases ${reconciliation.removedIds}, " +
                                "tombstonePreserved=${reconciliation.tombstonePreserved}"
                        }
                    }
                    result = RoutineIdentityMaintenanceResult(
                        componentsScanned = components,
                        componentsReconciled = reconciled,
                        aliasesRemoved = removed,
                        snapshotsRetained = snapshots,
                        tombstoneComponents = tombstones,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Collector cancellation must propagate: swallowing it here would
                // return a success-shaped result and let the caller mark the
                // profile maintained with no work done.
                throw e
            } catch (e: Exception) {
                Logger.e(e) {
                    "Issue #1162 identity maintenance failed for profile=$profileId; " +
                        "the run was rolled back and the original rows stay visible"
                }
                result = RoutineIdentityMaintenanceResult(failed = true, error = e.message)
            }
            result
        }

    /**
     * Issue #1162 final audit R4: recovery listing/restoration is authorized ONLY
     * for the authenticated portal identity and the active profile, resolved at
     * execution time. Rejected BEFORE any read or write: a missing/blank
     * authenticated identity (unsigned or logged out), a caller-supplied identity
     * that differs from the authenticated one, a profile that is not the active
     * profile, a missing profile row, and a profile whose stored owner differs
     * from the authenticated account (e.g. the former owner after an account
     * switch). Anonymous snapshots stay retained but hidden — never destroyed or
     * relabeled. A genuinely signed-in cached session keeps working offline.
     */
    private fun recoveryAccessAllowed(profileId: String, portalUserId: String): Boolean {
        val authenticated = signedInPortalUserId()?.takeIf { it.isNotBlank() } ?: return false
        if (portalUserId != authenticated) return false
        if (activeProfileId() != profileId) return false
        val profile = queries.getProfileById(profileId).executeAsOneOrNull() ?: return false
        if (profile.supabase_user_id != authenticated) return false
        return true
    }

    override suspend fun listRoutineRecoveries(
        profileId: String,
        portalUserId: String,
    ): List<RoutineRecoveryItem> = withContext(Dispatchers.IO) {
        if (!recoveryAccessAllowed(profileId, portalUserId)) {
            return@withContext emptyList()
        }
        routineRecoveryStore.listRecoverableRoutines(
            profileId = profileId,
            portalUserId = portalUserId,
            now = currentTimeMillis(),
        )
    }

    /**
     * Issue #1162 final audit R5: owner/profile-scoped observation of the local
     * recovery store. Emits whenever retained snapshots change, so a mounted
     * recovery surface discovers new recoveries without a remount, and
     * re-authorized at every emission (R4) — a scope that lost its authenticated
     * owner or active-profile status publishes nothing.
     */
    override fun observeRoutineRecoveries(
        profileId: String,
        portalUserId: String,
    ): Flow<List<RoutineRecoveryItem>> =
        queries.selectRoutineRecoveriesByProfile(
            profileId = profileId,
            portalUserId = portalUserId,
            now = currentTimeMillis(),
        )
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map {
                if (!recoveryAccessAllowed(profileId, portalUserId)) {
                    emptyList()
                } else {
                    try {
                        routineRecoveryStore.listRecoverableRoutines(
                            profileId = profileId,
                            portalUserId = portalUserId,
                            now = currentTimeMillis(),
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(e) { "RoutineRecovery: could not load recoverable routines" }
                        emptyList()
                    }
                }
            }

    /**
     * Issue #1162 explicit restore-as-copy, revalidated at execution time (final
     * audit R4): the authenticated identity, active profile and profile owner
     * are checked immediately before any write, so a scope that changed after
     * the list was loaded can never restore another owner's snapshot.
     */
    override suspend fun restoreRoutineRecoveryAsCopy(
        recoveryId: String,
        graphIndex: Int,
        profileId: String,
        portalUserId: String,
    ): String? = withContext(Dispatchers.IO) {
        if (!recoveryAccessAllowed(profileId, portalUserId)) {
            return@withContext null
        }
        db.transactionWithResult {
            routineRecoveryStore.restoreAsCopy(
                recoveryId = recoveryId,
                graphIndex = graphIndex,
                profileId = profileId,
                portalUserId = portalUserId,
                now = currentTimeMillis(),
            )
        }
    }

    /**
     * Best-effort heal of a RoutineExercise.exerciseId. A failed write (e.g. a foreign-key
     * violation) must not drop the exercise from the loaded routine: the caller keeps the
     * resolved exercise and the row stays unhealed until the next load (F-083).
     */
    private fun healRoutineExerciseId(exerciseId: String?, rowId: String) {
        try {
            queries.updateRoutineExerciseId(exerciseId, rowId)
        } catch (e: Exception) {
            Logger.w(e) { "Could not heal exerciseId for routine exercise $rowId; keeping the unhealed row" }
        }
    }

    override suspend fun saveRoutine(routine: Routine) {
        withContext(Dispatchers.IO) {
            // Generate a UUID for the routine if not provided. A generated identity is
            // written in canonical lowercase UUID text (Issue #1162 wire convention).
            val requestedId = routine.id.takeIf { it.isNotBlank() }
                ?: RoutineIdentity.canonicalize(generateUUID())

            db.transaction {
                val routineId = resolveWritableRoutineId(requestedId, routine.profileId, routine.name)
                    ?: return@transaction
                writeRoutineRows(routine, routineId)
            }

            Logger.d { "Saved routine '${routine.name}' with ${routine.exercises.size} exercises and ${routine.supersets.size} supersets" }
        }
    }

    /**
     * Issue #1162 write boundary: the one owner-scoped primary key this identity may
     * write, or null when the write must be refused. Every caller of [writeRoutineRows]
     * resolves here first, so the same resolver/owner/tombstone guards hold before all
     * routine writes:
     * - a primary key already owned by another profile is never written;
     * - a deleted identity is never silently resurrected. The tombstone check runs on
     *   the pure candidate read before alias reconciliation, so a refused write also
     *   leaves already-split rows untouched.
     * A validated-UUID case variant or genuine `serverId` alias of an existing row
     * resolves to that row instead of creating a canonical alias. Returns null when the
     * write must be skipped. Must run inside a [db.transaction] block.
     */
    private fun resolveWritableRoutineId(requestedId: String, profileId: String, routineName: String): String? {
        val ownerScope: (RoutineRow) -> Boolean = { it.profile_id == profileId }
        val tombstone = routineIdentityResolver
            .findCandidates(requestedId, scopeProfileId = profileId, scopeMatches = ownerScope)
            .firstOrNull { it.deletedAt != null }
        if (tombstone != null) {
            Logger.e("WorkoutRepository") {
                "Skipping write of routine '$routineName': identity '$requestedId' is deleted locally " +
                    "('${tombstone.id}'); a deleted identity is never resurrected by a write"
            }
            return null
        }
        val resolution = routineIdentityResolver.resolve(
            requestedId,
            scopeProfileId = profileId,
            source = "named_write",
            scopeMatches = ownerScope,
        )
        val foreignRow = queries.selectRoutineById(resolution.localId).executeAsOneOrNull()
            ?.takeIf { it.profile_id != profileId }
        if (foreignRow != null) {
            Logger.e("WorkoutRepository") {
                "Skipping write of routine '$routineName': primary key '${resolution.localId}' " +
                    "belongs to profile '${foreignRow.profile_id}', not '$profileId'"
            }
            return null
        }
        return resolution.localId
    }

    /** The routine row, then its supersets and exercises. Callers run it inside a transaction. */
    private fun writeRoutineRows(routine: Routine, routineId: String) {
        // Upsert in place: UPDATE, then INSERT OR IGNORE. A REPLACE would cascade-delete
        // the routine and null CycleDay.routine_id for any training cycle using it.
        val updatedAt = currentTimeMillis()
        queries.updateRoutineFields(
            name = routine.name,
            description = routine.description,
            createdAt = routine.createdAt,
            lastUsed = routine.lastUsed,
            useCount = routine.useCount.toLong(),
            updatedAt = updatedAt,
            profile_id = routine.profileId,
            groupId = routine.groupId,
            id = routineId,
        )
        queries.insertRoutineIgnore(
            id = routineId,
            name = routine.name,
            description = routine.description,
            createdAt = routine.createdAt,
            lastUsed = routine.lastUsed,
            useCount = routine.useCount.toLong(),
            updatedAt = updatedAt,
            profile_id = routine.profileId,
            groupId = routine.groupId,
        )

        // Reconcile supersets and exercises in place; only genuinely removed children
        // are deleted (see rebuildRoutineChildren).
        rebuildRoutineChildren(routine, routineId)
    }

    /**
     * Reconcile the routine's supersets and exercises against the model in place
     * (Issue #1162). A model child that matches an existing row of this routine by
     * identity — its own primary-key text, or a validated-UUID case variant of it —
     * updates that row in place instead of delete + re-insert. The old delete-and-
     * rebuild fired RoutineExercise's cascading foreign keys immediately: PlannedSet
     * rows were deleted (ON DELETE CASCADE) and CompletedSet.planned_set_id was NULLed
     * (ON DELETE SET NULL) before the same primary key came back, and a same-PK
     * re-insert cannot undo either action. Only children the model genuinely removed
     * are deleted now, and never a row owned by another routine. Callers run it inside
     * a transaction.
     */
    private fun rebuildRoutineChildren(routine: Routine, routineId: String) {
        val previousDurations = snapshotRoutineExerciseDurations(routineId)
        val existingSupersets = queries.selectSupersetsByRoutine(routineId).executeAsList()
        val existingExercises = queries.selectExercisesByRoutine(routineId).executeAsList()

        // Supersets first: RoutineExercise.supersetId references them.
        val matchedSupersetIds = HashSet<String>()
        val supersetIdByModelId = HashMap<String, String>()
        routine.supersets.forEach { superset ->
            val existing = matchExistingChild(existingSupersets, matchedSupersetIds, superset.id) { it.id }
            val stableId = existing?.id ?: superset.id
            supersetIdByModelId[superset.id] = stableId
            if (existing != null) {
                queries.updateSuperset(
                    name = superset.name,
                    colorIndex = superset.colorIndex.toLong(),
                    restBetweenSeconds = superset.restBetweenSeconds.toLong(),
                    orderIndex = superset.orderIndex.toLong(),
                    id = stableId,
                )
            } else {
                rejectForeignChildPrimaryKey("superset", superset.id, routineId)
                insertSuperset(routineId, superset.copy(id = stableId))
            }
        }

        val matchedExerciseIds = HashSet<String>()
        routine.exercises.forEachIndexed { index, exercise ->
            val existing = matchExistingChild(existingExercises, matchedExerciseIds, exercise.id) { it.id }
            if (existing == null) {
                rejectForeignChildPrimaryKey("exercise", exercise.id, routineId)
            }
            val row = exercise.copy(
                id = existing?.id ?: exercise.id,
                supersetId = exercise.supersetId?.let { supersetIdByModelId[it] ?: it },
            )
            writeRoutineExerciseRow(routineId, row, index, previousDurations, existingRowId = existing?.id)
        }

        // Children the model no longer has are the only rows deleted. A row whose
        // identity is still in the model (e.g. a duplicate UUID-case alias row no
        // model child claimed) stays put rather than cascading its planned sets away.
        val keptExerciseKeys = routine.exercises.mapTo(HashSet()) { RoutineIdentity.canonicalize(it.id) }
        existingExercises.filter { RoutineIdentity.canonicalize(it.id) !in keptExerciseKeys }
            .forEach { queries.deleteRoutineExerciseById(it.id) }
        val keptSupersetKeys = routine.supersets.mapTo(HashSet()) { RoutineIdentity.canonicalize(it.id) }
        existingSupersets.filter { RoutineIdentity.canonicalize(it.id) !in keptSupersetKeys }
            .forEach { queries.deleteSuperset(it.id) }
    }

    /**
     * The existing child row a model child updates in place: its own primary-key text
     * first, then a validated-UUID case variant no other model child has taken. Only
     * the routine's own rows are ever matched, so a primary key owned by another
     * routine is never updated through an identity match.
     */
    private fun <T> matchExistingChild(
        existing: List<T>,
        taken: MutableSet<String>,
        modelId: String,
        idOf: (T) -> String,
    ): T? {
        val match = existing.firstOrNull { idOf(it) !in taken && idOf(it) == modelId }
            ?: existing.firstOrNull {
                idOf(it) !in taken &&
                    RoutineIdentity.canonicalize(idOf(it)) == RoutineIdentity.canonicalize(modelId)
            }
        if (match != null) taken += idOf(match)
        return match
    }

    /**
     * A model child whose primary key is not one of this routine's rows must not
     * overwrite the same key on another routine (CSV imports and restores can carry
     * ids already stored elsewhere). Fail the transaction with an actionable error
     * instead of mutating the other row.
     */
    private fun rejectForeignChildPrimaryKey(kind: String, childId: String, routineId: String) {
        if (childId.isBlank()) return
        val taken = when (kind) {
            "superset" -> queries.selectSupersetById(childId).executeAsOneOrNull() != null
            else -> queries.selectRoutineExerciseById(childId).executeAsOneOrNull() != null
        }
        if (taken) {
            throw IllegalStateException(
                "Routine child conflict: $kind '$childId' (requested for routine '$routineId') " +
                    "already belongs to another routine; refusing to overwrite it",
            )
        }
    }

    override suspend fun getRoutineHeaders(profileId: String): List<Routine> = withContext(Dispatchers.IO) {
        queries.selectAllRoutines(profileId = profileId, mapper = ::mapToRoutineBasic).executeAsList()
    }

    override suspend fun getRoutineGroupsSnapshot(profileId: String): List<RoutineGroup> = withContext(Dispatchers.IO) {
        queries.selectAllRoutineGroups(profileId = profileId, mapper = ::mapToRoutineGroup).executeAsList()
    }

    override suspend fun commitRoutineCsvImport(
        profileId: String,
        newGroups: List<RoutineGroup>,
        routines: List<Routine>,
        overwriteRoutineIds: Set<String>,
    ) {
        withContext(Dispatchers.IO) {
            db.transaction {
                // The preview was read outside this transaction; a target deleted or moved since
                // then must not be resurrected or written into another profile.
                for (routineId in overwriteRoutineIds) {
                    val row = queries.selectRoutineById(routineId).executeAsOneOrNull()
                    if (row == null || row.profile_id != profileId || row.deletedAt != null) {
                        throw RoutineCsvImportConflictException(routineId)
                    }
                }
                for (group in newGroups) {
                    queries.insertRoutineGroup(
                        id = group.id,
                        name = group.name,
                        orderIndex = group.orderIndex.toLong(),
                        createdAt = group.createdAt,
                        profile_id = profileId,
                    )
                }
                for (routine in routines) {
                    // Issue #1162 write boundary: the same resolver/owner/tombstone
                    // guards as saveRoutine hold before every writeRoutineRows write.
                    // A case-variant id updates the existing identity instead of
                    // inserting a second logical UUID, a raw primary key owned by
                    // another profile is never written, and a deleted identity is
                    // never silently resurrected. Each of those is an actionable
                    // import conflict, not a silent mutation.
                    val routineId = resolveWritableRoutineId(routine.id, profileId, routine.name)
                        ?: throw RoutineCsvImportConflictException(routine.id)
                    writeRoutineRows(routine.copy(profileId = profileId), routineId)
                }
            }
            Logger.d { "Committed CSV import: ${routines.size} routines, ${newGroups.size} new groups" }
        }
    }

    /** Local (duration, durationSyncKnown) per routine exercise id, read before the child rows are written. */
    private fun snapshotRoutineExerciseDurations(routineId: String): Map<String, Pair<Long?, Long>> =
        queries.selectExercisesByRoutine(routineId).executeAsList()
            .associate { it.id to (it.duration to it.durationSyncKnown) }

    /**
     * Write one routine exercise row. A non-null [existingRowId] updates that row in
     * place (Issue #1162: an identity-matched child is never deleted first, so its
     * PlannedSet rows and CompletedSet planned-set links stay attached); null inserts
     * a new row. Callers run it inside a transaction.
     */
    private fun writeRoutineExerciseRow(
        routineId: String,
        exercise: RoutineExercise,
        index: Int,
        previousDurations: Map<String, Pair<Long?, Long>>,
        existingRowId: String?,
    ) {
        // Generate a UUID for the exercise if not provided (canonical lowercase text)
        val exerciseRowId = existingRowId
            ?: exercise.id.takeIf { it.isNotBlank() }
            ?: RoutineIdentity.canonicalize(generateUUID())

        val exerciseName = exercise.exercise.name
        val exerciseMuscleGroup = exercise.exercise.muscleGroup
        val exerciseEquipment = exercise.exercise.equipment
        val exerciseDefaultCableConfig = "DOUBLE" // Legacy field - no longer used
        val exerciseId = exercise.exercise.id
        val cableConfig = "DOUBLE" // Legacy field - no longer used
        val orderIndex = index.toLong()
        val setReps = exercise.setReps.joinToString(",") { it?.toString() ?: "AMRAP" }
        val weightPerCableKg = exercise.weightPerCableKg.toDouble()
        val setWeights = exercise.setWeightsPerCableKg.joinToString(",")
        val mode = serializeProgramMode(exercise.programMode)
        val eccentricLoad = exercise.eccentricLoad.percentage.toLong()
        val echoLevel = exercise.echoLevel.ordinal.toLong()
        val progressionKg = exercise.progressionKg.toDouble()
        val restSeconds = exercise.setRestSeconds.firstOrNull()?.toLong() ?: 60L
        val duration = exercise.duration?.toLong()
        val setRestSeconds = json.encodeToString(exercise.setRestSeconds)
        val perSetRestTime = if (exercise.perSetRestTime) 1L else 0L
        val isAMRAP = if (exercise.isAMRAP) 1L else 0L
        val supersetId = exercise.supersetId
        val orderInSuperset = exercise.orderInSuperset.toLong()
        // PR percentage scaling fields
        val usePercentOfPR = if (exercise.usePercentOfPR) 1L else 0L
        val weightPercentOfPR = exercise.weightPercentOfPR.toLong()
        val prTypeForScaling = exercise.prTypeForScaling.name
        val setWeightsPercentOfPR = if (exercise.setWeightsPercentOfPR.isEmpty()) {
            null
        } else {
            json.encodeToString(
                exercise.setWeightsPercentOfPR,
            )
        }
        // Per-exercise behavior overrides
        val stallDetectionEnabled = if (exercise.stallDetectionEnabled) 1L else 0L
        val stopAtTop = if (exercise.stopAtTop) 1L else 0L
        val repCountTiming = exercise.repCountTiming.name
        // Per-set echo levels (stored as JSON array of nullable ordinals)
        val setEchoLevels = if (exercise.setEchoLevels.isEmpty()) {
            ""
        } else {
            json.encodeToString(exercise.setEchoLevels.map { it?.ordinal })
        }
        // Variable warm-up sets (Phase 35C)
        val warmupSets = if (exercise.warmupSets.isEmpty()) {
            ""
        } else {
            json.encodeToString(exercise.warmupSets)
        }
        val defaultRackItemIds = json.encodeToString(
            exercise.defaultRackItemIds.filter { it.isNotBlank() }.distinct(),
        )
        val rackBehaviorOverrides = if (exercise.rackBehaviorOverrides.isEmpty()) {
            "{}"
        } else {
            json.encodeToString(exercise.rackBehaviorOverrides)
        }
        val scalingBasis = exercise.scalingBasis?.name
        // #635: persist the explicit flag so reloads and sync keep the exact
        // classification (null = derive from equipment, pre-migration behavior)
        val isBodyweight = exercise.exercise.isBodyweightOverride?.let { if (it) 1L else 0L }
        val dropSetEnabled = if (exercise.dropSetEnabled) 1L else 0L
        val dropSetMinWeightKg = exercise.dropSetMinWeightKg?.toDouble()

        if (existingRowId != null) {
            queries.updateRoutineExercise(
                exerciseName = exerciseName,
                exerciseMuscleGroup = exerciseMuscleGroup,
                exerciseEquipment = exerciseEquipment,
                exerciseDefaultCableConfig = exerciseDefaultCableConfig,
                exerciseId = exerciseId,
                cableConfig = cableConfig,
                orderIndex = orderIndex,
                setReps = setReps,
                weightPerCableKg = weightPerCableKg,
                setWeights = setWeights,
                mode = mode,
                eccentricLoad = eccentricLoad,
                echoLevel = echoLevel,
                progressionKg = progressionKg,
                restSeconds = restSeconds,
                duration = duration,
                setRestSeconds = setRestSeconds,
                perSetRestTime = perSetRestTime,
                isAMRAP = isAMRAP,
                supersetId = supersetId,
                orderInSuperset = orderInSuperset,
                usePercentOfPR = usePercentOfPR,
                weightPercentOfPR = weightPercentOfPR,
                prTypeForScaling = prTypeForScaling,
                setWeightsPercentOfPR = setWeightsPercentOfPR,
                stallDetectionEnabled = stallDetectionEnabled,
                stopAtTop = stopAtTop,
                repCountTiming = repCountTiming,
                setEchoLevels = setEchoLevels,
                warmupSets = warmupSets,
                defaultRackItemIds = defaultRackItemIds,
                rackBehaviorOverrides = rackBehaviorOverrides,
                scalingBasis = scalingBasis,
                isBodyweight = isBodyweight,
                dropSetEnabled = dropSetEnabled,
                dropSetMinWeightKg = dropSetMinWeightKg,
                id = exerciseRowId,
            )
        } else {
            queries.insertRoutineExercise(
                id = exerciseRowId,
                routineId = routineId,
                exerciseName = exerciseName,
                exerciseMuscleGroup = exerciseMuscleGroup,
                exerciseEquipment = exerciseEquipment,
                exerciseDefaultCableConfig = exerciseDefaultCableConfig,
                exerciseId = exerciseId,
                cableConfig = cableConfig,
                orderIndex = orderIndex,
                setReps = setReps,
                weightPerCableKg = weightPerCableKg,
                setWeights = setWeights,
                mode = mode,
                eccentricLoad = eccentricLoad,
                echoLevel = echoLevel,
                progressionKg = progressionKg,
                restSeconds = restSeconds,
                duration = duration,
                setRestSeconds = setRestSeconds,
                perSetRestTime = perSetRestTime,
                isAMRAP = isAMRAP,
                supersetId = supersetId,
                orderInSuperset = orderInSuperset,
                usePercentOfPR = usePercentOfPR,
                weightPercentOfPR = weightPercentOfPR,
                prTypeForScaling = prTypeForScaling,
                setWeightsPercentOfPR = setWeightsPercentOfPR,
                stallDetectionEnabled = stallDetectionEnabled,
                stopAtTop = stopAtTop,
                repCountTiming = repCountTiming,
                setEchoLevels = setEchoLevels,
                warmupSets = warmupSets,
                defaultRackItemIds = defaultRackItemIds,
                rackBehaviorOverrides = rackBehaviorOverrides,
                scalingBasis = scalingBasis,
                isBodyweight = isBodyweight,
                dropSetEnabled = dropSetEnabled,
                dropSetMinWeightKg = dropSetMinWeightKg,
            )
        }

        // Portal sync (PR 13): the duration is known to be current when this build
        // created the row, when it was already known, or when the user changed it.
        // An untouched row from an older build keeps "unknown" so a stale NULL is not
        // pushed as an explicit clear.
        val previous = previousDurations[exerciseRowId]
        val durationSyncState = when {
            previous == null ||
                previous.second == 1L ||
                previous.first != exercise.duration?.toLong() -> 1L
            // Preserve both legacy-unknown (0) and observed-malformed (2) when an
            // unrelated local edit rewrites the row. State 2 prevents another full
            // pull while still omitting a null duration from the next push.
            else -> previous.second
        }
        if (durationSyncState != 0L) {
            queries.updateRoutineExerciseDurationSyncKnown(durationSyncState, exerciseRowId)
        }
    }

    private fun insertSuperset(routineId: String, superset: Superset) {
        queries.insertSuperset(
            id = superset.id,
            routineId = routineId,
            name = superset.name,
            colorIndex = superset.colorIndex.toLong(),
            restBetweenSeconds = superset.restBetweenSeconds.toLong(),
            orderIndex = superset.orderIndex.toLong(),
        )
    }

    override suspend fun updateRoutine(routine: Routine) {
        withContext(Dispatchers.IO) {
            val requestedId = routine.id.takeIf { it.isNotBlank() } ?: return@withContext

            db.transaction {
                // Issue #1162: the update lands on the resolved owner-scoped primary key.
                val routineId = resolveWritableRoutineId(requestedId, routine.profileId, routine.name)
                    ?: return@transaction

                // Update the routine
                queries.updateRoutineById(
                    name = routine.name,
                    description = routine.description,
                    updatedAt = currentTimeMillis(),
                    id = routineId,
                )

                // Reconcile supersets and exercises in place; only genuinely removed
                // children are deleted (see rebuildRoutineChildren).
                rebuildRoutineChildren(routine, routineId)
            }

            Logger.d {
                "Updated routine '${routine.name}' with ${routine.exercises.size} exercises and ${routine.supersets.size} supersets"
            }
        }
    }

    override suspend fun deleteRoutine(routineId: String) {
        withContext(Dispatchers.IO) {
            if (routineId.isBlank()) return@withContext

            val now = currentTimeMillis()
            queries.softDeleteRoutine(
                deletedAt = now,
                updatedAt = now,
                id = routineId,
            )

            Logger.d { "Soft-deleted routine $routineId (deletedAt=$now)" }
        }
    }

    override suspend fun moveRoutineToProfile(routineId: String, targetProfileId: String) {
        withContext(Dispatchers.IO) {
            queries.adoptRoutineProfile(profileId = targetProfileId, id = routineId)
            Logger.d { "Moved routine $routineId to profile $targetProfileId" }
        }
    }

    override suspend fun getRoutineById(routineId: String): Routine? {
        return withContext(Dispatchers.IO) {
            if (routineId.isBlank()) return@withContext null
            val basicRoutine = queries.selectRoutineById(routineId, ::mapToRoutineBasic).executeAsOneOrNull()
                ?: return@withContext null

            loadRoutineWithExercises(
                routineId,
                basicRoutine.name,
                basicRoutine.createdAt,
                basicRoutine.lastUsed,
                basicRoutine.useCount,
                basicRoutine.profileId,
                basicRoutine.groupId,
                updatedAt = basicRoutine.updatedAt,
                description = basicRoutine.description,
            )
        }
    }

    // ==================== ROUTINE GROUP CRUD ====================

    fun getAllRoutineGroups(profileId: String): Flow<List<RoutineGroup>> =
        queries.selectAllRoutineGroups(profileId = profileId, mapper = ::mapToRoutineGroup).asFlow().mapToList(Dispatchers.IO)

    private fun mapToRoutineGroup(id: String, name: String, orderIndex: Long, createdAt: Long, profileId: String): RoutineGroup =
        RoutineGroup(
            id = id,
            name = name,
            orderIndex = orderIndex.toInt(),
            createdAt = createdAt,
            profileId = profileId,
        )

    suspend fun saveRoutineGroup(group: RoutineGroup) {
        withContext(Dispatchers.IO) {
            queries.insertRoutineGroup(
                id = group.id,
                name = group.name,
                orderIndex = group.orderIndex.toLong(),
                createdAt = group.createdAt,
                profile_id = group.profileId,
            )
            Logger.d { "Saved routine group '${group.name}'" }
        }
    }

    suspend fun updateRoutineGroup(group: RoutineGroup) {
        withContext(Dispatchers.IO) {
            queries.updateRoutineGroup(
                name = group.name,
                orderIndex = group.orderIndex.toLong(),
                id = group.id,
            )
            Logger.d { "Updated routine group '${group.name}'" }
        }
    }

    suspend fun deleteRoutineGroup(groupId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteRoutineGroup(groupId)
            Logger.d { "Deleted routine group $groupId" }
        }
    }

    suspend fun moveRoutineToGroup(routineId: String, groupId: String?) {
        withContext(Dispatchers.IO) {
            queries.updateRoutineGroupId(groupId, routineId)
            Logger.d { "Moved routine $routineId to group ${groupId ?: "ungrouped"}" }
        }
    }

    override suspend fun getAverageSetDurationMs(exerciseId: String, profileId: String): Long? = withContext(Dispatchers.IO) {
        queries.selectAverageSetDurationMs(exerciseId, profileId = profileId)
            .executeAsOneOrNull()
            ?.avgDurationMs
            ?.toLong()
    }

    override suspend fun getSessionCountForExercise(exerciseId: String, profileId: String): Long = withContext(Dispatchers.IO) {
        queries.selectSessionCountForExercise(exerciseId, profileId = profileId)
            .executeAsOne()
    }

    // ========== New methods for full parity ==========

    override fun getRecentSessions(profileId: String, limit: Int): Flow<List<WorkoutSession>> = queries.selectRecentVisibleSessions(profileId = profileId, limit = limit.toLong(), mapper = ::mapToSession)
        .asFlow()
        .mapToList(Dispatchers.IO)

    override suspend fun getLastWeightForExercise(profileId: String, exerciseId: String): Float? = withContext(Dispatchers.IO) {
        queries.selectLastWeightForExercise(profileId = profileId, exerciseId = exerciseId)
            .executeAsOneOrNull()
            ?.toFloat()
    }

    override suspend fun getSession(sessionId: String): WorkoutSession? = withContext(Dispatchers.IO) {
        queries.selectSessionById(sessionId, ::mapToSession).executeAsOneOrNull()
    }

    override suspend fun getSessionsForRoutineSession(
        profileId: String,
        routineSessionId: String,
    ): List<WorkoutSession> = withContext(Dispatchers.IO) {
        queries.selectSessionsByRoutineSessionId(
            profileId = profileId,
            routineSessionId = routineSessionId,
            mapper = ::mapToSession,
        ).executeAsList()
    }

    override suspend fun getCompletedHealthExportCandidates(profileId: String): List<WorkoutSession> = withContext(Dispatchers.IO) {
        queries.selectCompletedHealthExportCandidates(
            profileId = profileId,
            mapper = ::mapToSession,
        ).executeAsList()
    }

    override suspend fun getRecentSessionsSync(profileId: String, limit: Int): List<WorkoutSession> = withContext(Dispatchers.IO) {
        queries.selectRecentSessions(profileId = profileId, limit = limit.toLong(), mapper = ::mapToSession).executeAsList()
    }

    override suspend fun getVelocityPointsForExercise(
        exerciseId: String,
        profileId: String,
        sinceTimestampMs: Long,
    ): List<WorkoutVelocityPoint> = withContext(Dispatchers.IO) {
        queries.selectVelocityPointsByExercise(exerciseId, profileId, sinceTimestampMs).executeAsList().map { row ->
            WorkoutVelocityPoint(
                loadPerCableKg = (row.workingAvgWeightKg ?: row.weightPerCableKg).toFloat(),
                mcvMmS = (row.avgMcvMmS ?: 0.0).toFloat(), // non-null guaranteed by WHERE avgMcvMmS IS NOT NULL
                timestampMs = row.timestamp,
                workingReps = row.workingReps.toInt(),
            )
        }
    }

    override suspend fun getExerciseIdsWithVelocityData(profileId: String): List<String> =
        withContext(Dispatchers.IO) {
            queries.selectExerciseIdsWithVelocityData(profileId).executeAsList()
        }
}

/** Name given to a healed routine exercise whose stored name and id are both blank (#774). */
internal const val UNKNOWN_EXERCISE_NAME = "Unknown exercise"

internal fun healedExerciseName(storedName: String, exerciseId: String?): String =
    storedName.trim().ifEmpty { exerciseId?.trim()?.takeIf(String::isNotEmpty) ?: UNKNOWN_EXERCISE_NAME }
