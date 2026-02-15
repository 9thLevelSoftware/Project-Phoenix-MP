package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.migration.MigrationManager
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.detection.ExerciseClassifier
import com.devil.phoenixproject.domain.detection.SignatureExtractor
import com.devil.phoenixproject.domain.usecase.ProgressionUseCase
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.domain.usecase.TemplateConverter
import org.koin.dsl.module

val domainModule = module {
    // Preferences
    // Settings is provided by platformModule
    single<PreferencesManager> { SettingsPreferencesManager(get()) }

    // Use Cases
    single { RepCounterFromMachine() }
    single { ProgressionUseCase(get(), get()) }
    factory { ResolveRoutineWeightsUseCase(get()) }
    single { TemplateConverter(get()) }

    // Assessment
    single { AssessmentEngine() }

    // Exercise Detection
    single { SignatureExtractor() }
    single { ExerciseClassifier() }

    // Migration
    single { MigrationManager() }
}
