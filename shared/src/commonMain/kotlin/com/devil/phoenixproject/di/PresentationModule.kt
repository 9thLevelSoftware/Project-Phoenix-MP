package com.devil.phoenixproject.di

import com.devil.phoenixproject.presentation.viewmodel.ConnectionLogsViewModel
import com.devil.phoenixproject.presentation.viewmodel.CycleEditorViewModel
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.GamificationViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
import org.koin.dsl.module

val presentationModule = module {
    // ViewModels
    factory { MainViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { ConnectionLogsViewModel() }
    factory { CycleEditorViewModel(get()) }
    factory { GamificationViewModel(get()) }
    // ThemeViewModel as singleton - app-wide theme state that must persist
    single { ThemeViewModel(get()) }
    // EulaViewModel as singleton - tracks EULA acceptance across app lifecycle
    single { EulaViewModel(get()) }

    // Sync UI
    factory { LinkAccountViewModel(get()) }
}
