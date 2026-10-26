package com.devil.phoenixproject.presentation.tv

import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.IntegrationSyncCursorRepository
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.RecentJustLiftExerciseStore
import com.devil.phoenixproject.data.preferences.InMemoryRecentJustLiftExerciseStore
import com.devil.phoenixproject.data.repository.*
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.usecase.*
import com.devil.phoenixproject.presentation.manager.*
import com.devil.phoenixproject.testutil.*
import com.devil.phoenixproject.util.DataBackupManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TvCoreCompositionTest {
    @Test
    fun failedLocalSaveDoesNotSyncAndSharedRetryCommitsBeforeSync() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val target = FakeSyncTriggerTarget()
        val fixture = TvCoreFixture(backgroundScope, SyncTriggerManager(target, { true }))
        val main = createTvMainViewModel(fixture.koin.koin, backgroundScope)
        try {
            fixture.workouts.beforeSaveSession = { error("storage unavailable") }
            val tv = TvSessionController(main.workoutSessionManager, main.bleConnectionManager, backgroundScope) {
                main.autoplayEnabled.first { !it }
            }
            runCurrent()
            assertTrue(tv.beginRoutine(tvBodyweightRoutine()))
            runCurrent()
            assertTrue(tv.startRoutineSet())
            advanceTimeBy(35_000); runCurrent()
            assertTrue(tv.confirmBodyweightReps(5, 0))
            advanceTimeBy(2_000); runCurrent()
            val offer = assertNotNull(main.workoutSaveFailureOffer.value)
            assertTrue(fixture.workouts.allSessions().isEmpty())
            assertEquals(0, target.syncCallCount)
            assertFalse(tv.continueRoutine())
            fixture.workouts.beforeSaveSession = {}
            assertTrue(main.retryWorkoutSave(offer.sessionId))
            advanceTimeBy(2_000); runCurrent()
            assertNull(main.workoutSaveFailureOffer.value)
            assertEquals(1, fixture.workouts.allSessions().size)
            assertEquals(1, target.syncCallCount)
        } finally { main.viewModelScope.cancel(); fixture.close(); Dispatchers.resetMain() }
    }

    @Test
    fun productionTvFactoryPersistsThenInvokesSharedCompletionTrigger() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val target = FakeSyncTriggerTarget()
        val fixture = TvCoreFixture(backgroundScope, SyncTriggerManager(target, { true }))
        val main = createTvMainViewModel(fixture.koin.koin, backgroundScope)
        try {
            val before = fixture.profiles.activeProfileContext.value
            val tv = TvSessionController(main.workoutSessionManager, main.bleConnectionManager, backgroundScope) {
                main.autoplayEnabled.first { !it }
            }
            runCurrent()
            assertTrue(tv.beginRoutine(tvBodyweightRoutine()))
            runCurrent()
            assertTrue(tv.startRoutineSet())
            advanceTimeBy(35_000); runCurrent()
            assertIs<WorkoutState.BodyweightRepEntry>(main.workoutState.value)
            assertTrue(tv.confirmBodyweightReps(5, 0))
            advanceTimeBy(2_000); runCurrent()
            assertEquals(1, fixture.workouts.allSessions().size)
            assertEquals(1, target.syncCallCount, "TV production factory must not null the engine's completion trigger")
            assertEquals(before, fixture.profiles.activeProfileContext.value, "TV must not persist manual-start preferences")
        } finally {
            main.viewModelScope.cancel()
            fixture.close()
            Dispatchers.resetMain()
        }
    }
}

internal fun tvBodyweightRoutine() = Routine("tv-bw", "Pushups", exercises = listOf(
    RoutineExercise("bw-set", Exercise(id = "bw", name = "Pushup", muscleGroup = "Chest", equipment = "BODYWEIGHT"),
        0, setReps = listOf(8), weightPerCableKg = 0f, duration = 1),
))

/** Real TV factory and MainViewModel; fake storage/peripheral boundaries only. */
internal class TvCoreFixture(
    scope: CoroutineScope,
    trigger: SyncTriggerManager,
    val profiles: FakeUserProfileRepository = FakeUserProfileRepository().apply { setActiveProfileForTest() },
) {
    val ble = FakeBleRepository()
    val workouts = FakeWorkoutRepository()
    private val pr = FakePersonalRecordRepository()
    private val baseline = FakeProfileExerciseBaselineRepository()
    private val velocity = FakeVelocityOneRepMaxRepository()
    private val rack = ProfileEquipmentRackRepository(profiles, scope)
    val koin = koinApplication {
        modules(module {
            single<BleRepository> { ble }
            single<WorkoutRepository> { workouts }
            single<UserProfileRepository> { profiles }
            single<ExerciseRepository> { FakeExerciseRepository() }
            single<PersonalRecordRepository> { pr }
            single<ProfileExerciseBaselineRepository> { baseline }
            single { RepCounterFromMachine() }
            single<PreferencesManager> { FakePreferencesManager() }
            single<GamificationRepository> { FakeGamificationRepository() }
            single<TrainingCycleRepository> { FakeTrainingCycleRepository() }
            single<CompletedSetRepository> { FakeCompletedSetRepository() }
            single<ActiveWorkoutRuntimeRepository> { FakeActiveWorkoutRuntimeRepository() }
            single { DropSetEligibilityPolicy(DropSetFeatureGate { false }, DropSetCandidateResolver()) }
            single { trigger }
            single { ResolveRoutineWeightsUseCase(pr, baseline, velocity) }
            single { ApplyRoutineModifierUseCase(pr, baseline, velocity) }
            single { RecommendWeightAdjustmentUseCase() }
            single<EquipmentRackRepository> { rack }
            single { ApplyEquipmentRackLoadUseCase() }
            single<DataBackupManager> { FakeDataBackupManager() }
            single<ExternalActivityRepository> { FakeExternalActivityRepository() }
            single<WorkoutServiceController> { NoOpWorkoutServiceController }
            single<IntegrationSyncCursorRepository> { FakeIntegrationSyncCursorRepository() }
            single { ComputeVelocityOneRepMaxUseCase(
                workoutPoints = { _, _, _ -> emptyList() }, exerciseLookup = { null }, personalMvtLookup = { _, _ -> null },
                mvtProvider = com.devil.phoenixproject.domain.onerepmax.MvtProvider(),
                estimator = com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxEstimator(com.devil.phoenixproject.domain.assessment.AssessmentEngine()),
                persist = { _, _, _, _ -> },
            ) }
            single { RecordPersonalMvtSampleUseCase(object : PersonalMvtRepository {
                override suspend fun get(exerciseId: String, profileId: String) = null
                override suspend fun upsert(exerciseId: String, profileId: String, personalMvtMs: Float, sampleCount: Int) {}
            }) }
            single<VelocityOneRepMaxRepository> { velocity }
            single { CountVelocityOneRepMaxImprovementsUseCase() }
            single { BackfillVelocityOneRepMaxUseCase(exerciseIds = { emptyList() }, hasEstimates = { _, _ -> false }, computeAllTime = { _, _, _ -> null }) }
            single { MachineSafetyCoordinator(InMemoryMachineSafetyHazardRepository(), BleRepositoryMachineSafetyTransport(ble), scope, nowEpochMs = { currentTimeMillis() }) }
            single { ProfileRecoveryActivityTracker() }
            single<RecentJustLiftExerciseStore> { InMemoryRecentJustLiftExerciseStore() }
        })
    }
    fun close() { rack.close(); koin.close() }
}
