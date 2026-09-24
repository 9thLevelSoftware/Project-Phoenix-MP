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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.DatabaseMigrationFailureCode
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
import com.devil.phoenixproject.ui.theme.PhoenixTheme
import com.devil.phoenixproject.ui.theme.isDynamicColorAvailable
import com.devil.phoenixproject.util.CrashLog
import com.devil.phoenixproject.util.CrashReportAnswer
import com.devil.phoenixproject.util.deleteDatabaseExportArchive
import com.devil.phoenixproject.util.shareDatabaseFiles
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_retry
import projectphoenix.shared.generated.resources.crash_report_dismiss
import projectphoenix.shared.generated.resources.crash_report_message
import projectphoenix.shared.generated.resources.crash_report_share
import projectphoenix.shared.generated.resources.crash_report_title
import projectphoenix.shared.generated.resources.startup_contact_support
import projectphoenix.shared.generated.resources.startup_diagnostic_code
import projectphoenix.shared.generated.resources.startup_dual_databases_message
import projectphoenix.shared.generated.resources.startup_export_database_files
import projectphoenix.shared.generated.resources.startup_export_failed
import projectphoenix.shared.generated.resources.startup_local_data_preserved_title
import projectphoenix.shared.generated.resources.startup_storage_attention_title
import projectphoenix.shared.generated.resources.startup_storage_failed_message
import projectphoenix.shared.generated.resources.startup_support_code

private const val LAUNCH_SPLASH_DURATION_MS = 2_500L
private const val SUPPORT_EMAIL = "support@phoenix-portal.com"
private val DUAL_DATABASES_DIAGNOSTIC_CODE = "DB_${DatabaseMigrationFailureCode.DUAL_DATABASES.name}"

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

internal enum class StartupFailureAction { EXPORT_DATABASE_FILES, CONTACT_SUPPORT, RETRY }

/**
 * Issue #764: the conflict message tells users to send their database files to support
 * privately, so the support action opens an email to that address (with both codes in the
 * subject) instead of the public issue tracker.
 */
internal fun startupSupportMailtoUri(failure: StartupDependencyResolution.Failed): String {
    val codes = listOfNotNull(failure.diagnosticCode, failure.supportCode).joinToString(" / ")
    val subject = "Project Phoenix startup: $codes"
    return "mailto:$SUPPORT_EMAIL?subject=${encodeMailtoComponent(subject)}"
}

/**
 * Percent-encodes everything outside RFC 3986's unreserved set. That set is ASCII-only, so the
 * check uses explicit ranges: a UTF-8 continuation byte maps to a char in U+FF80..U+FFFF, many of
 * which Char.isLetterOrDigit() accepts.
 */
internal fun encodeMailtoComponent(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val char = byte.toInt().toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") {
            append(char)
        } else {
            append('%')
            append("0123456789ABCDEF"[(byte.toInt() shr 4) and 0x0F])
            append("0123456789ABCDEF"[byte.toInt() and 0x0F])
        }
    }
}

/**
 * Actions offered on the persisted-file startup failure screen. A database conflict stays
 * fail-closed (Phoenix never picks or deletes a copy itself), so the user gets a read-only export
 * of the files and a support route instead of a dead end.
 */
internal fun startupFailureActions(failure: StartupDependencyResolution.Failed): List<StartupFailureAction> = buildList {
    if (failure.diagnosticCode == DUAL_DATABASES_DIAGNOSTIC_CODE) {
        add(StartupFailureAction.EXPORT_DATABASE_FILES)
        add(StartupFailureAction.CONTACT_SUPPORT)
    }
    if (failure.retryAllowed) add(StartupFailureAction.RETRY)
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

@Composable
internal fun PersistedFileStartupFailureScreen(
    failure: StartupDependencyResolution.Failed,
    onRetry: () -> Unit,
) {
    val dualDatabases = failure.diagnosticCode == DUAL_DATABASES_DIAGNOSTIC_CODE
    val actions = startupFailureActions(failure)
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var exporting by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
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
            Text(
                stringResource(
                    if (dualDatabases) {
                        Res.string.startup_storage_attention_title
                    } else {
                        Res.string.startup_local_data_preserved_title
                    },
                ),
                color = Color.White,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    if (dualDatabases) {
                        Res.string.startup_dual_databases_message
                    } else {
                        Res.string.startup_storage_failed_message
                    },
                ),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(Res.string.startup_diagnostic_code, failure.diagnosticCode),
                color = Color(0xFFFF6B6B),
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp),
            )
            failure.supportCode?.let { supportCode ->
                Text(
                    stringResource(Res.string.startup_support_code, supportCode),
                    color = Color(0xFFFFD166),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp),
                )
            }
            if (StartupFailureAction.EXPORT_DATABASE_FILES in actions) {
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !exporting,
                    onClick = {
                        exporting = true
                        exportFailed = false
                        scope.launch {
                            exportFailed = !shareDatabaseFiles()
                            exporting = false
                        }
                    },
                ) {
                    Text(stringResource(Res.string.startup_export_database_files))
                }
                if (exportFailed) {
                    Text(
                        stringResource(Res.string.startup_export_failed),
                        color = Color(0xFFFF6B6B),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            if (StartupFailureAction.CONTACT_SUPPORT in actions) {
                TextButton(onClick = { runCatching { uriHandler.openUri(startupSupportMailtoUri(failure)) } }) {
                    Text(stringResource(Res.string.startup_contact_support))
                }
            }
            if (StartupFailureAction.RETRY in actions) {
                Spacer(Modifier.height(16.dp))
                // A retry may migrate files that changed; never while the export is still reading them.
                Button(enabled = !exporting, onClick = onRetry) {
                    Text(stringResource(Res.string.action_retry))
                }
            }
        }
    }
}

/** Offers the report left by the previous run's crash; see [CrashLog.answer] for when it is deleted. */
@Composable
private fun CrashReportPrompt() {
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.Default) { CrashLog.pending() }
    }
    val pending = report ?: return
    val answer = { choice: CrashReportAnswer ->
        CrashLog.answer(choice, pending)
        report = null
    }
    AlertDialog(
        onDismissRequest = { answer(CrashReportAnswer.DISMISSED) },
        title = { Text(stringResource(Res.string.crash_report_title)) },
        text = { Text(stringResource(Res.string.crash_report_message)) },
        confirmButton = {
            TextButton(onClick = { answer(CrashReportAnswer.SHARE) }) {
                Text(stringResource(Res.string.crash_report_share))
            }
        },
        dismissButton = {
            TextButton(onClick = { answer(CrashReportAnswer.NOT_NOW) }) {
                Text(stringResource(Res.string.crash_report_dismiss))
            }
        },
    )
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
 * What a platform host shows while startup (database open, schema heal, required migrations)
 * is still resolving on a background dispatcher. It needs no resolved dependencies, and it
 * shows the same splash [AppContent] uses, so a slow cold start is never a blank screen.
 * The saved theme isn't readable yet, so it uses the system theme.
 */
@Composable
fun StartupPendingSurface() {
    PhoenixTheme {
        SplashScreen(visible = true)
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

    // Startup succeeded, so a database export made from the failure screen is no longer needed.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { deleteDatabaseExportArchive() }
    }

    AppLifecycleObserver(syncTriggerManager, migrationManager)

    PhoenixTheme(themeMode = themeMode, dynamicColorEnabled = dynamicColorEnabled) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (startupSurface(eulaAccepted, launchSplashCompleted, migrationState)) {
                StartupSurface.EULA -> EulaScreen(onAccept = eulaViewModel::acceptEula)

                StartupSurface.SPLASH -> SplashScreen(visible = true)

                StartupSurface.MIGRATION_RETRY -> MigrationRetryScreen(
                    message = (migrationState as RequiredMigrationState.Failed).message,
                    onRetry = {
                        scope.launch(Dispatchers.IO) { migrationManager.retryRequiredMigrations() }
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
            if (eulaAccepted) CrashReportPrompt()
        }
    }
}
