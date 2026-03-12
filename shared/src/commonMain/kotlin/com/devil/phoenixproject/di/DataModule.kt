package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.local.DatabaseFactory
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.data.context.VendorContextProvider
import com.devil.phoenixproject.data.repository.*
import org.koin.dsl.module

val dataModule = module {
    // Database
    // DriverFactory is provided by platformModule
    single { DatabaseFactory(get()).createDatabase() }

    // Data Import
    single { ExerciseImporter(get()) }
    single { VendorContextProvider() }

    // Repositories
    // BleRepository is provided by platformModule
    // Order matters: ExerciseRepository must be created before WorkoutRepository
    single<ExerciseRepository> { SqlDelightExerciseRepository(get(), get()) }
    single<WorkoutRepository> { SqlDelightWorkoutRepository(get(), get(), get()) }
    single<PersonalRecordRepository> { SqlDelightPersonalRecordRepository(get()) }
    single<GamificationRepository> { SqlDelightGamificationRepository(get()) }
    single<UserProfileRepository> { SqlDelightUserProfileRepository(get()) }

    // Training Cycles Repositories
    single<TrainingCycleRepository> { SqlDelightTrainingCycleRepository(get(), get()) }
    single<CompletedSetRepository> { SqlDelightCompletedSetRepository(get()) }
    single<ProgressionRepository> { SqlDelightProgressionRepository(get()) }
}
