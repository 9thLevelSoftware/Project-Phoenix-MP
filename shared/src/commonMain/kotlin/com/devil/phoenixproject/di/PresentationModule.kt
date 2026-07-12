package com.devil.phoenixproject.di

import com.devil.phoenixproject.presentation.viewmodel.AssessmentViewModel
import com.devil.phoenixproject.presentation.viewmodel.ConnectionLogsViewModel
import com.devil.phoenixproject.presentation.viewmodel.CycleEditorViewModel
import com.devil.phoenixproject.presentation.viewmodel.DiagnosticsViewModel
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.ExerciseConfigViewModel
import com.devil.phoenixproject.presentation.viewmodel.ExerciseLibraryViewModel
import com.devil.phoenixproject.presentation.viewmodel.ExternalActivitiesViewModel
import com.devil.phoenixproject.presentation.viewmodel.ExternalMeasurementsViewModel
import com.devil.phoenixproject.presentation.viewmodel.ExternalProgramsViewModel
import com.devil.phoenixproject.presentation.viewmodel.ExternalRoutinesViewModel
import com.devil.phoenixproject.presentation.viewmodel.GamificationViewModel
import com.devil.phoenixproject.presentation.viewmodel.IntegrationsViewModel
import com.devil.phoenixproject.presentation.viewmodel.ProfileViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
import org.koin.dsl.module

val presentationModule = module {
    // ViewModels
    factory { ConnectionLogsViewModel() }
    factory { ExerciseConfigViewModel(get(), get(), get()) }
    factory { ExerciseLibraryViewModel(get()) }
    factory { DiagnosticsViewModel(get()) }
    factory { CycleEditorViewModel(get(), get()) }
    factory { GamificationViewModel(get(), get()) }
    factory { IntegrationsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { ExternalActivitiesViewModel(get(), get()) }
    factory { ExternalRoutinesViewModel(get(), get()) }
    factory { ExternalProgramsViewModel(get(), get(), get()) }
    factory { ExternalMeasurementsViewModel(get(), get()) }
    factory { AssessmentViewModel(get(), get(), get()) }
    factory {
        ProfileViewModel(
            profiles = get(),
            exercises = get(),
            workouts = get(),
            personalRecords = get(),
            resolveCurrentOneRepMax = get(),
            externalMeasurements = get(),
        )
    }
    // ThemeViewModel as singleton - app-wide theme state that must persist
    single { ThemeViewModel(get()) }
    // EulaViewModel as singleton - tracks EULA acceptance across app lifecycle
    single { EulaViewModel(get()) }

    // Sync UI
    factory { LinkAccountViewModel(get(), get()) }
}
