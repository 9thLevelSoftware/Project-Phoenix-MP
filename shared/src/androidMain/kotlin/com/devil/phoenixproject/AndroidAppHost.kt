package com.devil.phoenixproject

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
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
import org.koin.compose.viewmodel.koinActivityViewModel
import org.koin.mp.KoinPlatform

private data class AndroidAppDependencies(
    val themeViewModel: ThemeViewModel,
    val eulaViewModel: EulaViewModel,
    val exerciseRepository: ExerciseRepository,
    val syncTriggerManager: SyncTriggerManager,
    val migrationManager: MigrationManager,
)

private data class AndroidStartupDependencies(
    val migrationManager: MigrationManager,
)

@Composable
fun AndroidAppHost() {
    var retryAttempt by rememberSaveable { mutableIntStateOf(0) }
    var resolution by remember(retryAttempt) {
        mutableStateOf<StartupDependencyResolution<AndroidAppDependencies>?>(null)
    }
    LaunchedEffect(retryAttempt) {
        resolution = prepareAndroidHostGraph(
            resolveStartupOnly = {
            val koin = KoinPlatform.getKoin()
            koin.get<PersistedFileStartupPrerequisite>()
                AndroidStartupDependencies(migrationManager = koin.get())
            },
            prepareRequired = { startup ->
                startup.migrationManager.runRequiredMigrations()
                startup.migrationManager.awaitRequiredMigrations()
            },
            resolveFeatures = { startup ->
                val koin = KoinPlatform.getKoin()
            AndroidAppDependencies(
                themeViewModel = koin.get(),
                eulaViewModel = koin.get(),
                exerciseRepository = koin.get(),
                syncTriggerManager = koin.get(),
                    migrationManager = startup.migrationManager,
            )
            },
        )
    }

    when (val current = resolution) {
        null -> Unit
        is StartupDependencyResolution.Failed -> {
            Logger.e {
                "Android app dependency resolution blocked: code=${current.diagnosticCode}, " +
                    "support=${current.supportCode ?: "NONE"}, " +
                    "presence=${current.presenceSnapshot?.safeSummary() ?: "UNAVAILABLE"}"
            }
            PersistedFileStartupFailureScreen(current) { retryAttempt++ }
        }

        is StartupDependencyResolution.Ready -> RequireBlePermissions {
            AndroidAppContent(current.dependencies)
        }
    }
}

/** Testable boundary used by the Android host before any feature graph is resolved. */
internal suspend fun <S, T> prepareAndroidHostGraph(
    resolveStartupOnly: () -> S,
    prepareRequired: suspend (S) -> Unit,
    resolveFeatures: (S) -> T,
): StartupDependencyResolution<T> = prepareAppHostDependencies(
    resolveStartupOnly = resolveStartupOnly,
    prepareRequired = prepareRequired,
    resolveFeatures = resolveFeatures,
)

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
