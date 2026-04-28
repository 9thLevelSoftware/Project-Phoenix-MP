package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.usecase.BodyweightVolumeCalculator
import com.devil.phoenixproject.presentation.components.BackHandler
import com.devil.phoenixproject.presentation.components.SliderWithButtons
import com.devil.phoenixproject.presentation.components.VideoPlayer
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.Constants
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_cancel
import vitruvianprojectphoenix.shared.generated.resources.action_exit
import vitruvianprojectphoenix.shared.generated.resources.cd_next
import vitruvianprojectphoenix.shared.generated.resources.cd_previous
import vitruvianprojectphoenix.shared.generated.resources.cd_stop
import vitruvianprojectphoenix.shared.generated.resources.exit_routine_message
import vitruvianprojectphoenix.shared.generated.resources.exit_routine_title
import vitruvianprojectphoenix.shared.generated.resources.target_reps

/**
 * Set Ready Screen - Focused view for a single exercise/set.
 * Allows parameter adjustments before starting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetReadyScreen(navController: NavController, viewModel: MainViewModel, exerciseRepository: ExerciseRepository) {
    val routineFlowState by viewModel.routineFlowState.collectAsState()
    val workoutState by viewModel.workoutState.collectAsState()
    val loadedRoutine by viewModel.loadedRoutine.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val enableVideoPlayback by viewModel.enableVideoPlayback.collectAsState()

    // Get current state
    val setReadyState = routineFlowState as? RoutineFlowState.SetReady
    val routine = loadedRoutine

    // If no state/routine, just return early
    // Don't auto-navigate - the caller handles navigation to avoid double-back issues
    if (setReadyState == null || routine == null) {
        return
    }

    val currentExercise = routine.exercises.getOrNull(setReadyState.exerciseIndex)
    // If exercise is invalid, just return early
    if (currentExercise == null) {
        return
    }

    val isEchoMode = currentExercise.programMode is ProgramMode.Echo
    val isAMRAP = currentExercise.isAMRAP

    // Bodyweight = no cable accessories (handles, bar, rope, etc.) in equipment list
    val isBodyweight = !currentExercise.exercise.hasCableAccessory

    // Weight parameters matching RestTimerCard exactly
    val maxWeightKg = Constants.MAX_WEIGHT_PER_CABLE_KG
    val weightStepKg = 0.25f

    // Navigation state - uses superset-aware helpers from ViewModel
    val canGoPrev = viewModel.hasPreviousStep(setReadyState.exerciseIndex, setReadyState.setIndex)
    val canSkip = viewModel.hasNextStep(setReadyState.exerciseIndex, setReadyState.setIndex)

    // Stop confirmation dialog
    var showStopConfirmation by remember { mutableStateOf(false) }

    // Handle system back button
    BackHandler {
        viewModel.returnToOverview()
        navController.navigateUp()
    }

    // Clear topbar title to allow dynamic title from EnhancedMainScreen
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("")
    }

    // Load video for exercise
    // Issue #142: Key the remember on exerciseIndex so state resets when exercise changes.
    // This ensures the video entity is cleared and reloaded for each exercise.
    var videoEntity by remember(setReadyState.exerciseIndex) { mutableStateOf<ExerciseVideoEntity?>(null) }
    LaunchedEffect(setReadyState.exerciseIndex, currentExercise.exercise.id) {
        // Clear any stale video first
        videoEntity = null
        // Load new video if exercise has an ID
        currentExercise.exercise.id?.let { exerciseId ->
            try {
                val videos = exerciseRepository.getVideos(exerciseId)
                videoEntity = videos.firstOrNull()
            } catch (_: Exception) {
                // Video loading failed - videoEntity stays null
            }
        }
    }

    // Watch for workout state changes to navigate to ActiveWorkout
    // Use popUpTo(RoutineOverview) to maintain clean navigation stack:
    // Stack is always: DailyRoutines -> RoutineOverview -> (SetReady OR ActiveWorkout)
    LaunchedEffect(workoutState) {
        when (workoutState) {
            is WorkoutState.Countdown, is WorkoutState.Active -> {
                navController.navigate(NavigationRoutes.ActiveWorkout.route) {
                    popUpTo(NavigationRoutes.RoutineOverview.route) { inclusive = false }
                }
            }

            else -> {}
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            // Bottom navigation bar with all action buttons
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // PREV button - compact icon button
                    FilledTonalIconButton(
                        onClick = { viewModel.setReadyPrev() },
                        enabled = canGoPrev,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(Res.string.cd_previous),
                        )
                    }

                    // START SET button - primary action, takes most space
                    Button(
                        onClick = {
                            viewModel.ensureConnection(
                                onConnected = { viewModel.startSetFromReady() },
                                onFailed = {},
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        enabled = connectionState is ConnectionState.Connected,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "START",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }

                    // NEXT button - compact icon button
                    FilledTonalIconButton(
                        onClick = { viewModel.setReadySkip() },
                        enabled = canSkip,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(Res.string.cd_next),
                        )
                    }

                    // STOP button - destructive action
                    FilledTonalIconButton(
                        onClick = { showStopConfirmation = true },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(Res.string.cd_stop),
                        )
                    }
                }
            }
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
                )
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Header - Set X of Y
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Issue #142: Display exercise name prominently
                    Text(
                        currentExercise.exercise.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Set ${setReadyState.setIndex + 1} of ${currentExercise.setReps.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                    )
                    // Issue #222: Show "Bodyweight • XXs" for bodyweight, mode name for cable
                    if (isBodyweight) {
                        val durationText = currentExercise.duration?.let { "${it}s" } ?: "Timed"
                        Text(
                            "Bodyweight • $durationText",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    } else {
                        Text(
                            currentExercise.programMode.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Video thumbnail
            if (enableVideoPlayback) {
                VideoPlayer(
                    videoUrl = videoEntity?.videoUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.height(12.dp))
            }

            // Issue #229: Bodyweight variant picker (transient, not persisted)
            if (isBodyweight) {
                val variants = remember(currentExercise.exercise.name) {
                    BodyweightVolumeCalculator.getVariantsForExercise(currentExercise.exercise.name)
                }
                if (variants != null && variants.size > 1) {
                    var expanded by remember { mutableStateOf(false) }
                    var selectedVariant by remember { mutableStateOf(variants[0]) }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium),
                        ) {
                            Text(
                                "Exercise Variant",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = it },
                            ) {
                                OutlinedTextField(
                                    value = selectedVariant.first,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth(),
                                    singleLine = true,
                                )
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                ) {
                                    variants.forEach { variant ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "${variant.first} (${(variant.second * 100).toInt()}%)",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                            },
                                            onClick = {
                                                selectedVariant = variant
                                                expanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            val userPrefs by viewModel.userPreferences.collectAsState()
                            if (userPrefs.bodyWeightKg > 0f) {
                                val effectiveKg = userPrefs.bodyWeightKg * selectedVariant.second
                                val displayWeight = if (weightUnit == WeightUnit.KG) {
                                    "${com.devil.phoenixproject.util.UnitConverter.formatDecimal(effectiveKg)} kg"
                                } else {
                                    "${com.devil.phoenixproject.util.UnitConverter.formatDecimal(com.devil.phoenixproject.util.UnitConverter.kgToLb(effectiveKg))} lb"
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Effective load: $displayWeight",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // Configuration card - matching RestTimerCard style
            // Issue #222: Hide for bodyweight exercises (no cable settings to configure)
            if (!isBodyweight) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                    ) {
                        Text(
                            if (isEchoMode) "ECHO SETTINGS" else "SET CONFIGURATION",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp,
                        )

                        if (isEchoMode) {
                            // Echo Level selector - matching RestTimerCard style
                            SetReadyEchoLevelSelector(
                                selectedLevel = setReadyState.echoLevel ?: EchoLevel.HARD,
                                onLevelChange = { viewModel.updateSetReadyEchoLevel(it) },
                            )

                            // Eccentric Load slider - matching RestTimerCard style
                            SetReadyEccentricLoadSlider(
                                percent = setReadyState.eccentricLoadPercent ?: 100,
                                onPercentChange = { viewModel.updateSetReadyEccentricLoad(it) },
                            )

                            // Reps adjuster for Echo mode too
                            if (!isAMRAP) {
                                SliderWithButtons(
                                    value = setReadyState.adjustedReps.toFloat(),
                                    onValueChange = { newValue ->
                                        viewModel.updateSetReadyReps(newValue.toInt().coerceIn(1, 50))
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
                            // Standard mode: Weight + Reps using SliderWithButtons
                            // Delta from routine baseline
                            val baselineWeightKg = currentExercise.setWeightsPerCableKg.getOrNull(setReadyState.setIndex)
                                ?: currentExercise.weightPerCableKg
                            val deltaKg = setReadyState.adjustedWeight - baselineWeightKg
                            val deltaText = if (kotlin.math.abs(deltaKg) > 0.01f) {
                                val sign = if (deltaKg > 0) "+" else "-"
                                val absDeltaFormatted = viewModel.formatWeight(kotlin.math.abs(deltaKg), weightUnit)
                                "${sign}${absDeltaFormatted}"
                            } else null

                            SliderWithButtons(
                                value = setReadyState.adjustedWeight,
                                onValueChange = { newWeight ->
                                    viewModel.updateSetReadyWeight(newWeight.coerceIn(0f, maxWeightKg))
                                },
                                valueRange = 0f..maxWeightKg,
                                step = weightStepKg,
                                label = "Weight per cable",
                                formatValue = { viewModel.formatWeight(it, weightUnit) },
                                deltaText = deltaText,
                                isDeltaPositive = deltaKg >= 0f,
                            )

                            if (!isAMRAP) {
                                SliderWithButtons(
                                    value = setReadyState.adjustedReps.toFloat(),
                                    onValueChange = { newValue ->
                                        viewModel.updateSetReadyReps(newValue.toInt().coerceIn(1, 50))
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
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
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
                        navController.popBackStack(NavigationRoutes.DailyRoutines.route, false)
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

/**
 * Echo Level selector - Row of 4 buttons matching RestTimerCard style
 */
@Composable
private fun SetReadyEchoLevelSelector(selectedLevel: EchoLevel, onLevelChange: (EchoLevel) -> Unit) {
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
 * Eccentric Load slider matching RestTimerCard style (0-150%)
 */
@Composable
private fun SetReadyEccentricLoadSlider(percent: Int, onPercentChange: (Int) -> Unit) {
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
