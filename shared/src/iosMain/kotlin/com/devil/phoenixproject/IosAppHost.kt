package com.devil.phoenixproject

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.Dispatchers
import org.koin.mp.KoinPlatform

private data class IosAppDependencies(
    val mainViewModel: MainViewModel,
    val themeViewModel: ThemeViewModel,
    val eulaViewModel: EulaViewModel,
    val exerciseRepository: ExerciseRepository,
    val syncTriggerManager: SyncTriggerManager,
    val migrationManager: MigrationManager,
)

private data class IosStartupDependencies(
    val migrationManager: MigrationManager,
)

@Composable
fun IosAppHost() {
    var retryAttempt by rememberSaveable { mutableIntStateOf(0) }
    var resolution by remember(retryAttempt) {
        mutableStateOf<StartupDependencyResolution<IosAppDependencies>?>(null)
    }
    LaunchedEffect(retryAttempt) {
        resolution = prepareAppHostDependencies(
            resolveStartupOnly = {
                val koin = KoinPlatform.getKoin()
                Logger.i { "iOS AppHost: Resolving startup dependencies via Koin" }
                koin.get<PersistedFileStartupPrerequisite>()
                IosStartupDependencies(migrationManager = koin.get())
            },
            prepareRequired = { startup ->
                startup.migrationManager.runRequiredMigrations()
                startup.migrationManager.awaitRequiredMigrations()
            },
            resolveFeatures = { startup ->
                val koin = KoinPlatform.getKoin()
                IosAppDependencies(
                    mainViewModel = koin.get(),
                    themeViewModel = koin.get(),
                    eulaViewModel = koin.get(),
                    exerciseRepository = koin.get(),
                    syncTriggerManager = koin.get(),
                    migrationManager = startup.migrationManager,
                )
            },
            // Opening the database (schema heal) and the required migrations are
            // blocking work; keep them off the main thread while the splash draws.
            blockingDispatcher = Dispatchers.Default,
        )
    }

    when (val current = resolution) {
        // Startup resolves off the main thread; draw the splash instead of a blank frame meanwhile.
        null -> StartupPendingSurface()
        is StartupDependencyResolution.Failed -> {
            Logger.e {
                "iOS app dependency resolution blocked: code=${current.diagnosticCode}, " +
                    "support=${current.supportCode ?: "NONE"}, " +
                    "presence=${current.presenceSnapshot?.safeSummary() ?: "UNAVAILABLE"}"
            }
            PersistedFileStartupFailureScreen(current) { retryAttempt++ }
        }

        is StartupDependencyResolution.Ready -> RequireBlePermissions {
            IosAppContent(current.dependencies)
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
