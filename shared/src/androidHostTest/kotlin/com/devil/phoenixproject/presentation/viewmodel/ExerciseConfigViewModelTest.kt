package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.repository.SqlDelightPersonalRecordRepository
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.presentation.screen.shouldShowCableOnlyExerciseControls
import com.devil.phoenixproject.presentation.screen.shouldShowStopAtTopToggle
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExerciseConfigViewModelTest {

    @Test
    fun `initialize detects bodyweight exercise and forces duration mode`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-1",
            exercise = Exercise(
                id = "bw-1",
                name = "Plank",
                muscleGroup = "Core",
                muscleGroups = "Core",
                equipment = "",
            ),
            orderIndex = 0,
            setReps = listOf(10),
            weightPerCableKg = 0f,
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )

        assertEquals(ExerciseType.BODYWEIGHT, viewModel.exerciseType.value)
        assertEquals(SetMode.DURATION, viewModel.setMode.value)
    }

    @Test
    fun `initialize clears stale drop set mode outside Old School`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-drop-set-pump",
            exercise = Exercise(
                id = "cable-row-1",
                name = "Cable Row",
                muscleGroup = "Back",
                muscleGroups = "Back",
                equipment = "CABLE",
            ),
            orderIndex = 0,
            setReps = listOf(10),
            weightPerCableKg = 30f,
            progressionKg = -2f,
            programMode = ProgramMode.Pump,
            dropSetEnabled = true,
            dropSetMinWeightKg = 15f,
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )

        assertFalse(viewModel.dropSetEnabled.value)
        var saved: RoutineExercise? = null
        viewModel.onSave { saved = it }
        assertFalse(saved?.dropSetEnabled ?: true)
    }

    @Test
    fun `bodyweight exercise hides cable-only configuration toggles`() {
        val bodyweightSets = listOf(SetConfiguration(setNumber = 1, reps = 10))

        assertFalse(shouldShowCableOnlyExerciseControls(ExerciseType.BODYWEIGHT))
        assertFalse(shouldShowStopAtTopToggle(ExerciseType.BODYWEIGHT, bodyweightSets))
    }

    @Test
    fun `standard exercise shows cable-only configuration toggles except stop at top for all AMRAP`() {
        assertTrue(shouldShowCableOnlyExerciseControls(ExerciseType.STANDARD))
        assertTrue(
            shouldShowStopAtTopToggle(
                ExerciseType.STANDARD,
                listOf(SetConfiguration(setNumber = 1, reps = 10)),
            ),
        )
        assertFalse(
            shouldShowStopAtTopToggle(
                ExerciseType.STANDARD,
                listOf(SetConfiguration(setNumber = 1, reps = null)),
            ),
        )
    }

    @Test
    fun `onSave applies uniform rest time when per-set rest disabled`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-2",
            exercise = Exercise(
                id = "bench-1",
                name = "Bench Press",
                muscleGroup = "Chest",
                muscleGroups = "Chest",
                equipment = "BAR",
            ),
            orderIndex = 0,
            setReps = listOf(10, 8),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 20f),
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
            echoLevel = EchoLevel.HARDER,
            setRestSeconds = listOf(60, 60),
            perSetRestTime = true,
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )

        viewModel.onRestChange(90)
        viewModel.onPerSetRestTimeChange(false)

        val firstSetId = viewModel.sets.value.firstOrNull()?.id
        assertNotNull(firstSetId)
        viewModel.updateReps(firstSetId, 12)

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertEquals(12, saved.setReps.first())
        assertEquals(listOf(90, 90), saved.setRestSeconds)
    }

    @Test
    fun `percent of PR syncs visible set weights and saves resolved snapshots`() = runTest {
        val database = createTestDatabase()
        val queries = database.vitruvianDatabaseQueries
        val repository = SqlDelightPersonalRecordRepository(database)
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = benchRoutineExercise(
            id = "rex-pr-sync",
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 5f,
            setWeightsPerCableKg = listOf(5f, 5f, 5f),
        )

        insertExercise(queries, id = "bench-1", name = "Bench Press")
        insertWeightPR(queries, weight = 50.0)

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )
        assertEquals(5f, viewModel.sets.value.first().weightPerCable)

        waitForCondition { viewModel.currentExercisePR.value?.weightPerCableKg == 50f }
        viewModel.onUsePercentOfPRChange(true)

        assertEquals(listOf(40f, 40f, 40f), viewModel.sets.value.map { it.weightPerCable })
        viewModel.addSet()
        assertEquals(listOf(40f, 40f, 40f, 40f), viewModel.sets.value.map { it.weightPerCable })
        viewModel.deleteSet(3)
        assertEquals(listOf(40f, 40f, 40f), viewModel.sets.value.map { it.weightPerCable })

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertTrue(saved.usePercentOfPR)
        assertEquals(40f, saved.weightPerCableKg)
        assertEquals(listOf(40f, 40f, 40f), saved.setWeightsPerCableKg)
        assertEquals(listOf(80, 80, 80), saved.setWeightsPercentOfPR)
    }

    @Test
    fun `percent of PR uses nearest half kg rounding when syncing set weights`() = runTest {
        val database = createTestDatabase()
        val queries = database.vitruvianDatabaseQueries
        val repository = SqlDelightPersonalRecordRepository(database)
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = benchRoutineExercise(
            id = "rex-pr-rounding",
            setReps = listOf(10),
            weightPerCableKg = 5f,
            setWeightsPerCableKg = listOf(5f),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
        )

        insertExercise(queries, id = "bench-1", name = "Bench Press")
        insertWeightPR(queries, weight = 47.0)

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )

        waitForCondition { viewModel.sets.value.first().weightPerCable == 37.5f }
        assertEquals(37.5f, viewModel.calculateResolvedWeight())
        assertEquals(37.5f, viewModel.sets.value.first().weightPerCable)
    }

    @Test
    fun `global PR percent preserves custom per-set percentages`() = runTest {
        val repository = FakePersonalRecordRepository()
        repository.addRecord(weightPR(weight = 50f))
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = benchRoutineExercise(
            id = "rex-pr-custom",
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 5f,
            setWeightsPerCableKg = listOf(5f, 5f, 5f),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            setWeightsPercentOfPR = listOf(80, 80, 80),
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )
        advanceUntilIdle()
        waitForCondition { viewModel.sets.value.map { it.weightPerCable } == listOf(40f, 40f, 40f) }

        viewModel.updateWeight(viewModel.sets.value.first().id, 45f)
        assertEquals(listOf(45f, 40f, 40f), viewModel.sets.value.map { it.weightPerCable })

        viewModel.onWeightPercentOfPRChange(85)
        assertEquals(listOf(45f, 40f, 40f), viewModel.sets.value.map { it.weightPerCable })

        viewModel.addSet()
        assertEquals(listOf(45f, 40f, 40f, 42.5f), viewModel.sets.value.map { it.weightPerCable })

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertEquals(85, saved.weightPercentOfPR)
        assertEquals(listOf(90, 80, 80, 85), saved.setWeightsPercentOfPR)
        assertEquals(listOf(45f, 40f, 40f, 42.5f), saved.setWeightsPerCableKg)
    }

    @Test
    fun `manual PR percent weight edit before PR load converts when PR becomes available`() = runTest {
        val repository = FakePersonalRecordRepository()
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = benchRoutineExercise(
            id = "rex-pr-pending",
            setReps = listOf(10, 10),
            weightPerCableKg = 5f,
            setWeightsPerCableKg = listOf(5f, 5f),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            setWeightsPercentOfPR = listOf(80, 80),
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )
        advanceUntilIdle()
        assertNull(viewModel.currentExercisePR.value)

        viewModel.updateWeight(viewModel.sets.value.first().id, 25f)
        assertEquals(listOf(25f, 5f), viewModel.sets.value.map { it.weightPerCable })

        repository.addRecord(weightPR(weight = 50f))
        viewModel.loadPRForExercise("bench-1", "Old School")
        advanceUntilIdle()

        waitForCondition { viewModel.sets.value.map { it.weightPerCable } == listOf(25f, 40f) }

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertEquals(listOf(50, 80), saved.setWeightsPercentOfPR)
        assertEquals(listOf(25f, 40f), saved.setWeightsPerCableKg)
    }

    @Test
    fun `delete set while PR percent disabled keeps percentages aligned when re-enabled`() = runTest {
        val repository = FakePersonalRecordRepository()
        repository.addRecord(weightPR(weight = 50f))
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = benchRoutineExercise(
            id = "rex-pr-delete-disabled",
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 5f,
            setWeightsPerCableKg = listOf(5f, 5f, 5f),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            setWeightsPercentOfPR = listOf(80, 90, 100),
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )
        advanceUntilIdle()
        waitForCondition { viewModel.sets.value.map { it.weightPerCable } == listOf(40f, 45f, 50f) }

        viewModel.onUsePercentOfPRChange(false)
        viewModel.deleteSet(0)
        assertEquals(listOf(45f, 50f), viewModel.sets.value.map { it.weightPerCable })

        viewModel.onUsePercentOfPRChange(true)
        assertEquals(listOf(45f, 50f), viewModel.sets.value.map { it.weightPerCable })

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertEquals(listOf(90, 100), saved.setWeightsPercentOfPR)
        assertEquals(listOf(45f, 50f), saved.setWeightsPerCableKg)
    }

    @Test
    fun `initialize and save preserve default rack item ids`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = benchRoutineExercise(
            id = "rex-rack-defaults",
            setReps = listOf(10),
            weightPerCableKg = 20f,
            defaultRackItemIds = listOf("vest", "assist"),
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )

        assertEquals(listOf("vest", "assist"), viewModel.defaultRackItemIds.value)

        viewModel.onDefaultRackItemIdsChange(listOf("assist"))

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertEquals(listOf("assist"), saved.defaultRackItemIds)
    }

    @Test
    fun `initialize reloads PR lookup when active profile changes`() = runTest {
        val database = createTestDatabase()
        val queries = database.vitruvianDatabaseQueries
        val repository = SqlDelightPersonalRecordRepository(database)
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = RoutineExercise(
            id = "rex-3",
            exercise = Exercise(
                id = "bench-1",
                name = "Bench Press",
                muscleGroup = "Chest",
                muscleGroups = "Chest",
                equipment = "BAR",
            ),
            orderIndex = 0,
            setReps = listOf(10),
            weightPerCableKg = 20f,
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
            echoLevel = EchoLevel.HARDER,
        )

        insertExercise(queries, id = "bench-1", name = "Bench Press")
        queries.insertRecord(
            exerciseId = "bench-1",
            exerciseName = "Bench Press",
            weight = 55.0,
            reps = 6,
            oneRepMax = 66.0,
            achievedAt = 1_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = 330.0,
            phase = "COMBINED",
            profile_id = "default",
            cable_count = null,
            uuid = null,
        )
        queries.insertRecord(
            exerciseId = "bench-1",
            exerciseName = "Bench Press",
            weight = 72.5,
            reps = 5,
            oneRepMax = 84.5,
            achievedAt = 2_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = 362.5,
            phase = "COMBINED",
            profile_id = "profile-b",
            cable_count = null,
            uuid = null,
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
            profileId = "default",
        )
        waitForCondition { viewModel.currentExercisePR.value != null }
        assertNotNull(viewModel.currentExercisePR.value)
        assertEquals(55f, viewModel.currentExercisePR.value?.weightPerCableKg)

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
            profileId = "profile-b",
        )
        waitForCondition { viewModel.currentExercisePR.value?.weightPerCableKg == 72.5f }
        assertEquals(72.5f, viewModel.currentExercisePR.value?.weightPerCableKg)

        viewModel.loadPRForExercise("bench-1", "Pump")
        waitForCondition { viewModel.currentExercisePR.value == null }
        assertNull(viewModel.currentExercisePR.value)
    }

    @Test
    fun `initialize uses concentric PR for normal workout setup and ignores higher eccentric PR`() = runTest {
        val database = createTestDatabase()
        val queries = database.vitruvianDatabaseQueries
        val repository = SqlDelightPersonalRecordRepository(database)
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = benchRoutineExercise(
            id = "rex-phase",
            setReps = listOf(10),
            weightPerCableKg = 20f,
        )

        insertExercise(queries, id = "bench-1", name = "Bench Press")
        insertWeightPR(queries, weight = 35.0, phase = WorkoutPhase.COMBINED)
        insertWeightPR(queries, weight = 45.0, phase = WorkoutPhase.CONCENTRIC)
        insertWeightPR(queries, weight = 90.0, phase = WorkoutPhase.ECCENTRIC)

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
            profileId = "default",
        )

        waitForCondition { viewModel.currentExercisePR.value?.weightPerCableKg == 45f }
        assertEquals(WorkoutPhase.CONCENTRIC, viewModel.currentExercisePR.value?.phase)
        assertEquals(45f, viewModel.currentExercisePR.value?.weightPerCableKg)
    }

    @Test
    fun `initialize and save preserve explicit scalingBasis`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = benchRoutineExercise(
            id = "rex-scaling-basis",
            setReps = listOf(10),
            weightPerCableKg = 20f,
            scalingBasis = ScalingBasis.ESTIMATED_1RM,
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )

        assertEquals(ScalingBasis.ESTIMATED_1RM, viewModel.scalingBasis.value)

        viewModel.onScalingBasisChange(ScalingBasis.MAX_VOLUME_PR)
        assertEquals(ScalingBasis.MAX_VOLUME_PR, viewModel.scalingBasis.value)

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertEquals(ScalingBasis.MAX_VOLUME_PR, saved.scalingBasis)
    }

    @Test
    fun `legacy null scalingBasis with MAX_VOLUME prType resolves basis and baseline to volume PR`() = runTest {
        val database = createTestDatabase()
        val queries = database.vitruvianDatabaseQueries
        val repository = SqlDelightPersonalRecordRepository(database)
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = benchRoutineExercise(
            id = "rex-legacy-volume",
            setReps = listOf(10),
            weightPerCableKg = 5f,
            setWeightsPerCableKg = listOf(5f),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            // Legacy upgraded row: no explicit scalingBasis, but prTypeForScaling = MAX_VOLUME
            scalingBasis = null,
            prTypeForScaling = PRType.MAX_VOLUME,
        )

        insertExercise(queries, id = "bench-1", name = "Bench Press")
        // Max-weight PR (heavier) and a distinct max-volume PR (lighter, more reps)
        queries.insertRecord(
            exerciseId = "bench-1",
            exerciseName = "Bench Press",
            weight = 60.0,
            reps = 3,
            oneRepMax = 66.0,
            achievedAt = 1_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = 180.0,
            phase = "COMBINED",
            profile_id = "default",
            cable_count = null,
            uuid = null,
        )
        queries.insertRecord(
            exerciseId = "bench-1",
            exerciseName = "Bench Press",
            weight = 40.0,
            reps = 12,
            oneRepMax = 56.0,
            achievedAt = 2_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_VOLUME.name,
            volume = 480.0,
            phase = "COMBINED",
            profile_id = "default",
            cable_count = null,
            uuid = null,
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
            profileId = "default",
        )

        // Editor basis must derive to MAX_VOLUME_PR (not default MAX_WEIGHT_PR)
        assertEquals(ScalingBasis.MAX_VOLUME_PR, viewModel.effectiveScalingBasis())
        // Baseline + per-set weights resolve off the VOLUME PR (40kg), not the weight PR (60kg).
        waitForCondition { viewModel.baselineKgForCurrentBasis() == 40f }
        assertEquals(40f, viewModel.baselineKgForCurrentBasis())
        // Per-set rows must re-sync to 80% of the volume PR once it loads (Finding B).
        waitForCondition { viewModel.sets.value.first().weightPerCable == 32f }
        assertEquals(32f, viewModel.sets.value.first().weightPerCable)
    }

    @Test
    fun `initialize with null scalingBasis derives from prTypeForScaling`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = benchRoutineExercise(
            id = "rex-scaling-null",
            setReps = listOf(10),
            weightPerCableKg = 20f,
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )

        // null scalingBasis means back-compat derivation — effectiveScalingBasis should derive
        assertNull(viewModel.scalingBasis.value)

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertNull(saved.scalingBasis)
        assertEquals(ScalingBasis.MAX_WEIGHT_PR, saved.effectiveScalingBasis)
    }

    @Test
    fun `editor preview uses same profile cross mode baseline when selected mode has none`() = runTest {
        val prRepository = FakePersonalRecordRepository()
        val velocityRepository = FakeVelocityOneRepMaxRepository()
        val exerciseRepository = FakeExerciseRepository()
        exerciseRepository.addExercise(
            Exercise(
                id = "bench-1",
                name = "Bench Press",
                muscleGroup = "Chest",
                muscleGroups = "Chest",
                equipment = "BAR",
            ),
        )
        prRepository.addRecord(
            PersonalRecord(
                id = 595,
                exerciseId = "bench-1",
                exerciseName = "Bench Press",
                weightPerCableKg = 60f,
                reps = 5,
                oneRepMax = 72f,
                timestamp = 2_000L,
                workoutMode = "Pump",
                prType = PRType.MAX_WEIGHT,
                volume = 300f,
                profileId = "default",
            ),
        )

        val viewModel = ExerciseConfigViewModel(prRepository, velocityRepository, exerciseRepository)
        val exercise = benchRoutineExercise(
            id = "rex-cross-mode-preview",
            setReps = listOf(10),
            weightPerCableKg = 5f,
            setWeightsPerCableKg = listOf(5f),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            scalingBasis = ScalingBasis.MAX_WEIGHT_PR,
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
            profileId = "default",
        )
        advanceUntilIdle()

        assertNull(viewModel.currentExercisePR.value)
        waitForCondition { viewModel.baselineKgForCurrentBasis() == 60f }
        waitForCondition { viewModel.sets.value.first().weightPerCable == 48f }
        assertEquals(48f, viewModel.calculateResolvedWeight())
    }

    private fun benchRoutineExercise(
        id: String,
        setReps: List<Int?>,
        weightPerCableKg: Float,
        setWeightsPerCableKg: List<Float> = emptyList(),
        usePercentOfPR: Boolean = false,
        weightPercentOfPR: Int = 80,
        setWeightsPercentOfPR: List<Int> = emptyList(),
        defaultRackItemIds: List<String> = emptyList(),
        scalingBasis: ScalingBasis? = null,
        prTypeForScaling: PRType = PRType.MAX_WEIGHT,
    ) = RoutineExercise(
        id = id,
        exercise = Exercise(
            id = "bench-1",
            name = "Bench Press",
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            equipment = "BAR",
        ),
        orderIndex = 0,
        setReps = setReps,
        weightPerCableKg = weightPerCableKg,
        setWeightsPerCableKg = setWeightsPerCableKg,
        programMode = ProgramMode.OldSchool,
        eccentricLoad = EccentricLoad.LOAD_100,
        echoLevel = EchoLevel.HARDER,
        usePercentOfPR = usePercentOfPR,
        weightPercentOfPR = weightPercentOfPR,
        setWeightsPercentOfPR = setWeightsPercentOfPR,
        defaultRackItemIds = defaultRackItemIds,
        scalingBasis = scalingBasis,
        prTypeForScaling = prTypeForScaling,
    )

    private fun weightPR(weight: Float) = PersonalRecord(
        id = 1,
        exerciseId = "bench-1",
        exerciseName = "Bench Press",
        weightPerCableKg = weight,
        reps = 6,
        oneRepMax = weight * 1.2f,
        timestamp = 1_000L,
        workoutMode = "Old School",
        prType = PRType.MAX_WEIGHT,
        volume = weight * 6,
    )

    private fun insertExercise(queries: com.devil.phoenixproject.database.VitruvianDatabaseQueries, id: String, name: String) {
        queries.insertExercise(
            id = id,
            name = name,
            displayName = null,
            description = null,
            created = 0L,
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            muscles = null,
            equipment = "BAR",
            movement = null,
            sidedness = null,
            grip = null,
            gripWidth = null,
            minRepRange = null,
            popularity = 0.0,
            archived = 0L,
            isFavorite = 0L,
            isCustom = 0L,
            timesPerformed = 0L,
            lastPerformed = null,
            aliases = null,
            defaultCableConfig = "DOUBLE",
            one_rep_max_kg = null,
            mvtOverrideMs = null,
            isBodyweight = null,
        )
    }

    private fun insertWeightPR(
        queries: com.devil.phoenixproject.database.VitruvianDatabaseQueries,
        weight: Double,
        phase: WorkoutPhase = WorkoutPhase.COMBINED,
    ) {
        queries.insertRecord(
            exerciseId = "bench-1",
            exerciseName = "Bench Press",
            weight = weight,
            reps = 6,
            oneRepMax = weight * 1.2,
            achievedAt = 1_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = weight * 6,
            phase = phase.name,
            profile_id = "default",
            cable_count = null,
            uuid = null,
        )
    }

    private fun waitForCondition(timeoutMs: Long = 1_000L, pollMs: Long = 25L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(pollMs)
        }
        assertTrue(condition(), "Timed out waiting for view model state update")
    }

    // ──────────────────────────────────────────────────────────────────
    // Issue #667: Set repetition tests
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `onRepeatCountChange updates correct set`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-667-1",
            exercise = Exercise(id = "bench-1", name = "Bench Press", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "BAR"),
            orderIndex = 0,
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 20f, 20f),
        )
        viewModel.initialize(exercise = exercise, unit = WeightUnit.KG, toDisplay = { v, _ -> v }, toKg = { v, _ -> v })

        val secondSetId = viewModel.sets.value[1].id
        viewModel.onRepeatCountChange(secondSetId, 4)

        assertEquals(1, viewModel.sets.value[0].repeatCount)
        assertEquals(4, viewModel.sets.value[1].repeatCount)
        assertEquals(1, viewModel.sets.value[2].repeatCount)
    }

    @Test
    fun `onRepeatCountChange coerces to 1-20 range`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-667-2",
            exercise = Exercise(id = "bench-1", name = "Bench Press", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "BAR"),
            orderIndex = 0,
            setReps = listOf(10),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f),
        )
        viewModel.initialize(exercise = exercise, unit = WeightUnit.KG, toDisplay = { v, _ -> v }, toKg = { v, _ -> v })

        val setId = viewModel.sets.value[0].id

        viewModel.onRepeatCountChange(setId, 0)
        assertEquals(1, viewModel.sets.value[0].repeatCount, "0 should coerce to 1")

        viewModel.onRepeatCountChange(setId, 25)
        assertEquals(20, viewModel.sets.value[0].repeatCount, "25 should coerce to 20")
    }

    @Test
    fun `totalExpandedSetCount sums repeatCounts`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-667-3",
            exercise = Exercise(id = "bench-1", name = "Bench Press", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "BAR"),
            orderIndex = 0,
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 20f, 20f),
        )
        viewModel.initialize(exercise = exercise, unit = WeightUnit.KG, toDisplay = { v, _ -> v }, toKg = { v, _ -> v })

        assertEquals(3, viewModel.totalExpandedSetCount, "All repeatCount=1 → 3")

        val setIds = viewModel.sets.value.map { it.id }
        viewModel.onRepeatCountChange(setIds[1], 3)
        viewModel.onRepeatCountChange(setIds[2], 2)
        assertEquals(6, viewModel.totalExpandedSetCount, "[1,3,2] → 6")
    }

    @Test
    fun `onSave with repeatCount produces expanded arrays`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-667-4",
            exercise = Exercise(id = "bench-1", name = "Bench Press", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "BAR"),
            orderIndex = 0,
            setReps = listOf(10, 8, 10),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 25f, 20f),
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
            echoLevel = EchoLevel.HARDER,
            setRestSeconds = listOf(60, 60, 60),
        )
        viewModel.initialize(exercise = exercise, unit = WeightUnit.KG, toDisplay = { v, _ -> v }, toKg = { v, _ -> v })

        // Set 2 has repeatCount=3
        val setIds = viewModel.sets.value.map { it.id }
        viewModel.onRepeatCountChange(setIds[1], 3)

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        // 1 + 3 + 1 = 5 expanded sets
        assertEquals(5, saved!!.setReps.size, "setReps should have 5 entries")
        assertEquals(5, saved!!.setWeightsPerCableKg.size, "setWeights should have 5 entries")
        assertEquals(5, saved!!.setRestSeconds.size, "setRestSeconds should have 5 entries")
        // Check values: [10, 8, 8, 8, 10]
        assertEquals(listOf(10, 8, 8, 8, 10), saved!!.setReps)
        // Check weights: [20, 25, 25, 25, 20]
        assertEquals(listOf(20f, 25f, 25f, 25f, 20f), saved!!.setWeightsPerCableKg)
    }

    @Test
    fun `onSave with repeatCount and AMRAP sets`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-667-5",
            exercise = Exercise(id = "bench-1", name = "Bench Press", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "BAR"),
            orderIndex = 0,
            setReps = listOf(null, null),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 20f),
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
            echoLevel = EchoLevel.HARDER,
            isAMRAP = true,
        )
        viewModel.initialize(exercise = exercise, unit = WeightUnit.KG, toDisplay = { v, _ -> v }, toKg = { v, _ -> v })

        val setIds = viewModel.sets.value.map { it.id }
        viewModel.onRepeatCountChange(setIds[0], 2)

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertTrue(saved!!.isAMRAP, "AMRAP should be preserved")
        assertEquals(3, saved!!.setReps.size, "2+1=3 expanded sets")
        assertTrue(saved!!.setReps.all { it == null }, "All reps should be null (AMRAP)")
    }

    @Test
    fun `onSave with repeatCount and uniform rest`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-667-6",
            exercise = Exercise(id = "bench-1", name = "Bench Press", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "BAR"),
            orderIndex = 0,
            setReps = listOf(10, 10),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 20f),
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
            echoLevel = EchoLevel.HARDER,
            perSetRestTime = false,
        )
        viewModel.initialize(exercise = exercise, unit = WeightUnit.KG, toDisplay = { v, _ -> v }, toKg = { v, _ -> v })
        viewModel.onRestChange(90)
        viewModel.onPerSetRestTimeChange(false)

        val setIds = viewModel.sets.value.map { it.id }
        viewModel.onRepeatCountChange(setIds[0], 3)

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertEquals(4, saved!!.setRestSeconds.size, "3+1=4 expanded rest entries")
        assertTrue(saved!!.setRestSeconds.all { it == 90 }, "Uniform rest applied to all expanded sets")
    }

    @Test
    fun `initialize sets all repeatCount to 1`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-667-7",
            exercise = Exercise(id = "bench-1", name = "Bench Press", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "BAR"),
            orderIndex = 0,
            setReps = listOf(10, 8, 6),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 25f, 30f),
        )
        viewModel.initialize(exercise = exercise, unit = WeightUnit.KG, toDisplay = { v, _ -> v }, toKg = { v, _ -> v })

        assertEquals(3, viewModel.sets.value.size)
        assertTrue(viewModel.sets.value.all { it.repeatCount == 1 }, "All sets should have repeatCount=1 after init")
    }

    @Test
    fun `addSet creates set with repeatCount 1`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-667-8",
            exercise = Exercise(id = "bench-1", name = "Bench Press", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "BAR"),
            orderIndex = 0,
            setReps = listOf(10, 10),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 20f),
        )
        viewModel.initialize(exercise = exercise, unit = WeightUnit.KG, toDisplay = { v, _ -> v }, toKg = { v, _ -> v })

        viewModel.addSet()
        assertEquals(3, viewModel.sets.value.size)
        assertEquals(1, viewModel.sets.value.last().repeatCount, "New set should have repeatCount=1")
    }

    @Test
    fun `deleteSet preserves repeatCount on remaining sets`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-667-9",
            exercise = Exercise(id = "bench-1", name = "Bench Press", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "BAR"),
            orderIndex = 0,
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 20f, 20f),
        )
        viewModel.initialize(exercise = exercise, unit = WeightUnit.KG, toDisplay = { v, _ -> v }, toKg = { v, _ -> v })

        val setIds = viewModel.sets.value.map { it.id }
        viewModel.onRepeatCountChange(setIds[0], 1)
        viewModel.onRepeatCountChange(setIds[1], 3)
        viewModel.onRepeatCountChange(setIds[2], 1)

        viewModel.deleteSet(0) // Remove first set
        assertEquals(2, viewModel.sets.value.size)
        assertEquals(3, viewModel.sets.value[0].repeatCount, "Second set's repeatCount should survive reindex")
        assertEquals(1, viewModel.sets.value[1].repeatCount)
    }

    @Test
    fun `onSave backward compat - all repeatCount 1 produces identical output`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-667-10",
            exercise = Exercise(id = "bench-1", name = "Bench Press", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "BAR"),
            orderIndex = 0,
            setReps = listOf(10, 8, 6),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 25f, 30f),
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
            echoLevel = EchoLevel.HARDER,
            setRestSeconds = listOf(60, 90, 120),
            perSetRestTime = true,
        )
        viewModel.initialize(exercise = exercise, unit = WeightUnit.KG, toDisplay = { v, _ -> v }, toKg = { v, _ -> v })

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertEquals(listOf(10, 8, 6), saved!!.setReps)
        assertEquals(listOf(20f, 25f, 30f), saved!!.setWeightsPerCableKg)
        assertEquals(listOf(60, 90, 120), saved!!.setRestSeconds)
    }

    @Test
    fun `onSave preserves per-set echo overrides through expansion`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-667-echo",
            exercise = Exercise(id = "bench-1", name = "Bench Press", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "BAR"),
            orderIndex = 0,
            setReps = listOf(10, 8),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 25f),
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
            echoLevel = EchoLevel.HARDER,
            setEchoLevels = listOf(EchoLevel.HARD, EchoLevel.HARDER), // Per-set overrides
            setRestSeconds = listOf(60, 90),
            perSetRestTime = true,
        )
        viewModel.initialize(exercise = exercise, unit = WeightUnit.KG, toDisplay = { v, _ -> v }, toKg = { v, _ -> v })

        // Set 2 has repeatCount=3
        val setIds = viewModel.sets.value.map { it.id }
        viewModel.onRepeatCountChange(setIds[1], 3)

        // Verify SetConfiguration carries echo override
        assertEquals(EchoLevel.HARD, viewModel.sets.value[0].echoLevel, "Set 1 should carry HARD echo override")
        assertEquals(EchoLevel.HARDER, viewModel.sets.value[1].echoLevel, "Set 2 should carry HARDER echo override")

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        // Expanded: set1 (HARD) + set2×3 (HARDER each)
        assertEquals(listOf(EchoLevel.HARD, EchoLevel.HARDER, EchoLevel.HARDER, EchoLevel.HARDER), saved!!.setEchoLevels,
            "Per-set Echo overrides should be preserved through expansion")
    }

    @Test
    fun `initialize loads per-set echo overrides into SetConfiguration`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-667-echo-init",
            exercise = Exercise(id = "squat-1", name = "Squat", muscleGroup = "Legs", muscleGroups = "Legs", equipment = "BAR"),
            orderIndex = 0,
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 50f,
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
            echoLevel = EchoLevel.HARDER,
            setEchoLevels = listOf(EchoLevel.HARD, null, EchoLevel.HARDER), // Mixed overrides
        )
        viewModel.initialize(exercise = exercise, unit = WeightUnit.KG, toDisplay = { v, _ -> v }, toKg = { v, _ -> v })

        val sets = viewModel.sets.value
        assertEquals(EchoLevel.HARD, sets[0].echoLevel, "Set 1 echo should be HARD")
        assertNull(sets[1].echoLevel, "Set 2 echo should be null (no override)")
        assertEquals(EchoLevel.HARDER, sets[2].echoLevel, "Set 3 echo should be HARDER")
    }
}
