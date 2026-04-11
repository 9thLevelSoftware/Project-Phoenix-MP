package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.components.*
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.CsvImportResult
import com.devil.phoenixproject.util.CsvImporter
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.util.rememberFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

// Helper function for timestamp formatting
private fun formatTimestamp(timestamp: Long): String = KmpUtils.formatTimestamp(timestamp, "MMM dd, yyyy")

// ProgressTab composable - Deep-dive analytics with Personal Records list
@Composable
fun ProgressTab(
    personalRecords: List<PersonalRecord>,
    workoutSessions: List<WorkoutSession>,
    exerciseRepository: ExerciseRepository,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        item {
            Text(
                stringResource(Res.string.analytics_progress),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.analytics_progress_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Lifetime Stats Card at the top
        item {
            LifetimeStatsCard(
                workoutSessions = workoutSessions,
                exerciseRepository = exerciseRepository,
                weightUnit = weightUnit,
            )
        }

        // Workout Mode Distribution Card
        item {
            WorkoutModeDistributionCard(
                workoutSessions = workoutSessions,
            )
        }

        item {
            Text(
                stringResource(Res.string.analytics_personal_records),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }

        if (personalRecords.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    title = stringResource(Res.string.analytics_no_prs_title),
                    message = stringResource(Res.string.analytics_no_prs_message),
                )
            }
        } else {
            // Group PRs by workout mode for organized display
            // Note: No 'remember' needed here - LazyColumn scope recalculates on recomposition
            // Using toList().sortedBy().toMap() instead of toSortedMap() for Kotlin/Native compatibility
            val prsByMode = personalRecords.groupBy { it.workoutMode }
                .toList()
                .sortedBy { it.first }
                .toMap()

            prsByMode.forEach { (mode, modePRs) ->
                // Mode header
                item(key = "mode_header_$mode") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                    ) {
                        Text(
                            text = mode.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                // PRs for this mode
                items(modePRs, key = { it.id }) { pr ->
                    var exerciseName by remember(pr.exerciseId) { mutableStateOf("Loading...") }

                    LaunchedEffect(pr.exerciseId) {
                        val exercise = exerciseRepository.getExerciseById(pr.exerciseId)
                        exerciseName = exercise?.name ?: "Unknown Exercise"
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        shape = RoundedCornerShape(0.dp), // Flat edges for grouped look
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = exerciseName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = formatTimestamp(pr.timestamp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = formatWeight(pr.weightPerCableKg, weightUnit),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "${pr.reps} Reps",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // Bottom spacer for mode group
                item(key = "mode_spacer_$mode") {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // Bottom padding for FAB
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

/**
 * Analytics screen with three tabs: Dashboard, Progress, and History.
 * - Dashboard: Key insights and analytics overview
 * - Progress: Deep-dive into strength gains and personal records
 * - History: Workout log and session details
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(viewModel: MainViewModel, themeMode: com.devil.phoenixproject.ui.theme.ThemeMode) {
    val workoutHistory by viewModel.workoutHistory.collectAsState()
    val groupedWorkoutHistory by viewModel.groupedWorkoutHistory.collectAsState()
    val allWorkoutSessions by viewModel.allWorkoutSessions.collectAsState()
    val personalRecords by viewModel.allPersonalRecords.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()

    @Suppress("UNUSED_VARIABLE") // Reserved for future connecting overlay
    val isAutoConnecting by viewModel.isAutoConnecting.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()

    // Responsive sizing based on window size class
    val windowSizeClass = LocalWindowSizeClass.current
    val tabIndicatorHeight = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 12.dp
        WindowWidthSizeClass.Medium -> 10.dp
        WindowWidthSizeClass.Compact -> 8.dp
    }
    val fabIconSize = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 36.dp
        WindowWidthSizeClass.Medium -> 32.dp
        WindowWidthSizeClass.Compact -> 28.dp
    }

    // Set global title
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("Analytics")
    }

    // Pager state for swipe gestures
    val pagerState = rememberPagerState(pageCount = { 3 })
    var showExportMenu by remember { mutableStateOf(false) }
    var showDateRangePicker by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    // CsvExporter and CsvImporter from DI
    val csvExporter: CsvExporter = koinInject()
    val csvImporter: CsvImporter = koinInject()
    val userProfileRepository: UserProfileRepository = koinInject()
    val activeProfile by userProfileRepository.activeProfile.collectAsState()
    val activeProfileId = activeProfile?.id ?: "default"
    val scope = rememberCoroutineScope()

    // Import state
    var launchCsvFilePicker by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<CsvImportResult?>(null) }
    var showImportResultDialog by remember { mutableStateOf(false) }

    // Build exercise names map for export
    val exerciseNames = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(personalRecords, allWorkoutSessions) {
        val allExerciseIds = personalRecords.map { it.exerciseId } +
            allWorkoutSessions.mapNotNull { it.exerciseId }
        allExerciseIds.distinct().forEach { exerciseId ->
            if (!exerciseNames.containsKey(exerciseId)) {
                val exercise = viewModel.exerciseRepository.getExerciseById(exerciseId)
                exerciseNames[exerciseId] = exercise?.name ?: "Unknown Exercise"
            }
        }
    }

    // Sync pager with tab selection
    LaunchedEffect(pagerState.currentPage) {
        // Update occurs when user swipes
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            // Tab Row - Redesigned: Dashboard, Progress, History
            PrimaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                indicator = {
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier
                            .tabIndicatorOffset(pagerState.currentPage)
                            .height(tabIndicatorHeight),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = {
                        Text(
                            stringResource(Res.string.analytics_dashboard),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = if (pagerState.currentPage == 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    icon = {
                        Icon(
                            Icons.Default.Dashboard,
                            contentDescription = stringResource(Res.string.cd_dashboard),
                            tint = if (pagerState.currentPage == 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = {
                        Text(
                            stringResource(Res.string.analytics_progress),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = if (pagerState.currentPage == 1) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = stringResource(Res.string.cd_progress_tracking),
                            tint = if (pagerState.currentPage == 1) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    text = {
                        Text(
                            stringResource(Res.string.analytics_history),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = if (pagerState.currentPage == 2) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(Res.string.cd_workout_history),
                            tint = if (pagerState.currentPage == 2) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
            }

            // Tab Content with Swipe Support
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> InsightsTab(
                        prs = personalRecords,
                        workoutSessions = workoutHistory,
                        exerciseRepository = viewModel.exerciseRepository,
                        weightUnit = weightUnit,
                        formatWeight = viewModel::formatWeight,
                        modifier = Modifier.fillMaxSize(),
                    )

                    1 -> ProgressTab(
                        personalRecords = personalRecords,
                        workoutSessions = allWorkoutSessions,
                        exerciseRepository = viewModel.exerciseRepository,
                        weightUnit = weightUnit,
                        formatWeight = viewModel::formatWeight,
                        modifier = Modifier.fillMaxSize(),
                    )

                    2 -> HistoryTab(
                        groupedWorkoutHistory = groupedWorkoutHistory,
                        weightUnit = weightUnit,
                        formatWeight = viewModel::formatWeight,
                        kgToDisplay = viewModel::kgToDisplay,
                        onDeleteWorkout = { viewModel.deleteWorkout(it) },
                        exerciseRepository = viewModel.exerciseRepository,
                        onRefresh = { /* Workout history refreshes automatically via StateFlow */ },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Connection error dialog (ConnectingOverlay removed - status shown in top bar button)
        connectionError?.let { error ->
            com.devil.phoenixproject.presentation.components.ConnectionErrorDialog(
                message = error,
                onDismiss = { viewModel.clearConnectionError() },
            )
        }

        // Export FAB - Material 3 Expressive
        // TODO: Export functionality needs platform-specific context implementation
        FloatingActionButton(
            onClick = { showExportMenu = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.large),
            containerColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(28.dp), // Material 3 Expressive: Very rounded FAB
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 8.dp, // Material 3 Expressive: Higher elevation
                pressedElevation = 4.dp,
            ),
        ) {
            Icon(
                Icons.Default.Share,
                contentDescription = stringResource(Res.string.cd_export_data),
                modifier = Modifier.size(fabIconSize), // Material 3 Expressive: Responsive icon size
            )
        }
    }

    // Hoist format templates for use in coroutine callbacks (not composable scope)
    // Use __PLACEHOLDER__ as a marker to replace at runtime since stringResource is composable-only
    val exportSuccessTemplate = stringResource(Res.string.export_success, "__PLACEHOLDER__")
    val exportFailedTemplate = stringResource(Res.string.export_failed, "__PLACEHOLDER__")

    // Export / Import options dialog
    if (showExportMenu) {
        AlertDialog(
            onDismissRequest = { if (!isExporting && !isImporting) showExportMenu = false },
            title = { Text(stringResource(Res.string.export_import_data)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.choose_export), style = MaterialTheme.typography.bodyMedium)

                    // Export Personal Records
                    OutlinedButton(
                        onClick = {
                            isExporting = true
                            scope.launch(Dispatchers.Default) {
                                val result = csvExporter.exportPersonalRecords(
                                    personalRecords = personalRecords,
                                    exerciseNames = exerciseNames.toMap(),
                                    weightUnit = weightUnit,
                                    formatWeight = viewModel::formatWeight,
                                )
                                isExporting = false
                                result.fold(
                                    onSuccess = { path ->
                                        exportMessage = exportSuccessTemplate.replace("__PLACEHOLDER__", path)
                                        csvExporter.shareCSV(path, "personal_records.csv")
                                    },
                                    onFailure = { error ->
                                        exportMessage = exportFailedTemplate.replace("__PLACEHOLDER__", error.message ?: "")
                                    },
                                )
                                showExportMenu = false
                            }
                        },
                        enabled = !isExporting && personalRecords.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.EmojiEvents, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.personal_records_count, personalRecords.size))
                    }

                    // Export Workout History - Opens date range picker
                    OutlinedButton(
                        onClick = {
                            showExportMenu = false
                            showDateRangePicker = true
                        },
                        enabled = !isExporting && allWorkoutSessions.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.workout_history_count, allWorkoutSessions.size))
                    }

                    // Export PR Progression
                    OutlinedButton(
                        onClick = {
                            isExporting = true
                            scope.launch(Dispatchers.Default) {
                                val result = csvExporter.exportPRProgression(
                                    personalRecords = personalRecords,
                                    exerciseNames = exerciseNames.toMap(),
                                    weightUnit = weightUnit,
                                    formatWeight = viewModel::formatWeight,
                                )
                                isExporting = false
                                result.fold(
                                    onSuccess = { path ->
                                        exportMessage = exportSuccessTemplate.replace("__PLACEHOLDER__", path)
                                        csvExporter.shareCSV(path, "pr_progression.csv")
                                    },
                                    onFailure = { error ->
                                        exportMessage = exportFailedTemplate.replace("__PLACEHOLDER__", error.message ?: "")
                                    },
                                )
                                showExportMenu = false
                            }
                        },
                        enabled = !isExporting && personalRecords.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.pr_progression))
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.small))

                    Text(stringResource(Res.string.import_label), style = MaterialTheme.typography.bodyMedium)

                    // Import Workout History from CSV
                    OutlinedButton(
                        onClick = {
                            showExportMenu = false
                            launchCsvFilePicker = true
                        },
                        enabled = !isExporting && !isImporting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.import_workout_csv))
                    }

                    if (isExporting || isImporting) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showExportMenu = false },
                    enabled = !isExporting && !isImporting,
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(
                        stringResource(Res.string.action_cancel),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(28.dp),
        )
    }

    // Date range picker for workout history export
    if (showDateRangePicker) {
        DateRangePickerDialog(
            totalRecords = allWorkoutSessions.size,
            onDateRangeSelected = { startDate, endDate ->
                showDateRangePicker = false
                isExporting = true
                scope.launch(Dispatchers.Default) {
                    val result = csvExporter.exportWorkoutHistoryFiltered(
                        workoutSessions = allWorkoutSessions,
                        startDate = startDate,
                        endDate = endDate,
                        exerciseNames = exerciseNames.toMap(),
                        weightUnit = weightUnit,
                        formatWeight = viewModel::formatWeight,
                    )
                    isExporting = false
                    result.fold(
                        onSuccess = { path ->
                            exportMessage = exportSuccessTemplate.replace("__PLACEHOLDER__", path)
                            csvExporter.shareCSV(path, "workout_history.csv")
                        },
                        onFailure = { error ->
                            exportMessage = exportFailedTemplate.replace("__PLACEHOLDER__", error.message ?: "")
                        },
                    )
                }
            },
            onDismiss = { showDateRangePicker = false },
            filterRecordCount = { startDate, endDate ->
                allWorkoutSessions.count { session ->
                    val afterStart = startDate == null || session.timestamp >= startDate
                    val beforeEnd = endDate == null || session.timestamp <= endDate
                    afterStart && beforeEnd
                }
            },
        )
    }

    // Export result snackbar
    exportMessage?.let { message ->
        Snackbar(
            modifier = Modifier
                .padding(16.dp),
            action = {
                TextButton(onClick = { exportMessage = null }) {
                    Text(stringResource(Res.string.action_dismiss))
                }
            },
        ) {
            Text(message)
        }
    }

    // CSV file picker for import
    if (launchCsvFilePicker) {
        val filePicker = rememberFilePicker()
        filePicker.LaunchCsvFilePicker { selectedFile ->
            launchCsvFilePicker = false
            if (selectedFile != null) {
                isImporting = true
                scope.launch(Dispatchers.Default) {
                    try {
                        val result = csvImporter.importFromCsv(selectedFile, activeProfileId)
                        importResult = result
                        showImportResultDialog = true
                    } catch (e: Exception) {
                        importResult = CsvImportResult(
                            imported = 0,
                            skipped = 0,
                            failed = 0,
                            errors = listOf("Import failed: ${e.message}"),
                        )
                        showImportResultDialog = true
                    } finally {
                        isImporting = false
                    }
                }
            }
        }
    }

    // Import result dialog
    if (showImportResultDialog) {
        val result = importResult
        AlertDialog(
            onDismissRequest = { showImportResultDialog = false },
            title = {
                Text(
                    if (result != null && result.hasImports) {
                        stringResource(Res.string.import_complete_title)
                    } else {
                        stringResource(Res.string.import_result_title)
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    if (result != null) {
                        Text(result.summary(), style = MaterialTheme.typography.bodyLarge)

                        if (result.errors.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(Spacing.small))
                            Text(
                                stringResource(Res.string.import_issues),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            result.errors.take(5).forEach { error ->
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (result.errors.size > 5) {
                                Text(
                                    stringResource(Res.string.import_and_more, result.errors.size - 5),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Text(stringResource(Res.string.no_result_available))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImportResultDialog = false }) {
                    Text(stringResource(Res.string.action_ok))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(28.dp),
        )
    }
}
