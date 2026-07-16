package com.devil.phoenixproject

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.migration.MigrationManager
import com.devil.phoenixproject.data.migration.RequiredMigrationState
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.presentation.screen.EnhancedMainScreen
import com.devil.phoenixproject.presentation.screen.EulaScreen
import com.devil.phoenixproject.presentation.screen.SplashScreen
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.ui.theme.VitruvianTheme
import com.devil.phoenixproject.ui.theme.isDynamicColorAvailable
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_retry

private const val LAUNCH_SPLASH_DURATION_MS = 2_500L

internal enum class StartupSurface { EULA, SPLASH, MIGRATION_RETRY, MAIN }

internal fun startupSurface(
    eulaAccepted: Boolean,
    splashCompleted: Boolean,
    migrationState: RequiredMigrationState,
): StartupSurface = when {
    !eulaAccepted -> StartupSurface.EULA
    migrationState is RequiredMigrationState.Failed -> StartupSurface.MIGRATION_RETRY
    migrationState != RequiredMigrationState.Ready || !splashCompleted -> StartupSurface.SPLASH
    else -> StartupSurface.MAIN
}

/**
 * Observes app lifecycle and triggers sync on foreground.
 */
@Composable
private fun AppLifecycleObserver(
    syncTriggerManager: SyncTriggerManager,
    migrationManager: MigrationManager,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner, syncTriggerManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    try {
                        migrationManager.awaitRequiredMigrations()
                        syncTriggerManager.onAppForeground()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Logger.e(e) { "AppLifecycleObserver: onAppForeground failed" }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

/**
 * Crash-safe error screen shown when DI resolution fails.
 * Displays the exception details so the user can report them.
 */
@Composable
fun CrashErrorScreen(error: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A2E)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text("Project Phoenix", color = Color(0xFFFF6B35), fontSize = 24.sp)
            Spacer(Modifier.height(16.dp))
            Text("Startup Error", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "The app failed to initialize. Please capture this screen for support.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                error,
                color = Color(0xFFFF6B6B),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun MigrationRetryScreen(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(stringResource(Res.string.action_retry))
            }
        }
    }
}

/**
 * Shared app content. Platform hosts own DI/lifecycle scoping and pass
 * retained dependencies into this pure UI entry point.
 */
@Composable
fun AppContent(
    mainViewModel: MainViewModel,
    themeViewModel: ThemeViewModel,
    eulaViewModel: EulaViewModel,
    exerciseRepository: ExerciseRepository,
    syncTriggerManager: SyncTriggerManager,
    migrationManager: MigrationManager,
) {
    val themeMode by themeViewModel.themeMode.collectAsState()
    val dynamicColorEnabled by themeViewModel.dynamicColorEnabled.collectAsState()
    val eulaAccepted by eulaViewModel.eulaAccepted.collectAsState()
    val migrationState by migrationManager.requiredMigrationState.collectAsState()
    val dynamicColorAvailable = isDynamicColorAvailable()
    val scope = rememberCoroutineScope()

    var launchSplashCompleted by rememberSaveable { mutableStateOf(false) }
    var launchSplashStartedAtMillis by rememberSaveable { mutableLongStateOf(0L) }

    LaunchedEffect(eulaAccepted, launchSplashCompleted, launchSplashStartedAtMillis) {
        if (!eulaAccepted) {
            launchSplashCompleted = false
            launchSplashStartedAtMillis = 0L
            return@LaunchedEffect
        }

        if (launchSplashCompleted) {
            return@LaunchedEffect
        }

        val now = Clock.System.now().toEpochMilliseconds()
        if (launchSplashStartedAtMillis == 0L) {
            launchSplashStartedAtMillis = now
        }

        val elapsed = (now - launchSplashStartedAtMillis).coerceAtLeast(0L)
        val remaining = (LAUNCH_SPLASH_DURATION_MS - elapsed).coerceAtLeast(0L)

        delay(remaining)
        launchSplashCompleted = true
        launchSplashStartedAtMillis = 0L
    }

    LaunchedEffect(migrationManager) {
        migrationManager.runRequiredMigrations()
    }

    AppLifecycleObserver(syncTriggerManager, migrationManager)

    VitruvianTheme(themeMode = themeMode, dynamicColorEnabled = dynamicColorEnabled) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (startupSurface(eulaAccepted, launchSplashCompleted, migrationState)) {
                StartupSurface.EULA -> EulaScreen(onAccept = eulaViewModel::acceptEula)
                StartupSurface.SPLASH -> SplashScreen(visible = true)
                StartupSurface.MIGRATION_RETRY -> MigrationRetryScreen(
                    message = (migrationState as RequiredMigrationState.Failed).message,
                    onRetry = {
                        scope.launch { migrationManager.retryRequiredMigrations() }
                    },
                )
                StartupSurface.MAIN -> EnhancedMainScreen(
                    viewModel = mainViewModel,
                    exerciseRepository = exerciseRepository,
                    themeMode = themeMode,
                    onThemeModeChange = themeViewModel::setThemeMode,
                    dynamicColorAvailable = dynamicColorAvailable,
                    dynamicColorEnabled = dynamicColorEnabled,
                    onDynamicColorEnabledChange = themeViewModel::setDynamicColorEnabled,
                )
            }
        }
    }
}
