package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
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
import com.devil.phoenixproject.presentation.components.EchoLevelPillSelector
import com.devil.phoenixproject.presentation.components.WeightChangePerRepControl
import com.devil.phoenixproject.presentation.components.formatRackLoadContributionSummary
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.navigation.safePopOrNavigate
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.labelAllCaps
import com.devil.phoenixproject.ui.theme.labelSmallAllCaps
import com.devil.phoenixproject.util.Constants
import com.devil.phoenixproject.util.UnitConverter
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_cancel
import vitruvianprojectphoenix.shared.generated.resources.action_exit
import vitruvianprojectphoenix.shared.generated.resources.cd_next
import vitruvianprojectphoenix.shared.generated.resources.cd_previous
import vitruvianprojectphoenix.shared.generated.resources.cd_stop
import vitruvianprojectphoenix.shared.generated.resources.bodyweight_effective_load_includes
import vitruvianprojectphoenix.shared.generated.resources.bodyweight_effective_load_more
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_save_override_confirm
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_save_override_dismiss
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_save_override_message
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_save_override_title
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

    var runtimeBehaviorOverrides by remember(setReadyState.exerciseIndex) {
        mutableStateOf(currentExercise.rackBehaviorOverrides)
    }
    var showSaveOverridePrompt by remember { mutableStateOf(false) }
    var pendingOverridesToSave by remember { mutableStateOf<Map<String, RackItemBehavior>>(emptyMap()) }

    val isEchoMode = currentExercise.programMode is ProgramMode.Echo
    val isAMRAP = currentExercise.isAMRAP

    // #635: explicit stored flag with equipment-derivation fallback
    val isBodyweight = currentExercise.exercise.isBodyweight
    val matchingWeightRecommendation = weightRecommendation?.takeIf { recommendation ->
        !isBodyweight &&
            recommendation.targetExerciseId == currentExercise.exercise.id &&
            recommendation.targetSetIndex == setReadyState.setIndex
    }

    // Issue #266/#410: Use configured weight increment from user preferences
    val userPreferences by viewModel.userPreferences.collectAsState()
    val sessionBodyweightState by viewModel.sessionBodyweightState.collectAsState()
    val currentRackLoadAdjustment by viewModel.currentRackLoadAdjustment.collectAsState()
    val resolvedBodyWeightKg = sessionBodyweightState.sessionBodyWeightKg ?: userPreferences.bodyWeightKg
    val bodyweightPromptPending = sessionBodyweightState.routineHasBodyweight &&
        !sessionBodyweightState.promptHandled
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

    var showSessionBodyweightEditor by remember { mutableStateOf(false) }
    var sessionBodyweightInput by remember { mutableStateOf("") }
    var saveSessionBodyweightToProfile by remember { mutableStateOf(false) }
    fun openSessionBodyweightEditor() {
        val initialKg = resolvedBodyWeightKg.takeIf { it > 0f }
        sessionBodyweightInput = initialKg?.let { formatBodyWeightNumberForUnit(it, weightUnit) } ?: ""
        saveSessionBodyweightToProfile = false
        showSessionBodyweightEditor = true
    }

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
                        enabled = connectionState is ConnectionState.Connected && !bodyweightPromptPending,
                        shape = MaterialTheme.shapes.small,
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
        // Issue #582: make the SetReady body vertically scrollable so the
        // EquipmentRackSelectionCard (always rendered for cable exercises) does
        // not get pushed below the visible/reachable viewport by the cable-only
        // SET CONFIGURATION / ECHO SETTINGS card on phone-sized portrait screens.
        // The Scaffold bottomBar stays anchored — only the body content scrolls.
        val setReadyScrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(screenBackgroundBrush())
                .verticalScroll(setReadyScrollState)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Compact header - exercise, set picker, and mode/bodyweight context.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Issue #142: Display exercise name prominently
                    Text(
                        currentExercise.exercise.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Issue #541: Make "Set X of Y" a tappable set picker. The label is the
                        // dropdown anchor; tapping it opens a menu of every set index in the current
                        // exercise. Selecting an entry calls viewModel.enterSetReady(ex, pickedSet) which
                        // the engine layer already supports. Keep Role.DropdownList and descriptions.
                        ExposedDropdownMenuBox(
                            expanded = setPickerExpanded && currentExercise.setReps.size > 1,
                            onExpandedChange = { requested ->
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
                                style = MaterialTheme.typography.labelLarge,
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
                        Text(
                            text = if (isBodyweight) {
                                val durationText = currentExercise.duration?.let { "${it}s" } ?: "Timed"
                                "Bodyweight • $durationText"
                            } else {
                                currentExercise.programMode.displayName
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Issue #600: once-per-session Current bodyweight card for any routine
            // containing a bodyweight exercise, even if the current Set Ready exercise is cable.
            if (bodyweightPromptPending) {
                CurrentBodyweightPromptCard(
                    savedBodyWeightKg = userPreferences.bodyWeightKg,
                    resolvedBodyWeightKg = resolvedBodyWeightKg,
                    weightUnit = weightUnit,
                    onConfirmStored = { viewModel.confirmSessionBodyWeight(null, saveToProfile = false) },
                    onEdit = { openSessionBodyweightEditor() },
                    onSkip = viewModel::skipSessionBodyWeightPrompt,
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
                        shape = MaterialTheme.shapes.medium,
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
                            if (resolvedBodyWeightKg > 0f) {
                                val effectiveKg = if (selectedVariant.percentage > 0f) {
                                    (
                                        resolvedBodyWeightKg * selectedVariant.percentage +
                                            currentRackLoadAdjustment.externalAddedLoadKg -
                                            currentRackLoadAdjustment.counterweightKg
                                        ).coerceAtLeast(0f)
                                } else {
                                    0f
                                }
                                val displayWeight = if (weightUnit == WeightUnit.KG) {
                                    "${com.devil.phoenixproject.util.UnitConverter.formatDecimal(effectiveKg)} kg"
                                } else {
                                    "${com.devil.phoenixproject.util.UnitConverter.formatDecimal(com.devil.phoenixproject.util.UnitConverter.kgToLb(effectiveKg))} lb"
                                }
                                val rackLoadContributionSummary = formatRackLoadContributionSummary(
                                    contributions = currentRackLoadAdjustment.loadContributions,
                                    weightUnit = weightUnit,
                                    formatWeight = viewModel::formatWeight,
                                    moreTemplate = stringResource(Res.string.bodyweight_effective_load_more),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Effective load: $displayWeight",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (rackLoadContributionSummary != null) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        stringResource(
                                            Res.string.bodyweight_effective_load_includes,
                                            rackLoadContributionSummary,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (isBodyweight && sessionBodyweightState.promptHandled) {
                HandledSessionBodyweightCard(
                    resolvedBodyWeightKg = resolvedBodyWeightKg,
                    weightUnit = weightUnit,
                    onEdit = { openSessionBodyweightEditor() },
                )
                Spacer(Modifier.height(12.dp))
            }

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
                    shape = MaterialTheme.shapes.medium,
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
                            style = labelAllCaps.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (isEchoMode) {
                            // Echo Level selector - matching RestTimerCard style
                            EchoLevelPillSelector(
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

                            WeightChangePerRepControl(
                                valueKg = setReadyState.adjustedProgressionKg,
                                weightUnit = weightUnit,
                                kgToDisplay = viewModel::kgToDisplay,
                                displayToKg = viewModel::displayToKg,
                                onValueChangeKg = { viewModel.updateSetReadyProgressionKg(it) },
                                modifier = Modifier.testTag(SetReadyTestTags.PROGRESSION_CONTROL),
                            )
                        }
                    }
                }
            }

            EquipmentRackSelectionCard(
                rackItems = rackItems,
                activeRackItemIds = activeRackItemIds,
                behaviorOverrides = runtimeBehaviorOverrides,
                weightUnit = weightUnit,
                formatWeight = viewModel::formatWeight,
                onSelectionChange = viewModel::updateActiveRackSelection,
                onBehaviorOverrideChange = { newOverrides ->
                    runtimeBehaviorOverrides = newOverrides
                    viewModel.updateActiveRackBehaviorOverrides(newOverrides)
                    pendingOverridesToSave = newOverrides
                    showSaveOverridePrompt = true
                },
                onManageRack = { navController.navigate(NavigationRoutes.EquipmentRack.route) },
                showBehaviorOverrides = true,
                // Issue #582: regression tag for Compose UI / screenshot tests that
                // verify the Equipment Rack card is reachable on cable SetReady.
                modifier = Modifier.testTag(SetReadyTestTags.RACK_CARD),
            )

            Spacer(Modifier.height(12.dp))

            // Video thumbnail stays available but no longer pushes primary set configuration down.
            if (enableVideoPlayback) {
                VideoPlayer(
                    videoUrl = videoEntity?.videoUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(MaterialTheme.shapes.small),
                )
                Spacer(Modifier.height(12.dp))
            }
            // Issue #582: a trailing `Spacer(Modifier.weight(1f))` was removed here
            // because `Modifier.weight` is not allowed inside a vertically-scrollable
            // Column (Compose throws at composition). The scroll wrapper above keeps
            // the rack card reachable on small portrait screens without forcing it
            // to the bottom; the Scaffold bottomBar still anchors the action row.
            Spacer(Modifier.height(12.dp))
        }
    }

    val parsedSessionBodyWeightKg = parseBodyWeightInputKg(sessionBodyweightInput, weightUnit)
    if (showSessionBodyweightEditor) {
        AlertDialog(
            onDismissRequest = { showSessionBodyweightEditor = false },
            title = { Text("Edit current bodyweight") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text("Used only for this workout unless saved to your profile.")
                    OutlinedTextField(
                        value = sessionBodyweightInput,
                        onValueChange = { value ->
                            sessionBodyweightInput = value.filter { it.isDigit() || it == '.' || it == ',' }
                        },
                        label = { Text("Current bodyweight (${if (weightUnit == WeightUnit.KG) "kg" else "lb"})") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { saveSessionBodyweightToProfile = !saveSessionBodyweightToProfile },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = saveSessionBodyweightToProfile,
                            onCheckedChange = { saveSessionBodyweightToProfile = it },
                        )
                        Text("Save to profile for next time")
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = parsedSessionBodyWeightKg != null && parsedSessionBodyWeightKg > 0f,
                    onClick = {
                        parsedSessionBodyWeightKg?.let { kg ->
                            viewModel.confirmSessionBodyWeight(
                                weightKg = kg,
                                saveToProfile = saveSessionBodyweightToProfile,
                            )
                        }
                        showSessionBodyweightEditor = false
                    },
                ) {
                    Text("Save for session")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSessionBodyweightEditor = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Stop confirmation dialog
    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text(stringResource(Res.string.exit_routine_title)) },
            text = { Text(stringResource(Res.string.exit_routine_message)) },
            confirmButton = {
                // lens-navigation-ux-3: read destination BEFORE exitRoutineFlow() clears the origin.
                Button(
                    onClick = {
                        showStopConfirmation = false
                        val dest = viewModel.routineExitDestination()
                        viewModel.exitRoutineFlow()
                        navController.safePopOrNavigate(dest)
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

    if (showSaveOverridePrompt) {
        AlertDialog(
            onDismissRequest = { showSaveOverridePrompt = false },
            title = { Text(stringResource(Res.string.equipment_rack_save_override_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.equipment_rack_save_override_message,
                        currentExercise.exercise.name,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSaveOverridePrompt = false
                        viewModel.saveRackBehaviorOverridesForExercise(
                            setReadyState.exerciseIndex,
                            pendingOverridesToSave,
                        )
                    },
                ) {
                    Text(stringResource(Res.string.equipment_rack_save_override_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveOverridePrompt = false }) {
                    Text(stringResource(Res.string.equipment_rack_save_override_dismiss))
                }
            },
        )
    }
}

@Composable
private fun CurrentBodyweightPromptCard(
    savedBodyWeightKg: Float,
    resolvedBodyWeightKg: Float,
    weightUnit: WeightUnit,
    onConfirmStored: () -> Unit,
    onEdit: () -> Unit,
    onSkip: () -> Unit,
) {
    val hasSavedBodyweight = savedBodyWeightKg > 0f
    val displayBodyweight = (savedBodyWeightKg.takeIf { it > 0f } ?: resolvedBodyWeightKg)
        .takeIf { it > 0f }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SetReadyTestTags.SESSION_BODYWEIGHT_CARD),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                "Current bodyweight",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (hasSavedBodyweight) {
                    "Used for bodyweight effective load and volume in this session."
                } else {
                    "Add bodyweight to calculate bodyweight effective load and volume for this session."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (displayBodyweight != null) {
                Text(
                    formatBodyWeightForUnit(displayBodyweight, weightUnit),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasSavedBodyweight) {
                    Button(onClick = onConfirmStored) {
                        Text("Use for this session")
                    }
                    TextButton(onClick = onEdit) {
                        Text("Edit")
                    }
                } else {
                    Button(onClick = onEdit) {
                        Text("Save for session")
                    }
                    TextButton(onClick = onSkip) {
                        Text("Not now")
                    }
                }
            }
        }
    }
}

@Composable
private fun HandledSessionBodyweightCard(
    resolvedBodyWeightKg: Float,
    weightUnit: WeightUnit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Current bodyweight",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (resolvedBodyWeightKg > 0f) {
                        formatBodyWeightForUnit(resolvedBodyWeightKg, weightUnit)
                    } else {
                        "Not set for this session"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(onClick = onEdit) {
                Text("Edit current bodyweight")
            }
        }
    }
}

private fun formatBodyWeightNumberForUnit(weightKg: Float, weightUnit: WeightUnit): String {
    val displayValue = when (weightUnit) {
        WeightUnit.KG -> weightKg
        WeightUnit.LB -> UnitConverter.kgToLb(weightKg)
    }
    return UnitConverter.formatDecimal(displayValue)
}

private fun formatBodyWeightForUnit(weightKg: Float, weightUnit: WeightUnit): String {
    val suffix = when (weightUnit) {
        WeightUnit.KG -> "kg"
        WeightUnit.LB -> "lb"
    }
    return "${formatBodyWeightNumberForUnit(weightKg, weightUnit)} $suffix"
}

private fun parseBodyWeightInputKg(input: String, weightUnit: WeightUnit): Float? {
    val displayValue = input.trim().replace(',', '.').toFloatOrNull() ?: return null
    return when (weightUnit) {
        WeightUnit.KG -> displayValue
        WeightUnit.LB -> UnitConverter.lbToKg(displayValue)
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
                style = labelSmallAllCaps,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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

/**
 * Test tags used by [SetReadyScreen]. Issue #582 introduced `RACK_CARD` so a
 * Compose UI / screenshot test can assert that the Equipment Rack card stays
 * reachable on a phone-sized portrait screen when a cable exercise is selected.
 *
 * Source-level regression coverage lives in
 * `shared/src/commonTest/.../SetReadyScreenScrollWiringTest.kt`; that test pins
 * this file's structure so the wiring here cannot regress without the unit test
 * failing first.
 */
object SetReadyTestTags {
    /** EquipmentRackSelectionCard root inside SetReadyScreen. Issue #582. */
    const val RACK_CARD: String = "set_ready_rack_card"

    /** Weight Change / Rep control inside cable non-Echo Set Configuration. Issue #604. */
    const val PROGRESSION_CONTROL: String = "set_ready_progression_control"

    /** Current bodyweight prompt card shown once per bodyweight-containing session. Issue #600. */
    const val SESSION_BODYWEIGHT_CARD: String = "set_ready_session_bodyweight_card"
}
