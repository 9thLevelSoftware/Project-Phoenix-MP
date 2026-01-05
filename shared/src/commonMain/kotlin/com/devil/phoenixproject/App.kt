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
import com.devil.phoenixproject.presentation.screen.SplashScreen
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
    val viewModel = koinViewModel<MainViewModel>()
    val themeViewModel = koinViewModel<ThemeViewModel>()
    val exerciseRepository = koinInject<ExerciseRepository>()
    val syncTriggerManager = koinInject<SyncTriggerManager>()

    // Theme state - persisted via ThemeViewModel
    val themeMode by themeViewModel.themeMode.collectAsState()

    // Splash screen state
    var showSplash by remember { mutableStateOf(true) }

    // Hide splash after animation completes (2500ms for full effect)
    LaunchedEffect(Unit) {
        delay(2500)
        showSplash = false
    }

    // Lifecycle observer for foreground sync
    AppLifecycleObserver(syncTriggerManager)

    VitruvianTheme(themeMode = themeMode) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Main content (always rendered, splash overlays it)
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
