package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.local.DatabaseFactory
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import com.devil.phoenixproject.data.repository.*
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ConnectionLogsViewModel
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
    single<WorkoutRepository> { SqlDelightWorkoutRepository(get()) }
    single<ExerciseRepository> { SqlDelightExerciseRepository(get(), get()) }
    single<PersonalRecordRepository> { SqlDelightPersonalRecordRepository(get()) }
    
    // Preferences
    // Settings is provided by platformModule
    single<PreferencesManager> { SettingsPreferencesManager(get()) }
    
    // Use Cases
    single { RepCounterFromMachine() }
    
    // ViewModels
    factory { MainViewModel(get(), get(), get(), get(), get(), get()) }
    factory { ConnectionLogsViewModel() }
}