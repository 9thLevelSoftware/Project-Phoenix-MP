package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.QualityTrend
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.RepQualityScore
import com.devil.phoenixproject.domain.model.SetQualitySummary
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class WorkoutExitPersistenceTest {

    @Test
    fun `automatic completion claiming first prevents End Workout duplicate writes`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            startTrackedCableSet(harness)
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.activeSessionEngine.handleSetCompletion()
            runCurrent()

            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.size)

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()

            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.size)

            releaseSave.complete(Unit)
            advanceUntilIdle()

            val saved = harness.fakeWorkoutRepo.saveSessionAttempts.single()
            assertEquals(saved.id, harness.fakeCompletedSetRepo.saved.single().sessionId)
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `End Workout claiming first suppresses suspended automatic completion persistence`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseReset = CompletableDeferred<Result<Unit>>()
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeBleRepo.stopWorkoutBlock = { releaseReset.await() }

            harness.activeSessionEngine.handleSetCompletion()
            runCurrent()
            assertTrue(harness.fakeWorkoutRepo.saveSessionAttempts.isEmpty())

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()

            val saved = harness.fakeWorkoutRepo.saveSessionAttempts.single()
            assertEquals(lease.sessionId, saved.id)

            releaseReset.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == lease.sessionId })
        } finally {
            releaseReset.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `profile switch before suspended exit save keeps origin attribution`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            harness.fakeUserProfileRepo.seedReadyProfileForTest("profile-a")
            startTrackedCableSet(harness)
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            harness.fakeUserProfileRepo.seedReadyProfileForTest("profile-b")
            runCurrent()

            releaseSave.complete(Unit)
            advanceUntilIdle()

            val saved = harness.fakeWorkoutRepo.saveSessionAttempts.single()
            assertEquals("profile-a", saved.profileId)
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == saved.id })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == saved.id })
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `persistence for execution A remains claimed while safe execution B starts`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            startTrackedCableSet(harness)
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()

            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)
            assertIs<PersistenceClaimResult.DuplicateInProgress>(
                harness.activeSessionEngine.executionGuard.claimPersistence(
                    leaseA.sessionId,
                    TerminalPath.AUTO_COMPLETE,
                ),
            )

            startTrackedCableSet(harness)
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()

            assertNotEquals(leaseA.sessionId, leaseB.sessionId)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)

            releaseSave.complete(Unit)
            advanceUntilIdle()
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `failed save leaves ready trainer startable and reopens only A persistence claim`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startTrackedCableSet(harness)
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.beforeSaveSession = { error("forced save failure") }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)
            assertIs<PersistenceClaimResult.Claimed>(
                harness.activeSessionEngine.executionGuard.claimPersistence(
                    leaseA.sessionId,
                    TerminalPath.AUTO_COMPLETE,
                ),
            )

            harness.fakeWorkoutRepo.beforeSaveSession = {}
            startTrackedCableSet(harness)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `retry after session partial write leaves one stable session and completed set`() = runTest {
        val harness = DWSMTestHarness(this)
        var failOnce = true
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.afterSaveSession = {
                if (failOnce) {
                    failOnce = false
                    error("forced failure after session insert")
                }
            }

            harness.activeSessionEngine.handleSetCompletion()
            advanceUntilIdle()

            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(1, harness.fakeWorkoutRepo.allSessions().count { it.id == lease.sessionId })
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == lease.sessionId })
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `terminal capture deep copies rep metric and biomechanics arrays`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            startTrackedCableSet(harness)
            val repMetric = repMetric()
            val biomechanicsResult = harness.coordinator.biomechanicsEngine.processRep(
                repNumber = 1,
                concentricMetrics = biomechanicsMetrics(),
                allRepMetrics = biomechanicsMetrics(),
                timestamp = harness.nowMs,
            )
            val expectedRepPositions = repMetric.concentricPositions.copyOf()
            val expectedForces = biomechanicsResult.forceCurve.normalizedForceN.copyOf()
            harness.coordinator.setRepMetrics.value = listOf(repMetric)
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)

            repMetric.concentricPositions.fill(-999f)
            biomechanicsResult.forceCurve.normalizedForceN.fill(-999f)
            runCurrent()
            releaseSave.complete(Unit)
            advanceUntilIdle()

            val sessionId = harness.fakeWorkoutRepo.saveSessionAttempts.single().id
            assertContentEquals(
                expectedRepPositions,
                harness.fakeRepMetricRepo.savedMetrics.getValue(sessionId).single().concentricPositions,
            )
            assertContentEquals(
                expectedForces,
                harness.fakeBiomechanicsRepo.savedBiomechanics.getValue(sessionId).single().forceCurve.normalizedForceN,
            )
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `terminal summary copy isolates quality score list`() {
        val sourceScores = mutableListOf(
            RepQualityScore(
                composite = 90,
                romScore = 27f,
                velocityScore = 22f,
                eccentricControlScore = 23f,
                smoothnessScore = 18f,
                repNumber = 1,
            ),
        )
        val summary = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakLoadKgPerCable = 25f,
            avgLoadKgPerCable = 25f,
            repCount = 1,
            qualitySummary = SetQualitySummary(
                averageScore = 90,
                bestScore = 90,
                worstScore = 90,
                bestRepNumber = 1,
                worstRepNumber = 1,
                trend = QualityTrend.STABLE,
                repScores = sourceScores,
            ),
        )

        val snapshot = summary.deepCopyForExitSnapshot()
        sourceScores.clear()

        assertEquals(1, snapshot.qualitySummary?.repScores?.size)
    }

    private fun startTrackedCableSet(harness: DWSMTestHarness) {
        harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 3,
                warmupReps = 0,
                weightPerCableKg = 25f,
                selectedExerciseId = TestFixtures.benchPress.id,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
        harness.coordinator._repCount.value = RepCount(workingReps = 2)
    }

    private fun repMetric() = RepMetricData(
        repNumber = 1,
        isWarmup = false,
        startTimestamp = 10L,
        endTimestamp = 20L,
        durationMs = 10L,
        concentricDurationMs = 4L,
        concentricPositions = floatArrayOf(10f, 20f),
        concentricLoadsA = floatArrayOf(21f, 22f),
        concentricLoadsB = floatArrayOf(23f, 24f),
        concentricVelocities = floatArrayOf(100f, 200f),
        concentricTimestamps = longArrayOf(1L, 2L),
        eccentricDurationMs = 6L,
        eccentricPositions = floatArrayOf(20f, 10f),
        eccentricLoadsA = floatArrayOf(22f, 21f),
        eccentricLoadsB = floatArrayOf(24f, 23f),
        eccentricVelocities = floatArrayOf(-100f, -200f),
        eccentricTimestamps = longArrayOf(3L, 4L),
        peakForceA = 22f,
        peakForceB = 24f,
        avgForceConcentricA = 21.5f,
        avgForceConcentricB = 23.5f,
        avgForceEccentricA = 21.5f,
        avgForceEccentricB = 23.5f,
        peakVelocity = 200f,
        avgVelocityConcentric = 150f,
        avgVelocityEccentric = -150f,
        rangeOfMotionMm = 10f,
        peakPowerWatts = 100f,
        avgPowerWatts = 80f,
    )

    private fun biomechanicsMetrics() = listOf(
        WorkoutMetric(
            timestamp = 10L,
            loadA = 20f,
            loadB = 22f,
            positionA = 0f,
            positionB = 0f,
            velocityA = 100.0,
            velocityB = 110.0,
        ),
        WorkoutMetric(
            timestamp = 20L,
            loadA = 24f,
            loadB = 26f,
            positionA = 100f,
            positionB = 100f,
            velocityA = 120.0,
            velocityB = 130.0,
        ),
    )
}
