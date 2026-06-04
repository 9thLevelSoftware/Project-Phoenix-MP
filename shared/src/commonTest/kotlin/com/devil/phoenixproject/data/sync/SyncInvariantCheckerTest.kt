package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertTrue

class SyncInvariantCheckerTest {

    @Test
    fun `valid pull page produces no warnings`() {
        val response = PortalSyncPullResponse(
            syncTime = 123L,
            sessions = listOf(
                PullWorkoutSessionDto(
                    id = "session-1",
                    exercises = listOf(
                        PullExerciseDto(
                            id = "exercise-1",
                            sessionId = "session-1",
                            name = "Bench",
                            sets = listOf(
                                PullSetDto(
                                    id = "set-1",
                                    exerciseId = "exercise-1",
                                    repSummaries = listOf(
                                        PullRepSummaryDto(id = "rep-1", setId = "set-1"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    notes = "felt strong",
                ),
            ),
            routines = listOf(
                PullRoutineDto(
                    id = "routine-1",
                    name = "Push",
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "routine-exercise-1",
                            routineId = "routine-1",
                            exerciseId = "catalog-bench",
                            name = "Bench",
                        ),
                    ),
                ),
            ),
            cycles = listOf(
                PullTrainingCycleDto(
                    id = "cycle-1",
                    name = "Base",
                    days = listOf(PullCycleDayDto(id = "day-1", cycleId = "cycle-1")),
                ),
            ),
            personalRecords = listOf(
                PullPersonalRecordDto(
                    id = "pr-1",
                    exerciseName = "Bench",
                    recordType = "MAX_WEIGHT",
                    workoutPhase = "COMBINED",
                    achievedAt = "2026-06-01T00:00:00Z",
                ),
            ),
        )

        val violations = SyncInvariantChecker.checkPullPage(
            pullResponse = response,
            mobileSessions = listOf(
                WorkoutSession(
                    id = "mobile-session-1",
                    timestamp = 1000L,
                    mode = "OldSchool",
                    exerciseId = "catalog-bench",
                    exerciseName = "Bench",
                    routineSessionId = "session-1",
                ),
            ),
            sessionNoteKeys = setOf("session-1"),
        )

        assertTrue(violations.isEmpty(), "Expected no invariant warnings, got $violations")
    }

    @Test
    fun `suspicious pull page reports duplicate orphan and malformed records`() {
        val response = PortalSyncPullResponse(
            syncTime = 123L,
            sessions = listOf(
                PullWorkoutSessionDto(id = "session-1"),
                PullWorkoutSessionDto(
                    id = "SESSION-1",
                    exercises = listOf(
                        PullExerciseDto(
                            id = "exercise-1",
                            sessionId = "missing-session",
                            sets = listOf(
                                PullSetDto(
                                    id = "set-1",
                                    exerciseId = "missing-exercise",
                                    repSummaries = listOf(
                                        PullRepSummaryDto(id = "rep-1", setId = "missing-set"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            routines = listOf(
                PullRoutineDto(
                    id = "routine-1",
                    name = "Push",
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "routine-exercise-1",
                            routineId = "missing-routine",
                            exerciseId = null,
                            name = "",
                        ),
                    ),
                ),
            ),
            cycles = listOf(
                PullTrainingCycleDto(
                    id = "cycle-1",
                    name = "Base",
                    days = listOf(PullCycleDayDto(id = "day-1", cycleId = "missing-cycle")),
                ),
            ),
            personalRecords = listOf(
                PullPersonalRecordDto(id = "pr-1", exerciseName = "", achievedAt = null),
                PullPersonalRecordDto(
                    id = "pr-2",
                    exerciseName = "Bench",
                    recordType = "MAX_WEIGHT",
                    workoutPhase = "COMBINED",
                    achievedAt = "2026-06-01T00:00:00Z",
                    sessionId = "session-1",
                ),
                PullPersonalRecordDto(
                    id = "pr-3",
                    exerciseName = "Bench",
                    recordType = "MAX_WEIGHT",
                    workoutPhase = "COMBINED",
                    achievedAt = "2026-06-01T00:00:00Z",
                    sessionId = "session-1",
                ),
            ),
        )

        val violations = SyncInvariantChecker.checkPullPage(
            pullResponse = response,
            mobileSessions = listOf(
                WorkoutSession(
                    id = "mobile-session-1",
                    timestamp = 1000L,
                    mode = "OldSchool",
                    exerciseId = "catalog-bench",
                    exerciseName = "Bench",
                    routineSessionId = "routine-run-1",
                ),
                WorkoutSession(
                    id = "mobile-session-2",
                    timestamp = 1000L,
                    mode = "OldSchool",
                    exerciseId = "catalog-bench",
                    exerciseName = "Bench",
                    routineSessionId = "routine-run-1",
                ),
            ),
            sessionNoteKeys = setOf("missing-session-note"),
        )
        val codes = violations.map { it.code }.toSet()

        assertTrue("DUPLICATE_PULL_SESSION_ID" in codes)
        assertTrue("ORPHAN_PULL_EXERCISE" in codes)
        assertTrue("ORPHAN_PULL_SET" in codes)
        assertTrue("ORPHAN_PULL_REP_SUMMARY" in codes)
        assertTrue("ORPHAN_PULL_ROUTINE_EXERCISE" in codes)
        assertTrue("MISSING_ROUTINE_EXERCISE_IDENTITY" in codes)
        assertTrue("ORPHAN_PULL_CYCLE_DAY" in codes)
        assertTrue("MISSING_PR_EXERCISE_IDENTITY" in codes)
        assertTrue("MISSING_PR_ACHIEVED_AT" in codes)
        assertTrue("DUPLICATE_LOGICAL_PR" in codes)
        assertTrue("ORPHAN_SESSION_NOTE" in codes)
        assertTrue("DUPLICATE_LOGICAL_SESSION" in codes)
    }
}
