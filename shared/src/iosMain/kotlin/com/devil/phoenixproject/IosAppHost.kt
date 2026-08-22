package com.devil.phoenixproject

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.migration.MigrationManager
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.di.PersistedFileStartupPrerequisite
import com.devil.phoenixproject.presentation.components.RequireBlePermissions
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import org.koin.mp.KoinPlatform

private data class IosAppDependencies(
    val mainViewModel: MainViewModel,
    val themeViewModel: ThemeViewModel,
    val eulaViewModel: EulaViewModel,
    val exerciseRepository: ExerciseRepository,
    val syncTriggerManager: SyncTriggerManager,
    val migrationManager: MigrationManager,
)

@Composable
fun IosAppHost() {
    var retryAttempt by rememberSaveable { mutableIntStateOf(0) }
    val resolution = remember(retryAttempt) {
        resolveStartupDependencies {
            val koin = KoinPlatform.getKoin()
            Logger.i { "iOS AppHost: Resolving app dependencies via Koin" }
            koin.get<PersistedFileStartupPrerequisite>()
            IosAppDependencies(
                mainViewModel = koin.get(),
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
            Logger.e { "iOS app dependency resolution blocked: ${resolution.diagnosticCode}" }
            PersistedFileStartupFailureScreen(resolution) { retryAttempt++ }
        }

        is StartupDependencyResolution.Ready -> RequireBlePermissions {
            IosAppContent(resolution.dependencies)
        }
    }
}

@Composable
private fun IosAppContent(deps: IosAppDependencies) {
    AppContent(
        mainViewModel = deps.mainViewModel,
        themeViewModel = deps.themeViewModel,
        eulaViewModel = deps.eulaViewModel,
        exerciseRepository = deps.exerciseRepository,
        syncTriggerManager = deps.syncTriggerManager,
        migrationManager = deps.migrationManager,
    )
}
