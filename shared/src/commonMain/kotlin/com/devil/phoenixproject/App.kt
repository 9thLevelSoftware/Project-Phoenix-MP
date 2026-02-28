package com.devil.phoenixproject

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.presentation.screen.EnhancedMainScreen
import com.devil.phoenixproject.presentation.screen.EulaScreen
import com.devil.phoenixproject.presentation.screen.SplashScreen
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.ui.theme.VitruvianTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject

/**
 * Observes app lifecycle and triggers sync on foreground.
 */
@Composable
private fun AppLifecycleObserver(syncTriggerManager: SyncTriggerManager) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    syncTriggerManager.onAppForeground()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
fun App() {
    co.touchlab.kermit.Logger.i { "iOS App: App() composable starting..." }

    co.touchlab.kermit.Logger.i { "iOS App: Creating MainViewModel..." }
    val viewModel = koinViewModel<MainViewModel>()
    co.touchlab.kermit.Logger.i { "iOS App: MainViewModel created" }

    co.touchlab.kermit.Logger.i { "iOS App: Creating ThemeViewModel..." }
    val themeViewModel = koinViewModel<ThemeViewModel>()
    co.touchlab.kermit.Logger.i { "iOS App: ThemeViewModel created" }

    co.touchlab.kermit.Logger.i { "iOS App: Creating EulaViewModel..." }
    val eulaViewModel = koinViewModel<EulaViewModel>()
    co.touchlab.kermit.Logger.i { "iOS App: EulaViewModel created" }

    co.touchlab.kermit.Logger.i { "iOS App: Injecting repositories..." }
    val exerciseRepository = koinInject<ExerciseRepository>()
    val syncTriggerManager = koinInject<SyncTriggerManager>()
    co.touchlab.kermit.Logger.i { "iOS App: All dependencies injected successfully" }

    // Theme state - persisted via ThemeViewModel
    val themeMode by themeViewModel.themeMode.collectAsState()

    // Color-blind mode - persisted via SettingsManager through MainViewModel
    val colorBlindModeEnabled by viewModel.colorBlindModeEnabled.collectAsState()

    // EULA acceptance state
    val eulaAccepted by eulaViewModel.eulaAccepted.collectAsState()

    // Splash screen state - only show splash if EULA is already accepted
    var showSplash by remember { mutableStateOf(eulaAccepted) }

    // Hide splash after animation completes (2500ms for full effect)
    // Only run if EULA is accepted
    LaunchedEffect(eulaAccepted) {
        if (eulaAccepted) {
            showSplash = true
            delay(2500)
            showSplash = false
        }
    }

    // Lifecycle observer for foreground sync
    AppLifecycleObserver(syncTriggerManager)

    VitruvianTheme(themeMode = themeMode, colorBlindMode = colorBlindModeEnabled) {
        Box(modifier = Modifier.fillMaxSize()) {
            // EULA acceptance screen - shown first if not accepted
            if (!eulaAccepted) {
                EulaScreen(
                    onAccept = { eulaViewModel.acceptEula() }
                )
            } else {
                // Main content (only rendered after EULA accepted)
                if (!showSplash) {
                    EnhancedMainScreen(
                        viewModel = viewModel,
                        exerciseRepository = exerciseRepository,
                        themeMode = themeMode,
                        onThemeModeChange = { themeViewModel.setThemeMode(it) }
                    )
                }

                // Splash screen overlay with fade animation
                SplashScreen(visible = showSplash)
            }
        }
    }
}
