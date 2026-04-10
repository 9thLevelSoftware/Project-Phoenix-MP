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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.presentation.screen.EnhancedMainScreen
import com.devil.phoenixproject.presentation.screen.EulaScreen
import com.devil.phoenixproject.presentation.screen.SplashScreen
import com.devil.phoenixproject.presentation.util.TestTags
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.ui.theme.VitruvianTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

private data class AppDependencies(
    val mainViewModel: MainViewModel,
    val themeViewModel: ThemeViewModel,
    val eulaViewModel: EulaViewModel,
    val exerciseRepository: ExerciseRepository,
    val syncTriggerManager: SyncTriggerManager,
)

@Composable
fun App(mainViewModel: MainViewModel? = null) {
    co.touchlab.kermit.Logger.i { "iOS App: App() composable starting..." }

    // Resolve dependencies via direct Koin access inside remember{} to enable try-catch.
    // koinViewModel() is @Composable and Compose forbids try-catch around @Composable calls.
    // Direct koin.get<T>() is a regular function. We cache in remember{} for lifecycle.
    // On iOS/Kotlin Native, unhandled exceptions during Compose recomposition propagate
    // through StandaloneCoroutine → propagateExceptionFinalResort → abort().
    // This catch-and-display approach turns SIGABRT into a visible diagnostic screen.
    val koin = KoinPlatform.getKoin()
    val depsResult = remember(mainViewModel) {
        runCatching {
            co.touchlab.kermit.Logger.i { "iOS App: Resolving dependencies via Koin..." }
            val resolvedMainViewModel = mainViewModel ?: koin.get<MainViewModel>()
            co.touchlab.kermit.Logger.i { "iOS App: MainViewModel resolved" }
            val themeVm = koin.get<ThemeViewModel>()
            val eulaVm = koin.get<EulaViewModel>()
            val exRepo = koin.get<ExerciseRepository>()
            val syncMgr = koin.get<SyncTriggerManager>()
            co.touchlab.kermit.Logger.i { "iOS App: All dependencies resolved" }
            AppDependencies(
                mainViewModel = resolvedMainViewModel,
                themeViewModel = themeVm,
                eulaViewModel = eulaVm,
                exerciseRepository = exRepo,
                syncTriggerManager = syncMgr,
            )
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
    val vm = deps.mainViewModel
    val themeVm = deps.themeViewModel
    val eulaVm = deps.eulaViewModel
    val exRepo = deps.exerciseRepository
    val syncMgr = deps.syncTriggerManager

    // Locale is applied BEFORE composition in platform-specific startup code:
    // - Android: MainActivity.applyStoredLocaleBeforeComposition() in onCreate()
    // - iOS: Applied via NSUserDefaults AppleLanguages before Compose entry point
    // This prevents the first-frame English flicker on non-EN locales.

    // Theme state - persisted via ThemeViewModel
    val themeMode by themeVm.themeMode.collectAsState()

    // EULA acceptance state
    val eulaAccepted by eulaVm.eulaAccepted.collectAsState()

    val navController = rememberNavController()

    // Splash screen state - play once after EULA acceptance and restore across config changes.
    var hasPlayedSplash by rememberSaveable { mutableStateOf(false) }
    var showSplash by rememberSaveable { mutableStateOf(eulaAccepted) }

    LaunchedEffect(eulaAccepted, hasPlayedSplash) {
        if (!eulaAccepted) {
            hasPlayedSplash = false
            showSplash = false
            return@LaunchedEffect
        }

        if (!hasPlayedSplash) {
            showSplash = true
            delay(2500)
            showSplash = false
            hasPlayedSplash = true
        }
    }

    // Lifecycle observer for foreground sync
    AppLifecycleObserver(syncMgr)

    VitruvianTheme(themeMode = themeMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(TestTags.APP_ROOT),
        ) {
            // EULA acceptance screen - shown first if not accepted
            if (!eulaAccepted) {
                EulaScreen(
                    onAccept = { eulaVm.acceptEula() },
                    modifier = Modifier.testTag(TestTags.SCREEN_EULA),
                )
            } else {
                if (showSplash) {
                    SplashScreen(
                        visible = true,
                        modifier = Modifier.testTag(TestTags.APP_SPLASH),
                    )
                } else {
                    // Main content (only rendered after EULA accepted)
                    Box(modifier = Modifier.fillMaxSize().testTag(TestTags.APP_MAIN_SHELL)) {
                        EnhancedMainScreen(
                            viewModel = vm,
                            exerciseRepository = exRepo,
                            themeMode = themeMode,
                            onThemeModeChange = { themeVm.setThemeMode(it) },
                            navController = navController,
                        )
                    }
                }
            }
        }
    }
}
