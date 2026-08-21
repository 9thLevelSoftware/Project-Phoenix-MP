package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

class DataBackupManagerRoutineExerciseDropSetTest {
    private lateinit var database: com.devil.phoenixproject.database.VitruvianDatabase
    private lateinit var workoutRepository: SqlDelightWorkoutRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
    }

    @Test
    fun exportImportRoundTripPreservesDropSetConfiguration() = runTest {
        workoutRepository.saveRoutine(
            Routine(
                id = "routine-drop",
                name = "Drop Routine",
                exercises = listOf(
                    RoutineExercise(
                        id = "rex-on",
                        exercise = Exercise(id = "bench", name = "Bench Press", muscleGroup = "Chest"),
                        orderIndex = 0,
                        setReps = listOf(8),
                        weightPerCableKg = 20f,
                        programMode = ProgramMode.OldSchool,
                        dropSetEnabled = true,
                        dropSetMinWeightKg = 7.5f,
                    ),
                    RoutineExercise(
                        id = "rex-off",
                        exercise = Exercise(id = "row", name = "Row", muscleGroup = "Back"),
                        orderIndex = 1,
                        setReps = listOf(10),
                        weightPerCableKg = 15f,
                        programMode = ProgramMode.OldSchool,
                    ),
                ),
            ),
        )

        val exported = workoutRepository.getRoutineById("routine-drop")
        assertTrue(exported!!.exercises[0].dropSetEnabled)
        assertEquals(7.5f, exported.exercises[0].dropSetMinWeightKg)
        assertFalse(exported.exercises[1].dropSetEnabled)
        assertNull(exported.exercises[1].dropSetMinWeightKg)

        val backup = BackupData(
            version = CURRENT_BACKUP_VERSION,
            exportedAt = "2026-08-20T12:00:00Z",
            appVersion = "test",
            data = BackupContent(
                routines = listOf(
                    RoutineBackup(
                        id = "routine-drop",
                        name = "Drop Routine",
                        createdAt = 1_700_000_000_000,
                    ),
                ),
                routineExercises = exported.exercises.map { exercise ->
                    RoutineExerciseBackup(
                        id = exercise.id,
                        routineId = "routine-drop",
                        exerciseName = exercise.exercise.name,
                        exerciseMuscleGroup = exercise.exercise.muscleGroup,
                        exerciseDefaultCableConfig = "DOUBLE",
                        exerciseId = exercise.exercise.id,
                        cableConfig = "DOUBLE",
                        orderIndex = exercise.orderIndex,
                        setReps = exercise.setReps.joinToString(","),
                        weightPerCableKg = exercise.weightPerCableKg,
                        dropSetEnabled = exercise.dropSetEnabled,
                        dropSetMinWeightKg = exercise.dropSetMinWeightKg,
                    )
                },
            ),
        )
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        val restored = json.decodeFromString<BackupData>(json.encodeToString(backup))
        val on = restored.data.routineExercises.first { it.id == "rex-on" }
        val off = restored.data.routineExercises.first { it.id == "rex-off" }
        assertTrue(on.dropSetEnabled)
        assertEquals(7.5f, on.dropSetMinWeightKg)
        assertFalse(off.dropSetEnabled)
        assertNull(off.dropSetMinWeightKg)
    }
}
