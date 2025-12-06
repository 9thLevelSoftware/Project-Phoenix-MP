package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.local.DatabaseFactory
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.data.migration.MigrationManager
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import com.devil.phoenixproject.data.repository.*
import com.devil.phoenixproject.domain.usecase.ProgressionUseCase
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ConnectionLogsViewModel
import com.devil.phoenixproject.presentation.viewmodel.ProtocolTesterViewModel
import com.devil.phoenixproject.presentation.viewmodel.GamificationViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import org.koin.core.module.Module
import org.koin.dsl.module

expect val platformModule: Module

val commonModule = module {
    // Database
    // DriverFactory is provided by platformModule
    single { DatabaseFactory(get()).createDatabase() }

    // Data Import
    single { ExerciseImporter(get()) }

    // Repositories
    // BleRepository is provided by platformModule
    // Order matters: ExerciseRepository must be created before WorkoutRepository
    single<ExerciseRepository> { SqlDelightExerciseRepository(get(), get()) }
    single<WorkoutRepository> { SqlDelightWorkoutRepository(get(), get()) }
    single<PersonalRecordRepository> { SqlDelightPersonalRecordRepository(get()) }
    single<GamificationRepository> { SqlDelightGamificationRepository(get()) }

    // Training Cycles Repositories
    single<TrainingCycleRepository> { SqlDelightTrainingCycleRepository(get()) }
    single<CompletedSetRepository> { SqlDelightCompletedSetRepository(get()) }
    single<ProgressionRepository> { SqlDelightProgressionRepository(get()) }

    // Preferences
    // Settings is provided by platformModule
    single<PreferencesManager> { SettingsPreferencesManager(get()) }
    
    // Use Cases
    single { RepCounterFromMachine() }
    single { ProgressionUseCase(get(), get()) }

    // Migration
    single { MigrationManager(get(), get()) }
    
    // ViewModels
    factory { MainViewModel(get(), get(), get(), get(), get(), get(), get()) }
    factory { ConnectionLogsViewModel() }
    factory { ProtocolTesterViewModel(get()) }
    factory { GamificationViewModel(get()) }
    factory { ThemeViewModel(get()) }
}