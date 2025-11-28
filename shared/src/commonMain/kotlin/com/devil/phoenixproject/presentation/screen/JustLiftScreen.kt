package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.CompactNumberPicker
import com.devil.phoenixproject.presentation.components.ExpressiveCard
import com.devil.phoenixproject.presentation.components.ExpressiveSlider
import com.devil.phoenixproject.presentation.components.ProgressionSlider
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.ThemeMode
import kotlinx.coroutines.delay

/**
 * Just Lift screen - quick workout configuration.
 * Allows user to select mode, eccentric load percentage, and progression/regression.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JustLiftScreen(
    navController: NavController,
    viewModel: MainViewModel,
    themeMode: ThemeMode
) {
    val workoutState by viewModel.workoutState.collectAsState()
    val workoutParameters by viewModel.workoutParameters.collectAsState()
    val currentMetric by viewModel.currentMetric.collectAsState()
    val repCount by viewModel.repCount.collectAsState()
    val autoStopState by viewModel.autoStopState.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val isAutoConnecting by viewModel.isAutoConnecting.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()

    var selectedMode by remember { mutableStateOf(workoutParameters.workoutType.toWorkoutMode()) }
    // Initialize to match the picker's default: 1 lb = 0.453592 kg
    var weightPerCable by remember { mutableStateOf(0.453592f) }
    var weightChangePerRep by remember { mutableStateOf(0) } // Progression/Regression value
    var eccentricLoad by remember { mutableStateOf(EccentricLoad.LOAD_100) }
    var echoLevel by remember { mutableStateOf(EchoLevel.HARDER) }
    var defaultsLoaded by remember { mutableStateOf(false) }

    // Load saved Just Lift defaults on screen init
    LaunchedEffect(Unit) {
        if (!defaultsLoaded) {
            val defaults = viewModel.getJustLiftDefaults()
            if (defaults != null) {
                // Apply saved defaults
                weightPerCable = defaults.weightPerCableKg

                // Convert stored weight change (KG) to display unit if needed
                weightChangePerRep = if (weightUnit == WeightUnit.LB) {
                    kotlin.math.round(defaults.weightChangePerRep * 2.20462f).toInt()
                } else {
                    defaults.weightChangePerRep
                }

                // Set mode from saved defaults
                val savedWorkoutType = defaults.toWorkoutType()
                selectedMode = savedWorkoutType.toWorkoutMode()

                // Restore eccentric load and echo level for Echo mode
                eccentricLoad = defaults.getEccentricLoad()
                echoLevel = defaults.getEchoLevel()

                Logger.d("Loaded Just Lift defaults: modeId=${defaults.workoutModeId}, weight=${defaults.weightPerCableKg}kg, progression=${defaults.weightChangePerRep}")
            }
            defaultsLoaded = true
        }
    }

    LaunchedEffect(workoutParameters.workoutType) {
        val workoutType = workoutParameters.workoutType
        if (workoutType is WorkoutType.Echo) {
            eccentricLoad = workoutType.eccentricLoad
            echoLevel = workoutType.level
        }
    }

    // Navigate to ActiveWorkout when workout becomes active
    LaunchedEffect(workoutState) {
        if (workoutState is WorkoutState.Active) {
            navController.navigate(NavigationRoutes.ActiveWorkout.route)
        }
    }

    // Enable handle detection for auto-start when connected (matches official app)
    val connectionState by viewModel.connectionState.collectAsState()
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            viewModel.enableHandleDetection()
        }
    }

    // Reset workout state if entering Just Lift with any non-Idle state (matches official app)
    LaunchedEffect(workoutState) {
        if (workoutState !is WorkoutState.Idle && workoutState !is WorkoutState.Active) {
            viewModel.prepareForJustLift()
        }
    }

    // Update parameters whenever user changes them
    LaunchedEffect(selectedMode, weightPerCable, weightChangePerRep) {
        val weightChangeKg = if (weightUnit == WeightUnit.LB) {
            weightChangePerRep / 2.20462f
        } else {
            weightChangePerRep.toFloat()
        }

        val updatedParameters = workoutParameters.copy(
            workoutType = selectedMode.toWorkoutType(eccentricLoad),
            weightPerCableKg = weightPerCable,
            progressionRegressionKg = weightChangeKg,
            isJustLift = true,
            useAutoStart = true // Enable auto-start for Just Lift
        )
        viewModel.updateWorkoutParameters(updatedParameters)
    }

    // Set global title
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("Just Lift")
    }

    Scaffold { padding ->
        // Use Material Theme colors for dynamic background
        val backgroundGradient = Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.surfaceContainer,
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundGradient)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(Spacing.large)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium)
            ) {
                // Only show Auto-Start/Stop Card when IDLE
                if (workoutState is WorkoutState.Idle) {
                    val autoStartCountdown by viewModel.autoStartCountdown.collectAsState()
                    AutoStartStopCard(
                        workoutState = workoutState,
                        autoStartCountdown = autoStartCountdown,
                        autoStopState = autoStopState
                    )
                }

                // Mode Selection Card
                var isModePressed by remember { mutableStateOf(false) }
                val modeScale by animateFloatAsState(
                    targetValue = if (isModePressed) 0.95f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "modeScale"
                )
                ExpressiveCard(
                    onClick = { isModePressed = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (isModePressed) 8.dp else 12.dp),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium)
                    ) {
                        Text(
                            "Workout Mode",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))

                        // Segmented Button Row for Modes
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val modes = listOf(
                                Triple("Old School", WorkoutMode.OldSchool, 0),
                                Triple("Pump", WorkoutMode.Pump, 1),
                                Triple("Echo", WorkoutMode.Echo(echoLevel), 2)
                            )

                            modes.forEachIndexed { index, (label, mode, _) ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                                    onClick = { selectedMode = mode },
                                    selected = selectedMode::class == mode::class,
                                    icon = {} // No icon to save space
                                ) {
                                    Text(label, maxLines = 1)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.small))

                        Text(
                            when (selectedMode) {
                                is WorkoutMode.OldSchool -> "Constant resistance throughout the movement."
                                is WorkoutMode.Pump -> "Resistance increases the faster you go."
                                is WorkoutMode.Echo -> "Adaptive resistance with echo feedback."
                                else -> selectedMode.displayName
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                LaunchedEffect(isModePressed) {
                    if (isModePressed) {
                        delay(100)
                        isModePressed = false
                    }
                }

                // Mode-specific options

                // OLD SCHOOL & PUMP: Weight per cable, Progression/Regression
                val isOldSchoolOrPump = selectedMode is WorkoutMode.OldSchool || selectedMode is WorkoutMode.Pump
                if (isOldSchoolOrPump) {
                    // Weight per Cable Card
                    ExpressiveCard(
                        onClick = {},
                        enabled = false, // Static card
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium)
                        ) {
                            val weightSuffix = if (weightUnit == WeightUnit.LB) "lbs" else "kg"
                            val maxWeight = if (weightUnit == WeightUnit.LB) 220f else 100f
                            val weightStep = if (weightUnit == WeightUnit.LB) 0.5f else 0.25f
                            val displayWeight = viewModel.kgToDisplay(weightPerCable, weightUnit)

                            CompactNumberPicker(
                                value = displayWeight,
                                onValueChange = { newValue ->
                                    weightPerCable = viewModel.displayToKg(newValue, weightUnit)
                                },
                                range = 1f..maxWeight,
                                step = weightStep,
                                label = "Weight per Cable",
                                suffix = weightSuffix,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Weight Change Per Rep Card
                    ExpressiveCard(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium)
                        ) {
                            Text(
                                "Weight Change Per Rep",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(Spacing.medium))

                            val maxWeightChange = 10f

                            ProgressionSlider(
                                value = weightChangePerRep.toFloat(),
                                onValueChange = { weightChangePerRep = it.toInt() },
                                valueRange = -maxWeightChange..maxWeightChange,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Negative = Regression, Positive = Progression",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.small)
                            )
                        }
                    }
                }

                // ECHO MODE: Eccentric Load, Echo Level
                val isEchoMode = selectedMode is WorkoutMode.Echo
                if (isEchoMode) {
                    // Eccentric Load Card
                    ExpressiveCard(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium)
                        ) {
                            Text(
                                "Eccentric Load: ${eccentricLoad.percentage}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(Spacing.medium))

                            val eccentricLoadValues = listOf(
                                EccentricLoad.LOAD_0,
                                EccentricLoad.LOAD_50,
                                EccentricLoad.LOAD_75,
                                EccentricLoad.LOAD_100,
                                EccentricLoad.LOAD_125,
                                EccentricLoad.LOAD_150
                            )
                            val currentIndex = eccentricLoadValues.indexOf(eccentricLoad).let {
                                if (it < 0) 3 else it
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "0%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "150%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            ExpressiveSlider(
                                value = currentIndex.toFloat(),
                                onValueChange = { value ->
                                    val index = value.toInt().coerceIn(0, eccentricLoadValues.size - 1)
                                    eccentricLoad = eccentricLoadValues[index]
                                },
                                valueRange = 0f..(eccentricLoadValues.size - 1).toFloat(),
                                steps = eccentricLoadValues.size - 2,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(Spacing.small))

                            Text(
                                "Load percentage applied during eccentric (lowering) phase",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Echo Level Card
                    ExpressiveCard(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium)
                        ) {
                            Text(
                                "Echo Level",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(Spacing.small))

                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val levels = EchoLevel.entries
                                levels.forEachIndexed { index, level ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = levels.size),
                                        onClick = {
                                            echoLevel = level
                                            selectedMode = WorkoutMode.Echo(level)
                                        },
                                        selected = echoLevel == level
                                    ) {
                                        Text(level.displayName, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                // Current workout status if active
                if (workoutState !is WorkoutState.Idle) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.medium))

                    ActiveStatusCard(
                        workoutState = workoutState,
                        currentMetric = currentMetric,
                        repCount = repCount,
                        weightUnit = weightUnit,
                        formatWeight = viewModel::formatWeight,
                        onStopWorkout = { viewModel.stopWorkout() }
                    )
                }
            }

            // Auto-connect UI overlays
            if (isAutoConnecting) {
                com.devil.phoenixproject.presentation.components.ConnectingOverlay(
                    onCancel = { viewModel.cancelAutoConnecting() }
                )
            }

            connectionError?.let { error ->
                com.devil.phoenixproject.presentation.components.ConnectionErrorDialog(
                    message = error,
                    onDismiss = { viewModel.clearConnectionError() }
                )
            }
        }
    }
}

/**
 * Simple workout status card showing current state with live indicator.
 */
@Composable
fun ActiveStatusCard(
    workoutState: WorkoutState,
    currentMetric: WorkoutMetric?,
    repCount: RepCount,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onStopWorkout: () -> Unit
) {
    // Live pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "alpha"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (workoutState is WorkoutState.Active)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            // Header with Live Indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (workoutState) {
                        is WorkoutState.Countdown -> "Get Ready: ${workoutState.secondsRemaining}s"
                        is WorkoutState.Active -> "Workout Active"
                        is WorkoutState.Resting -> "Resting: ${workoutState.restSecondsRemaining}s"
                        is WorkoutState.Completed -> "Workout Complete"
                        else -> "Workout Status"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Spacer(Modifier.weight(1f))

                if (workoutState is WorkoutState.Active) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Color.Green.copy(alpha = alpha), CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.Green, fontWeight = FontWeight.Bold)
                }
            }

            if (workoutState is WorkoutState.Active) {
                Spacer(modifier = Modifier.height(Spacing.medium))

                // BIG Rep Counter
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${repCount.totalReps}",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "REPS",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Metric Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Load",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        val loadText = currentMetric?.let { formatWeight(it.totalLoad, weightUnit) } ?: "--"
                        Text(
                            loadText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                Button(
                    onClick = onStopWorkout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 2.dp
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close workout")
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "Finish Set",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Unified Auto-Start/Auto-Stop Card for Just Lift Mode
 * Shows auto-start when idle, auto-stop when active
 */
@Composable
fun AutoStartStopCard(
    workoutState: WorkoutState,
    autoStartCountdown: Int?,
    autoStopState: AutoStopUiState
) {
    val isIdle = workoutState is WorkoutState.Idle
    val isActive = workoutState is WorkoutState.Active

    // Show card when idle (for auto-start) or active (for auto-stop)
    if (isIdle || isActive) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    autoStartCountdown != null -> MaterialTheme.colorScheme.primaryContainer
                    autoStopState.isActive -> MaterialTheme.colorScheme.errorContainer
                    isActive -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                }
            ),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = BorderStroke(2.dp, if (isIdle) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isIdle) Icons.Default.PlayCircle else Icons.Default.PanTool,
                        contentDescription = if (isIdle) "Start workout" else "Hands on handles",
                        modifier = Modifier.size(32.dp),
                        tint = when {
                            autoStartCountdown != null -> MaterialTheme.colorScheme.onPrimaryContainer
                            autoStopState.isActive -> MaterialTheme.colorScheme.onErrorContainer
                            isActive -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                    Spacer(Modifier.width(Spacing.small))
                    Text(
                        text = when {
                            autoStartCountdown != null -> "Starting..."
                            autoStopState.isActive -> "Stopping in ${autoStopState.secondsRemaining}s..."
                            isActive -> "Auto-Stop Ready"
                            else -> "Auto-Start Ready"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            autoStartCountdown != null -> MaterialTheme.colorScheme.onPrimaryContainer
                            autoStopState.isActive -> MaterialTheme.colorScheme.onErrorContainer
                            isActive -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onTertiaryContainer
                        }
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Progress for countdowns
                if (autoStartCountdown != null) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(Spacing.small))
                } else if (autoStopState.isActive) {
                    LinearProgressIndicator(
                        progress = { autoStopState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(Spacing.small))
                }

                // Instructions
                val instructionText = if (isIdle) {
                    "Grab and hold handles (~5s) to start"
                } else {
                    "Put handles down for 5 seconds to stop"
                }
                Text(
                    text = instructionText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        autoStartCountdown != null -> MaterialTheme.colorScheme.onPrimaryContainer
                        autoStopState.isActive -> MaterialTheme.colorScheme.onErrorContainer
                        isActive -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onTertiaryContainer
                    },
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
