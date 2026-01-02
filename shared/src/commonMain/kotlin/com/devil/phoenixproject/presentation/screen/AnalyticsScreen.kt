package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.presentation.components.*
import com.devil.phoenixproject.util.CsvExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.PersonalRecord
import androidx.compose.foundation.lazy.items

// Helper function for timestamp formatting
private fun formatTimestamp(timestamp: Long): String {
    return KmpUtils.formatTimestamp(timestamp, "MMM dd, yyyy")
}

// ProgressTab composable - Deep-dive analytics with Personal Records list
@Composable
fun ProgressTab(
    personalRecords: List<PersonalRecord>,
    workoutSessions: List<WorkoutSession>,
    exerciseRepository: ExerciseRepository,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium)
    ) {
        item {
            Text(
                "Progress",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Track your strength gains over time",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Lifetime Stats Card at the top
        item {
            LifetimeStatsCard(
                workoutSessions = workoutSessions,
                exerciseRepository = exerciseRepository,
                weightUnit = weightUnit
            )
        }

        // Workout Mode Distribution Card
        item {
            WorkoutModeDistributionCard(
                workoutSessions = workoutSessions
            )
        }

        item {
            Text(
                "Personal Records",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        }

        if (personalRecords.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    title = "No PRs Yet",
                    message = "Complete workouts to set personal records!"
                )
            }
        } else {
            items(personalRecords, key = { it.id }) { pr ->
                var exerciseName by remember(pr.exerciseId) { mutableStateOf("Loading...") }

                LaunchedEffect(pr.exerciseId) {
                    val exercise = exerciseRepository.getExerciseById(pr.exerciseId)
                    exerciseName = exercise?.name ?: "Unknown Exercise"
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = exerciseName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = formatTimestamp(pr.timestamp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = formatWeight(pr.weightPerCableKg, weightUnit),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${pr.reps} Reps • ${pr.workoutMode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
fun AnalyticsScreen(
    viewModel: MainViewModel,
    themeMode: com.devil.phoenixproject.ui.theme.ThemeMode
) {
    val workoutHistory by viewModel.workoutHistory.collectAsState()
    val groupedWorkoutHistory by viewModel.groupedWorkoutHistory.collectAsState()
    val allWorkoutSessions by viewModel.allWorkoutSessions.collectAsState()
    val personalRecords by viewModel.allPersonalRecords.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    @Suppress("UNUSED_VARIABLE") // Reserved for future connecting overlay
    val isAutoConnecting by viewModel.isAutoConnecting.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()

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

    // CsvExporter from DI
    val csvExporter: CsvExporter = koinInject()
    val scope = rememberCoroutineScope()

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
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = {
                        Text(
                            "Dashboard",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = if (pagerState.currentPage == 0)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    icon = {
                        Icon(
                            Icons.Default.Dashboard,
                            contentDescription = "Dashboard overview",
                            tint = if (pagerState.currentPage == 0)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = {
                        Text(
                            "Progress",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = if (pagerState.currentPage == 1)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = "Progress tracking",
                            tint = if (pagerState.currentPage == 1)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    text = {
                        Text(
                            "History",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = if (pagerState.currentPage == 2)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = "Workout history",
                            tint = if (pagerState.currentPage == 2)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            // Tab Content with Swipe Support
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> InsightsTab(
                        prs = personalRecords,
                        workoutSessions = workoutHistory,
                        exerciseRepository = viewModel.exerciseRepository,
                        weightUnit = weightUnit,
                        formatWeight = viewModel::formatWeight,
                        modifier = Modifier.fillMaxSize()
                    )
                    1 -> ProgressTab(
                        personalRecords = personalRecords,
                        workoutSessions = allWorkoutSessions,
                        exerciseRepository = viewModel.exerciseRepository,
                        weightUnit = weightUnit,
                        formatWeight = viewModel::formatWeight,
                        modifier = Modifier.fillMaxSize()
                    )
                    2 -> HistoryTab(
                        groupedWorkoutHistory = groupedWorkoutHistory,
                        weightUnit = weightUnit,
                        formatWeight = viewModel::formatWeight,
                        kgToDisplay = viewModel::kgToDisplay,
                        onDeleteWorkout = { viewModel.deleteWorkout(it) },
                        exerciseRepository = viewModel.exerciseRepository,
                        onRefresh = { /* Workout history refreshes automatically via StateFlow */ },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Connection error dialog (ConnectingOverlay removed - status shown in top bar button)
        connectionError?.let { error ->
            com.devil.phoenixproject.presentation.components.ConnectionErrorDialog(
                message = error,
                onDismiss = { viewModel.clearConnectionError() }
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
                pressedElevation = 4.dp
            )
        ) {
            Icon(
                Icons.Default.Share,
                contentDescription = "Export data",
                modifier = Modifier.size(28.dp) // Material 3 Expressive: Larger icon (was default)
            )
        }
    }

    // Export options dialog
    if (showExportMenu) {
        AlertDialog(
            onDismissRequest = { if (!isExporting) showExportMenu = false },
            title = { Text("Export Data") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose what to export:", style = MaterialTheme.typography.bodyMedium)

                    // Export Personal Records
                    OutlinedButton(
                        onClick = {
                            isExporting = true
                            scope.launch(Dispatchers.Default) {
                                val result = csvExporter.exportPersonalRecords(
                                    personalRecords = personalRecords,
                                    exerciseNames = exerciseNames.toMap(),
                                    weightUnit = weightUnit,
                                    formatWeight = viewModel::formatWeight
                                )
                                isExporting = false
                                result.fold(
                                    onSuccess = { path ->
                                        exportMessage = "Exported to: $path"
                                        csvExporter.shareCSV(path, "personal_records.csv")
                                    },
                                    onFailure = { error ->
                                        exportMessage = "Export failed: ${error.message}"
                                    }
                                )
                                showExportMenu = false
                            }
                        },
                        enabled = !isExporting && personalRecords.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.EmojiEvents, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Personal Records (${personalRecords.size})")
                    }

                    // Export Workout History - Opens date range picker
                    OutlinedButton(
                        onClick = {
                            showExportMenu = false
                            showDateRangePicker = true
                        },
                        enabled = !isExporting && allWorkoutSessions.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Workout History (${allWorkoutSessions.size})")
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
                                    formatWeight = viewModel::formatWeight
                                )
                                isExporting = false
                                result.fold(
                                    onSuccess = { path ->
                                        exportMessage = "Exported to: $path"
                                        csvExporter.shareCSV(path, "pr_progression.csv")
                                    },
                                    onFailure = { error ->
                                        exportMessage = "Export failed: ${error.message}"
                                    }
                                )
                                showExportMenu = false
                            }
                        },
                        enabled = !isExporting && personalRecords.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("PR Progression")
                    }

                    if (isExporting) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showExportMenu = false },
                    enabled = !isExporting,
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(28.dp)
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
                        formatWeight = viewModel::formatWeight
                    )
                    isExporting = false
                    result.fold(
                        onSuccess = { path ->
                            exportMessage = "Exported to: $path"
                            csvExporter.shareCSV(path, "workout_history.csv")
                        },
                        onFailure = { error ->
                            exportMessage = "Export failed: ${error.message}"
                        }
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
            }
        )
    }

    // Export result snackbar
    exportMessage?.let { message ->
        Snackbar(
            modifier = Modifier
                .padding(16.dp),
            action = {
                TextButton(onClick = { exportMessage = null }) {
                    Text("Dismiss")
                }
            }
        ) {
            Text(message)
        }
    }
}
