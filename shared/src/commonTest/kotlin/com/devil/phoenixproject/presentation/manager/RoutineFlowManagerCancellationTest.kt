package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.devil.phoenixproject.data.repository.ProfileEquipmentRackRepository
import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.usecase.ApplyEquipmentRackLoadUseCase
import com.devil.phoenixproject.domain.usecase.ApplyRoutineModifierUseCase
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeProfileExerciseBaselineRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RoutineFlowManagerCancellationTest {

    @Test
    fun `cancelling scope during ROUTINE_GROUPS failure backoff exits immediately without retry delay`() = runTest {
        val db = createTestDatabase()
        val exerciseRepo = FakeExerciseRepository()
        val sqlWorkoutRepo = SqlDelightWorkoutRepository(db, exerciseRepo)
        val baseProfileRepo = FakeUserProfileRepository().apply { setActiveProfileForTest() }
        val failingActiveProfileFlow = object : StateFlow<UserProfile?> by baseProfileRepo.activeProfile {
            override suspend fun collect(collector: FlowCollector<UserProfile?>): Nothing {
                throw IllegalStateException("simulated routine group query failure")
            }
        }
        val failingProfileRepo = object : UserProfileRepository by baseProfileRepo {
            override val activeProfile: StateFlow<UserProfile?> = failingActiveProfileFlow
        }

        val recordedLogs = mutableListOf<String>()
        val originalWriters = Logger.config.logWriterList
        Logger.setLogWriters(
            object : LogWriter() {
                override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                    recordedLogs += "$severity:$message"
                }
            },
        )

        val managerJob = SupervisorJob()
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + managerJob)
        val rackRepo = ProfileEquipmentRackRepository(baseProfileRepo, managerScope)
        try {
            RoutineFlowManager(
                coordinator = WorkoutCoordinator(),
                workoutRepository = sqlWorkoutRepo,
                exerciseRepository = exerciseRepo,
                resolveWeightsUseCase = ResolveRoutineWeightsUseCase(
                    FakePersonalRecordRepository(),
                    FakeProfileExerciseBaselineRepository(),
                    FakeVelocityOneRepMaxRepository(),
                    FakeCompletedSetRepository(),
                ),
                applyRoutineModifierUseCase = ApplyRoutineModifierUseCase(
                    FakePersonalRecordRepository(),
                    FakeProfileExerciseBaselineRepository(),
                    FakeVelocityOneRepMaxRepository(),
                    FakeCompletedSetRepository(),
                ),
                userProfileRepository = failingProfileRepo,
                equipmentRackRepository = rackRepo,
                applyEquipmentRackLoadUseCase = ApplyEquipmentRackLoadUseCase(),
                scope = managerScope,
            )

            runCurrent()
            assertEquals(
                1,
                recordedLogs.count { it.contains("ROUTINE_GROUPS: Error loading groups") },
                "Should log attempt 1/3 before entering backoff delay",
            )

            // Cancel during the 1000ms backoff delay
            advanceTimeBy(100)
            runCurrent()
            managerScope.cancel()
            advanceUntilIdle()

            assertEquals(100L, testScheduler.currentTime)
            assertEquals(
                1,
                recordedLogs.count { it.contains("ROUTINE_GROUPS: Error loading groups") },
                "Cancelling during ROUTINE_GROUPS backoff must rethrow CancellationException instead of retrying 2/3..4/3",
            )
            assertTrue(
                recordedLogs.none { it.contains("All 3 retries exhausted") },
                "Cancelled collector must not log exhausted retries",
            )
        } finally {
            rackRepo.close()
            managerScope.cancel()
            Logger.setLogWriters(originalWriters)
        }
    }

    @Test
    fun `deleteRoutines and loadRoutineFromCycle log RuntimeException non-fatally when repository throws`() = runTest {
        val backingWorkoutRepo = FakeWorkoutRepository()
        val throwingWorkoutRepo = object : WorkoutRepository by backingWorkoutRepo {
            override suspend fun deleteRoutine(routineId: String) {
                throw RuntimeException("deleteRoutine boom")
            }

            override suspend fun getRoutineById(routineId: String): Routine? {
                throw RuntimeException("getRoutineById boom")
            }
        }
        val recordedLogs = mutableListOf<String>()
        val originalWriters = Logger.config.logWriterList
        Logger.setLogWriters(
            object : LogWriter() {
                override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                    recordedLogs += "$severity:$message"
                }
            },
        )

        val harness = DWSMTestHarness(this, workoutRepositoryOverride = throwingWorkoutRepo)
        try {
            advanceUntilIdle()
            harness.dwsm.deleteRoutines(setOf("r1"))
            harness.dwsm.loadRoutineFromCycle("missing-id", "cycle-1", 1)
            advanceUntilIdle()

            assertTrue(
                recordedLogs.any { it.contains("Failed to delete routines") },
                "deleteRoutines failure must be caught and logged non-fatally",
            )
            assertTrue(
                recordedLogs.any { it.contains("Failed to load routine missing-id from cycle cycle-1 day 1") },
                "loadRoutineFromCycle failure must be caught and logged non-fatally",
            )
        } finally {
            harness.cleanup()
            Logger.setLogWriters(originalWriters)
        }
    }
}
