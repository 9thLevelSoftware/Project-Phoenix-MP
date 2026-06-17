package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HealthWorkoutExportBuilderTest {

    @Test
    fun routineWorkoutUsesOneRecordWithSegmentsFromCompletedSets() {
        val routineId = "routine-session-1"
        val sessions = listOf(
            workoutSession(
                id = "session-a",
                timestamp = 1_000L,
                duration = 30_000L,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                routineSessionId = routineId,
                routineName = "Push Day",
                workingReps = 8,
                weightPerCableKg = 30f,
                displayMultiplier = 2,
                estimatedCalories = 40f,
                rpe = 7,
            ),
            workoutSession(
                id = "session-b",
                timestamp = 40_000L,
                duration = 45_000L,
                exerciseId = "lat-pulldown",
                exerciseName = "Lat Pulldown",
                routineSessionId = routineId,
                routineName = "Push Day",
                workingReps = 10,
                weightPerCableKg = 25f,
                displayMultiplier = 1,
                estimatedCalories = 35f,
            ),
        )
        val completedSets = mapOf(
            "session-a" to listOf(
                completedSet(
                    id = "set-a",
                    sessionId = "session-a",
                    setNumber = 0,
                    actualReps = 9,
                    actualWeightKg = 60f,
                    loggedRpe = 8,
                    completedAt = 31_000L,
                ),
            ),
            "session-b" to listOf(
                completedSet(
                    id = "set-b",
                    sessionId = "session-b",
                    setNumber = 1,
                    setType = SetType.WARMUP,
                    actualReps = 12,
                    actualWeightKg = 25f,
                    loggedRpe = null,
                    completedAt = 85_000L,
                ),
            ),
        )

        val export = HealthWorkoutExportBuilder.buildRoutineWorkout(
            routineSessionId = routineId,
            sessions = sessions,
            completedSetsBySessionId = completedSets,
        )

        assertNotNull(export)
        assertEquals("Push Day", export.title)
        assertEquals("phoenix:routine:$routineId", export.externalId)
        assertEquals(1_000L, export.startTimeMs)
        assertEquals(85_000L, export.endTimeMs)
        assertEquals(75f, export.totalCalories)
        assertEquals(2, export.segments.size)

        val first = export.segments[0]
        assertEquals("session-a", first.sessionId)
        assertEquals("bench-press", first.exerciseId)
        assertEquals("Bench Press", first.exerciseName)
        assertEquals(0, first.setIndex)
        assertEquals(SetType.STANDARD, first.setType)
        assertEquals(9, first.reps)
        assertEquals(60f, first.weightKg)
        assertEquals(8, first.rpe)
        assertEquals(1_000L, first.startTimeMs)
        assertEquals(31_000L, first.endTimeMs)

        val second = export.segments[1]
        assertEquals("session-b", second.sessionId)
        assertEquals(1, second.setIndex)
        assertEquals(SetType.WARMUP, second.setType)
        assertEquals(12, second.reps)
        assertEquals(25f, second.weightKg)
        assertEquals(null, second.rpe)
        assertEquals(40_000L, second.startTimeMs)
        assertEquals(85_000L, second.endTimeMs)
    }

    @Test
    fun standaloneWorkoutFallsBackToSessionWhenCompletedSetMissing() {
        val session = workoutSession(
            id = "standalone-1",
            timestamp = 10_000L,
            duration = 20_000L,
            exerciseId = "cable-row",
            exerciseName = "Cable Row",
            workingReps = 11,
            totalReps = 13,
            weightPerCableKg = 22.5f,
            displayMultiplier = 2,
            estimatedCalories = 18f,
            rpe = 6,
        )

        val export = HealthWorkoutExportBuilder.buildStandaloneWorkout(
            session = session,
            completedSets = emptyList(),
        )

        assertNotNull(export)
        assertEquals("Cable Row", export.title)
        assertEquals("phoenix:session:standalone-1", export.externalId)
        assertEquals(10_000L, export.startTimeMs)
        assertEquals(30_000L, export.endTimeMs)
        assertEquals(18f, export.totalCalories)
        assertEquals(1, export.segments.size)
        assertEquals(11, export.segments.single().reps)
        assertEquals(45f, export.segments.single().weightKg)
        assertEquals(6, export.segments.single().rpe)
    }

    @Test
    fun invalidRoutineReturnsNullWhenNoCompletedRepsExist() {
        val routineId = "empty-routine"
        val export = HealthWorkoutExportBuilder.buildRoutineWorkout(
            routineSessionId = routineId,
            sessions = listOf(
                workoutSession(
                    id = "session-empty",
                    routineSessionId = routineId,
                    routineName = "Empty Routine",
                    workingReps = 0,
                    totalReps = 0,
                ),
            ),
            completedSetsBySessionId = emptyMap(),
        )

        assertNull(export)
    }

    @Test
    fun multipleSetsInOneSessionAreAdjustedToNonOverlappingSegments() {
        val session = workoutSession(
            id = "session-overlap",
            timestamp = 10_000L,
            duration = 30_000L,
            workingReps = 8,
        )
        val export = HealthWorkoutExportBuilder.buildStandaloneWorkout(
            session = session,
            completedSets = listOf(
                completedSet(
                    id = "set-1",
                    sessionId = session.id,
                    setNumber = 0,
                    actualReps = 8,
                    actualWeightKg = 20f,
                    loggedRpe = null,
                    completedAt = 40_000L,
                ),
                completedSet(
                    id = "set-2",
                    sessionId = session.id,
                    setNumber = 1,
                    actualReps = 8,
                    actualWeightKg = 20f,
                    loggedRpe = null,
                    completedAt = 45_000L,
                ),
            ),
        )

        assertNotNull(export)
        val first = export.segments[0]
        val second = export.segments[1]
        assertEquals(10_000L, first.startTimeMs)
        assertEquals(40_000L, first.endTimeMs)
        assertEquals(40_000L, second.startTimeMs)
        assertEquals(45_000L, second.endTimeMs)
    }

    @Test
    fun bodyweightRoutineExportsEffectiveLoadNotZero() {
        val routineId = "bodyweight-routine"
        val sessions = listOf(
            workoutSession(
                id = "session-pushup",
                timestamp = 1_000L,
                duration = 30_000L,
                exerciseId = "push-up",
                exerciseName = "Push Up",
                routineSessionId = routineId,
                routineName = "Bodyweight Day",
                workingReps = 12,
                weightPerCableKg = 0f,
                displayMultiplier = 1,
                estimatedCalories = 25f,
                heaviestLiftKg = 62.5f,
            ),
        )
        val completedSets = mapOf(
            "session-pushup" to listOf(
                completedSet(
                    id = "set-pushup",
                    sessionId = "session-pushup",
                    setNumber = 0,
                    actualReps = 12,
                    actualWeightKg = 62.5f,
                    loggedRpe = 7,
                    completedAt = 31_000L,
                ),
            ),
        )

        val export = HealthWorkoutExportBuilder.buildRoutineWorkout(
            routineSessionId = routineId,
            sessions = sessions,
            completedSetsBySessionId = completedSets,
        )

        assertNotNull(export)
        assertEquals(62.5f, export.segments.single().weightKg)
    }

    private fun workoutSession(
        id: String,
        timestamp: Long = 1_000L,
        duration: Long = 30_000L,
        exerciseId: String? = "exercise",
        exerciseName: String? = "Exercise",
        routineSessionId: String? = null,
        routineName: String? = null,
        workingReps: Int = 8,
        totalReps: Int = workingReps,
        weightPerCableKg: Float = 20f,
        displayMultiplier: Int? = 1,
        estimatedCalories: Float? = null,
        rpe: Int? = null,
        heaviestLiftKg: Float? = null,
    ) = WorkoutSession(
        id = id,
        timestamp = timestamp,
        duration = duration,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        routineSessionId = routineSessionId,
        routineName = routineName,
        workingReps = workingReps,
        totalReps = totalReps,
        weightPerCableKg = weightPerCableKg,
        displayMultiplier = displayMultiplier,
        estimatedCalories = estimatedCalories,
        rpe = rpe,
        heaviestLiftKg = heaviestLiftKg,
    )

    private fun completedSet(
        id: String,
        sessionId: String,
        setNumber: Int,
        setType: SetType = SetType.STANDARD,
        actualReps: Int,
        actualWeightKg: Float,
        loggedRpe: Int?,
        completedAt: Long,
    ) = CompletedSet(
        id = id,
        sessionId = sessionId,
        plannedSetId = null,
        setNumber = setNumber,
        setType = setType,
        actualReps = actualReps,
        actualWeightKg = actualWeightKg,
        loggedRpe = loggedRpe,
        isPr = false,
        completedAt = completedAt,
    )
}
