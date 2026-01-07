package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.AddProfileDialog
import com.devil.phoenixproject.presentation.components.CompactNumberPicker
import com.devil.phoenixproject.presentation.components.ExpressiveSlider
import com.devil.phoenixproject.presentation.components.ProfileSidePanel
import com.devil.phoenixproject.presentation.components.ProgressionSlider
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import com.devil.phoenixproject.ui.theme.ThemeMode

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
    val userPreferences by viewModel.userPreferences.collectAsState()
    @Suppress("UNUSED_VARIABLE") // Reserved for future connecting overlay
    val isAutoConnecting by viewModel.isAutoConnecting.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()

    var selectedMode by remember { mutableStateOf(workoutParameters.programMode.toWorkoutMode(workoutParameters.echoLevel)) }
    // Initialize to match the picker's default: 1 lb = 0.453592 kg
    var weightPerCable by remember { mutableStateOf(0.453592f) }
    var weightChangePerRep by remember { mutableStateOf(0) } // Progression/Regression value
    var eccentricLoad by remember { mutableStateOf(EccentricLoad.LOAD_100) }
    var echoLevel by remember { mutableStateOf(EchoLevel.HARDER) }
    var defaultsLoaded by remember { mutableStateOf(false) }
    // Profile management
    val scope = rememberCoroutineScope()
    val profileRepository: UserProfileRepository = koinInject()
    val profiles by profileRepository.allProfiles.collectAsState()
    val activeProfile by profileRepository.activeProfile.collectAsState()
    var showAddProfileDialog by remember { mutableStateOf(false) }

    // Load saved Just Lift defaults on screen init
    LaunchedEffect(Unit) {
        if (!defaultsLoaded) {
            val defaults = viewModel.getJustLiftDefaults()
            if (defaults != null) {
                // Apply saved defaults
                weightPerCable = defaults.weightPerCableKg

                // Convert stored weight change (KG) to display unit if needed
                // weightChangePerRep is already Int in viewmodel format
                weightChangePerRep = if (weightUnit == WeightUnit.LB) {
                    kotlin.math.round(defaults.weightChangePerRep * 2.20462f).toInt()
                } else {
                    defaults.weightChangePerRep
                }

                // Set mode from saved defaults
                val savedProgramMode = defaults.toProgramMode()
                selectedMode = savedProgramMode.toWorkoutMode(defaults.getEchoLevel())

                // Restore eccentric load and echo level for Echo mode
                eccentricLoad = defaults.getEccentricLoad()
                echoLevel = defaults.getEchoLevel()

                Logger.d("Loaded Just Lift defaults: modeId=${defaults.workoutModeId}, weight=${defaults.weightPerCableKg}kg, progression=${defaults.weightChangePerRep}")
            }
            defaultsLoaded = true
        }
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

    // Enable handle detection for auto-start when connected (matches official app)
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

    // Reset workout state if entering Just Lift with any non-Idle state (matches official app)
    LaunchedEffect(workoutState) {
        if (workoutState !is WorkoutState.Idle && workoutState !is WorkoutState.Active) {
            viewModel.prepareForJustLift()
        }
    }

    // Update parameters whenever user changes them
    LaunchedEffect(selectedMode, weightPerCable, weightChangePerRep, userPreferences.stallDetectionEnabled) {
        val weightChangeKg = if (weightUnit == WeightUnit.LB) {
            weightChangePerRep / 2.20462f
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
            stallDetectionEnabled = userPreferences.stallDetectionEnabled,
            selectedExerciseId = null // Issue #97: Clear exercise ID for Just Lift sessions
        )
        viewModel.updateWorkoutParameters(updatedParameters)
    }

    // Set global title
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("Just Lift")
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                // Auto-Start/Stop Banner (compact, always visible when idle)
                if (workoutState is WorkoutState.Idle) {
                    val autoStartCountdown by viewModel.autoStartCountdown.collectAsState()
                    AutoStartStopCard(
                        workoutState = workoutState,
                        autoStartCountdown = autoStartCountdown,
                        autoStopState = autoStopState
                    )
                }

                // Mode Selection Card - expands to fill space
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Spacing.medium),
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text(
                            "Workout Mode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val modes = listOf(
                                "Old School" to WorkoutMode.OldSchool,
                                "Pump" to WorkoutMode.Pump,
                                "Echo" to WorkoutMode.Echo(echoLevel)
                            )
                            modes.forEachIndexed { index, (label, mode) ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                                    onClick = { selectedMode = mode },
                                    selected = selectedMode::class == mode::class,
                                    icon = {}
                                ) {
                                    Text(label, maxLines = 1)
                                }
                            }
                        }

                        Text(
                            when (selectedMode) {
                                is WorkoutMode.OldSchool -> "Constant resistance throughout the movement"
                                is WorkoutMode.Pump -> "Resistance increases the faster you go"
                                is WorkoutMode.Echo -> "Adaptive resistance with echo feedback"
                                else -> selectedMode.displayName
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }


                // Mode-specific options - OLD SCHOOL & PUMP
                val isOldSchoolOrPump = selectedMode is WorkoutMode.OldSchool || selectedMode is WorkoutMode.Pump
                if (isOldSchoolOrPump) {
                    // Weight per Cable Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(Spacing.medium),
                            verticalArrangement = Arrangement.Center
                        ) {
                            val weightSuffix = if (weightUnit == WeightUnit.LB) "lbs" else "kg"
                            val maxWeight = if (weightUnit == WeightUnit.LB) 242f else 110f  // 110kg per cable max
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
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(Spacing.medium),
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Text(
                                "Weight Change Per Rep",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            ProgressionSlider(
                                value = weightChangePerRep.toFloat(),
                                onValueChange = { weightChangePerRep = it.toInt() },
                                valueRange = -10f..10f,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                "Negative = Regression, Positive = Progression",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Mode-specific options - ECHO MODE
                val isEchoMode = selectedMode is WorkoutMode.Echo
                if (isEchoMode) {
                    // Eccentric Load Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(Spacing.medium),
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Text(
                                "Eccentric Load",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            var expanded by remember { mutableStateOf(false) }

                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = it }
                            ) {
                                OutlinedTextField(
                                    value = eccentricLoad.displayName,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth(),
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                                )

                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    EccentricLoad.entries.forEach { load ->
                                        DropdownMenuItem(
                                            text = { Text(load.displayName) },
                                            onClick = {
                                                eccentricLoad = load
                                                expanded = false
                                            },
                                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                        )
                                    }
                                }
                            }

                            Text(
                                "Load during eccentric (lowering) phase",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Echo Level Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(Spacing.medium),
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Text(
                                "Echo Level",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                EchoLevel.entries.forEachIndexed { index, level ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = EchoLevel.entries.size),
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

                // Active workout status (replaces mode cards when active)
                if (workoutState !is WorkoutState.Idle) {
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

            // Connection error dialog (ConnectingOverlay removed - status shown in top bar button)
            connectionError?.let { error ->
                com.devil.phoenixproject.presentation.components.ConnectionErrorDialog(
                    message = error,
                    onDismiss = { viewModel.clearConnectionError() }
                )
            }

            // Profile side panel
            ProfileSidePanel(
                profiles = profiles,
                activeProfile = activeProfile,
                profileRepository = profileRepository,
                scope = scope,
                onAddProfile = { showAddProfileDialog = true }
            )
        // Add Profile Dialog
        if (showAddProfileDialog) {
            AddProfileDialog(
                profiles = profiles,
                profileRepository = profileRepository,
                scope = scope,
                onDismiss = { showAddProfileDialog = false }
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

// Auto-start theme colors
private val AutoStartGreen = Color(0xFF10B981)
private val AutoStartGreenLight = Color(0xFF34D399)
private val CountdownOrange = Color(0xFFF59E0B)
private val CountdownOrangeLight = Color(0xFFFBBF24)
private val StopRed = Color(0xFFEF4444)
private val StopRedLight = Color(0xFFF87171)

/**
 * Compact Auto-Start/Auto-Stop Banner for Just Lift Mode
 * Features animated rings, pulsing glow, and countdown display
 */
@Composable
fun AutoStartStopCard(
    workoutState: WorkoutState,
    autoStartCountdown: Int?,
    autoStopState: AutoStopUiState
) {
    val isIdle = workoutState is WorkoutState.Idle
    val isActive = workoutState is WorkoutState.Active
    val isCountingDown = autoStartCountdown != null
    val isStopping = autoStopState.isActive

    // Only show when relevant
    if (!isIdle && !isActive) return

    // Infinite transition for continuous animations
    val infiniteTransition = rememberInfiniteTransition(label = "autoStart")

    // Pulse animation for "ready" state
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Glow intensity animation
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    // Rotating ring animation for countdown
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Determine colors based on state
    val primaryColor = when {
        isStopping -> StopRed
        isCountingDown -> CountdownOrange
        else -> AutoStartGreen
    }
    val secondaryColor = when {
        isStopping -> StopRedLight
        isCountingDown -> CountdownOrangeLight
        else -> AutoStartGreenLight
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isCountingDown || isStopping) 8.dp else 4.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Animated indicator
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isCountingDown || isStopping) {
                    // Rotating ring
                    Canvas(
                        modifier = Modifier
                            .size(56.dp)
                            .alpha(0.6f)
                    ) {
                        rotate(ringRotation) {
                            drawArc(
                                color = primaryColor,
                                startAngle = 0f,
                                sweepAngle = 120f,
                                useCenter = false,
                                style = Stroke(width = 3f, cap = StrokeCap.Round),
                                size = Size(size.width, size.height)
                            )
                        }
                    }

                    // Progress ring
                    val progress = if (isStopping) autoStopState.progress else 0.6f
                    Canvas(modifier = Modifier.size(44.dp)) {
                        drawArc(
                            color = primaryColor.copy(alpha = 0.2f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 4f, cap = StrokeCap.Round),
                            size = Size(size.width, size.height)
                        )
                        drawArc(
                            color = primaryColor,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            style = Stroke(width = 4f, cap = StrokeCap.Round),
                            size = Size(size.width, size.height)
                        )
                    }

                    // Countdown number
                    val countValue = if (isStopping) autoStopState.secondsRemaining else autoStartCountdown ?: 0
                    AnimatedContent(
                        targetState = countValue,
                        transitionSpec = {
                            (scaleIn(initialScale = 1.3f, animationSpec = tween(200)) + fadeIn())
                                .togetherWith(scaleOut(targetScale = 0.7f) + fadeOut())
                        },
                        label = "countdown"
                    ) { count ->
                        Text(
                            text = "$count",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = primaryColor
                        )
                    }
                } else {
                    // Pulsing glow for ready state
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .scale(pulseScale)
                            .alpha(glowAlpha * 0.5f)
                            .blur(12.dp)
                            .background(primaryColor, CircleShape)
                    )

                    // Handle grip icon - two parallel bars
                    Canvas(
                        modifier = Modifier
                            .size(40.dp)
                            .scale(pulseScale)
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
                                (size.height - barHeight) / 2
                            ),
                            size = Size(barWidth, barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius)
                        )

                        // Right handle bar
                        drawRoundRect(
                            color = primaryColor,
                            topLeft = Offset(
                                (size.width + spacing) / 2,
                                (size.height - barHeight) / 2
                            ),
                            size = Size(barWidth, barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius)
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
                                    y - gripHeight / 2
                                ),
                                size = Size(gripWidth, gripHeight),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f)
                            )
                            // Right grip marks
                            drawRoundRect(
                                color = gripColor,
                                topLeft = Offset(
                                    (size.width + spacing) / 2 + barWidth - gripWidth + 2f,
                                    y - gripHeight / 2
                                ),
                                size = Size(gripWidth, gripHeight),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.medium))

            // Center: Text content
            Column(modifier = Modifier.weight(1f)) {
                // Status label
                Text(
                    text = when {
                        isStopping -> "AUTO-STOP"
                        isCountingDown -> "STARTING..."
                        isActive -> "AUTO-STOP READY"
                        else -> "AUTO-START READY"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )

                // Instruction
                Text(
                    text = when {
                        isStopping -> "Release handles to stop"
                        isCountingDown -> "Hold steady"
                        isActive -> "Release handles for 5s"
                        else -> "Grab handles to start"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Right: Status indicator dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .scale(if (!isCountingDown && !isStopping) pulseScale else 1f)
                    .background(
                        color = primaryColor.copy(alpha = glowAlpha),
                        shape = CircleShape
                    )
            )
        }
    }
}
