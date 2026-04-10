package com.devil.phoenixproject

import androidx.compose.runtime.Composable
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun AndroidAppHost(
    mainViewModel: MainViewModel = koinActivityViewModel(),
    themeViewModel: ThemeViewModel = koinInject(),
    eulaViewModel: EulaViewModel = koinInject(),
    exerciseRepository: ExerciseRepository = koinInject(),
    syncTriggerManager: SyncTriggerManager = koinInject(),
) {
    AppContent(
        mainViewModel = mainViewModel,
        themeViewModel = themeViewModel,
        eulaViewModel = eulaViewModel,
        exerciseRepository = exerciseRepository,
        syncTriggerManager = syncTriggerManager,
    )
}
