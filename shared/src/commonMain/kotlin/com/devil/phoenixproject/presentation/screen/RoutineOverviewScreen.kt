package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.usecase.RoutineTimeEstimate
import com.devil.phoenixproject.domain.usecase.RoutineTimeEstimator
import com.devil.phoenixproject.presentation.components.BackHandler
import com.devil.phoenixproject.presentation.components.SliderWithButtons
import com.devil.phoenixproject.presentation.components.VideoPlayer
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.TestTags
import com.devil.phoenixproject.presentation.util.WindowHeightSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.Constants
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_cancel
import vitruvianprojectphoenix.shared.generated.resources.action_exit
import vitruvianprojectphoenix.shared.generated.resources.action_stop
import vitruvianprojectphoenix.shared.generated.resources.exit_routine_message
import vitruvianprojectphoenix.shared.generated.resources.exit_routine_title
import vitruvianprojectphoenix.shared.generated.resources.start_exercise
import vitruvianprojectphoenix.shared.generated.resources.target_reps

/**
 * Routine Overview Screen - Entry point when starting a routine.
 * Shows a horizontal carousel of exercises with the ability to browse
 * and select where to begin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineOverviewScreen(navController: NavController, viewModel: MainViewModel, exerciseRepository: ExerciseRepository) {
    val routineFlowState by viewModel.routineFlowState.collectAsState()
    val completedExercises by viewModel.completedExercises.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val enableVideoPlayback by viewModel.enableVideoPlayback.collectAsState()
    val userPreferences by viewModel.userPreferences.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    // Get the current routine from flow state
    val routine = when (val state = routineFlowState) {
        is RoutineFlowState.Overview -> state.routine
        else -> null
    }

    // If no routine is loaded, just return early
    // Don't auto-navigate here - the caller (dialog, back button) handles navigation
    // Auto-navigating causes double-back issues when exitRoutineFlow() clears the routine
    if (routine == null) {
        return
    }

    // Issue #190: Auto-start routine — skip overview and enter SetReady for exercise 0
    // Uses a one-shot flag to prevent re-triggering on recomposition or back-navigation.
    // Guard conditions: pref enabled, routine non-empty, BLE connected, no resumable progress.
    var autoStartFired by remember { mutableStateOf(false) }
    LaunchedEffect(routine.id, userPreferences.autoStartRoutine) {
        if (
            !autoStartFired &&
            userPreferences.autoStartRoutine &&
            routine.exercises.isNotEmpty() &&
            connectionState is ConnectionState.Connected &&
            !viewModel.hasResumableProgress(routine.id)
        ) {
            autoStartFired = true
            Logger.d("AutoStart") { "AutoStart: skipping overview, entering SetReady for exercise 0" }
            val firstExercise = routine.exercises[0]
            val initialWeight = firstExercise.setWeightsPerCableKg.firstOrNull() ?: firstExercise.weightPerCableKg
            val initialReps = firstExercise.setReps.firstOrNull() ?: 10
            viewModel.enterSetReadyWithAdjustments(0, 0, initialWeight, initialReps)
            navController.navigate(NavigationRoutes.SetReady.route)
        }
    }

    val pagerState = rememberPagerState(
        initialPage = (routineFlowState as? RoutineFlowState.Overview)?.selectedExerciseIndex ?: 0,
        pageCount = { routine.exercises.size },
    )
    val overviewSizing = routineOverviewSizing()
    val adjustmentStates = remember(routine.id, routine.exercises) {
        routine.exercises.associate { exercise ->
            exercise.id to mutableStateOf(defaultOverviewAdjustments(exercise))
        }
    }

    // Clear topbar title to allow dynamic title from EnhancedMainScreen
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("")
    }

    // Sync pager with viewmodel
    LaunchedEffect(pagerState.currentPage) {
        viewModel.selectExerciseInOverview(pagerState.currentPage)
    }

    // Time estimate (Issue #225)
    val timeEstimator: RoutineTimeEstimator = koinInject()
    var timeEstimate by remember { mutableStateOf<RoutineTimeEstimate?>(null) }
    LaunchedEffect(routine) {
        try {
            timeEstimate = timeEstimator.estimateRoutineDuration(routine, routine.profileId)
        } catch (e: Exception) {
            Logger.w(e) { "Failed to compute routine time estimate" }
        }
    }

    // Stop routine confirmation dialog
    var showStopConfirmation by remember { mutableStateOf(false) }

    // Handle system back press - show confirmation instead of silently exiting
    BackHandler {
        showStopConfirmation = true
    }

    fun startCurrentExercise() {
        val exerciseIndex = pagerState.currentPage
        val exercise = routine.exercises.getOrNull(exerciseIndex) ?: return
        val adjustments = adjustmentStates[exercise.id]?.value ?: defaultOverviewAdjustments(exercise)

        // Use ensureConnection to auto-connect if needed (matches other start buttons)
        viewModel.ensureConnection(
            onConnected = {
                // Pass adjusted values to SetReady
                viewModel.enterSetReadyWithAdjustments(
                    exerciseIndex = exerciseIndex,
                    setIndex = 0,
                    adjustedWeight = adjustments.weight,
                    adjustedReps = adjustments.reps,
                )
                navController.navigate(NavigationRoutes.SetReady.route)
            },
            onFailed = {}, // Toast/error handled by ensureConnection
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            RoutineOverviewActionBar(
                sizing = overviewSizing,
                onStopRoutine = { showStopConfirmation = true },
                onStartExercise = { startCurrentExercise() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ),
                ),
        ) {
            // Time estimate badge (Issue #225)
            timeEstimate?.let { estimate ->
                if (routine.exercises.isNotEmpty() && estimate.totalSeconds > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        val displayText = buildString {
                            append("Est. ")
                            append(if (estimate.hasRange) estimate.formattedRange else estimate.formattedDuration)
                            if (estimate.isEntirelyFallback) append(" (est.)")
                        }
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Horizontal pager for exercises
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp),
                pageSpacing = 16.dp,
            ) { page ->
                val exercise = routine.exercises[page]
                val isCompleted = completedExercises.contains(page)
                val adjustmentState = adjustmentStates.getValue(exercise.id)
                val adjustments by adjustmentState

                // Load video for this exercise
                var videoEntity by remember { mutableStateOf<ExerciseVideoEntity?>(null) }
                LaunchedEffect(exercise.exercise.id) {
                    exercise.exercise.id?.let { exerciseId ->
                        try {
                            val videos = exerciseRepository.getVideos(exerciseId)
                            videoEntity = videos.firstOrNull()
                        } catch (_: Exception) {
                            // Video loading failed - will show placeholder
                        }
                    }
                }

                ExerciseOverviewCard(
                    exercise = exercise,
                    exerciseIndex = page,
                    isCompleted = isCompleted,
                    weightUnit = weightUnit,
                    formatWeight = viewModel::formatWeight,
                    videoUrl = if (enableVideoPlayback) videoEntity?.videoUrl else null,
                    adjustedWeight = adjustments.weight,
                    adjustedReps = adjustments.reps,
                    isAMRAP = exercise.isAMRAP,
                    isEchoMode = exercise.programMode is ProgramMode.Echo,
                    echoLevel = adjustments.echoLevel,
                    eccentricLoadPercent = adjustments.eccentricLoadPercent,
                    sizing = overviewSizing,
                    onWeightChange = { newWeight ->
                        if (newWeight >= 0f) {
                            adjustmentState.value = adjustmentState.value.copy(weight = newWeight)
                        }
                    },
                    onRepsChange = { newReps ->
                        if (newReps >= 1) {
                            adjustmentState.value = adjustmentState.value.copy(reps = newReps)
                        }
                    },
                    onEchoLevelChange = {
                        adjustmentState.value = adjustmentState.value.copy(echoLevel = it)
                    },
                    onEccentricLoadChange = {
                        adjustmentState.value = adjustmentState.value.copy(eccentricLoadPercent = it)
                    },
                )
            }

            // Page indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(routine.exercises.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    val isCompleted = completedExercises.contains(index)

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isSelected) 12.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCompleted -> MaterialTheme.colorScheme.tertiary
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                    )
                }
            }
        }
    }

    // Stop confirmation dialog
    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text(stringResource(Res.string.exit_routine_title)) },
            text = { Text(stringResource(Res.string.exit_routine_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirmation = false
                        viewModel.exitRoutineFlow()
                        navController.navigateUp()
                    },
                ) {
                    Text(stringResource(Res.string.action_exit))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmation = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

private data class ExerciseOverviewAdjustments(
    val weight: Float,
    val reps: Int,
    val echoLevel: EchoLevel,
    val eccentricLoadPercent: Int,
)

private data class RoutineOverviewSizing(
    val isReducedViewport: Boolean,
    val cardPadding: Dp,
    val contentSpacing: Dp,
    val configPadding: Dp,
    val configSpacing: Dp,
    val videoHeight: Dp,
    val actionBarVerticalPadding: Dp,
    val actionButtonMinHeight: Dp,
)

private fun defaultOverviewAdjustments(exercise: RoutineExercise): ExerciseOverviewAdjustments {
    val initialWeight = exercise.setWeightsPerCableKg.firstOrNull() ?: exercise.weightPerCableKg
    val initialReps = exercise.setReps.firstOrNull() ?: 10

    return ExerciseOverviewAdjustments(
        weight = initialWeight,
        reps = initialReps,
        echoLevel = exercise.echoLevel,
        eccentricLoadPercent = exercise.eccentricLoad.percentage,
    )
}

@Composable
private fun routineOverviewSizing(): RoutineOverviewSizing {
    val windowSizeClass = LocalWindowSizeClass.current
    val fontScale = LocalDensity.current.fontScale
    val isCompactWidth = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
    val isReducedViewport = isCompactWidth &&
        (
            windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact ||
                windowSizeClass.heightDp < 700.dp ||
                fontScale >= 1.15f
            )

    return if (isReducedViewport) {
        RoutineOverviewSizing(
            isReducedViewport = true,
            cardPadding = 12.dp,
            contentSpacing = 8.dp,
            configPadding = 12.dp,
            configSpacing = 10.dp,
            videoHeight = 200.dp,
            actionBarVerticalPadding = 8.dp,
            actionButtonMinHeight = 52.dp,
        )
    } else {
        RoutineOverviewSizing(
            isReducedViewport = false,
            cardPadding = 16.dp,
            contentSpacing = 12.dp,
            configPadding = Spacing.medium,
            configSpacing = Spacing.medium,
            videoHeight = 220.dp,
            actionBarVerticalPadding = 12.dp,
            actionButtonMinHeight = 56.dp,
        )
    }
}

@Composable
private fun RoutineOverviewActionBar(
    sizing: RoutineOverviewSizing,
    onStopRoutine: () -> Unit,
    onStartExercise: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = 16.dp,
                    vertical = sizing.actionBarVerticalPadding,
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onStopRoutine,
                modifier = Modifier
                    .size(sizing.actionButtonMinHeight)
                    .testTag(TestTags.ACTION_STOP_ROUTINE_OVERVIEW),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.action_stop),
                )
            }

            Button(
                onClick = onStartExercise,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = sizing.actionButtonMinHeight)
                    .testTag(TestTags.ACTION_START_ROUTINE_EXERCISE),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(
                    horizontal = if (sizing.isReducedViewport) 12.dp else 16.dp,
                    vertical = 10.dp,
                ),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(Res.string.start_exercise),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ExerciseOverviewCard(
    exercise: RoutineExercise,
    exerciseIndex: Int,
    isCompleted: Boolean,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    videoUrl: String?,
    adjustedWeight: Float,
    adjustedReps: Int,
    isAMRAP: Boolean,
    isEchoMode: Boolean,
    echoLevel: EchoLevel,
    eccentricLoadPercent: Int,
    sizing: RoutineOverviewSizing,
    onWeightChange: (Float) -> Unit,
    onRepsChange: (Int) -> Unit,
    onEchoLevelChange: (EchoLevel) -> Unit,
    onEccentricLoadChange: (Int) -> Unit,
) {
    val maxWeightKg = Constants.MAX_WEIGHT_PER_CABLE_KG
    val weightStepKg = 0.25f

    // Bodyweight = no cable accessories (handles, bar, rope, etc.) in equipment list
    val isBodyweight = !exercise.exercise.hasCableAccessory

    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(sizing.cardPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(sizing.contentSpacing),
            ) {
                // Exercise header
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Exercise ${exerciseIndex + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        exercise.exercise.displayName,
                        modifier = Modifier.fillMaxWidth(),
                        style = if (sizing.isReducedViewport) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        exercise.exercise.muscleGroups,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Video thumbnail
                VideoPlayer(
                    videoUrl = videoUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(sizing.videoHeight)
                        .clip(RoundedCornerShape(12.dp)),
                )

                // Mode indicator (read-only) - Issue #222: Hide for bodyweight exercises
                if (!isBodyweight) {
                    Text(
                        exercise.programMode.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Show "Timed Exercise" or duration for bodyweight
                    val durationText = exercise.duration?.let { "${it}s" } ?: "Timed"
                    Text(
                        "Bodyweight • $durationText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Adjustment controls - matching RestTimerCard style
                // Issue #222: Only show for cable exercises, not bodyweight
                if (!isBodyweight) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(sizing.configPadding),
                            verticalArrangement = Arrangement.spacedBy(sizing.configSpacing),
                        ) {
                            Text(
                                if (isEchoMode) "ECHO SETTINGS" else "SET CONFIGURATION",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 1.sp,
                            )

                            Text(
                                "${exercise.setReps.size} sets",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )

                            if (isEchoMode) {
                                // Echo mode: Show Echo Level + Eccentric Load + Reps
                                OverviewEchoLevelSelector(
                                    selectedLevel = echoLevel,
                                    onLevelChange = onEchoLevelChange,
                                )

                                OverviewEccentricLoadSlider(
                                    percent = eccentricLoadPercent,
                                    onPercentChange = onEccentricLoadChange,
                                )

                                // Reps for Echo mode
                                if (!isAMRAP) {
                                    SliderWithButtons(
                                        value = adjustedReps.toFloat(),
                                        onValueChange = { newValue ->
                                            onRepsChange(newValue.toInt().coerceIn(1, 50))
                                        },
                                        valueRange = 1f..50f,
                                        step = 1f,
                                        label = "Target Reps",
                                        formatValue = { it.toInt().toString() },
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(stringResource(Res.string.target_reps), style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "AMRAP",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            } else {
                                // Standard modes: Weight + Reps
                                // Delta from routine baseline
                                val baselineWeightKg = exercise.setWeightsPerCableKg.firstOrNull()
                                    ?: exercise.weightPerCableKg
                                val deltaKg = adjustedWeight - baselineWeightKg
                                val deltaText = if (kotlin.math.abs(deltaKg) > 0.01f) {
                                    val sign = if (deltaKg > 0) "+" else "-"
                                    val absDeltaFormatted = formatWeight(kotlin.math.abs(deltaKg), weightUnit)
                                    "${sign}${absDeltaFormatted}"
                                } else null

                                SliderWithButtons(
                                    value = adjustedWeight,
                                    onValueChange = { newWeight ->
                                        onWeightChange(newWeight.coerceIn(0f, maxWeightKg))
                                    },
                                    valueRange = 0f..maxWeightKg,
                                    step = weightStepKg,
                                    label = "Weight per cable",
                                    formatValue = { formatWeight(it, weightUnit) },
                                    deltaText = deltaText,
                                    isDeltaPositive = deltaKg >= 0f,
                                )

                                // Reps adjuster (or AMRAP indicator)
                                if (isAMRAP) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(stringResource(Res.string.target_reps), style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "AMRAP",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                } else {
                                    SliderWithButtons(
                                        value = adjustedReps.toFloat(),
                                        onValueChange = { newValue ->
                                            onRepsChange(newValue.toInt().coerceIn(1, 50))
                                        },
                                        valueRange = 1f..50f,
                                        step = 1f,
                                        label = "Target Reps",
                                        formatValue = { it.toInt().toString() },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(sizing.contentSpacing))
            }

            // Completed overlay
            if (isCompleted) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        "Completed",
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

/**
 * Echo Level selector for Overview - Row of 4 buttons matching RestTimerCard style
 */
@Composable
private fun OverviewEchoLevelSelector(selectedLevel: EchoLevel, onLevelChange: (EchoLevel) -> Unit) {
    Column {
        Text(
            text = "ECHO LEVEL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    RoundedCornerShape(Spacing.medium),
                )
                .padding(Spacing.extraSmall),
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            EchoLevel.entries.forEach { level ->
                val isSelected = level == selectedLevel

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Spacing.small),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    },
                    onClick = { onLevelChange(level) },
                ) {
                    Text(
                        text = level.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.small),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Eccentric Load slider for Overview matching RestTimerCard style (0-150%)
 */
@Composable
private fun OverviewEccentricLoadSlider(percent: Int, onPercentChange: (Int) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ECCENTRIC LOAD",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        Slider(
            value = percent.toFloat(),
            onValueChange = { onPercentChange(it.toInt()) },
            valueRange = 0f..150f,
            steps = 29, // 5% increments: 0, 5, 10, ... 150
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
