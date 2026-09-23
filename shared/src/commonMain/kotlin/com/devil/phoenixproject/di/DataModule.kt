package com.devil.phoenixproject.di

import app.cash.sqldelight.db.SqlDriver
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.ExternalExerciseTemplateRepository
import com.devil.phoenixproject.data.integration.ExternalMeasurementRepository
import com.devil.phoenixproject.data.integration.ExternalProgramRepository
import com.devil.phoenixproject.data.integration.ExternalRoutineRepository
import com.devil.phoenixproject.data.integration.HealthBackfillManager
import com.devil.phoenixproject.data.integration.IntegrationSyncCursorRepository
import com.devil.phoenixproject.data.integration.SqlDelightExternalActivityRepository
import com.devil.phoenixproject.data.integration.SqlDelightExternalExerciseTemplateRepository
import com.devil.phoenixproject.data.integration.SqlDelightExternalMeasurementRepository
import com.devil.phoenixproject.data.integration.SqlDelightExternalProgramRepository
import com.devil.phoenixproject.data.integration.SqlDelightExternalRoutineRepository
import com.devil.phoenixproject.data.integration.SqlDelightIntegrationSyncCursorRepository
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.data.preferences.LegacyProfilePreferencesReader
import com.devil.phoenixproject.data.preferences.PendingProfileDeletionStore
import com.devil.phoenixproject.data.preferences.ProfileLocalSafetyStore
import com.devil.phoenixproject.data.preferences.SettingsLegacyProfilePreferencesReader
import com.devil.phoenixproject.data.preferences.SettingsPendingProfileDeletionStore
import com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore
import com.devil.phoenixproject.data.repository.*
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import org.koin.dsl.onClose
import com.devil.phoenixproject.presentation.manager.MachineSafetyCoordinator
import com.devil.phoenixproject.presentation.manager.MachineSafetyTransport

val dataModule = module {
    // Database
    // DriverFactory is provided by platformModule. Create the driver once and hand
    // the same instance to PhoenixDatabase and MigrationManager.
    single<SqlDriver> { get<DriverFactory>().createDriver() }
    single { PhoenixDatabase(get()) }

    // Data Import
    single { ExerciseImporter(get()) }

    // Repositories
    // BleRepository is provided by platformModule
    // Order matters: ExerciseRepository must be created before WorkoutRepository
    single<ExerciseRepository> { SqlDelightExerciseRepository(get(), get(), get()) }
    single<ProfileExerciseBaselineRepository> { SqlDelightProfileExerciseBaselineRepository(get()) }
    single { LegacyBaselineRepair(get()) }
    single { ProfileMutationBarrier() }
    single { ProfileRecoveryActivityTracker() }
    single<WorkoutRepository> { SqlDelightWorkoutRepository(get(), get()) }
    single<WorkoutDeletionRepository> { SqlDelightWorkoutDeletionRepository(get()) }
    single<PersonalRecordRepository> { SqlDelightPersonalRecordRepository(get()) }
    single<GamificationRepository> { SqlDelightGamificationRepository(get()) }
    single<ProfilePreferencesRepository> { SqlDelightProfilePreferencesRepository(get()) }
    single<ProfileLocalSafetyStore> { SettingsProfileLocalSafetyStore(get()) }
    single<LegacyProfilePreferencesReader> { SettingsLegacyProfilePreferencesReader(get(), get()) }
    single { ProfileScopedDataMerger(get()) }
    single<PendingProfileDeletionStore> { SettingsPendingProfileDeletionStore(get()) }
    single<UserProfileRepository> {
        val scope = this
        SqlDelightUserProfileRepository(
            database = get(),
            profilePreferencesRepository = get(),
            profileLocalSafetyStore = get(),
            gamificationRepository = get(),
            profileScopedDataMerger = get(),
            profileMutationBarrier = get(),
            pendingDeletionStore = get(),
            // Resolved per call: the token store lives in the sync module and the
            // signed-in account changes over the app's lifetime.
            signedInPortalUserId = { scope.getOrNull<PortalTokenStorage>()?.currentUser?.value?.id },
            lastSyncedPortalUserId = { scope.getOrNull<PortalTokenStorage>()?.getLastSyncedPortalUserId() },
        )
    }
    single { ProfileRecoveryDiscovery(database = get(), driver = get()) }
    single<OwnershipTransferRepository> { SqlDelightOwnershipTransferRepository(get()) }
    single<LocalOwnershipClaimLookup> { SqlDelightLocalOwnershipClaimLookup(get()) }
    single<OwnershipEventApplier> {
        SqlDelightOwnershipEventApplier(
            database = get(),
            driver = get(),
            profileScopedDataMerger = get(),
        )
    }
    single<ProfileRecoveryRepository> {
        SqlDelightProfileRecoveryRepository(
            database = get(),
            driver = get(),
            profileScopedDataMerger = get(),
            baselineRepository = get(),
            legacyBaselineRepair = get(),
            userProfileRepository = get(),
            gamificationRepository = get(),
            profileMutationBarrier = get(),
            activityTracker = get(),
            profileRecoverySourceVerifier = get(),
        )
    }

    // Rep Metrics Repository
    single<RepMetricRepository> { SqlDelightRepMetricRepository(get()) }

    // Biomechanics Repository (Phase 13 - per-rep VBT, force curve, asymmetry)
    single<BiomechanicsRepository> { SqlDelightBiomechanicsRepository(get()) }

    // Training Cycles Repositories
    single<TrainingCycleRepository> { SqlDelightTrainingCycleRepository(get()) }
    single<CompletedSetRepository> { SqlDelightCompletedSetRepository(get()) }
    single<ActiveWorkoutRuntimeRepository> { SqlDelightActiveWorkoutRuntimeRepository(get()) }
    single<MachineSafetyHazardRepository> { SqlDelightMachineSafetyHazardRepository(get()) }
    single<MachineSafetyTransport> { BleRepositoryMachineSafetyTransport(get()) }
    single {
        MachineSafetyCoordinator(
            repository = get(),
            transport = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            nowEpochMs = ::currentTimeMillis,
        )
    }
    single<EquipmentRackRepository> {
        ProfileEquipmentRackRepository(
            profiles = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    } onClose { repository ->
        (repository as? ProfileEquipmentRackRepository)?.close()
    }

    // Smart Suggestions Repository
    single<SmartSuggestionsRepository> { SqlDelightSmartSuggestionsRepository(get()) }

    // Assessment Repository
    single<AssessmentRepository> { SqlDelightAssessmentRepository(get(), get(), get()) }

    // Velocity-based 1RM Repositories (issue #517)
    single<VelocityOneRepMaxRepository> { SqlDelightVelocityOneRepMaxRepository(get()) }
    single<PersonalMvtRepository> { SqlDelightPersonalMvtRepository(get()) }

    // External Activity Repository (Task 3 - third-party integrations)
    single<ExternalActivityRepository> { SqlDelightExternalActivityRepository(get()) }
    single<ExternalRoutineRepository> { SqlDelightExternalRoutineRepository(get()) }
    single<ExternalProgramRepository> { SqlDelightExternalProgramRepository(get()) }
    single<ExternalMeasurementRepository> { SqlDelightExternalMeasurementRepository(get()) }
    single<ExternalExerciseTemplateRepository> { SqlDelightExternalExerciseTemplateRepository(get()) }
    single<IntegrationSyncCursorRepository> { SqlDelightIntegrationSyncCursorRepository(get()) }
    single { HealthBackfillManager(get(), get(), get(), get()) }
}
