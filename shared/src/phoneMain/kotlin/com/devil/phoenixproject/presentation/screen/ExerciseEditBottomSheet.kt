package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseImageEntity
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.echoLevelLabel
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutMode
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.usecase.RoutineScalingBaselineSource
import com.devil.phoenixproject.presentation.components.CompactNumberPicker
import com.devil.phoenixproject.presentation.components.EquipmentRackSelectionCard
import com.devil.phoenixproject.presentation.components.ExerciseDemoImage
import com.devil.phoenixproject.presentation.components.ExpressiveSlider
import com.devil.phoenixproject.presentation.components.PhoenixSheetHeader
import com.devil.phoenixproject.presentation.components.ProgressionSlider
import com.devil.phoenixproject.presentation.components.ResponsiveSegmentedSelector
import com.devil.phoenixproject.presentation.util.LocalWeightTextScale
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.presentation.util.effectiveWeightTextScale
import com.devil.phoenixproject.util.rethrowIfCancellation
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.presentation.util.scaledForWeightValue
import com.devil.phoenixproject.presentation.util.shouldStackExerciseSetPickers
import com.devil.phoenixproject.presentation.viewmodel.ExerciseConfigViewModel
import com.devil.phoenixproject.presentation.viewmodel.ExerciseType
import com.devil.phoenixproject.presentation.viewmodel.ScalingBaselineLoadStatus
import com.devil.phoenixproject.presentation.viewmodel.SetConfiguration
import com.devil.phoenixproject.presentation.viewmodel.SetMode
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.CommandLimits
import com.devil.phoenixproject.util.Constants
import com.devil.phoenixproject.util.UnitConverter
import com.devil.phoenixproject.util.parseLocalizedDecimal
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Exercise configuration bottom sheet for SingleExerciseScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseEditBottomSheet(
    exercise: RoutineExercise,
    weightUnit: WeightUnit,
    enableVideoPlayback: Boolean,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    exerciseRepository: ExerciseRepository,
    personalRecordRepository: PersonalRecordRepository,
    formatWeight: (Float, WeightUnit) -> String,
    rackItems: List<RackItem> = emptyList(),
    onSave: (RoutineExercise) -> Unit,
    onDismiss: () -> Unit,
    buttonText: String = "Save",
    weightStepOverride: Float = 0f, // Issue #266/#410: 0 = use default for unit
    // KD-9: per-cable ceiling for the weight slider. This editor is used offline, so the
    // caller supplies the LAST connected trainer's ceiling (widest hardware when unknown).
    planningMaxWeightPerCableKg: Float = Constants.MAX_WEIGHT_PER_CABLE_KG,
    primaryActionEnabled: Boolean = true,
    primaryActionSupportingContent: (@Composable () -> Unit)? = null,
) {
    // Create local ViewModel instance with repositories for PR and velocity-1RM lookups
    val velocityOneRepMaxRepository: VelocityOneRepMaxRepository = koinInject()
    val baselineRepository: ProfileExerciseBaselineRepository = koinInject()
    val completedSetRepository: CompletedSetRepository = koinInject()
    val viewModel = remember {
        ExerciseConfigViewModel(
            personalRecordRepository = personalRecordRepository,
            velocityOneRepMaxRepository = velocityOneRepMaxRepository,
            baselineRepository = baselineRepository,
            completedSetRepository = completedSetRepository,
        )
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.clear()
        }
    }
    val userProfileRepository: UserProfileRepository = koinInject()
    val activeProfile by userProfileRepository.activeProfile.collectAsState()
    val activeProfileId = activeProfile?.id ?: "default"

    var images by remember { mutableStateOf<List<ExerciseImageEntity>>(emptyList()) }
    LaunchedEffect(exercise.exercise.id) {
        exercise.exercise.id?.let { exerciseId ->
            try {
                images = exerciseRepository.getImages(exerciseId)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                // Handle error - images will remain empty
            }
        }
    }

    // Initialize the ViewModel - PR loading is now handled internally by the ViewModel
    LaunchedEffect(exercise, weightUnit, activeProfileId) {
        viewModel.initialize(exercise, weightUnit, kgToDisplay, displayToKg, profileId = activeProfileId)
    }

    // Collect state from the ViewModel
    val exerciseType by viewModel.exerciseType.collectAsState()
    val setMode by viewModel.setMode.collectAsState()
    val sets by viewModel.sets.collectAsState()
    val selectedMode by viewModel.selectedMode.collectAsState()
    val weightChange by viewModel.weightChange.collectAsState()
    val rest by viewModel.rest.collectAsState()
    val perSetRestTime by viewModel.perSetRestTime.collectAsState()
    val eccentricLoad by viewModel.eccentricLoad.collectAsState()
    val echoLevel by viewModel.echoLevel.collectAsState()
    val stallDetectionEnabled by viewModel.stallDetectionEnabled.collectAsState()
    val repCountTiming by viewModel.repCountTiming.collectAsState()
    val stopAtTop by viewModel.stopAtTop.collectAsState()
    val dropSetEnabled by viewModel.dropSetEnabled.collectAsState()
    val dropSetMinWeightKg by viewModel.dropSetMinWeightKg.collectAsState()
    val isDropSetMinWeightValid by viewModel.isDropSetMinWeightValid.collectAsState()
    val defaultRackItemIds by viewModel.defaultRackItemIds.collectAsState()
    val rackBehaviorOverrides by viewModel.rackBehaviorOverrides.collectAsState()

    // Warm-up sets state (Issue #30)
    val warmupSets by viewModel.warmupSets.collectAsState()

    // PR weight from ViewModel - automatically updates when mode changes
    val currentExercisePR by viewModel.currentExercisePR.collectAsState()

    // PR percentage scaling state (Issue #57)
    val usePercentOfPR by viewModel.usePercentOfPR.collectAsState()
    val weightPercentOfPR by viewModel.weightPercentOfPR.collectAsState()
    // Issue #517: per-exercise scaling basis selector
    val scalingBasis by viewModel.scalingBasis.collectAsState()
    val prTypeForScaling by viewModel.prTypeForScaling.collectAsState()

    // Additional baselines for the 3-way scaling basis (Issue #517 gating + preview fix)
    val currentMaxVolumePR by viewModel.currentMaxVolumePR.collectAsState()
    val routineScalingBaseline by viewModel.routineScalingBaseline.collectAsState()
    val baselineLoadStatus by viewModel.baselineLoadStatus.collectAsState()
    val baselineResolutionReason by viewModel.baselineResolutionReason.collectAsState()
    val estimatedOneRepMaxBaselineAvailable by viewModel.estimatedOneRepMaxBaselineAvailable.collectAsState()

    // The shared resolver is authoritative for ESTIMATED_1RM so a rejected velocity value
    // cannot reappear through an editor-only fallback.
    val effectiveScalingBasis = scalingBasis ?: ScalingBasis.fromPrType(prTypeForScaling)
    val maxWeightBaselineKg = currentExercisePR?.weightPerCableKg?.takeIf { it > 0 }
    val maxVolumeBaselineKg = currentMaxVolumePR?.weightPerCableKg?.takeIf { it > 0 }
    val sharedBaseline = routineScalingBaseline
        ?.takeIf { it.basis == effectiveScalingBasis }
        ?.takeIf { it.weightPerCableKg.isFinite() && it.weightPerCableKg > 0f }
    val sharedBaselineKg = sharedBaseline?.weightPerCableKg
    val baselineWeightKg: Float? = sharedBaselineKg ?: when (effectiveScalingBasis) {
        ScalingBasis.MAX_WEIGHT_PR -> maxWeightBaselineKg
        ScalingBasis.MAX_VOLUME_PR -> maxVolumeBaselineKg
        ScalingBasis.ESTIMATED_1RM -> null
    }
    val baselineSourceMessage = when {
        effectiveScalingBasis == ScalingBasis.ESTIMATED_1RM && sharedBaseline != null -> {
            val ageNote = if (
                sharedBaseline.source == RoutineScalingBaselineSource.VELOCITY_ESTIMATE ||
                sharedBaseline.source == RoutineScalingBaselineSource.REP_DERIVED_ESTIMATE
            ) {
                sharedBaseline.sourceAgeDays?.let { stringResource(Res.string.exercise_edit_days_old, it) }.orEmpty()
            } else {
                ""
            }
            stringResource(
                Res.string.exercise_edit_using_baseline_source,
                sharedBaseline.source.displayLabel,
                ageNote,
                sharedBaseline.resolutionReason.orEmpty(),
            )
        }

        effectiveScalingBasis == ScalingBasis.ESTIMATED_1RM && baselineLoadStatus == ScalingBaselineLoadStatus.LOADING ->
            stringResource(Res.string.exercise_edit_checking_1rm)

        effectiveScalingBasis == ScalingBasis.ESTIMATED_1RM && baselineLoadStatus == ScalingBaselineLoadStatus.UNAVAILABLE ->
            stringResource(Res.string.exercise_edit_no_valid_1rm, baselineResolutionReason.orEmpty())

        sharedBaseline?.source == RoutineScalingBaselineSource.CROSS_MODE_PR ->
            sharedBaseline.sourceMode?.let { sourceMode ->
                val basisLabel = stringResource(
                    when (effectiveScalingBasis) {
                        ScalingBasis.MAX_WEIGHT_PR -> Res.string.exercise_edit_basis_max_weight
                        ScalingBasis.MAX_VOLUME_PR -> Res.string.exercise_edit_basis_max_volume
                        ScalingBasis.ESTIMATED_1RM -> Res.string.exercise_edit_basis_est_1rm
                    },
                )
                stringResource(
                    Res.string.exercise_edit_cross_mode_pr,
                    selectedMode.displayName,
                    basisLabel,
                    sourceMode.toString(),
                )
            }

        else -> null
    }
    // True if a baseline exists for AT LEAST ONE basis. Used to gate the % toggle and
    // warning so the user can enable scaling and switch to a basis that has data, rather
    // than being locked out when only the currently-selected basis lacks a baseline (#517).
    // The ViewModel computes this from the current-basis baseline, either PR, and an
    // ESTIMATED_1RM resolution probe, so a user whose only 1RM source is a velocity or
    // rep-derived estimate can still reach the Est. 1RM basis.
    val hasAnyBaseline = maxWeightBaselineKg != null ||
        maxVolumeBaselineKg != null ||
        sharedBaselineKg != null ||
        estimatedOneRepMaxBaselineAvailable

    val weightSuffix = stringResource(if (weightUnit == WeightUnit.LB) Res.string.label_lbs else Res.string.label_kg)
    val maxWeight = kgToDisplay(planningMaxWeightPerCableKg, weightUnit)
    // Issue #266/#410: Use configured increment if provided, otherwise default for unit
    val weightStep = if (weightStepOverride > 0f) {
        kgToDisplay(weightStepOverride, weightUnit)
    } else {
        if (weightUnit == WeightUnit.LB) 0.5f else 0.25f
    }
    // KD-9 / F-020: the per-rep progression slider now offers only what may actually be
    // commanded. Values are whole display units, so LB rounds DOWN (6 lb = 2.72 kg) rather
    // than offering a step the command-resolution clamp would immediately cap.
    val maxWeightChange = kgToDisplay(CommandLimits.MAX_PROGRESSION_KG, weightUnit).toInt()
    val showCableOnlyExerciseControls = shouldShowCableOnlyExerciseControls(exerciseType)
    val isTutMode = showCableOnlyExerciseControls &&
        (selectedMode is WorkoutMode.TUT || selectedMode is WorkoutMode.TUTBeast)
    val isEchoMode = showCableOnlyExerciseControls && selectedMode is WorkoutMode.Echo

    LaunchedEffect(rackItems, defaultRackItemIds) {
        val enabledRackIds = rackItems.filter { it.enabled }.map { it.id }.toSet()
        val filtered = defaultRackItemIds.filter { it in enabledRackIds }
        if (filtered != defaultRackItemIds) {
            viewModel.onDefaultRackItemIdsChange(filtered)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val dismissSheet: () -> Unit = {
        scope.launch {
            sheetState.hide()
            viewModel.onDismiss()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.onDismiss()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
        ) {
            PhoenixSheetHeader(
                title = stringResource(Res.string.exercise_edit_title),
                subtitle = exercise.exercise.displayName,
                onClose = dismissSheet,
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Scrollable content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                if (enableVideoPlayback && images.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    ) {
                        ExerciseDemoImage(
                            imageUrls = images.map { it.url },
                            modifier = Modifier.fillMaxSize(),
                            contentDescription = exercise.exercise.displayName,
                        )
                    }
                }

                // Personal Record Display
                currentExercisePR?.let { pr ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                            ) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = stringResource(Res.string.cd_personal_record),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp),
                                )
                                Column {
                                    Text(
                                        stringResource(
                                            Res.string.exercise_edit_pr_title,
                                            pr.phase.displayLabel(),
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Text(
                                        stringResource(
                                            Res.string.exercise_edit_pr_summary,
                                            formatWeight(pr.weightPerCableKg, weightUnit),
                                            pr.reps,
                                        ),
                                        style = MaterialTheme.typography.titleMedium.scaledForWeightValue(LocalWeightTextScale.current),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }

                // Mode Selector — leads the sheet because it gates which controls appear
                // (TUT Beast toggle, Echo/Eccentric selectors, Weight Change Per Rep visibility).
                if (showCableOnlyExerciseControls) {
                    ModeSelector(
                        selectedMode = selectedMode,
                        onModeChange = viewModel::onSelectedModeChange,
                    )
                }

                // TUT Beast toggle
                if (isTutMode) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                        shadowElevation = 2.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.small),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(Res.string.exercise_edit_beast_mode),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Switch(
                                checked = selectedMode is WorkoutMode.TUTBeast,
                                onCheckedChange = { isBeast ->
                                    viewModel.onSelectedModeChange(if (isBeast) WorkoutMode.TUTBeast else WorkoutMode.TUT)
                                },
                            )
                        }
                    }
                }

                // Echo Mode options
                if (isEchoMode) {
                    EccentricLoadSelector(
                        eccentricLoad = eccentricLoad,
                        onLoadChange = viewModel::onEccentricLoadChange,
                    )
                    EchoLevelSelector(
                        level = echoLevel,
                        onLevelChange = viewModel::onEchoLevelChange,
                    )
                }

                // Set Mode Toggle — mode now leads (above) because it gates dependent controls;
                // sets & reps follow immediately after mode selection (routines-1).
                if (showCableOnlyExerciseControls) {
                    SetModeToggle(
                        setMode = setMode,
                        onModeChange = viewModel::onSetModeChange,
                    )
                }

                // Sets Configuration — follows mode selection; mode leads because it gates
                // dependent controls, and most-edited set/rep fields follow right after (routines-1).
                SetsConfiguration(
                    sets = sets,
                    setMode = setMode,
                    exerciseType = exerciseType,
                    weightSuffix = weightSuffix,
                    maxWeight = maxWeight,
                    weightStep = weightStep,
                    isEchoMode = isEchoMode,
                    perSetRestTime = perSetRestTime,
                    onRepsChange = viewModel::updateReps,
                    onWeightChange = viewModel::updateWeight,
                    onDurationChange = viewModel::updateDuration,
                    onRestChange = viewModel::updateRestTime,
                    onAddSet = viewModel::addSet,
                    onDeleteSet = viewModel::deleteSet,
                    onRepeatCountChange = viewModel::onRepeatCountChange, // Issue #667
                )

                // Per Set Rest Time toggle — immediately follows SetsConfiguration so rest
                // config lives in the same primary zone as the sets it applies to.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    shadowElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.small),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.exercise_edit_per_set_rest),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (perSetRestTime) FontWeight.Bold else FontWeight.Normal,
                            color = if (perSetRestTime) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Switch(
                            checked = perSetRestTime,
                            onCheckedChange = viewModel::onPerSetRestTimeChange,
                        )
                    }
                }

                // Single rest time picker — kept adjacent to the Per Set Rest Time toggle
                // (only visible when per-set rest is off).
                if (!perSetRestTime) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                        shadowElevation = 2.dp,
                    ) {
                        Column(modifier = Modifier.padding(Spacing.small)) {
                            Text(
                                stringResource(Res.string.exercise_edit_rest_time_seconds, rest),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = Spacing.extraSmall),
                            )
                            ExpressiveSlider(
                                value = rest.toFloat(),
                                onValueChange = { viewModel.onRestChange(it.toInt()) },
                                valueRange = 0f..300f,
                                steps = 59,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                EquipmentRackSelectionCard(
                    rackItems = rackItems,
                    activeRackItemIds = defaultRackItemIds,
                    behaviorOverrides = rackBehaviorOverrides,
                    weightUnit = weightUnit,
                    formatWeight = formatWeight,
                    onSelectionChange = viewModel::onDefaultRackItemIdsChange,
                    onBehaviorOverrideChange = viewModel::onRackBehaviorOverridesChange,
                )

                // Weight Configuration Section (PR Percentage Scaling - Issue #57)
                // Only show for standard exercises (not bodyweight)
                if (showCableOnlyExerciseControls) {
                    WeightConfigurationCard(
                        usePercentOfPR = usePercentOfPR,
                        weightPercentOfPR = weightPercentOfPR,
                        currentExercisePR = currentExercisePR,
                        effectiveScalingBasis = effectiveScalingBasis,
                        baselineWeightKg = baselineWeightKg,
                        hasAnyBaseline = hasAnyBaseline,
                        baselineSourceMessage = baselineSourceMessage,
                        baselineSourceLabel = sharedBaseline?.source?.displayLabel,
                        weightUnit = weightUnit,
                        formatWeight = formatWeight,
                        onUsePercentOfPRChange = viewModel::onUsePercentOfPRChange,
                        onWeightPercentOfPRChange = viewModel::onWeightPercentOfPRChange,
                        onScalingBasisChange = viewModel::onScalingBasisChange,
                    )
                }

                // Weight Change Per Rep
                if (showCableOnlyExerciseControls && !isEchoMode) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium),
                        ) {
                            Text(
                                stringResource(Res.string.exercise_edit_weight_change_per_rep),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(Spacing.medium))

                            ProgressionSlider(
                                value = weightChange.toFloat(),
                                onValueChange = { viewModel.onWeightChange(it.roundToInt()) },
                                valueRange = -maxWeightChange.toFloat()..maxWeightChange.toFloat(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(Res.string.exercise_edit_weight_change_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.small),
                            )
                        }
                    }
                }

                if (showCableOnlyExerciseControls) {
                    // Stall Detection toggle
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                        shadowElevation = 2.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.small),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Res.string.exercise_edit_stall_detection_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (stallDetectionEnabled) FontWeight.Bold else FontWeight.Normal,
                                    color = if (stallDetectionEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(Res.string.exercise_edit_stall_detection_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = stallDetectionEnabled,
                                onCheckedChange = viewModel::onStallDetectionEnabledChange,
                            )
                        }
                    }

                    if (selectedMode is WorkoutMode.OldSchool) {
                        DropSetRoutineConfigurationCard(
                            dropSetEnabled = dropSetEnabled,
                            dropSetMinWeightKg = dropSetMinWeightKg,
                            isDropSetMinWeightValid = isDropSetMinWeightValid,
                            weightUnit = weightUnit,
                            weightSuffix = weightSuffix,
                            kgToDisplay = kgToDisplay,
                            displayToKg = displayToKg,
                            onDropSetEnabledChange = viewModel::onDropSetEnabledChange,
                            onDropSetMinWeightKgChange = viewModel::onDropSetMinWeightKgChange,
                        )
                    }

                    // Rep Count Timing toggle
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                        shadowElevation = 2.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.small),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Res.string.rep_count_timing),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (repCountTiming == RepCountTiming.TOP) FontWeight.Bold else FontWeight.Normal,
                                    color = if (repCountTiming == RepCountTiming.TOP) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(
                                        if (repCountTiming == RepCountTiming.TOP) {
                                            Res.string.rep_count_timing_top
                                        } else {
                                            Res.string.rep_count_timing_bottom
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = repCountTiming == RepCountTiming.TOP,
                                onCheckedChange = { isTop ->
                                    viewModel.onRepCountTimingChange(
                                        if (isTop) RepCountTiming.TOP else RepCountTiming.BOTTOM,
                                    )
                                },
                            )
                        }
                    }
                }

                // Stop at Top toggle - hidden for bodyweight and fully AMRAP exercises
                if (shouldShowStopAtTopToggle(exerciseType, sets)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                        shadowElevation = 2.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.small),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Res.string.exercise_edit_stop_at_top_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (stopAtTop) FontWeight.Bold else FontWeight.Normal,
                                    color = if (stopAtTop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(
                                        if (stopAtTop) {
                                            Res.string.exercise_edit_stop_at_top_desc
                                        } else {
                                            Res.string.exercise_edit_stop_at_bottom_desc
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = stopAtTop,
                                onCheckedChange = viewModel::onStopAtTopChange,
                            )
                        }
                    }
                }

                // Warm-up Sets Configuration (Issue #30)
                // Only show for standard exercises (not bodyweight)
                if (showCableOnlyExerciseControls) {
                    WarmupSetsConfiguration(
                        warmupSets = warmupSets,
                        workingWeight = sets.firstOrNull()?.weightPerCable ?: 0f,
                        weightSuffix = weightSuffix,
                        onAddWarmupSet = viewModel::addWarmupSet,
                        onRemoveWarmupSet = viewModel::removeWarmupSet,
                        onUpdateReps = viewModel::updateWarmupSetReps,
                        onUpdatePercent = viewModel::updateWarmupSetPercent,
                        onApplyPreset = viewModel::applyClassicWarmupPreset,
                        onClearAll = viewModel::clearWarmupSets,
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            primaryActionSupportingContent?.invoke()

            // Bottom actions
            ExerciseEditBottomActions(
                buttonText = buttonText,
                enabled = sets.isNotEmpty() && primaryActionEnabled && isDropSetMinWeightValid,
                onCancel = dismissSheet,
                onSave = { viewModel.onSave(onSave) },
            )
        }
    }
}

/**
 * Issue #1358 visual QA F2: Configure Exercise's bottom Cancel / primary-action
 * pair. The buttons keep a 56.dp floor but grow with their label (heightIn),
 * so an accessibility-sized two-line "Start Workout" label is fully visible
 * instead of being clipped by a fixed-height button.
 */
@Composable
internal fun ExerciseEditBottomActions(
    buttonText: String,
    enabled: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                stringResource(Res.string.action_cancel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Button(
            onClick = onSave,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = MaterialTheme.shapes.medium,
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 4.dp,
                pressedElevation = 2.dp,
            ),
        ) {
            Text(
                if (buttonText == "Save") stringResource(Res.string.action_save) else buttonText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

internal fun shouldShowCableOnlyExerciseControls(exerciseType: ExerciseType): Boolean = exerciseType == ExerciseType.STANDARD

internal fun shouldShowStopAtTopToggle(exerciseType: ExerciseType, sets: List<SetConfiguration>): Boolean = shouldShowCableOnlyExerciseControls(exerciseType) && sets.any { it.reps != null }

@Composable
private fun SetModeToggle(
    setMode: SetMode,
    onModeChange: (SetMode) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(
            stringResource(Res.string.exercise_edit_set_mode),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = Spacing.extraSmall),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = setMode == SetMode.REPS,
                onClick = { onModeChange(SetMode.REPS) },
                label = { Text(stringResource(Res.string.label_reps)) },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = setMode == SetMode.DURATION,
                onClick = { onModeChange(SetMode.DURATION) },
                label = { Text(stringResource(Res.string.label_duration)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SetsConfiguration(
    sets: List<SetConfiguration>,
    setMode: SetMode,
    exerciseType: ExerciseType,
    weightSuffix: String,
    maxWeight: Float,
    weightStep: Float = 0.5f,
    isEchoMode: Boolean = false,
    perSetRestTime: Boolean = false,
    onRepsChange: (String, Int?) -> Unit,
    onWeightChange: (String, Float) -> Unit,
    onDurationChange: (String, Int) -> Unit,
    onRestChange: (String, Int) -> Unit,
    onAddSet: () -> Unit,
    onDeleteSet: (Int) -> Unit,
    onRepeatCountChange: (String, Int) -> Unit = { _, _ -> }, // Issue #667
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(
            stringResource(
                Res.string.exercise_edit_sets_and_target,
                stringResource(if (setMode == SetMode.REPS) Res.string.label_reps else Res.string.label_duration),
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = Spacing.extraSmall),
        )

        sets.forEachIndexed { index, setConfig ->
            key(setConfig.id) {
                SetRow(
                    setConfig = setConfig,
                    setMode = setMode,
                    exerciseType = exerciseType,
                    weightSuffix = weightSuffix,
                    maxWeight = maxWeight,
                    weightStep = weightStep,
                    isEchoMode = isEchoMode,
                    canDelete = sets.size > 1,
                    onRepsChange = { newReps -> onRepsChange(setConfig.id, newReps) },
                    onWeightChange = { newWeight -> onWeightChange(setConfig.id, newWeight) },
                    onDurationChange = { newDuration -> onDurationChange(setConfig.id, newDuration) },
                    onRestChange = { newRest -> onRestChange(setConfig.id, newRest) },
                    onDelete = { onDeleteSet(index) },
                    perSetRestTime = perSetRestTime,
                    repeatCount = setConfig.repeatCount, // Issue #667
                    onRepeatCountChange = { newCount -> onRepeatCountChange(setConfig.id, newCount) }, // Issue #667
                )
            }
        }

        // Issue #667: Total expanded sets preview
        if (sets.any { it.repeatCount > 1 }) {
            val total = sets.sumOf { it.repeatCount.coerceIn(1, 20) }
            Text(
                stringResource(Res.string.exercise_edit_total_sets_expanded, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(vertical = Spacing.extraSmall),
            )
        }

        // Add Set button
        OutlinedButton(
            onClick = onAddSet,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(Res.string.cd_add_set),
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(Spacing.small))
            Text(
                stringResource(Res.string.exercise_edit_add_set),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun SetRow(
    setConfig: SetConfiguration,
    setMode: SetMode,
    exerciseType: ExerciseType,
    weightSuffix: String,
    maxWeight: Float,
    weightStep: Float = 0.5f,
    isEchoMode: Boolean = false,
    canDelete: Boolean,
    onRepsChange: (Int?) -> Unit,
    onWeightChange: (Float) -> Unit,
    onDurationChange: (Int) -> Unit,
    onRestChange: (Int) -> Unit,
    onDelete: () -> Unit,
    perSetRestTime: Boolean = false,
    repeatCount: Int = 1, // Issue #667
    onRepeatCountChange: (Int) -> Unit = {}, // Issue #667
) {
    val weightTextScale = LocalWeightTextScale.current
    val fontScale = LocalDensity.current.fontScale
    val isCompactWidth = LocalWindowSizeClass.current.widthSizeClass == WindowWidthSizeClass.Compact
    val stackPickers = shouldStackExerciseSetPickers(weightTextScale, fontScale, isCompactWidth)
    val showPickerLabels = stackPickers || setConfig.setNumber == 1

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
        ) {
            // Set label and Delete button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.set_ready_set_numbered, setConfig.setNumber),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                // Issue #667: ×N badge for repeated sets
                if (repeatCount > 1) {
                    Text(
                        " ×$repeatCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                IconButton(
                    onClick = onDelete,
                    enabled = canDelete,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.cd_delete_set),
                        tint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // AMRAP toggle
            if (setMode == SetMode.REPS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(
                        checked = setConfig.reps == null,
                        onCheckedChange = { isAMRAP ->
                            onRepsChange(if (isAMRAP) null else 10)
                        },
                    )
                    Text(
                        text = stringResource(Res.string.exercise_edit_amrap_full),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (setConfig.reps == null) FontWeight.Bold else FontWeight.Normal,
                        color = if (setConfig.reps == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))
            }

            // Issue #667: Repeat counter
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                if (!stackPickers) {
                    Text(
                        stringResource(Res.string.exercise_edit_repeat_colon),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                CompactNumberPicker(
                    value = repeatCount,
                    onValueChange = onRepeatCountChange,
                    range = 1..20,
                    label = if (stackPickers) stringResource(Res.string.exercise_edit_repeat) else "",
                    suffix = "x",
                    modifier = if (stackPickers) Modifier.fillMaxWidth() else Modifier,
                    applySelectorScale = true,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            val spacerHeight = when {
                stackPickers -> Spacing.extraSmall
                setConfig.setNumber == 1 -> 60.dp
                else -> 80.dp
            }
            val repsOrDurationSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                Box(modifier = slotModifier) {
                    if (setMode == SetMode.REPS) {
                        if (setConfig.reps != null) {
                            CompactNumberPicker(
                                value = setConfig.reps,
                                onValueChange = onRepsChange,
                                range = 1..50,
                                label = if (showPickerLabels) stringResource(Res.string.label_reps) else "",
                                suffix = stringResource(Res.string.label_reps_suffix),
                                modifier = Modifier.fillMaxWidth(),
                                applySelectorScale = true,
                            )
                        } else {
                            // AMRAP label
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                if (showPickerLabels) {
                                    Text(
                                        stringResource(Res.string.exercise_edit_target),
                                        style = MaterialTheme.typography.labelMedium.scaledForWeightValue(weightTextScale, fontScale),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(spacerHeight))
                                Text(
                                    stringResource(Res.string.label_amrap),
                                    style = MaterialTheme.typography.titleMedium.scaledForWeightValue(weightTextScale, fontScale),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    } else {
                        CompactNumberPicker(
                            value = setConfig.duration,
                            onValueChange = onDurationChange,
                            range = RoutineExercise.MIN_TIMED_DURATION_SECONDS..RoutineExercise.MAX_TIMED_DURATION_SECONDS,
                            label = if (showPickerLabels) stringResource(Res.string.label_duration) else "",
                            suffix = stringResource(Res.string.label_sec_suffix),
                            modifier = Modifier.fillMaxWidth(),
                            applySelectorScale = true,
                        )
                    }
                }
            }
            val weightSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                Box(modifier = slotModifier) {
                    when {
                        isEchoMode -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                if (showPickerLabels) {
                                    Text(
                                        stringResource(Res.string.exercise_edit_force_per_cable),
                                        style = MaterialTheme.typography.labelMedium.scaledForWeightValue(weightTextScale, fontScale),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(spacerHeight))
                                Text(
                                    stringResource(Res.string.hud_adaptive),
                                    style = MaterialTheme.typography.bodyMedium.scaledForWeightValue(weightTextScale),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        exerciseType == ExerciseType.STANDARD -> {
                            CompactNumberPicker(
                                value = setConfig.weightPerCable,
                                onValueChange = onWeightChange,
                                range = 1f..maxWeight,
                                step = weightStep,
                                label = if (showPickerLabels) stringResource(Res.string.exercise_edit_weight_per_cable) else "",
                                suffix = weightSuffix,
                                modifier = Modifier.fillMaxWidth(),
                                applySelectorScale = true,
                            )
                        }

                        else -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                if (showPickerLabels) {
                                    Text(
                                        stringResource(Res.string.label_weight),
                                        style = MaterialTheme.typography.labelMedium.scaledForWeightValue(weightTextScale, fontScale),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(spacerHeight))
                                Text(
                                    stringResource(Res.string.label_bodyweight),
                                    style = MaterialTheme.typography.bodyMedium.scaledForWeightValue(weightTextScale),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (stackPickers) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    repsOrDurationSlot(Modifier.fillMaxWidth())
                    weightSlot(Modifier.fillMaxWidth())
                }
            } else {
                // Reps/Duration and Weight side-by-side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    repsOrDurationSlot(Modifier.weight(1f))
                    weightSlot(Modifier.weight(1f))
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Rest Time picker (per-set)
            if (perSetRestTime) {
                Text(
                    stringResource(Res.string.exercise_edit_rest_time_seconds, setConfig.restSeconds),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = Spacing.extraSmall),
                )
                ExpressiveSlider(
                    value = setConfig.restSeconds.toFloat(),
                    onValueChange = { onRestChange(it.toInt()) },
                    valueRange = 0f..300f,
                    steps = 59,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(
    selectedMode: WorkoutMode,
    onModeChange: (WorkoutMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val allModes = listOf(
        WorkoutMode.OldSchool,
        WorkoutMode.Pump,
        WorkoutMode.TUT,
        WorkoutMode.EccentricOnly,
        WorkoutMode.Echo(EchoLevel.HARDER),
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            Text(
                stringResource(Res.string.exercise_edit_workout_mode),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = Spacing.small),
            )

            // Use Box with DropdownMenu for multiplatform compatibility
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = selectedMode.displayName,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(if (expanded) Res.string.cd_collapse else Res.string.cd_expand),
                            )
                        }
                    },
                    interactionSource = remember { MutableInteractionSource() }
                        .also { interactionSource ->
                            LaunchedEffect(interactionSource) {
                                interactionSource.interactions.collect { interaction ->
                                    if (interaction is PressInteraction.Release) {
                                        expanded = !expanded
                                    }
                                }
                            }
                        },
                )

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f),
                ) {
                    allModes.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.displayName) },
                            onClick = {
                                onModeChange(mode)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EccentricLoadSelector(
    eccentricLoad: EccentricLoad,
    onLoadChange: (EccentricLoad) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
        ) {
            Text(
                stringResource(Res.string.eccentric_load),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Spacing.small))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = eccentricLoad.displayName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    EccentricLoad.entries.forEach { load ->
                        DropdownMenuItem(
                            text = { Text(load.displayName) },
                            onClick = {
                                onLoadChange(load)
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            Text(
                stringResource(Res.string.exercise_edit_eccentric_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EchoLevelSelector(
    level: EchoLevel,
    onLevelChange: (EchoLevel) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
        ) {
            Text(
                stringResource(Res.string.echo_level),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Spacing.small))

            ResponsiveSegmentedSelector(
                options = EchoLevel.entries,
                selectedOption = level,
                onSelect = onLevelChange,
                labelForOption = { echoLevelLabel(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Weight Configuration Card - allows toggling between absolute weight and percentage-of-PR
 * Issue #57: PR percentage scaling feature
 */
@Composable
internal fun WeightConfigurationCard(
    usePercentOfPR: Boolean,
    weightPercentOfPR: Int,
    currentExercisePR: PersonalRecord?,
    /**
     * The basis actually in effect (already derived via effectiveScalingBasis, i.e.
     * scalingBasis ?: fromPrType(prTypeForScaling)) so legacy rows select the right basis.
     */
    effectiveScalingBasis: ScalingBasis = ScalingBasis.MAX_WEIGHT_PR,
    /**
     * Resolved baseline weight (kg/cable) for the currently-selected scaling basis.
     * When non-null the resolved-weight preview is shown and uses this weight.
     * Callers should mirror ResolveRoutineWeightsUseCase's resolution order so the
     * editor preview matches what the workout will actually start from.
     */
    baselineWeightKg: Float? = null,
    /**
     * True if a baseline exists for at least one scaling basis. Gates the % toggle and
     * the warning so the user can enable scaling and switch to a basis that has data,
     * even when the currently-selected basis lacks a baseline (Issue #517).
     */
    hasAnyBaseline: Boolean = baselineWeightKg != null,
    baselineSourceMessage: String? = null,
    baselineSourceLabel: String? = null,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onUsePercentOfPRChange: (Boolean) -> Unit,
    onWeightPercentOfPRChange: (Int) -> Unit,
    onScalingBasisChange: (ScalingBasis) -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
        ) {
            Text(
                stringResource(Res.string.exercise_edit_weight_config),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Toggle between absolute weight and % of PR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.exercise_edit_use_percent_pr),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (usePercentOfPR) FontWeight.Bold else FontWeight.Normal,
                        color = if (usePercentOfPR) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    // Subtitle: describes which baseline will be used
                    Text(
                        text = when {
                            baselineWeightKg != null && effectiveScalingBasis == ScalingBasis.ESTIMATED_1RM ->
                                stringResource(
                                    Res.string.exercise_edit_scale_from_1rm,
                                    baselineSourceLabel ?: stringResource(Res.string.exercise_edit_selected_1rm_baseline),
                                )

                            baselineWeightKg != null && effectiveScalingBasis == ScalingBasis.MAX_VOLUME_PR ->
                                stringResource(Res.string.exercise_edit_scale_from_max_volume)

                            baselineWeightKg != null && effectiveScalingBasis == ScalingBasis.MAX_WEIGHT_PR ->
                                stringResource(Res.string.exercise_edit_scale_from_max_weight)

                            currentExercisePR != null ->
                                stringResource(
                                    Res.string.exercise_edit_scale_from_phase_pr,
                                    currentExercisePR.phase.displayLabel().lowercase(),
                                )

                            else ->
                                stringResource(Res.string.exercise_edit_no_baseline_selected)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    baselineSourceMessage?.let { message ->
                        Spacer(modifier = Modifier.height(Spacing.extraSmall))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Switch(
                    checked = usePercentOfPR,
                    onCheckedChange = onUsePercentOfPRChange,
                    // Enable the toggle whenever a baseline exists for ANY basis — the user
                    // can then switch to the basis that has data (fixes ESTIMATED_1RM lockout).
                    // If scaling was seeded by routine defaults before a baseline exists,
                    // keep the switch reversible so saving does not leave a latent % rule.
                    enabled = hasAnyBaseline || usePercentOfPR,
                )
            }

            // When scaling is ON, the basis selector is ALWAYS available so the user can
            // switch to a basis that has data; the resolved-weight preview only appears when
            // the currently-selected basis actually has a baseline (Issue #517).
            val effectiveBasisForControls = effectiveScalingBasis
            if (usePercentOfPR) {
                // Issue #517: 3-way scaling basis selector (always shown while scaling enabled)
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(
                    text = stringResource(Res.string.exercise_edit_scale_from),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.extraSmall))
                val basisOptions = listOf(
                    ScalingBasis.MAX_WEIGHT_PR to stringResource(Res.string.exercise_edit_basis_max_weight),
                    ScalingBasis.MAX_VOLUME_PR to stringResource(Res.string.exercise_edit_basis_max_volume),
                    ScalingBasis.ESTIMATED_1RM to stringResource(Res.string.exercise_edit_basis_est_1rm),
                )
                ResponsiveSegmentedSelector(
                    options = basisOptions.map { it.first },
                    selectedOption = effectiveBasisForControls,
                    onSelect = onScalingBasisChange,
                    labelForOption = { basis -> basisOptions.first { it.first == basis }.second },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (baselineWeightKg != null) {
                    Spacer(modifier = Modifier.height(Spacing.medium))

                    // Resolved weight from the selected basis's baseline (not always max-weight PR)
                    val resolvedWeight = (baselineWeightKg * weightPercentOfPR / 100f)
                        .let { (it * 2).roundToInt() / 2f } // Round to 0.5kg
                    val weightTextScale = LocalWeightTextScale.current
                    val fontScale = LocalDensity.current.fontScale
                    val effectiveScale = effectiveWeightTextScale(weightTextScale, fontScale)
                    val isCompactWidth = LocalWindowSizeClass.current.widthSizeClass == WindowWidthSizeClass.Compact

                    val percentageLabelText: @Composable () -> Unit = {
                        Text(
                            text = when (effectiveBasisForControls) {
                                ScalingBasis.ESTIMATED_1RM -> stringResource(
                                    Res.string.exercise_edit_percent_of_basis,
                                    weightPercentOfPR,
                                    stringResource(Res.string.exercise_edit_basis_est_1rm),
                                )

                                ScalingBasis.MAX_VOLUME_PR -> stringResource(
                                    Res.string.exercise_edit_percent_of_basis,
                                    weightPercentOfPR,
                                    stringResource(Res.string.exercise_edit_basis_max_volume),
                                )

                                ScalingBasis.MAX_WEIGHT_PR -> stringResource(
                                    Res.string.exercise_edit_percent_of_phase_pr,
                                    weightPercentOfPR,
                                    currentExercisePR?.phase?.displayLabel()
                                        ?: stringResource(Res.string.exercise_edit_max_weight_short),
                                )
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val resolvedWeightText: @Composable () -> Unit = {
                        Text(
                            text = stringResource(
                                Res.string.exercise_edit_resolved_per_cable,
                                formatWeight(resolvedWeight, weightUnit),
                            ),
                            style = MaterialTheme.typography.titleMedium.scaledForWeightValue(weightTextScale, fontScale),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Display current percentage and resolved weight
                    if (effectiveScale > 1.0f || (isCompactWidth && fontScale >= 1.15f)) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            percentageLabelText()
                            resolvedWeightText()
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            percentageLabelText()
                            resolvedWeightText()
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.small))

                    // Percentage slider (50% - 120%, 5% increments)
                    ExpressiveSlider(
                        value = weightPercentOfPR.toFloat(),
                        onValueChange = { onWeightPercentOfPRChange(it.toInt()) },
                        valueRange = 50f..120f,
                        steps = 13, // (120-50)/5 - 1 = 13 steps for 5% increments
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(Spacing.small))

                    // Common preset buttons wrapped cleanly for narrow/scaled widths
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        listOf(70, 80, 90, 100).forEach { percent ->
                            FilterChip(
                                selected = weightPercentOfPR == percent,
                                onClick = { onWeightPercentOfPRChange(percent) },
                                label = { Text(stringResource(Res.string.percent_label, percent)) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                } else {
                    // Selected basis has no baseline — guide the user to pick one that does.
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Text(
                        text = stringResource(Res.string.exercise_edit_no_data_for_basis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Show warning when no baseline is available for any basis and toggle is off
            if (!hasAnyBaseline && !usePercentOfPR) {
                Spacer(modifier = Modifier.height(Spacing.small))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.small),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(Res.string.exercise_edit_complete_workout_for_scaling),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DropSetRoutineConfigurationCard(
    dropSetEnabled: Boolean,
    dropSetMinWeightKg: Float?,
    isDropSetMinWeightValid: Boolean,
    weightUnit: WeightUnit,
    weightSuffix: String,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    onDropSetEnabledChange: (Boolean) -> Unit,
    onDropSetMinWeightKgChange: (Float?) -> Unit,
) {
    var dropSetMinWeightText by remember {
        mutableStateOf(formatDropSetMinWeightInput(dropSetMinWeightKg, weightUnit, kgToDisplay))
    }
    LaunchedEffect(dropSetMinWeightKg, weightUnit) {
        val parsedKg = parseLocalizedDecimal(dropSetMinWeightText)?.let { displayToKg(it, weightUnit) }
        if (parsedKg != dropSetMinWeightKg) {
            dropSetMinWeightText = formatDropSetMinWeightInput(dropSetMinWeightKg, weightUnit, kgToDisplay)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.drop_set_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (dropSetEnabled) FontWeight.Bold else FontWeight.Normal,
                        color = if (dropSetEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = stringResource(Res.string.drop_set_offer_toggle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = dropSetEnabled,
                    onCheckedChange = onDropSetEnabledChange,
                )
            }

            if (dropSetEnabled) {
                OutlinedTextField(
                    value = dropSetMinWeightText,
                    onValueChange = { value ->
                        dropSetMinWeightText = value
                        onDropSetMinWeightKgChange(
                            parseLocalizedDecimal(value)?.let { displayToKg(it, weightUnit) },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.drop_set_min_weight)) },
                    singleLine = true,
                    isError = !isDropSetMinWeightValid,
                    supportingText = {
                        Text(
                            text = if (isDropSetMinWeightValid) {
                                stringResource(Res.string.drop_set_min_weight_supporting, weightSuffix)
                            } else {
                                stringResource(Res.string.drop_set_min_weight_error)
                            },
                            color = if (isDropSetMinWeightValid) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        }
    }
}

internal fun formatDropSetMinWeightInput(
    weightKg: Float?,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
): String {
    val display = weightKg?.let { kgToDisplay(it, weightUnit) } ?: return ""
    return UnitConverter.formatDecimal(display)
}

/**
 * Warm-up Sets Configuration Card (Issue #30)
 * Allows users to configure variable warm-up sets before working sets.
 * Each warm-up set has a number of reps and a percentage of the working weight.
 */
@Composable
private fun WarmupSetsConfiguration(
    warmupSets: List<WarmupSet>,
    workingWeight: Float,
    weightSuffix: String,
    onAddWarmupSet: () -> Unit,
    onRemoveWarmupSet: (Int) -> Unit,
    onUpdateReps: (Int, Int) -> Unit,
    onUpdatePercent: (Int, Int) -> Unit,
    onApplyPreset: () -> Unit,
    onClearAll: () -> Unit,
) {
    var expanded by remember { mutableStateOf(warmupSets.isNotEmpty()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(
            2.dp,
            if (warmupSets.isNotEmpty()) {
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
        ) {
            // Header with toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.exercise_edit_warmup_sets),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (warmupSets.isNotEmpty()) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        when (warmupSets.size) {
                            0 -> stringResource(Res.string.exercise_edit_warmup_empty)
                            1 -> stringResource(Res.string.exercise_edit_warmup_count_one)
                            else -> stringResource(Res.string.exercise_edit_warmup_count_other, warmupSets.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(if (expanded) Res.string.cd_collapse else Res.string.cd_expand),
                    )
                }
            }

            // Expanded content
            if (expanded) {
                Spacer(modifier = Modifier.height(Spacing.medium))

                // Quick preset button
                if (warmupSets.isEmpty()) {
                    Button(
                        onClick = onApplyPreset,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    ) {
                        Text(stringResource(Res.string.exercise_edit_warmup_preset))
                    }
                    Spacer(modifier = Modifier.height(Spacing.small))
                }

                // Warm-up sets list
                warmupSets.forEachIndexed { index, warmupSet ->
                    WarmupSetRow(
                        index = index,
                        warmupSet = warmupSet,
                        workingWeight = workingWeight,
                        weightSuffix = weightSuffix,
                        onUpdateReps = { reps -> onUpdateReps(index, reps) },
                        onUpdatePercent = { percent -> onUpdatePercent(index, percent) },
                        onRemove = { onRemoveWarmupSet(index) },
                    )
                    if (index < warmupSets.lastIndex) {
                        Spacer(modifier = Modifier.height(Spacing.small))
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Add/Clear buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    OutlinedButton(
                        onClick = onAddWarmupSet,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(Res.string.exercise_edit_add_warmup))
                    }
                    if (warmupSets.isNotEmpty()) {
                        TextButton(
                            onClick = onClearAll,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(stringResource(Res.string.clear_all))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual warm-up set row with reps picker, percentage picker, and calculated weight preview.
 */
@Composable
internal fun WarmupSetRow(
    index: Int,
    warmupSet: WarmupSet,
    workingWeight: Float,
    weightSuffix: String,
    onUpdateReps: (Int) -> Unit,
    onUpdatePercent: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val calculatedWeight = (workingWeight * warmupSet.percentOfWorking / 100f)
        .let { (it * 2).roundToInt() / 2f } // Round to 0.5
    val weightTextScale = LocalWeightTextScale.current
    val fontScale = LocalDensity.current.fontScale
    val effectiveScale = effectiveWeightTextScale(weightTextScale, fontScale)
    val calculatedWeightStyle = MaterialTheme.typography.titleMedium.scaledForWeightValue(weightTextScale, fontScale)

    val warmupLabel: @Composable () -> Unit = {
        Text(
            "W${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.width(28.dp),
        )
    }
    val repsStepper: @Composable (Modifier) -> Unit = { slotModifier ->
        Column(
            modifier = slotModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(Res.string.label_reps),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = { onUpdateReps(warmupSet.reps - 1) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    enabled = warmupSet.reps > 1,
                ) {
                    Text("-", fontWeight = FontWeight.Bold)
                }
                Text(
                    "${warmupSet.reps}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(
                    onClick = { onUpdateReps(warmupSet.reps + 1) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    enabled = warmupSet.reps < 20,
                ) {
                    Text("+", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    val percentStepper: @Composable (Modifier) -> Unit = { slotModifier ->
        Column(
            modifier = slotModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(Res.string.exercise_edit_warmup_percent_working),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = { onUpdatePercent(warmupSet.percentOfWorking - 5) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    enabled = warmupSet.percentOfWorking > 10,
                ) {
                    Text("-", fontWeight = FontWeight.Bold)
                }
                Text(
                    "${warmupSet.percentOfWorking}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                IconButton(
                    onClick = { onUpdatePercent(warmupSet.percentOfWorking + 5) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    enabled = warmupSet.percentOfWorking < 100,
                ) {
                    Text("+", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    val deleteButton: @Composable () -> Unit = {
        IconButton(
            onClick = onRemove,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(Res.string.exercise_edit_cd_remove_warmup),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    val stackWarmupRow = effectiveScale > 1.0f ||
        isCompactAccessibilityLayout() ||
        LocalWindowSizeClass.current.widthSizeClass == WindowWidthSizeClass.Compact

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (stackWarmupRow) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        warmupLabel()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "=",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "$calculatedWeight",
                                style = calculatedWeightStyle,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Text(
                                weightSuffix,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    deleteButton()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repsStepper(Modifier.weight(1f))
                    percentStepper(Modifier.weight(1f))
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.small),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                warmupLabel()
                repsStepper(Modifier.weight(1f))
                percentStepper(Modifier.weight(1f))

                // Calculated weight preview
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(Res.string.label_weight),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "$calculatedWeight",
                        style = calculatedWeightStyle,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        weightSuffix,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                deleteButton()
            }
        }
    }
}

@Composable
private fun WorkoutPhase.displayLabel(): String = stringResource(
    when (this) {
        WorkoutPhase.CONCENTRIC -> Res.string.phase_concentric
        WorkoutPhase.ECCENTRIC -> Res.string.phase_eccentric
        WorkoutPhase.COMBINED -> Res.string.phase_combined
    },
)
