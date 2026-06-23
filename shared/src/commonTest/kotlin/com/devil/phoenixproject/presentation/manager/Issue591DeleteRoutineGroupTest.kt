package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #591 follow-up (chatgpt-codex-connector P2): When the user
 * deletes a routine session from the History tab, the group action
 * must remove every WorkoutSession row that belongs to the routine
 * session id — including zero-rep / ghost rows that
 * `getHistoryVisibleSessions` would have filtered out of the visible
 * list. Without this, the ghost rows persist silently after the
 * deletion and re-appear in any code path that bypasses the History
 * filter (e.g. Cloud sync pull, streak calc, DataBackup export).
 */
class Issue591DeleteRoutineGroupTest {

    private val testProfileId = "test-profile"

    private fun newScope() = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @Test
    fun `deleteRoutineWorkouts removes ghost rows for the same routineSessionId`() = runTest {
        val scope = newScope()
        try {
            val workoutRepo = FakeWorkoutRepository()
            val prRepo = FakePersonalRecordRepository()
            val profileRepo = FakeUserProfileRepository()
            profileRepo.setActiveProfileForTest(id = testProfileId)
            val manager = HistoryManager(
                workoutRepository = workoutRepo,
                personalRecordRepository = prRepo,
                userProfileRepository = profileRepo,
                scope = scope,
            )

            // GIVEN: A routine with valid sets (visible) AND ghost
            // zero-rep rows (filtered out by `getHistoryVisibleSessions`).
            val routineSessionId = "routine-1"
            val baseTimestamp = 1_700_000_000_000L
            val valid = (0 until 3).map { i ->
                session(
                    id = "bench-$i",
                    timestamp = baseTimestamp + i * 60_000L,
                    routineSessionId = routineSessionId,
                    exerciseId = "bench",
                    exerciseName = "Incline Bench Press",
                    weightPerCableKg = 30f,
                    totalReps = 8,
                    workingReps = 8,
                )
            }
            val ghost = (0 until 4).map { i ->
                session(
                    id = "fly-ghost-$i",
                    timestamp = baseTimestamp + 240_000L + i * 60_000L,
                    routineSessionId = routineSessionId,
                    exerciseId = "fly",
                    exerciseName = "Incline Fly",
                    weightPerCableKg = 25f,
                    totalReps = 0,
                    workingReps = 0,
                )
            }
            // Unrelated session for a different routine that must survive.
            val other = listOf(
                session(
                    id = "curl-other",
                    timestamp = baseTimestamp + 1_000_000L,
                    routineSessionId = "routine-2",
                    exerciseId = "curl",
                    exerciseName = "Curl",
                    weightPerCableKg = 20f,
                    totalReps = 5,
                    workingReps = 5,
                ),
            )
            workoutRepo.addSessions(valid + ghost + other)
            assertEquals(8, workoutRepo.allSessions().size, "precondition: all 8 rows present")

            // WHEN: the user deletes the routine group.
            manager.deleteRoutineWorkouts(routineSessionId)

            // THEN: every row tied to that routineSessionId is gone —
            // both the visible valid sets and the ghost rows. The
            // unrelated routine survives.
            val remaining = workoutRepo.allSessions()
            assertEquals(1, remaining.size, "only the unrelated routine survives")
            assertEquals("curl-other", remaining.single().id)

            // HistoryManager should no longer surface the deleted
            // routine either.
            val grouped = manager.groupedWorkoutHistory.first()
            assertTrue(
                grouped.none { it is GroupedRoutineHistoryItem && it.routineSessionId == routineSessionId },
                "deleted routine must not appear in groupedWorkoutHistory",
            )
        } finally {
            scope.cancel()
        }
    }

    private fun session(
        id: String,
        timestamp: Long = 1_700_000_000_000L,
        routineSessionId: String? = null,
        exerciseId: String? = null,
        exerciseName: String? = null,
        weightPerCableKg: Float = 0f,
        totalReps: Int = 0,
        workingReps: Int = 0,
    ) = com.devil.phoenixproject.domain.model.WorkoutSession(
        id = id,
        timestamp = timestamp,
        mode = "OldSchool",
        reps = totalReps,
        weightPerCableKg = weightPerCableKg,
        progressionKg = 0f,
        duration = 0L,
        totalReps = totalReps,
        warmupReps = 0,
        workingReps = workingReps,
        isJustLift = false,
        stopAtTop = false,
        eccentricLoad = 100,
        echoLevel = 1,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        routineSessionId = routineSessionId,
        routineName = if (routineSessionId != null) "Test Routine" else null,
        safetyFlags = 0,
        deloadWarningCount = 0,
        romViolationCount = 0,
        spotterActivations = 0,
        profileId = testProfileId,
    )
}
