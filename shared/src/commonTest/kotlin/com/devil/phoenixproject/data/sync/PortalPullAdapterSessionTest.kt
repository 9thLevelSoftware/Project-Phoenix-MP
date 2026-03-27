package com.devil.phoenixproject.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class PortalPullAdapterSessionTest {

    @Test
    fun `single exercise session maps to one WorkoutSession`() {
        val portalSession = PullWorkoutSessionDto(
            id = "portal-session-1",
            userId = "user-1",
            name = "Bench Press Workout",
            startedAt = "2026-03-20T10:00:00Z",
            durationSeconds = 300,
            totalVolume = 1200f,
            setCount = 3,
            exerciseCount = 1,
            prCount = 0,
            routineName = null,
            workoutMode = "OLD_SCHOOL",
            exercises = listOf(
                PullExerciseDto(
                    id = "ex-1",
                    sessionId = "portal-session-1",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    orderIndex = 0,
                    sets = listOf(
                        PullSetDto(id = "set-1", exerciseId = "ex-1", setNumber = 1, actualReps = 10, weightKg = 50f),
                        PullSetDto(id = "set-2", exerciseId = "ex-1", setNumber = 2, actualReps = 10, weightKg = 55f),
                        PullSetDto(id = "set-3", exerciseId = "ex-1", setNumber = 3, actualReps = 8, weightKg = 60f),
                    ),
                ),
            ),
        )

        val sessions = PortalPullAdapter.toWorkoutSessions(portalSession, "default")

        assertEquals(1, sessions.size)
        val session = sessions.first()
        assertEquals("Bench Press", session.exerciseName)
        assertEquals("OldSchool", session.mode) // portalModeToMobileMode converts SCREAMING_SNAKE to PascalCase
        assertEquals(60f, session.weightPerCableKg) // max weight across sets (DB stores per-cable, no division needed)
        assertEquals(28, session.totalReps) // 10 + 10 + 8
        assertEquals("portal-session-1", session.routineSessionId)
        assertEquals("default", session.profileId)
    }

    @Test
    fun `multi exercise session maps to N WorkoutSessions`() {
        val portalSession = PullWorkoutSessionDto(
            id = "portal-session-2",
            userId = "user-1",
            startedAt = "2026-03-20T10:00:00Z",
            durationSeconds = 600,
            totalVolume = 2400f,
            setCount = 6,
            exerciseCount = 2,
            routineName = "Push Day",
            workoutMode = "OLD_SCHOOL",
            exercises = listOf(
                PullExerciseDto(
                    id = "ex-1",
                    sessionId = "portal-session-2",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    orderIndex = 0,
                    sets = listOf(
                        PullSetDto(id = "s1", exerciseId = "ex-1", setNumber = 1, actualReps = 10, weightKg = 50f),
                    ),
                ),
                PullExerciseDto(
                    id = "ex-2",
                    sessionId = "portal-session-2",
                    name = "Shoulder Press",
                    muscleGroup = "Shoulders",
                    orderIndex = 1,
                    sets = listOf(
                        PullSetDto(id = "s2", exerciseId = "ex-2", setNumber = 1, actualReps = 12, weightKg = 30f),
                    ),
                ),
            ),
        )

        val sessions = PortalPullAdapter.toWorkoutSessions(portalSession, "default")

        assertEquals(2, sessions.size)
        assertEquals("Bench Press", sessions[0].exerciseName)
        assertEquals("Shoulder Press", sessions[1].exerciseName)
        // Both share the same routineSessionId
        assertEquals(sessions[0].routineSessionId, sessions[1].routineSessionId)
        assertEquals("Push Day", sessions[0].routineName)
    }

    @Test
    fun `empty exercises produces empty list`() {
        val portalSession = PullWorkoutSessionDto(
            id = "portal-session-3",
            startedAt = "2026-03-20T10:00:00Z",
            exercises = emptyList(),
        )

        val sessions = PortalPullAdapter.toWorkoutSessions(portalSession, "default")
        assertEquals(0, sessions.size)
    }

    @Test
    fun `null startedAt produces empty list`() {
        val portalSession = PullWorkoutSessionDto(
            id = "portal-session-4",
            startedAt = null,
            exercises = listOf(
                PullExerciseDto(
                    id = "ex-1",
                    sessionId = "portal-session-4",
                    name = "Deadlift",
                    sets = listOf(PullSetDto(id = "s1", exerciseId = "ex-1", setNumber = 1, actualReps = 5, weightKg = 100f)),
                ),
            ),
        )

        val sessions = PortalPullAdapter.toWorkoutSessions(portalSession, "default")
        assertEquals(0, sessions.size)
    }

    @Test
    fun `session duration is split evenly across exercises`() {
        val portalSession = PullWorkoutSessionDto(
            id = "portal-session-5",
            startedAt = "2026-03-20T10:00:00Z",
            durationSeconds = 600,
            exerciseCount = 3,
            exercises = listOf(
                PullExerciseDto(
                    id = "ex-1",
                    sessionId = "s5",
                    name = "Ex1",
                    sets = listOf(PullSetDto(id = "s1", exerciseId = "ex-1", setNumber = 1, actualReps = 10, weightKg = 50f)),
                ),
                PullExerciseDto(
                    id = "ex-2",
                    sessionId = "s5",
                    name = "Ex2",
                    sets = listOf(PullSetDto(id = "s2", exerciseId = "ex-2", setNumber = 1, actualReps = 10, weightKg = 50f)),
                ),
                PullExerciseDto(
                    id = "ex-3",
                    sessionId = "s5",
                    name = "Ex3",
                    sets = listOf(PullSetDto(id = "s3", exerciseId = "ex-3", setNumber = 1, actualReps = 10, weightKg = 50f)),
                ),
            ),
        )

        val sessions = PortalPullAdapter.toWorkoutSessions(portalSession, "default")
        // 600 seconds / 3 exercises = 200 seconds each = 200_000 ms
        assertEquals(200_000L, sessions[0].duration)
    }

    @Test
    fun `exercise with no sets gets zero weight and zero reps`() {
        val portalSession = PullWorkoutSessionDto(
            id = "portal-session-6",
            startedAt = "2026-03-20T10:00:00Z",
            exerciseCount = 1,
            exercises = listOf(
                PullExerciseDto(id = "ex-1", sessionId = "s6", name = "Empty Exercise", sets = emptyList()),
            ),
        )

        val sessions = PortalPullAdapter.toWorkoutSessions(portalSession, "default")
        assertEquals(1, sessions.size)
        assertEquals(0f, sessions[0].weightPerCableKg)
        assertEquals(0, sessions[0].totalReps)
    }
}
