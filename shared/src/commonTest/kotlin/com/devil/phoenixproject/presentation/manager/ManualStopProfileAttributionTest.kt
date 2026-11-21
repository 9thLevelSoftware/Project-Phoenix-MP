package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FP-6 / F-072: the manual-stop path that runs when no execution lease is
 * current still writes a WorkoutSession, a CompletedSet and PRs. It used to
 * stamp them with `userProfileRepository.activeProfile`, so a profile switch
 * between the set and the Stop press filed one member's work under whichever
 * profile happens to be on screen. The write must follow the profile that
 * OWNED the execution.
 *
 * Reaching that branch takes a real sequence, because a live lease always
 * produces a completion and therefore takes the snapshot path instead: run a
 * routine set to completion, end the workout (which invalidates the lease and,
 * through the runtime cleanup, releases the stop guard), then press Stop again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualStopProfileAttributionTest {
    private fun routine() = Routine(
        id = "attribution-routine",
        name = "Attribution",
        exercises = listOf(
            RoutineExercise(
                id = "rex-1",
                exercise = Exercise(
                    id = "ex-1",
                    name = "Cable Row",
                    muscleGroup = "Back",
                    muscleGroups = "Back",
                    equipment = "Cable",
                ),
                orderIndex = 0,
                setReps = listOf(8, 8),
                weightPerCableKg = 40f,
                programMode = ProgramMode.OldSchool,
            ),
        ),
    )

    /**
     * Leaves the engine with no current lease, the stop guard released and the
     * completed set's execution context still in place — the exact state the
     * no-completion manual-stop branch runs in.
     */
    private suspend fun TestScope.runASetThenEndTheWorkout(harness: DWSMTestHarness) {
        harness.fakeUserProfileRepo.seedReadyProfileForTest(OWNER, name = "Owner")
        harness.fakeUserProfileRepo.seedReadyProfileForTest(OTHER, name = "Other")
        harness.fakeUserProfileRepo.emitReadyForTest(OWNER)
        advanceUntilIdle()

        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.loadRoutineAsync(routine())
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        harness.coordinator._repCount.value = RepCount(workingReps = 8, totalReps = 8, isWarmupComplete = true)
        harness.activeSessionEngine.handleSetCompletion(
            harness.activeSessionEngine.currentExecutionLeaseForTest(),
            SetEndReason.TARGET_REPS_REACHED,
        )
        advanceUntilIdle()

        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()

        assertTrue(
            runCatching { harness.activeSessionEngine.currentExecutionLeaseForTest() }.isFailure,
            "the no-completion branch only runs without a current lease",
        )
        assertEquals(false, harness.coordinator.stopWorkoutInProgress.value)
    }

    private fun TestScope.stopAgainUnderAnotherProfile(harness: DWSMTestHarness) {
        harness.coordinator._repCount.value = RepCount(
            workingReps = LEGACY_REPS,
            totalReps = LEGACY_REPS,
            isWarmupComplete = true,
        )
        harness.coordinator.workoutStartTime = harness.nowMs - 10_000
        // The profile switch the finding describes: it lands between the set and Stop.
        harness.fakeUserProfileRepo.emitReadyForTest(OTHER)
        advanceUntilIdle()
        assertEquals(OTHER, harness.fakeUserProfileRepo.activeProfile.value?.id)

        harness.dwsm.stopWorkout()
        advanceUntilIdle()
    }

    @Test
    fun manualStopWithoutALeaseFilesTheSessionSetAndPrsUnderTheOwningProfile() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            runASetThenEndTheWorkout(harness)
            stopAgainUnderAnotherProfile(harness)

            val legacySession = harness.fakeWorkoutRepo.allSessions().single { it.totalReps == LEGACY_REPS }
            assertEquals(OWNER, legacySession.profileId, "the session must follow the profile that owned the set")

            val legacySet = harness.fakeCompletedSetRepo.saved.single { it.actualReps == LEGACY_REPS }
            assertEquals(legacySession.id, legacySet.sessionId)

            val ownerPrs = harness.fakePRRepo.getAllPRsForExercise("ex-1", OWNER)
            val otherPrs = harness.fakePRRepo.getAllPRsForExercise("ex-1", OTHER)
            assertTrue(
                ownerPrs.any { it.prType == PRType.MAX_WEIGHT && it.phase == WorkoutPhase.COMBINED },
                "the PR must be filed under the owning profile",
            )
            assertTrue(otherPrs.isEmpty(), "no PR may leak into the profile that was merely on screen")
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun manualStopWithoutALeaseCommitsTheSessionAndItsSetInOneTransaction() = runTest {
        val inner = FakeWorkoutRepository()
        val recorder = RecordingWorkoutRepository(inner)
        val harness = DWSMTestHarness(this, workoutRepositoryOverride = recorder)
        try {
            inner.completedSetRepository = harness.fakeCompletedSetRepo
            inner.repMetricRepository = harness.fakeRepMetricRepo
            inner.biomechanicsRepository = harness.fakeBiomechanicsRepo

            runASetThenEndTheWorkout(harness)
            recorder.saveSessionCalls.clear()
            recorder.commits.clear()

            stopAgainUnderAnotherProfile(harness)

            val commit = recorder.commits.singleOrNull { it.first.totalReps == LEGACY_REPS }
            assertNotNull(commit, "the manual-stop save must go through commitCompletedSet")
            assertNotNull(commit.second, "the CompletedSet must ride in the same transaction as its session")
            assertEquals(commit.first.id, commit.second?.sessionId)
            assertTrue(
                recorder.saveSessionCalls.none { it.totalReps == LEGACY_REPS },
                "the session must not be written by a separate saveSession transaction",
            )
        } finally {
            harness.cleanup()
        }
    }

    private class RecordingWorkoutRepository(
        private val delegate: FakeWorkoutRepository,
    ) : WorkoutRepository by delegate {
        val saveSessionCalls = mutableListOf<WorkoutSession>()
        val commits = mutableListOf<Pair<WorkoutSession, CompletedSet?>>()

        override suspend fun saveSession(session: WorkoutSession) {
            saveSessionCalls += session
            delegate.saveSession(session)
        }

        override suspend fun commitCompletedSet(
            session: WorkoutSession,
            metrics: List<WorkoutMetric>,
            completedSet: CompletedSet?,
            repMetrics: List<RepMetricData>,
            repBiomechanics: List<BiomechanicsRepResult>,
        ) {
            commits += session to completedSet
            delegate.commitCompletedSet(session, metrics, completedSet, repMetrics, repBiomechanics)
        }
    }

    private companion object {
        const val OWNER = "owner"
        const val OTHER = "other"
        const val LEGACY_REPS = 7
    }
}
