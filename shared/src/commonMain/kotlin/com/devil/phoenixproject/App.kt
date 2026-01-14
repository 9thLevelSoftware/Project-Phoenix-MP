package com.devil.phoenixproject

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.presentation.screen.EnhancedMainScreen
import com.devil.phoenixproject.presentation.screen.EulaScreen
import com.devil.phoenixproject.presentation.screen.SplashScreen
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.ui.theme.VitruvianTheme
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject

@Composable
fun App() {
    val viewModel = koinViewModel<MainViewModel>()
    val themeViewModel = koinViewModel<ThemeViewModel>()
    val eulaViewModel = koinViewModel<EulaViewModel>()
    val exerciseRepository = koinInject<ExerciseRepository>()

    // Theme state - persisted via ThemeViewModel
    val themeMode by themeViewModel.themeMode.collectAsState()

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

    VitruvianTheme(themeMode = themeMode) {
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