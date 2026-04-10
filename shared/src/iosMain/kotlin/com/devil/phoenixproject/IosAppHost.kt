package com.devil.phoenixproject

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
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
)

@Composable
fun IosAppHost() {
    val koin = KoinPlatform.getKoin()
    val depsResult = remember {
        runCatching {
            Logger.i { "iOS AppHost: Resolving app dependencies via Koin" }
            IosAppDependencies(
                mainViewModel = koin.get(),
                themeViewModel = koin.get(),
                eulaViewModel = koin.get(),
                exerciseRepository = koin.get(),
                syncTriggerManager = koin.get(),
            )
        }
    }

    if (depsResult.isFailure) {
        val exception = depsResult.exceptionOrNull()!!
        val error = formatCauseChain(exception)
        Logger.e(exception) { "FATAL iOS app dependency resolution:\n$error" }
        CrashErrorScreen(error)
        return
    }

    val deps = depsResult.getOrThrow()
    AppContent(
        mainViewModel = deps.mainViewModel,
        themeViewModel = deps.themeViewModel,
        eulaViewModel = deps.eulaViewModel,
        exerciseRepository = deps.exerciseRepository,
        syncTriggerManager = deps.syncTriggerManager,
    )
}

private fun formatCauseChain(error: Throwable): String = buildString {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 10) {
        if (depth > 0) append("\n")
        append("[$depth] ${current::class.simpleName}: ${current.message?.take(200)}")
        current = current.cause
        depth++
    }
}
