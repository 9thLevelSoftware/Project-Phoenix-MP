package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.CompactNumberPicker
import com.devil.phoenixproject.presentation.components.ProgressionSlider
import com.devil.phoenixproject.presentation.components.VideoPlayer
import com.devil.phoenixproject.presentation.viewmodel.ExerciseConfigViewModel
import com.devil.phoenixproject.presentation.viewmodel.ExerciseType
import com.devil.phoenixproject.presentation.viewmodel.SetConfiguration
import com.devil.phoenixproject.presentation.viewmodel.SetMode
import com.devil.phoenixproject.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    onSave: (RoutineExercise) -> Unit,
    onDismiss: () -> Unit,
    buttonText: String = "Save"
) {
    // Create local ViewModel instance with PersonalRecordRepository for PR lookups
    val viewModel = remember { ExerciseConfigViewModel(personalRecordRepository) }

    // Fetch videos for exercise
    var videos by remember { mutableStateOf<List<ExerciseVideoEntity>>(emptyList()) }
    LaunchedEffect(exercise.exercise.id) {
        exercise.exercise.id?.let { exerciseId ->
            try {
                videos = exerciseRepository.getVideos(exerciseId)
            } catch (_: Exception) {
                // Handle error - videos will remain empty
            }
        }
    }
    val preferredVideo = videos.firstOrNull { it.angle == "FRONT" } ?: videos.firstOrNull()

    // Initialize the ViewModel - PR loading is now handled internally by the ViewModel
    LaunchedEffect(exercise, weightUnit) {
        viewModel.initialize(exercise, weightUnit, kgToDisplay, displayToKg)
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

    // PR weight from ViewModel - automatically updates when mode changes
    val currentExercisePR by viewModel.currentExercisePR.collectAsState()

    // PR percentage scaling state (Issue #57)
    val usePercentOfPR by viewModel.usePercentOfPR.collectAsState()
    val weightPercentOfPR by viewModel.weightPercentOfPR.collectAsState()

    val weightSuffix = if (weightUnit == WeightUnit.LB) "lbs" else "kg"
    val maxWeight = if (weightUnit == WeightUnit.LB) 242f else 110f  // 110kg per cable max
    val weightStep = if (weightUnit == WeightUnit.LB) 0.5f else 0.25f
    val maxWeightChange = 10

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
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.small)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Configure Exercise",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        exercise.exercise.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = dismissSheet) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Scrollable content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                // Video Player
                if (enableVideoPlayback) {
                    preferredVideo?.let { video ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            VideoPlayer(
                                videoUrl = video.videoUrl,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // Personal Record Display
                currentExercisePR?.let { pr ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                            ) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = "Personal Record",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column {
                                    Text(
                                        "Personal Record",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        "${formatWeight(pr.weightPerCableKg, weightUnit)}/cable x ${pr.reps} reps",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // Weight Configuration Section (PR Percentage Scaling - Issue #57)
                // Only show for standard exercises (not bodyweight)
                if (exerciseType == ExerciseType.STANDARD) {
                    WeightConfigurationCard(
                        usePercentOfPR = usePercentOfPR,
                        weightPercentOfPR = weightPercentOfPR,
                        currentExercisePR = currentExercisePR,
                        weightUnit = weightUnit,
                        formatWeight = formatWeight,
                        onUsePercentOfPRChange = viewModel::onUsePercentOfPRChange,
                        onWeightPercentOfPRChange = viewModel::onWeightPercentOfPRChange
                    )
                }

                // Mode Selector
                if (exerciseType == ExerciseType.STANDARD) {
                    ModeSelector(
                        selectedMode = selectedMode,
                        onModeChange = viewModel::onSelectedModeChange
                    )
                }

                // TUT Beast toggle
                val isTutMode = selectedMode is WorkoutMode.TUT || selectedMode is WorkoutMode.TUTBeast
                if (isTutMode) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                        shadowElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.small),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Beast Mode",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Switch(
                                checked = selectedMode is WorkoutMode.TUTBeast,
                                onCheckedChange = { isBeast ->
                                    viewModel.onSelectedModeChange(if (isBeast) WorkoutMode.TUTBeast else WorkoutMode.TUT)
                                }
                            )
                        }
                    }
                }

                // Echo Mode options
                val isEchoMode = selectedMode is WorkoutMode.Echo
                if (isEchoMode) {
                    EccentricLoadSelector(
                        eccentricLoad = eccentricLoad,
                        onLoadChange = viewModel::onEccentricLoadChange
                    )
                    EchoLevelSelector(
                        level = echoLevel,
                        onLevelChange = viewModel::onEchoLevelChange
                    )
                }

                // Weight Change Per Rep
                if (exerciseType == ExerciseType.STANDARD && !isEchoMode) {
                    Card(
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

                            ProgressionSlider(
                                value = weightChange.toFloat(),
                                onValueChange = { viewModel.onWeightChange(it.toInt()) },
                                valueRange = -maxWeightChange.toFloat()..maxWeightChange.toFloat(),
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

                // Set Mode Toggle
                SetModeToggle(
                    setMode = setMode,
                    onModeChange = viewModel::onSetModeChange
                )

                // Per Set Rest Time toggle
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.small),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Per Set Rest Time",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (perSetRestTime) FontWeight.Bold else FontWeight.Normal,
                            color = if (perSetRestTime) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = perSetRestTime,
                            onCheckedChange = viewModel::onPerSetRestTimeChange
                        )
                    }
                }

                // Stall Detection toggle - show when any set is AMRAP
                val hasAMRAPSets = sets.any { it.reps == null }
                if (hasAMRAPSets) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                        shadowElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.small),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Stall Detection",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (stallDetectionEnabled) FontWeight.Bold else FontWeight.Normal,
                                    color = if (stallDetectionEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Auto-stop set when movement pauses for 5 seconds",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = stallDetectionEnabled,
                                onCheckedChange = viewModel::onStallDetectionEnabledChange
                            )
                        }
                    }
                }

                // Sets Configuration
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
                    onDeleteSet = viewModel::deleteSet
                )

                // Single rest time picker
                if (!perSetRestTime) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                        shadowElevation = 2.dp
                    ) {
                        Column(modifier = Modifier.padding(Spacing.small)) {
                            Text(
                                "Rest Time: ${rest}s",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = Spacing.extraSmall)
                            )
                            Slider(
                                value = rest.toFloat(),
                                onValueChange = { viewModel.onRestChange(it.toInt()) },
                                valueRange = 0f..300f,
                                steps = 59,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Bottom actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                TextButton(
                    onClick = dismissSheet,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Button(
                    onClick = { viewModel.onSave(onSave) },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    enabled = sets.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(20.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 2.dp
                    )
                ) {
                    Text(
                        buttonText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun SetModeToggle(
    setMode: SetMode,
    onModeChange: (SetMode) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        Text(
            "Set Mode",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = Spacing.extraSmall)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = setMode == SetMode.REPS,
                onClick = { onModeChange(SetMode.REPS) },
                label = { Text("Reps") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = setMode == SetMode.DURATION,
                onClick = { onModeChange(SetMode.DURATION) },
                label = { Text("Duration") },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun SetsConfiguration(
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
    onDeleteSet: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        Text(
            "Sets & ${if (setMode == SetMode.REPS) "Reps" else "Duration"}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = Spacing.extraSmall)
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
                    perSetRestTime = perSetRestTime
                )
            }
        }

        // Add Set button
        OutlinedButton(
            onClick = onAddSet,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add set",
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.small))
            Text(
                "Add Set",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SetRow(
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
    perSetRestTime: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            // Set label and Delete button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Set ${setConfig.setNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onDelete,
                    enabled = canDelete
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete set",
                        tint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // AMRAP toggle
            if (setMode == SetMode.REPS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = setConfig.reps == null,
                        onCheckedChange = { isAMRAP ->
                            onRepsChange(if (isAMRAP) null else 10)
                        }
                    )
                    Text(
                        text = "AMRAP (As Many Reps As Possible)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (setConfig.reps == null) FontWeight.Bold else FontWeight.Normal,
                        color = if (setConfig.reps == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))
            }

            // Reps/Duration and Weight side-by-side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                // Reps or Duration picker
                Box(modifier = Modifier.weight(1f)) {
                    if (setMode == SetMode.REPS) {
                        if (setConfig.reps != null) {
                            CompactNumberPicker(
                                value = setConfig.reps,
                                onValueChange = onRepsChange,
                                range = 1..50,
                                label = if (setConfig.setNumber == 1) "Reps" else "",
                                suffix = "reps",
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            // AMRAP label
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (setConfig.setNumber == 1) {
                                    Text(
                                        "Target",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(if (setConfig.setNumber == 1) 60.dp else 80.dp))
                                Text(
                                    "AMRAP",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        CompactNumberPicker(
                            value = setConfig.duration,
                            onValueChange = onDurationChange,
                            range = 10..300,
                            label = if (setConfig.setNumber == 1) "Duration" else "",
                            suffix = "sec",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Weight picker
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        isEchoMode -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (setConfig.setNumber == 1) {
                                    Text(
                                        "Force per Cable",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(if (setConfig.setNumber == 1) 60.dp else 80.dp))
                                Text(
                                    "Adaptive",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        exerciseType == ExerciseType.STANDARD -> {
                            CompactNumberPicker(
                                value = setConfig.weightPerCable,
                                onValueChange = onWeightChange,
                                range = 1f..maxWeight,
                                step = weightStep,
                                label = if (setConfig.setNumber == 1) "Weight per Cable" else "",
                                suffix = weightSuffix,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (setConfig.setNumber == 1) {
                                    Text(
                                        "Weight",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(if (setConfig.setNumber == 1) 60.dp else 80.dp))
                                Text(
                                    "Bodyweight",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Rest Time picker (per-set)
            if (perSetRestTime) {
                Text(
                    "Rest Time: ${setConfig.restSeconds}s",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = Spacing.extraSmall)
                )
                Slider(
                    value = setConfig.restSeconds.toFloat(),
                    onValueChange = { onRestChange(it.toInt()) },
                    valueRange = 0f..300f,
                    steps = 59,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ModeSelector(
    selectedMode: WorkoutMode,
    onModeChange: (WorkoutMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val allModes = listOf(
        WorkoutMode.OldSchool,
        WorkoutMode.Pump,
        WorkoutMode.TUT,
        WorkoutMode.EccentricOnly,
        WorkoutMode.Echo(EchoLevel.HARDER)
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            Text(
                "Workout Mode",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = Spacing.small)
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
                                contentDescription = if (expanded) "Collapse" else "Expand"
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
                        }
                )

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    allModes.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.displayName) },
                            onClick = {
                                onModeChange(mode)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EccentricLoadSelector(
    eccentricLoad: EccentricLoad,
    onLoadChange: (EccentricLoad) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
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
                "Eccentric Load",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.small))

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
                                onLoadChange(load)
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            Text(
                "Load percentage applied during eccentric (lowering) phase",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoLevelSelector(
    level: EchoLevel,
    onLevelChange: (EchoLevel) -> Unit
) {
    Card(
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
                levels.forEachIndexed { index, echoLevel ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = levels.size),
                        onClick = { onLevelChange(echoLevel) },
                        selected = level == echoLevel
                    ) {
                        Text(echoLevel.displayName, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Weight Configuration Card - allows toggling between absolute weight and percentage-of-PR
 * Issue #57: PR percentage scaling feature
 */
@Composable
fun WeightConfigurationCard(
    usePercentOfPR: Boolean,
    weightPercentOfPR: Int,
    currentExercisePR: PersonalRecord?,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onUsePercentOfPRChange: (Boolean) -> Unit,
    onWeightPercentOfPRChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            Text(
                "Weight Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Toggle between absolute weight and % of PR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use % of PR",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (usePercentOfPR) FontWeight.Bold else FontWeight.Normal,
                        color = if (usePercentOfPR) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (currentExercisePR != null) {
                            "Scale weight based on your personal record"
                        } else {
                            "No PR set for this exercise"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = usePercentOfPR,
                    onCheckedChange = onUsePercentOfPRChange,
                    enabled = currentExercisePR != null
                )
            }

            // Show percentage controls when toggle is ON and PR exists
            if (usePercentOfPR && currentExercisePR != null) {
                Spacer(modifier = Modifier.height(Spacing.medium))

                // Calculate resolved weight
                val resolvedWeight = (currentExercisePR.weightPerCableKg * weightPercentOfPR / 100f)
                    .let { (it * 2).roundToInt() / 2f } // Round to 0.5kg

                // Display current percentage and resolved weight
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$weightPercentOfPR% of PR",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "= ${formatWeight(resolvedWeight, weightUnit)}/cable",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Percentage slider (50% - 120%, 5% increments)
                Slider(
                    value = weightPercentOfPR.toFloat(),
                    onValueChange = { onWeightPercentOfPRChange(it.toInt()) },
                    valueRange = 50f..120f,
                    steps = 13, // (120-50)/5 - 1 = 13 steps for 5% increments
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(Spacing.small))

                // Common preset buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                ) {
                    listOf(70, 80, 90, 100).forEach { percent ->
                        FilterChip(
                            selected = weightPercentOfPR == percent,
                            onClick = { onWeightPercentOfPRChange(percent) },
                            label = { Text("$percent%") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Show warning if no PR available and toggle is off
            if (currentExercisePR == null && !usePercentOfPR) {
                Spacer(modifier = Modifier.height(Spacing.small))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.small),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Complete a workout to set your PR and enable percentage scaling",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
    }
}
