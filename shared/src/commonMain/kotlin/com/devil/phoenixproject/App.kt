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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.devil.phoenixproject.util.KmpUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LAUNCH_SPLASH_DURATION_MS = 2500L

@Composable
private fun AppLifecycleObserver(syncTriggerManager: SyncTriggerManager) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner, syncTriggerManager) {
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
internal fun CrashErrorScreen(error: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .padding(24.dp),
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
                "The app failed to initialize. Please take a screenshot and submit TestFlight feedback.",
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
fun AppContent(
    mainViewModel: MainViewModel,
    themeViewModel: ThemeViewModel,
    eulaViewModel: EulaViewModel,
    exerciseRepository: ExerciseRepository,
    syncTriggerManager: SyncTriggerManager,
) {
    val themeMode by themeViewModel.themeMode.collectAsState()
    val eulaAccepted by eulaViewModel.eulaAccepted.collectAsState()

    var launchSplashCompleted by rememberSaveable { mutableStateOf(false) }
    var launchSplashStartedAtMillis by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(eulaAccepted) {
        if (!eulaAccepted) {
            launchSplashCompleted = false
            launchSplashStartedAtMillis = null
            return@LaunchedEffect
        }

        if (launchSplashCompleted) return@LaunchedEffect

        val startedAt = launchSplashStartedAtMillis ?: KmpUtils.currentTimeMillis().also {
            launchSplashStartedAtMillis = it
        }
        val elapsed = (KmpUtils.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        val remaining = (LAUNCH_SPLASH_DURATION_MS - elapsed).coerceAtLeast(0L)

        if (remaining > 0L) {
            delay(remaining)
        }

        launchSplashCompleted = true
        launchSplashStartedAtMillis = null
    }

    val showLaunchSplash = eulaAccepted && !launchSplashCompleted

    AppLifecycleObserver(syncTriggerManager)

    VitruvianTheme(themeMode = themeMode) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!eulaAccepted) {
                EulaScreen(
                    onAccept = { eulaViewModel.acceptEula() },
                )
            } else {
                if (!showLaunchSplash) {
                    EnhancedMainScreen(
                        viewModel = mainViewModel,
                        exerciseRepository = exerciseRepository,
                        themeMode = themeMode,
                        onThemeModeChange = themeViewModel::setThemeMode,
                    )
                }

                SplashScreen(visible = showLaunchSplash)
            }
        }
    }
}
