package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.migration.MigrationManager
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.usecase.ProgressionUseCase
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.domain.usecase.RoutineTimeEstimator
import com.devil.phoenixproject.domain.usecase.TemplateConverter
import com.devil.phoenixproject.domain.voice.SafeWordDetectionManager
import org.koin.dsl.module

val domainModule = module {
    // Preferences
    // Settings is provided by platformModule
    single { SettingsPreferencesManager(get()) }
    single<PreferencesManager> { get<SettingsPreferencesManager>() }

    // Use Cases
    single { RepCounterFromMachine() }
    single { ProgressionUseCase(get(), get()) }
    factory { ResolveRoutineWeightsUseCase(get(), get()) }
    factory { RoutineTimeEstimator(get()) }
    single { TemplateConverter(get()) }

    // Assessment
    single { AssessmentEngine() }

    // Migration
    single { MigrationManager(get(), get<UserProfileRepository>(), get<GamificationRepository>()) }

    // Voice / Safe Word (Issue #141)
    // SafeWordListenerFactory is provided by platformModule
    single { SafeWordDetectionManager(get(), get()) }
}
