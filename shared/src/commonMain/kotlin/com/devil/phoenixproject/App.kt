package com.devil.phoenixproject

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.presentation.screen.EnhancedMainScreen
import com.devil.phoenixproject.presentation.screen.SplashScreen
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.VitruvianTheme
import com.devil.phoenixproject.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import org.koin.compose.KoinContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject

@Composable
fun App() {
    KoinContext {
        val viewModel = koinViewModel<MainViewModel>()
        val exerciseRepository = koinInject<ExerciseRepository>()

        // Theme state - temporarily local, ideally from preferences
        var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }

        // Splash screen state
        var showSplash by remember { mutableStateOf(true) }

        // Hide splash after a short delay (900ms matches parent project)
        LaunchedEffect(Unit) {
            delay(900)
            showSplash = false
        }

        VitruvianTheme(themeMode = themeMode) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Main content (always rendered, splash overlays it)
                if (!showSplash) {
                    EnhancedMainScreen(
                        viewModel = viewModel,
                        exerciseRepository = exerciseRepository,
                        themeMode = themeMode,
                        onThemeModeChange = { themeMode = it }
                    )
                }

                // Splash screen overlay with fade animation
                SplashScreen(visible = showSplash)
            }
        }
    }
}