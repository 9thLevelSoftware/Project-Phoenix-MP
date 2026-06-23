package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.WorkoutSession
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

/**
 * Issue #591: Analytics only records first set of first exercise in
 * multi-exercise routine; per-set drill-down shows 'after v0.2.1'
 * placeholder on v0.9.2.
 *
 * Regression coverage for the HistoryManager / WorkoutRepository
 * filter that excludes zero-rep / soft-deleted sessions from the
 * Analytics / History grouping. The reporter saw a Daily Routine card
 * that counted six zero-rep rows for "Incline Fly" as six sets
 * ("0 reps / 6 sets / 0 lbs"). After the fix, only sessions with at
 * least one recorded rep appear in `groupedWorkoutHistory`.
 */
class Issue591HistoryGroupingTest {

    private val testProfileId = "test-profile"

    /**
     * HistoryManager.stateIn starts work eagerly on subscription; the
     * test must drive the work immediately or the first() call returns
     * the empty initialValue. UnconfinedTestDispatcher schedules work
     * inline so the upstream flows have time to publish before we read.
     */
    private fun newScope() = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @Test
    fun `groupedWorkoutHistory excludes zero-rep sessions from routine set counts`() = runTest {
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

            // GIVEN: A routine with mixed valid / zero-rep rows.
            // Mirrors the reporter's screenshot:
            //   - Incline Bench Press: 3 valid sets (8 reps each, 30 kg)
            //   - Incline Fly: 6 zero-rep rows (recorder bug, never
            //     captured rep counts for that exercise).
            val routineSessionId = "routine-1"
            val baseTimestamp = 1_700_000_000_000L
            val bench = (0 until 3).map { i ->
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
            val fly = (0 until 6).map { i ->
                session(
                    id = "fly-$i",
                    timestamp = baseTimestamp + 240_000L + i * 60_000L,
                    routineSessionId = routineSessionId,
                    exerciseId = "fly",
                    exerciseName = "Incline Fly",
                    weightPerCableKg = 25f,
                    totalReps = 0, // Ghost rows
                    workingReps = 0,
                )
            }
            workoutRepo.addSessions(bench + fly)

            // WHEN: History is grouped.
            val grouped = manager.groupedWorkoutHistory.first()
                .filterIsInstance<GroupedRoutineHistoryItem>()
                .single()

            // THEN: only valid sets are counted per exercise.
            // Incline Fly rows were all zero-rep and the History filter
            // excludes them; the exercise should not appear as a group
            // because no valid sets exist for it.
            val exercisesByName = grouped.sessions
                .groupBy { it.exerciseName ?: "Unknown" }
            val benchGroup = exercisesByName["Incline Bench Press"]
                ?: error("Incline Bench Press group should exist")
            assertEquals(3, benchGroup.size, "Incline Bench Press should show 3 sets")
            assertEquals(
                emptyList(),
                exercisesByName["Incline Fly"] ?: emptyList(),
                "Incline Fly should not appear in routine set grouping (every row was a zero-rep ghost row)",
            )
            assertEquals(24, grouped.totalReps, "totalReps sums only the valid bench rows (8 x 3)")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `groupedWorkoutHistory includes rows with non-zero totalReps even if workingReps is zero`() = runTest {
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

            // A legacy / tagged Just Lift session that populated only
            // totalReps but no warmup/working split is still a real
            // workout. The SQL filter (workingReps > 0 OR totalReps > 0)
            // accepts it.
            workoutRepo.addSession(
                session(
                    id = "legacy-1",
                    routineSessionId = "r-legacy",
                    exerciseId = "ex-legacy",
                    exerciseName = "Curl",
                    totalReps = 5,
                    workingReps = 0,
                ),
            )

            val grouped = manager.groupedWorkoutHistory.first()
                .filterIsInstance<GroupedRoutineHistoryItem>()
                .single()
            assertEquals(1, grouped.sessions.size)
            assertEquals(5, grouped.totalReps)
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
    ): WorkoutSession = WorkoutSession(
        id = id,
        timestamp = timestamp,
        mode = "OldSchool",
        reps = 10,
        weightPerCableKg = weightPerCableKg,
        duration = if (totalReps > 0) 60_000L else 0L,
        totalReps = totalReps,
        warmupReps = 0,
        workingReps = workingReps,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        routineSessionId = routineSessionId,
        routineName = if (routineSessionId != null) "Test Routine" else null,
        profileId = testProfileId,
    )
}
