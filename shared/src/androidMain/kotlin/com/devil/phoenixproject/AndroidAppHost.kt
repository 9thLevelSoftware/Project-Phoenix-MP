package com.devil.phoenixproject

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.devil.phoenixproject.data.migration.MigrationManager
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.di.PersistedFileStartupPrerequisite
import com.devil.phoenixproject.presentation.components.RequireBlePermissions
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import org.koin.compose.viewmodel.koinActivityViewModel
import org.koin.mp.KoinPlatform

private data class AndroidAppDependencies(
    val themeViewModel: ThemeViewModel,
    val eulaViewModel: EulaViewModel,
    val exerciseRepository: ExerciseRepository,
    val syncTriggerManager: SyncTriggerManager,
    val migrationManager: MigrationManager,
)

@Composable
fun AndroidAppHost() {
    var retryAttempt by rememberSaveable { mutableIntStateOf(0) }
    val resolution = remember(retryAttempt) {
        resolveStartupDependencies {
            val koin = KoinPlatform.getKoin()
            koin.get<PersistedFileStartupPrerequisite>()
            AndroidAppDependencies(
                themeViewModel = koin.get(),
                eulaViewModel = koin.get(),
                exerciseRepository = koin.get(),
                syncTriggerManager = koin.get(),
                migrationManager = koin.get(),
            )
        }
    }

    when (resolution) {
        is StartupDependencyResolution.Failed -> {
            PersistedFileStartupFailureScreen(resolution) { retryAttempt++ }
        }

        is StartupDependencyResolution.Ready -> RequireBlePermissions {
            AndroidAppContent(resolution.dependencies)
        }
    }
}

@Composable
private fun AndroidAppContent(dependencies: AndroidAppDependencies) {
    val mainViewModel: MainViewModel = koinActivityViewModel()

    AppContent(
        mainViewModel = mainViewModel,
        themeViewModel = dependencies.themeViewModel,
        eulaViewModel = dependencies.eulaViewModel,
        exerciseRepository = dependencies.exerciseRepository,
        syncTriggerManager = dependencies.syncTriggerManager,
        migrationManager = dependencies.migrationManager,
    )
}
