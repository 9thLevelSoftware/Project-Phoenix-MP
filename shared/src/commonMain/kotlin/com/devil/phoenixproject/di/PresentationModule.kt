package com.devil.phoenixproject.di

import com.devil.phoenixproject.presentation.manager.ExerciseDetectionManager
import com.devil.phoenixproject.presentation.viewmodel.AssessmentViewModel
import com.devil.phoenixproject.presentation.viewmodel.ConnectionLogsViewModel
import com.devil.phoenixproject.presentation.viewmodel.CycleEditorViewModel
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.GamificationViewModel
import com.devil.phoenixproject.presentation.viewmodel.IntegrationsViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
import org.koin.dsl.module

val presentationModule = module {
    // Exercise Detection Manager (per-session, not singleton)
    factory { ExerciseDetectionManager(get(), get(), get(), get()) }

    // ViewModels
    // CRITICAL FIX: MainViewModel parameters 10, 17, 18 are nullable with defaults (? = null).
    // Using get() for these crashes if the dependency can't be resolved (e.g., SyncTriggerManager
    // fails because its own dependencies fail). Using getOrNull() matches the constructor's intent:
    // these features are optional — sync, health integration, and external activities degrade
    // gracefully when unavailable.
    factory {
        MainViewModel(
            get(), get(), get(), get(), get(), get(), get(), get(), get(),
            getOrNull(),  // SyncTriggerManager? = null
            get(), get(), get(), get(), get(), get(),
            getOrNull(),  // HealthIntegration? = null
            getOrNull()   // ExternalActivityRepository? = null
        )
    }
    factory { ConnectionLogsViewModel() }
    factory { CycleEditorViewModel(get()) }
    factory { GamificationViewModel(get(), get()) }
    factory { IntegrationsViewModel(get(), get(), get(), get(), get(), get()) }
    factory { AssessmentViewModel(get(), get(), get()) }
    // ThemeViewModel as singleton - app-wide theme state that must persist
    single { ThemeViewModel(get()) }
    // EulaViewModel as singleton - tracks EULA acceptance across app lifecycle
    single { EulaViewModel(get()) }

    // Sync UI
    factory { LinkAccountViewModel(get()) }
}
