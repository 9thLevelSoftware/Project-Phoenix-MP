package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlin.math.roundToInt
import com.devil.phoenixproject.domain.model.WorkoutMode
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.eccentricLoadLabel
import com.devil.phoenixproject.domain.model.echoLevelLabel
import com.devil.phoenixproject.domain.model.toWorkoutMode
import com.devil.phoenixproject.presentation.components.CompactNumberPicker
import com.devil.phoenixproject.presentation.components.ProgressionSlider
import com.devil.phoenixproject.presentation.components.RestTimePickerDialog
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.util.DurationFormatter
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.LocalWeightTextScale
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import com.devil.phoenixproject.presentation.util.currentEffectiveWeightTextScale
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.presentation.util.scaledForWeightValue
import com.devil.phoenixproject.presentation.util.useStackedWeightCardsLayout
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import com.devil.phoenixproject.util.CommandLimits
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Just Lift screen - quick workout configuration.
 * Allows user to select mode, eccentric load percentage, and progression/regression.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JustLiftScreen(navController: NavController, viewModel: MainViewModel) {
    val workoutState by viewModel.workoutState.collectAsState()
    val workoutParameters by viewModel.workoutParameters.collectAsState()
    val currentMetric by viewModel.currentMetric.collectAsState()
    val repCount by viewModel.repCount.collectAsState()
    val autoStopState by viewModel.autoStopState.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val userPreferences by viewModel.userPreferences.collectAsState()

    var selectedMode by remember { mutableStateOf(workoutParameters.programMode.toWorkoutMode(workoutParameters.echoLevel)) }
    // Initialize to match the picker's default: 1 lb = 0.453592 kg
    // Float, Int, and Boolean types are natively saveable — use rememberSaveable
    // so values survive configuration changes and iOS composition recreation (#344).
    var weightPerCable by rememberSaveable { mutableStateOf(0.453592f) }
    var weightChangePerRep by rememberSaveable { mutableStateOf(0) } // Progression/Regression value
    // Keep enum/sealed types as `remember` — they need custom Savers:
    var eccentricLoad by remember { mutableStateOf(EccentricLoad.LOAD_100) }
    var echoLevel by remember { mutableStateOf(EchoLevel.HARDER) }
    var repCountTiming by remember { mutableStateOf(RepCountTiming.TOP) }
    var stallDetectionEnabled by rememberSaveable { mutableStateOf(true) }
    var restSeconds by rememberSaveable { mutableStateOf(60) } // Rest timer between sets (0 = off)
    var defaultsProfileId by remember { mutableStateOf<String?>(null) }
    // Profile-scoped workout defaults
    val profileRepository: UserProfileRepository = koinInject()
    val activeProfileContext by profileRepository.activeProfileContext.collectAsState()
    val readyProfileId = (activeProfileContext as? ActiveProfileContext.Ready)?.profile?.id
    val defaultsLoaded = readyProfileId != null && defaultsProfileId == readyProfileId

    // Reload profile-scoped defaults only when the atomic context is Ready.
    LaunchedEffect(readyProfileId) {
        val loadingProfileId = readyProfileId ?: return@LaunchedEffect
        val loadingContext = activeProfileContext as? ActiveProfileContext.Ready
            ?: return@LaunchedEffect
        val defaults = viewModel.getJustLiftDefaults()
        // Apply saved defaults
        weightPerCable = defaults.weightPerCableKg

        // Convert stored weight change (KG) to display unit if needed
        // weightChangePerRep is already Int in viewmodel format
        weightChangePerRep = if (loadingContext.preferences.core.value.weightUnit == WeightUnit.LB) {
            kotlin.math.round(
                viewModel.kgToDisplay(defaults.weightChangePerRep.toFloat(), WeightUnit.LB),
            ).toInt()
        } else {
            defaults.weightChangePerRep
        }

        // Set mode from saved defaults
        val savedProgramMode = defaults.toProgramMode()
        selectedMode = savedProgramMode.toWorkoutMode(defaults.getEchoLevel())

        // Restore eccentric load and echo level for Echo mode
        eccentricLoad = defaults.getEccentricLoad()
        echoLevel = defaults.getEchoLevel()

        // Restore rep count timing
        repCountTiming = defaults.getRepCountTiming()

        // Restore stall detection
        stallDetectionEnabled = defaults.stallDetectionEnabled

        // Restore rest timer duration
        restSeconds = defaults.restSeconds

        Logger.d(
            "Loaded Just Lift defaults for profile=$loadingProfileId: modeId=${defaults.workoutModeId}, weight=${defaults.weightPerCableKg}kg, progression=${defaults.weightChangePerRep}, repTiming=${defaults.repCountTimingName}, restSeconds=${defaults.restSeconds}",
        )
        defaultsProfileId = loadingProfileId
    }

    LaunchedEffect(workoutParameters.programMode) {
        if (workoutParameters.isEchoMode) {
            eccentricLoad = workoutParameters.eccentricLoad
            echoLevel = workoutParameters.echoLevel
        }
    }

    // Navigate to ActiveWorkout when workout becomes active
    LaunchedEffect(workoutState) {
        if (workoutState is WorkoutState.Active) {
            navController.navigate(NavigationRoutes.ActiveWorkout.route)
        }
    }

    // Enable handle detection for auto-start when connected
    val connectionState by viewModel.connectionState.collectAsState()

    // Single consolidated effect for handle detection (Issue: iOS autostart race condition fix)
    // Previously had two effects (Unit + connectionState) that could both fire and reset
    // the state machine mid-grab on iOS due to different recomposition timing.
    // Now uses connectionState as key - fires on initial composition AND when connection changes.
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            Logger.i("JustLiftScreen: Connection ready, enabling handle detection")
            viewModel.enableHandleDetection()
        }
    }

    // Prepare the workout once when entering Just Lift. Do not key this on
    // workoutState: Initializing and Countdown are states owned by the current
    // auto-start execution and must not trigger a reset.
    //
    // Use rememberSaveable to survive process death: if the screen is recreated
    // after the process dies, this flag is restored as true and the reset is
    // skipped, preserving the in-flight auto-start execution.
    var hasPreparedSession by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!hasPreparedSession) {
            hasPreparedSession = true
            viewModel.prepareForJustLift()
        }
    }

    // Update parameters whenever user changes them
    // BUG FIX: Added eccentricLoad and echoLevel to dependencies - without these,
    // changing eccentric load percentage (e.g., from 100% to 150%) would NOT update
    // workoutParameters, causing the machine to receive wrong eccentric load value
    LaunchedEffect(
        selectedMode,
        weightPerCable,
        weightChangePerRep,
        stallDetectionEnabled,
        eccentricLoad,
        echoLevel,
        repCountTiming,
        restSeconds,
        weightUnit,
        defaultsLoaded,
    ) {
        // Don't write params until saved defaults have been loaded.
        // Without this guard, the effect fires immediately with the hardcoded
        // initial weightPerCable (0.453592f) before the current profile's defaults
        // have returned, causing #344 or leaking values across a profile switch.
        if (!defaultsLoaded) return@LaunchedEffect

        val weightChangeKg = if (weightUnit == WeightUnit.LB) {
            viewModel.displayToKg(weightChangePerRep.toFloat(), WeightUnit.LB)
        } else {
            weightChangePerRep.toFloat()
        }

        val newEchoLevel = if (selectedMode is WorkoutMode.Echo) (selectedMode as WorkoutMode.Echo).level else workoutParameters.echoLevel
        val updatedParameters = workoutParameters.copy(
            programMode = selectedMode.toProgramMode(),
            echoLevel = newEchoLevel,
            eccentricLoad = eccentricLoad,
            weightPerCableKg = weightPerCable,
            progressionRegressionKg = weightChangeKg,
            isJustLift = true,
            useAutoStart = true, // Enable auto-start for Just Lift
            stallDetectionEnabled = stallDetectionEnabled,
            repCountTiming = repCountTiming,
            selectedExerciseId = null, // Issue #97: Clear exercise ID for Just Lift sessions
            justLiftRestSeconds = restSeconds, // Issue #113: Configurable rest timer
        )
        Logger.d { "JustLift: Updating params - eccentricLoad=${eccentricLoad.percentage}%, echoLevel=${newEchoLevel.displayName}" }
        viewModel.updateWorkoutParameters(updatedParameters)
    }

    // Set global title
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("Just Lift")
    }

    val useCompactAccessibility = isCompactAccessibilityLayout()
    // Issue #571: Responsive gate distinguishing short/compact layouts that stack the weight
    // cards from tall/regular screens so gestures remain decoupled from the ProgressionSlider.
    val stackWeightCards = useStackedWeightCardsLayout()
    val contentScrollState = rememberScrollState()
    var showRestTimerDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBackgroundBrush()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.medium, vertical = Spacing.small)
                // The configuration can overflow even on a normal-size 375x667 portrait
                // screen after app chrome and mode cards are measured. Keep scrolling
                // independent from accessibility presentation choices.
                .verticalScroll(contentScrollState),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            // D-5: Pin active workout status above the fold so controls/state stay visible immediately
            if (workoutState !is WorkoutState.Idle) {
                ActiveStatusCard(
                    workoutState = workoutState,
                    currentMetric = currentMetric,
                    repCount = repCount,
                    weightUnit = weightUnit,
                    formatWeight = viewModel::formatWeight,
                    onStopWorkout = { viewModel.stopWorkout() },
                )
            }

            // Pinned header: auto-start/stop status + workout mode (never clipped by lower content)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.small),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    if (workoutState is WorkoutState.Idle || workoutState is WorkoutState.Active) {
                        val autoStartCountdown by viewModel.autoStartCountdown.collectAsState()
                        AutoStartStopCard(
                            workoutState = workoutState,
                            autoStartCountdown = autoStartCountdown,
                            autoStopState = autoStopState,
                            isConnected = connectionState is ConnectionState.Connected,
                            compact = true,
                            embedded = true,
                        )

                        val justLiftRestCountdown by viewModel.justLiftRestCountdown.collectAsState()
                        if (workoutState is WorkoutState.Idle &&
                            justLiftRestCountdown != null &&
                            justLiftRestCountdown!! > 0
                        ) {
                            JustLiftRestCountdownRow(secondsRemaining = justLiftRestCountdown!!)
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }

                    Text(
                        stringResource(Res.string.exercise_edit_workout_mode),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    val modes = listOf(
                        stringResource(Res.string.mode_old_school) to WorkoutMode.OldSchool,
                        stringResource(Res.string.mode_pump) to WorkoutMode.Pump,
                        stringResource(Res.string.mode_tut) to WorkoutMode.TUT,
                        stringResource(Res.string.mode_echo) to WorkoutMode.Echo(echoLevel),
                    )
                    val modesPrimaryRow = modes.take(2)
                    val modesSecondaryRow = modes.drop(2)

                    val isModeSelected: (WorkoutMode) -> Boolean = { mode ->
                        when (mode) {
                            is WorkoutMode.TUT -> selectedMode is WorkoutMode.TUT || selectedMode is WorkoutMode.TUTBeast
                            else -> selectedMode::class == mode::class
                        }
                    }
                    val onModeSelected: (WorkoutMode) -> Unit = { mode ->
                        selectedMode = when (mode) {
                            is WorkoutMode.TUT -> {
                                if (selectedMode is WorkoutMode.TUTBeast) {
                                    WorkoutMode.TUTBeast
                                } else {
                                    WorkoutMode.TUT
                                }
                            }
                            else -> mode
                        }
                    }

                    if (useCompactAccessibility) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            modes.forEach { (label, mode) ->
                                FilterChip(
                                    selected = isModeSelected(mode),
                                    onClick = { onModeSelected(mode) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            JustLiftModeSegmentRow(
                                modes = modesPrimaryRow,
                                isModeSelected = isModeSelected,
                                onModeSelected = onModeSelected,
                            )
                            JustLiftModeSegmentRow(
                                modes = modesSecondaryRow,
                                isModeSelected = isModeSelected,
                                onModeSelected = onModeSelected,
                            )
                        }
                    }

                    val isTutOrBeastPinned = selectedMode is WorkoutMode.TUT || selectedMode is WorkoutMode.TUTBeast
                    if (isTutOrBeastPinned) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = selectedMode is WorkoutMode.TUT,
                                onClick = { selectedMode = WorkoutMode.TUT },
                                label = { Text(stringResource(Res.string.just_lift_tut_standard)) },
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = selectedMode is WorkoutMode.TUTBeast,
                                onClick = { selectedMode = WorkoutMode.TUTBeast },
                                label = { Text(stringResource(Res.string.just_lift_tut_beast)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Text(
                        when (selectedMode) {
                            is WorkoutMode.OldSchool -> stringResource(Res.string.just_lift_mode_old_school_desc)
                            is WorkoutMode.Pump -> stringResource(Res.string.just_lift_mode_pump_desc)
                            is WorkoutMode.TUTBeast -> stringResource(Res.string.config_mode_tut_beast_desc)
                            is WorkoutMode.TUT -> stringResource(Res.string.config_mode_tut_desc)
                            is WorkoutMode.Echo -> stringResource(Res.string.just_lift_mode_echo_desc)
                            else -> selectedMode.displayName
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Mode-specific options - OLD SCHOOL, PUMP, TUT & BEAST
            val isTutOrBeast = selectedMode is WorkoutMode.TUT || selectedMode is WorkoutMode.TUTBeast
            val showWeightAndProgression = selectedMode is WorkoutMode.OldSchool || selectedMode is WorkoutMode.Pump || isTutOrBeast
            if (showWeightAndProgression) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Issue #571: belt-and-braces vertical separation between the two
                        // weight cards when stacked, so the wheel's bottom edge is
                        // unambiguously above the slider's top edge even if the inner
                        // gesture-isolation step in CompactNumberPicker is bypassed by a
                        // future regression. Applied as bottom padding on the *first* card
                        // (rather than an explicit Spacer between them) so the outer
                        // Column's `Arrangement.spacedBy(Spacing.medium)` does not double
                        // the gap. Total gap = Spacing.medium (outer) + Spacing.small
                        // (this padding) = 24dp.
                        .then(
                            if (stackWeightCards) Modifier.padding(bottom = Spacing.small) else Modifier,
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.small),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        val weightSuffix = if (weightUnit == WeightUnit.LB) "lbs" else "kg"
                        // KD-9: bound by the CONNECTED trainer, like every other live slider.
                        val maxWeightKg = CommandLimits.maxWeightPerCableKg(
                            (connectionState as? ConnectionState.Connected)?.hardwareModel,
                        )
                        val maxWeight = viewModel.kgToDisplay(maxWeightKg, weightUnit)
                        val weightStep = viewModel.kgToDisplay(userPreferences.effectiveWeightIncrementKg, weightUnit)
                        val displayWeight = viewModel.kgToDisplay(weightPerCable, weightUnit)
                        val weightTextScale = LocalWeightTextScale.current
                        val effectiveWeightScale = currentEffectiveWeightTextScale(weightTextScale)
                        val pickerMaxWidth = if (effectiveWeightScale > 1.0f) 360.dp else 220.dp

                        Box(
                            modifier = Modifier
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CompactNumberPicker(
                                value = displayWeight,
                                onValueChange = { newValue ->
                                    val kg = viewModel.displayToKg(newValue, weightUnit)
                                    weightPerCable = kg
                                },
                                range = 1f..maxWeight,
                                step = weightStep,
                                label = stringResource(Res.string.exercise_edit_weight_per_cable),
                                suffix = weightSuffix,
                                compactWheel = true,
                                modifier = Modifier
                                    .widthIn(max = pickerMaxWidth)
                                    .fillMaxWidth(),
                                applySelectorScale = true,
                            )
                        }

                        val totalText = WeightDisplayFormatter.formatTwoCableTotalWeight(
                            weightPerCableKg = weightPerCable,
                            unit = weightUnit,
                        )
                        Text(
                            text = stringResource(Res.string.just_lift_total_weight, totalText, weightSuffix),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                // Weight Change Per Rep Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.small),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            stringResource(Res.string.exercise_edit_weight_change_per_rep),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        val maxProgression = kotlin.math.floor(
                            viewModel.kgToDisplay(CommandLimits.MAX_PROGRESSION_KG, weightUnit),
                        ).toInt().toFloat()
                        ProgressionSlider(
                            value = weightChangePerRep.toFloat().coerceIn(-maxProgression, maxProgression),
                            onValueChange = { weightChangePerRep = it.roundToInt() },
                            valueRange = -maxProgression..maxProgression,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            val isEchoMode = selectedMode is WorkoutMode.Echo
            if (isEchoMode) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.small),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            stringResource(Res.string.eccentric_load),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        var expanded by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                        ) {
                            OutlinedTextField(
                                value = eccentricLoadLabel(eccentricLoad),
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
                                        text = { Text(eccentricLoadLabel(load)) },
                                        onClick = {
                                            eccentricLoad = load
                                            expanded = false
                                        },
                                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                    )
                                }
                            }
                        }

                        Text(
                            stringResource(Res.string.eccentric_load_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        Text(
                            stringResource(Res.string.echo_level),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (useCompactAccessibility) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                EchoLevel.entries.forEach { level ->
                                    FilterChip(
                                        selected = echoLevel == level,
                                        onClick = {
                                            echoLevel = level
                                            selectedMode = WorkoutMode.Echo(level)
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        label = { Text(echoLevelLabel(level)) },
                                    )
                                }
                            }
                        } else {
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                            ) {
                                EchoLevel.entries.forEachIndexed { index, level ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = EchoLevel.entries.size),
                                        onClick = {
                                            echoLevel = level
                                            selectedMode = WorkoutMode.Echo(level)
                                        },
                                        selected = echoLevel == level,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text(
                                            echoLevelLabel(level),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Quick settings — single compact card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                ) {
                    JustLiftSettingRow(
                        title = stringResource(Res.string.rep_count_timing),
                        subtitle = if (repCountTiming == RepCountTiming.TOP) {
                            stringResource(Res.string.rep_count_timing_top)
                        } else {
                            stringResource(Res.string.rep_count_timing_bottom)
                        },
                        trailing = {
                            Switch(
                                checked = repCountTiming == RepCountTiming.TOP,
                                onCheckedChange = {
                                    repCountTiming = if (it) RepCountTiming.TOP else RepCountTiming.BOTTOM
                                },
                            )
                        },
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    JustLiftSettingRow(
                        title = stringResource(Res.string.exercise_edit_stall_detection_title),
                        subtitle = stringResource(Res.string.just_lift_stall_desc),
                        trailing = {
                            Switch(
                                checked = stallDetectionEnabled,
                                onCheckedChange = { stallDetectionEnabled = it },
                            )
                        },
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    JustLiftSettingRow(
                        title = stringResource(Res.string.just_lift_rest_timer),
                        subtitle = if (restSeconds == 0) {
                            stringResource(Res.string.just_lift_no_rest)
                        } else {
                            stringResource(Res.string.just_lift_rest_seconds_desc, restSeconds)
                        },
                        trailing = {
                            Surface(
                                modifier = Modifier
                                    .widthIn(min = 48.dp)
                                    .heightIn(min = 48.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable { showRestTimerDialog = true },
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = if (restSeconds == 0) stringResource(Res.string.hrm_state_off) else stringResource(Res.string.rep_replay_seconds_short, restSeconds.toString()),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        },
                    )
                }
            }

            if (showRestTimerDialog) {
                RestTimePickerDialog(
                    currentRestSeconds = restSeconds,
                    onSelect = {
                        restSeconds = it
                        showRestTimerDialog = false
                    },
                    onDismiss = { showRestTimerDialog = false },
                    options = listOf(0, 30, 60, 90, 120),
                    title = stringResource(Res.string.just_lift_rest_between_sets),
                    formatLabel = { if (it == 0) "Off" else "${it}s" },
                )
            }
        }

    }
}

/**
 * Simple workout status card showing current state with live indicator.
 */
@Composable
private fun ActiveStatusCard(
    workoutState: WorkoutState,
    currentMetric: WorkoutMetric?,
    repCount: RepCount,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onStopWorkout: () -> Unit,
) {
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val isActiveState = workoutState is WorkoutState.Active
    // Live pulse animation (skipped when reduceMotion is active)
    val alpha = if (reduceMotion || !isActiveState) {
        1f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "alpha",
        )
        animatedAlpha
    }
    val headerContentColor = if (isActiveState) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActiveState) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
        ) {
            // Header with Live Indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (workoutState) {
                        is WorkoutState.Countdown -> stringResource(Res.string.just_lift_get_ready_seconds, workoutState.secondsRemaining)
                        is WorkoutState.Active -> stringResource(Res.string.just_lift_workout_active)
                        is WorkoutState.Resting -> stringResource(Res.string.just_lift_resting_seconds, workoutState.restSecondsRemaining)
                        is WorkoutState.Completed -> stringResource(Res.string.just_lift_workout_complete)
                        else -> stringResource(Res.string.just_lift_workout_status)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = headerContentColor,
                    modifier = Modifier.weight(1f),
                )

                if (isActiveState) {
                    Spacer(Modifier.width(Spacing.small))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = AccessibilityTheme.colors.success,
                        contentColor = AccessibilityTheme.colors.onSuccess,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(AccessibilityTheme.colors.onSuccess.copy(alpha = alpha), CircleShape),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(Res.string.label_live),
                                style = MaterialTheme.typography.labelSmall,
                                color = AccessibilityTheme.colors.onSuccess,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            if (isActiveState) {
                Spacer(modifier = Modifier.height(Spacing.medium))

                // BIG Rep Counter
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "${repCount.totalReps}",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = stringResource(Res.string.hud_reps_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Metric Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(Res.string.just_lift_load_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        val loadText = currentMetric?.let { formatWeight(it.totalLoad, weightUnit) } ?: "--"
                        Text(
                            loadText,
                            style = MaterialTheme.typography.titleMedium.scaledForWeightValue(LocalWeightTextScale.current),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                Button(
                    onClick = onStopWorkout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 2.dp,
                    ),
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.cd_close_workout))
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        stringResource(Res.string.just_lift_finish_set),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// Auto-start theme colors - resolved from AccessibilityTheme at composable call sites

/**
 * Compact Auto-Start/Auto-Stop Banner for Just Lift Mode
 * Features animated rings, pulsing glow, and countdown display
 */
@Composable
private fun AutoStartStopCard(
    workoutState: WorkoutState,
    autoStartCountdown: Int?,
    autoStopState: AutoStopUiState,
    isConnected: Boolean,
    compact: Boolean = false,
    embedded: Boolean = false,
) {
    val isIdle = workoutState is WorkoutState.Idle
    val isActive = workoutState is WorkoutState.Active
    val isCountingDown = autoStartCountdown != null
    val isStopping = autoStopState.isActive

    // Only show when relevant
    if (!isIdle && !isActive) return

    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion

    // Infinite transition for continuous animations (skipped when reduceMotion is active)
    val pulseScale: Float
    val glowAlpha: Float
    val ringRotation: Float
    if (reduceMotion) {
        pulseScale = 1f
        glowAlpha = 0.7f
        ringRotation = 0f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "autoStart")
        val animatedPulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutQuad),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        val animatedGlowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = EaseInOutQuad),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glow",
        )
        val animatedRingRotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(2500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "rotation",
        )
        pulseScale = animatedPulseScale
        glowAlpha = animatedGlowAlpha
        ringRotation = animatedRingRotation
    }

    // Determine colors based on state (from AccessibilityTheme)
    val autoStartGreen = AccessibilityTheme.colors.success
    val countdownOrange = AccessibilityTheme.colors.warning
    val stopRed = AccessibilityTheme.colors.error
    // Ready-state color: green when connected (BLE present to arm auto-start),
    // onSurfaceVariant when not connected (indicator is informational only — nothing is armed).
    val readyColor = if (isConnected) autoStartGreen else MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = when {
        isStopping -> stopRed
        isCountingDown -> countdownOrange
        else -> readyColor
    }

    val indicatorSize = if (compact) 40.dp else 56.dp
    val innerIndicatorSize = if (compact) 32.dp else 44.dp
    val handleIconSize = if (compact) 32.dp else 40.dp
    val statusTitleStyle = if (compact) {
        MaterialTheme.typography.labelLarge
    } else {
        MaterialTheme.typography.titleMedium
    }
    val rowPadding = if (compact) {
        Modifier.padding(horizontal = Spacing.extraSmall, vertical = 2.dp)
    } else {
        Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small)
    }

    val bannerContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (embedded) {
                        Modifier
                    } else {
                        Modifier.background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    primaryColor.copy(alpha = 0.1f),
                                    Color.Transparent,
                                ),
                            ),
                        )
                    },
                )
                .then(rowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Animated indicator
            Box(
                modifier = Modifier.size(indicatorSize),
                contentAlignment = Alignment.Center,
            ) {
                if (isCountingDown || isStopping) {
                    // Rotating ring
                    Canvas(
                        modifier = Modifier
                            .size(indicatorSize)
                            .alpha(0.6f),
                    ) {
                        rotate(ringRotation) {
                            drawArc(
                                color = primaryColor,
                                startAngle = 0f,
                                sweepAngle = 120f,
                                useCenter = false,
                                style = Stroke(width = 3f, cap = StrokeCap.Round),
                                size = Size(size.width, size.height),
                            )
                        }
                    }

                    // Progress ring
                    val progress = if (isStopping) autoStopState.progress else 0.6f
                    Canvas(modifier = Modifier.size(innerIndicatorSize)) {
                        drawArc(
                            color = primaryColor.copy(alpha = 0.2f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 4f, cap = StrokeCap.Round),
                            size = Size(size.width, size.height),
                        )
                        drawArc(
                            color = primaryColor,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            style = Stroke(width = 4f, cap = StrokeCap.Round),
                            size = Size(size.width, size.height),
                        )
                    }

                    // Countdown number
                    val countValue = if (isStopping) autoStopState.secondsRemaining else autoStartCountdown ?: 0
                    AnimatedContent(
                        targetState = countValue,
                        transitionSpec = {
                            if (reduceMotion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                (scaleIn(initialScale = 1.3f, animationSpec = tween(200)) + fadeIn())
                                    .togetherWith(scaleOut(targetScale = 0.7f) + fadeOut())
                            }
                        },
                        label = "countdown",
                    ) { count ->
                        Text(
                            text = "$count",
                            style = MaterialTheme.typography.titleLarge,
                            color = primaryColor,
                        )
                    }
                } else {
                    // Pulsing glow for ready state
                    Box(
                        modifier = Modifier
                            .size(if (compact) 36.dp else 48.dp)
                            .scale(pulseScale)
                            .alpha(glowAlpha * 0.5f)
                            .blur(12.dp)
                            .background(primaryColor, CircleShape),
                    )

                    // Handle grip icon - two parallel bars
                    Canvas(
                        modifier = Modifier
                            .size(handleIconSize)
                            .scale(pulseScale),
                    ) {
                        val barWidth = 6f
                        val barHeight = size.height * 0.7f
                        val spacing = size.width * 0.35f
                        val cornerRadius = 3f

                        // Left handle bar
                        drawRoundRect(
                            color = primaryColor,
                            topLeft = Offset(
                                (size.width - spacing) / 2 - barWidth,
                                (size.height - barHeight) / 2,
                            ),
                            size = Size(barWidth, barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
                        )

                        // Right handle bar
                        drawRoundRect(
                            color = primaryColor,
                            topLeft = Offset(
                                (size.width + spacing) / 2,
                                (size.height - barHeight) / 2,
                            ),
                            size = Size(barWidth, barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
                        )

                        // Grip indicators (small horizontal lines)
                        val gripColor = primaryColor.copy(alpha = 0.6f)
                        val gripWidth = barWidth * 1.5f
                        val gripHeight = 2f
                        listOf(0.3f, 0.5f, 0.7f).forEach { ratio ->
                            val y = (size.height - barHeight) / 2 + barHeight * ratio
                            // Left grip marks
                            drawRoundRect(
                                color = gripColor,
                                topLeft = Offset(
                                    (size.width - spacing) / 2 - barWidth - 2f,
                                    y - gripHeight / 2,
                                ),
                                size = Size(gripWidth, gripHeight),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f),
                            )
                            // Right grip marks
                            drawRoundRect(
                                color = gripColor,
                                topLeft = Offset(
                                    (size.width + spacing) / 2 + barWidth - gripWidth + 2f,
                                    y - gripHeight / 2,
                                ),
                                size = Size(gripWidth, gripHeight),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(if (compact) Spacing.small else Spacing.medium))

            // Center: Text content
            Column(modifier = Modifier.weight(1f)) {
                // Status label
                Text(
                    text = when {
                        isStopping -> stringResource(Res.string.autostop_title)
                        isCountingDown -> stringResource(Res.string.just_lift_starting_ellipsis)
                        isActive -> stringResource(Res.string.just_lift_auto_stop_ready)
                        isConnected -> stringResource(Res.string.autostart_ready)
                        else -> stringResource(Res.string.autostart_connect_prompt)
                    },
                    style = statusTitleStyle,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    maxLines = 1,
                )

                // Instruction
                Text(
                    text = when {
                        isStopping -> stringResource(Res.string.just_lift_release_handles_stop)
                        isCountingDown -> stringResource(Res.string.just_lift_hold_steady)
                        isActive -> stringResource(Res.string.just_lift_release_handles_5s)
                        isConnected -> stringResource(Res.string.autostart_grab_handles)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Right: Status indicator dot
            Box(
                modifier = Modifier
                    .size(if (compact) 10.dp else 12.dp)
                    .scale(if (!isCountingDown && !isStopping) pulseScale else 1f)
                    .background(
                        color = primaryColor.copy(alpha = glowAlpha),
                        shape = CircleShape,
                    ),
            )
        }
    }

    if (embedded) {
        bannerContent()
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            shape = MaterialTheme.shapes.medium,
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (isCountingDown || isStopping) 8.dp else 4.dp,
            ),
        ) {
            bannerContent()
        }
    }
}

/**
 * Two-up segmented row for Just Lift mode selection.
 */
@Composable
private fun JustLiftModeSegmentRow(
    modes: List<Pair<String, WorkoutMode>>,
    isModeSelected: (WorkoutMode) -> Boolean,
    onModeSelected: (WorkoutMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        modes.forEachIndexed { index, (label, mode) ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                onClick = { onModeSelected(mode) },
                selected = isModeSelected(mode),
                modifier = Modifier.heightIn(min = 48.dp),
                icon = {},
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Compact settings row for Just Lift quick-settings card.
 */
@Composable
private fun JustLiftSettingRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.small),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/**
 * Compact rest countdown row for Just Lift mode (Issue #113).
 * Displayed inline in the JustLiftScreen column between the auto-start card
 * and the mode selection card. Purely informational — does not block auto-start.
 */
@Composable
private fun JustLiftRestCountdownRow(secondsRemaining: Int) {
    val timeText = DurationFormatter.formatJustLiftRestClock(secondsRemaining.toLong() * 1000L)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.just_lift_rest_clock, timeText),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
