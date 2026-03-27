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
import androidx.compose.runtime.*
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatform

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

/**
 * Crash-safe error screen shown when DI resolution fails.
 * Displays the exception details so the user can report them via TestFlight feedback.
 */
@Composable
private fun CrashErrorScreen(error: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A2E)).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            Text("Project Phoenix", color = Color(0xFFFF6B35), fontSize = 24.sp)
            Spacer(Modifier.height(16.dp))
            Text("Startup Error", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "The app failed to initialize. Please take a screenshot and submit TestFlight feedback.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
            Spacer(Modifier.height(16.dp))
            Text(
                error,
                color = Color(0xFFFF6B6B),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
fun App() {
    co.touchlab.kermit.Logger.i { "iOS App: App() composable starting..." }

    // Resolve dependencies via direct Koin access inside remember{} to enable try-catch.
    // koinViewModel() is @Composable and Compose forbids try-catch around @Composable calls.
    // Direct koin.get<T>() is a regular function. We cache in remember{} for lifecycle.
    // On iOS/Kotlin Native, unhandled exceptions during Compose recomposition propagate
    // through StandaloneCoroutine → propagateExceptionFinalResort → abort().
    // This catch-and-display approach turns SIGABRT into a visible diagnostic screen.
    val koin = KoinPlatform.getKoin()
    val depsResult = remember {
        runCatching {
            co.touchlab.kermit.Logger.i { "iOS App: Resolving dependencies via Koin..." }
            val vm = koin.get<MainViewModel>()
            co.touchlab.kermit.Logger.i { "iOS App: MainViewModel resolved" }
            val themeVm = koin.get<ThemeViewModel>()
            val eulaVm = koin.get<EulaViewModel>()
            val exRepo = koin.get<ExerciseRepository>()
            val syncMgr = koin.get<SyncTriggerManager>()
            co.touchlab.kermit.Logger.i { "iOS App: All dependencies resolved" }
            listOf<Any>(vm, themeVm, eulaVm, exRepo, syncMgr)
        }
    }

    if (depsResult.isFailure) {
        val e = depsResult.exceptionOrNull()!!
        // Traverse full cause chain for complete diagnostics
        val causes = buildString {
            var current: Throwable? = e
            var depth = 0
            while (current != null && depth < 10) {
                if (depth > 0) append("\n")
                append("[$depth] ${current::class.simpleName}: ${current.message?.take(200)}")
                current = current.cause
                depth++
            }
        }
        co.touchlab.kermit.Logger.e(e) { "FATAL DI resolution:\n$causes" }
        CrashErrorScreen(causes)
        return
    }

    val deps = depsResult.getOrThrow()
    val vm = deps[0] as MainViewModel
    val themeVm = deps[1] as ThemeViewModel
    val eulaVm = deps[2] as EulaViewModel
    val exRepo = deps[3] as ExerciseRepository
    val syncMgr = deps[4] as SyncTriggerManager

    // Locale is applied BEFORE composition in platform-specific startup code:
    // - Android: MainActivity.applyStoredLocaleBeforeComposition() in onCreate()
    // - iOS: Applied via NSUserDefaults AppleLanguages before Compose entry point
    // This prevents the first-frame English flicker on non-EN locales.

    // Theme state - persisted via ThemeViewModel
    val themeMode by themeVm.themeMode.collectAsState()

    // EULA acceptance state
    val eulaAccepted by eulaVm.eulaAccepted.collectAsState()

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
    AppLifecycleObserver(syncMgr)

    VitruvianTheme(themeMode = themeMode) {
        Box(modifier = Modifier.fillMaxSize()) {
            // EULA acceptance screen - shown first if not accepted
            if (!eulaAccepted) {
                EulaScreen(
                    onAccept = { eulaVm.acceptEula() }
                )
            } else {
                // Main content (only rendered after EULA accepted)
                if (!showSplash) {
                    EnhancedMainScreen(
                        viewModel = vm,
                        exerciseRepository = exRepo,
                        themeMode = themeMode,
                        onThemeModeChange = { themeVm.setThemeMode(it) }
                    )
                }

                // Splash screen overlay with fade animation
                SplashScreen(visible = showSplash)
            }
        }
    }
}
