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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackLoadAdjustment
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.usecase.BodyweightVolumeCalculator
import com.devil.phoenixproject.presentation.components.BackHandler
import com.devil.phoenixproject.presentation.components.EquipmentRackSelectionCard
import com.devil.phoenixproject.presentation.components.ExpressiveSlider
import com.devil.phoenixproject.presentation.components.SliderWithButtons
import com.devil.phoenixproject.presentation.components.VideoPlayer
import com.devil.phoenixproject.presentation.components.WeightRecommendationCard
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
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_active_selection
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_display_only_count
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_manage
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_no_enabled_items
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_none_selected
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_selected_count
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_selected_summary
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
    val weightRecommendation by viewModel.weightAdjustmentRecommendation.collectAsState()
    val rackItems by viewModel.rackItems.collectAsState()
    val activeRackItemIds by viewModel.activeRackItemIds.collectAsState()

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
    val matchingWeightRecommendation = weightRecommendation?.takeIf { recommendation ->
        !isBodyweight &&
            recommendation.targetExerciseId == currentExercise.exercise.id &&
            recommendation.targetSetIndex == setReadyState.setIndex
    }

    // Issue #266/#410: Use configured weight increment from user preferences
    val userPreferences by viewModel.userPreferences.collectAsState()
    val maxWeightKg = Constants.MAX_WEIGHT_PER_CABLE_KG
    val weightStepKg = userPreferences.effectiveWeightIncrementKg

    // Navigation state - uses superset-aware helpers from ViewModel
    val canGoPrev = viewModel.hasPreviousStep(setReadyState.exerciseIndex, setReadyState.setIndex)
    val canSkip = viewModel.hasNextStep(setReadyState.exerciseIndex, setReadyState.setIndex)

    // Stop confirmation dialog
    var showStopConfirmation by remember { mutableStateOf(false) }

    // Issue #541: Set picker dropdown state. Exposes a dropdown menu of all set indices so the
    // user can jump directly to any set (parity with the routines flow's per-exercise selector
    // and the reporter's request for "set selection in the middle of a workout"). Disabled when
    // there is only one set (no point picking).
    var setPickerExpanded by remember { mutableStateOf(false) }

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

    // Watch for workout state changes to navigate to ActiveWorkout.
    // Use popUpTo(SetReady.route) { inclusive = true } to replace SetReady on the back stack
    // with ActiveWorkout, preserving the parent screen (RoutineOverview for routines, TrainingCycles
    // for cycles) so system back from ActiveWorkout lands on the right place.
    // Issue #541: this was previously popUpTo(RoutineOverview.route), which is not on the cycle back
    // stack; the RCA explicitly recommends context-aware self-pop and the per-flow inclusive-pop
    // pattern preserves both back stacks without regression.
    LaunchedEffect(workoutState) {
        when (workoutState) {
            is WorkoutState.Countdown, is WorkoutState.Active -> {
                navController.navigate(NavigationRoutes.ActiveWorkout.route) {
                    popUpTo(NavigationRoutes.SetReady.route) { inclusive = true }
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
                    // Issue #541: Make "Set X of Y" a tappable set picker. The label is the
                    // dropdown anchor; tapping it opens a menu of every set index in the current
                    // exercise. Selecting an entry calls viewModel.enterSetReady(ex, pickedSet) which
                    // the engine layer already supports (RoutineFlowManager.kt:886). This is the
                    // parity surface for the reporter's "no set selector" bug.
                    //
                    // a11y: the anchor Text is marked with Role.DropdownList so screen readers
                    // announce it as an interactive picker rather than a static label. We avoid
                    // OutlinedTextField(readOnly=true) here because the label lives inside a
                    // primaryContainer card whose on-color doesn't match the OutlinedTextField's
                    // default chrome; the existing in-file bodyweight variant picker uses that
                    // pattern but lives in a surfaceContainerHighest card where it blends cleanly.
                    ExposedDropdownMenuBox(
                        expanded = setPickerExpanded && currentExercise.setReps.size > 1,
                        onExpandedChange = { requested ->
                            // Respect the requested state from the Material3 API (e.g. for
                            // accessibility services or external state changes) but only honor
                            // opens when there is more than one set to pick from.
                            if (currentExercise.setReps.size > 1) {
                                setPickerExpanded = requested
                            } else {
                                setPickerExpanded = false
                            }
                        },
                    ) {
                        Text(
                            text = "Set ${setReadyState.setIndex + 1} of ${currentExercise.setReps.size}" +
                                if (currentExercise.setReps.size > 1) "  \u25BE" else "",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                            modifier = Modifier
                                .menuAnchor()
                                .semantics {
                                    role = Role.DropdownList
                                    contentDescription = "Set selector, currently set " +
                                        "${setReadyState.setIndex + 1} of " +
                                        "${currentExercise.setReps.size}"
                                    if (currentExercise.setReps.size > 1) {
                                        stateDescription = "Tap to choose a different set"
                                    }
                                },
                        )
                        ExposedDropdownMenu(
                            expanded = setPickerExpanded && currentExercise.setReps.size > 1,
                            onDismissRequest = { setPickerExpanded = false },
                        ) {
                            currentExercise.setReps.indices.forEach { setIdx ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Set ${setIdx + 1}" +
                                                if (setIdx == setReadyState.setIndex) "  (current)" else "",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    },
                                    onClick = {
                                        setPickerExpanded = false
                                        if (setIdx != setReadyState.setIndex) {
                                            // enterSetReady is the engine-level "jump to (ex, set)"
                                            // API and updates routineFlowState, currentSetIndex, and
                                            // warm-up state for the new set. No workout state change.
                                            viewModel.enterSetReady(
                                                setReadyState.exerciseIndex,
                                                setIdx,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
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

            // Issue #229/#427: Bodyweight variant picker (runtime state, carried across sets)
            if (isBodyweight) {
                val variants = remember(currentExercise.exercise.name) {
                    BodyweightVolumeCalculator.getVariantsForExercise(currentExercise.exercise.name)
                }
                if (variants != null && variants.size > 1) {
                    var expanded by remember { mutableStateOf(false) }
                    val selectedBodyweightVariants by viewModel.selectedBodyweightVariants.collectAsState()
                    val bodyweightVariantKey = remember(currentExercise) {
                        viewModel.bodyweightVariantKey(currentExercise)
                    }
                    val selectedVariant = selectedBodyweightVariants[bodyweightVariantKey]?.takeIf { saved ->
                        variants.any { it.label == saved.label && it.percentage == saved.percentage }
                    } ?: variants[0]

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
                                    value = selectedVariant.label,
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
                                                    "${variant.label} (${(variant.percentage * 100).toInt()}%)",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                            },
                                            onClick = {
                                                viewModel.selectBodyweightVariant(bodyweightVariantKey, variant)
                                                expanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            val userPrefs by viewModel.userPreferences.collectAsState()
                            val currentRackLoadAdjustment by viewModel.currentRackLoadAdjustment.collectAsState()
                            if (userPrefs.bodyWeightKg > 0f) {
                                val effectiveKg = if (selectedVariant.percentage > 0f) {
                                    (userPrefs.bodyWeightKg * selectedVariant.percentage +
                                        currentRackLoadAdjustment.externalAddedLoadKg -
                                        currentRackLoadAdjustment.counterweightKg
                                    ).coerceAtLeast(0f)
                                } else 0f
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

            EquipmentRackSelectionCard(
                rackItems = rackItems,
                activeRackItemIds = activeRackItemIds,
                weightUnit = weightUnit,
                formatWeight = viewModel::formatWeight,
                onSelectionChange = viewModel::updateActiveRackSelection,
                onManageRack = { navController.navigate(NavigationRoutes.EquipmentRack.route) },
            )

            Spacer(Modifier.height(12.dp))

            // Configuration card - matching RestTimerCard style
            // Issue #222: Hide for bodyweight exercises (no cable settings to configure)
            if (!isBodyweight) {
                if (matchingWeightRecommendation != null) {
                    WeightRecommendationCard(
                        recommendation = matchingWeightRecommendation,
                        weightUnit = weightUnit,
                        formatWeight = viewModel::formatWeight,
                        onApply = viewModel::applyWeightRecommendation,
                        onDismiss = viewModel::dismissWeightRecommendation,
                    )
                    Spacer(Modifier.height(12.dp))
                }

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
                                selectedLevel = setReadyState.echoLevel ?: EchoLevel.HARDER,
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
                                "${sign}$absDeltaFormatted"
                            } else {
                                null
                            }

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

@Composable
private fun SetReadyRackSelectionCard(
    rackItems: List<RackItem>,
    activeRackItemIds: List<String>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onSelectionChange: (List<String>) -> Unit,
    onManageRack: () -> Unit,
) {
    val enabledItems = remember(rackItems) {
        rackItems
            .filter { it.enabled }
            .sortedWith(compareBy<RackItem> { it.sortOrder }.thenBy { it.name.lowercase() })
    }
    val activeIdSet = remember(activeRackItemIds) { activeRackItemIds.toSet() }
    val selectedItems = remember(enabledItems, activeIdSet) { enabledItems.filter { it.id in activeIdSet } }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.equipment_rack_active_selection),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                )
                TextButton(onClick = onManageRack) {
                    Text(stringResource(Res.string.equipment_rack_manage))
                }
            }

            if (enabledItems.isEmpty()) {
                Text(
                    stringResource(Res.string.equipment_rack_no_enabled_items),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                enabledItems.forEach { item ->
                    val selected = item.id in activeIdSet
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val updated = if (selected) {
                                activeRackItemIds.filterNot { it == item.id }
                            } else {
                                activeRackItemIds + item.id
                            }
                            onSelectionChange(updated)
                        },
                        label = {
                            Text(
                                "${item.name} - ${formatRackChipDetail(item, weightUnit, formatWeight)}",
                                maxLines = 1,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(
                    if (selectedItems.isEmpty()) {
                        stringResource(Res.string.equipment_rack_none_selected)
                    } else {
                        stringResource(
                            Res.string.equipment_rack_selected_summary,
                            rackSelectionSummary(selectedItems, weightUnit, formatWeight),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatRackChipDetail(
    item: RackItem,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
): String = when (item.behavior) {
    RackItemBehavior.ADDED_RESISTANCE -> "+${formatWeight(item.weightKg, weightUnit)}"
    RackItemBehavior.COUNTERWEIGHT -> "-${formatWeight(item.weightKg, weightUnit)}"
    RackItemBehavior.DISPLAY_ONLY -> formatWeight(item.weightKg, weightUnit)
}

@Composable
private fun rackSelectionSummary(
    items: List<RackItem>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
): String {
    val addedKg = items.filter { it.behavior == RackItemBehavior.ADDED_RESISTANCE }.sumOf { it.weightKg.toDouble() }.toFloat()
    val counterweightKg = items.filter { it.behavior == RackItemBehavior.COUNTERWEIGHT }.sumOf { it.weightKg.toDouble() }.toFloat()
    val displayOnlyCount = items.count { it.behavior == RackItemBehavior.DISPLAY_ONLY }
    val displayOnlyLabel = if (displayOnlyCount > 0) {
        stringResource(Res.string.equipment_rack_display_only_count, displayOnlyCount)
    } else {
        null
    }
    val selectedCountLabel = stringResource(Res.string.equipment_rack_selected_count, items.size)
    val parts = buildList {
        if (addedKg > 0f) add("+${formatWeight(addedKg, weightUnit)}")
        if (counterweightKg > 0f) add("-${formatWeight(counterweightKg, weightUnit)}")
        displayOnlyLabel?.let(::add)
    }
    return parts.ifEmpty { listOf(selectedCountLabel) }.joinToString(" / ")
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

        ExpressiveSlider(
            value = percent.toFloat(),
            onValueChange = { onPercentChange(it.toInt()) },
            valueRange = 0f..150f,
            steps = 29, // 5% increments: 0, 5, 10, ... 150
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
