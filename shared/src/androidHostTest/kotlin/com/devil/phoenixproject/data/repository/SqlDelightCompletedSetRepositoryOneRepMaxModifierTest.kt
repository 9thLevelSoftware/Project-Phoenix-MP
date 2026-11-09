package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.seedExercise
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test

class SqlDelightCompletedSetRepositoryOneRepMaxModifierTest {
    private lateinit var database: PhoenixDatabase
    private lateinit var exerciseRepository: FakeExerciseRepository
    private lateinit var workoutRepository: SqlDelightWorkoutRepository
    private lateinit var completedSetRepository: SqlDelightCompletedSetRepository
    private var plannedSetId: String = ""

    @Before
    fun setup() {
        database = createTestDatabase()
        exerciseRepository = FakeExerciseRepository()
        workoutRepository = SqlDelightWorkoutRepository(database, exerciseRepository)
        completedSetRepository = SqlDelightCompletedSetRepository(database)
    }

    @Test
    fun `a routine session with a modifier-marked set is excluded as a whole`() = runTest {
        seedRoutine()
        val base = 1_800_000_000_000L
        saveSession("modified-first-set", "modified-run", base, modifierApplied = true)
        saveSession("resumed-unmarked-set", "modified-run", base + 1_000, modifierApplied = false)
        saveSession("ordinary-set", "ordinary-run", base + 2_000, modifierApplied = false)

        insertSet("modified-first-set", "set-modified", base + 100)
        insertSet("resumed-unmarked-set", "set-resumed", base + 1_100)
        insertSet("ordinary-set", "set-ordinary", base + 2_100)

        val eligible = completedSetRepository.getEligibleWorkingSetsForOneRepMax(
            exerciseId = "bench-press",
            profileId = "default",
            mode = "OldSchool",
            completedAtSince = base,
            completedAtUntil = base + 10_000,
        )

        assertEquals(listOf("set-ordinary"), eligible.map { it.id })
    }

    private suspend fun seedRoutine() {
        val exerciseId = "bench-press"
        val exercise = Exercise(
            id = exerciseId,
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "Cable",
        )
        exerciseRepository.addExercise(exercise)
        database.seedExercise(id = exerciseId, name = exercise.name, equipment = exercise.equipment)
        workoutRepository.saveRoutine(
            Routine(
                id = "saved-routine",
                name = "Saved Routine",
                exercises = listOf(
                    RoutineExercise(
                        id = "bench-press-in-routine",
                        exercise = exercise,
                        orderIndex = 0,
                        setReps = listOf(10),
                        weightPerCableKg = 50f,
                    ),
                ),
            ),
        )
        plannedSetId = "planned-bench-press"
        database.phoenixDatabaseQueries.insertPlannedSet(
            plannedSetId,
            "bench-press-in-routine",
            1,
            "STANDARD",
            10,
            50.0,
            null,
            90,
        )
    }

    private suspend fun saveSession(
        id: String,
        routineSessionId: String,
        timestamp: Long,
        modifierApplied: Boolean,
    ) {
        workoutRepository.saveSession(
            WorkoutSession(
                id = id,
                timestamp = timestamp,
                mode = "OldSchool",
                reps = 10,
                weightPerCableKg = 50f,
                totalReps = 10,
                workingReps = 10,
                exerciseId = "bench-press",
                routineSessionId = routineSessionId,
                routineId = "saved-routine",
                routineModifierApplied = modifierApplied,
            ),
        )
    }

    private fun insertSet(sessionId: String, setId: String, completedAt: Long) {
        database.phoenixDatabaseQueries.insertCompletedSetRow(
            CompletedSet(
                id = setId,
                sessionId = sessionId,
                plannedSetId = plannedSetId,
                setNumber = 1,
                setType = SetType.STANDARD,
                actualReps = 10,
                actualWeightKg = 50f,
                loggedRpe = null,
                isPr = false,
                completedAt = completedAt,
                setEndReason = SetEndReason.TARGET_REPS_REACHED,
                routineExerciseId = "bench-press-in-routine",
            ),
        )
    }
}
