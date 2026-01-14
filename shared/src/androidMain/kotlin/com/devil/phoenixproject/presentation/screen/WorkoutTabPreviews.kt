package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.usecase.RepRanges
import com.devil.phoenixproject.presentation.components.AutoStopOverlay
import com.devil.phoenixproject.presentation.components.AutoStartOverlay
import com.devil.phoenixproject.presentation.components.EnhancedCablePositionBar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Preview of the full WorkoutTab in active workout state with position bars visible.
 * Shows mock data simulating a connected machine mid-workout.
 */
@Preview(
    name = "WorkoutTab - Active Workout",
    showBackground = true,
    backgroundColor = 0xFF0F172A,
    widthDp = 400,
    heightDp = 800
)
@Composable
private fun WorkoutTabActivePreview() {
    val mockMetric = WorkoutMetric(
        timestamp = System.currentTimeMillis(),
        loadA = 25f,
        loadB = 25f,
        positionA = 450f,  // Left cable mid-pull (mm)
        positionB = 520f,  // Right cable slightly higher (mm)
        velocityA = 120.0,
        velocityB = 115.0,
        ticks = 12345,
        status = 0
    )

    val mockRepRanges = RepRanges(
        minPosA = 80f,
        maxPosA = 750f,
        minPosB = 85f,
        maxPosB = 760f,
        minRangeA = Pair(50f, 120f),
        maxRangeA = Pair(700f, 800f),
        minRangeB = Pair(55f, 115f),
        maxRangeB = Pair(710f, 810f)
    )

    val mockParameters = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        weightPerCableKg = 25f,
        reps = 12,
        warmupReps = 3,
        isJustLift = false,
        stopAtTop = true
    )

    val mockRepCount = RepCount(
        warmupReps = 3,
        workingReps = 7,
        isWarmupComplete = true,
        hasPendingRep = false
    )

    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Connected(
                deviceName = "Vee_Preview",
                deviceAddress = "00:11:22:33:44:55"
            ),
            workoutState = WorkoutState.Active,
            currentMetric = mockMetric,
            workoutParameters = mockParameters,
            repCount = mockRepCount,
            repRanges = mockRepRanges,
            autoStopState = AutoStopUiState(isActive = false, secondsRemaining = 5, progress = 0f),
            weightUnit = WeightUnit.KG,
            enableVideoPlayback = true,  // Show video placeholder
            exerciseRepository = PreviewExerciseRepository(),
            isWorkoutSetupDialogVisible = false,
            hapticEvents = null,
            loadedRoutine = null,
            currentExerciseIndex = 0,
            autoplayEnabled = false,
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onProceedFromSummary = {},
            onResetForNewWorkout = {},
            onStartNextExercise = {},
            onUpdateParameters = {},
            onShowWorkoutSetupDialog = {},
            onHideWorkoutSetupDialog = {},
            showConnectionCard = false,
            showWorkoutSetupCard = false
        )
    }
}

/**
 * Preview of the enhanced vertical position bars showing different movement phases.
 */
@Preview(
    name = "Enhanced Position Bars",
    showBackground = true,
    backgroundColor = 0xFF1E293B,
    widthDp = 220,
    heightDp = 400
)
@Composable
private fun EnhancedPositionBarsPreview() {
    MaterialTheme {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Concentric phase (lifting) - Green
            EnhancedCablePositionBar(
                label = "L",
                currentPosition = 600f,  // mm
                velocity = 100.0,  // Positive = concentric
                minPosition = 200f,
                maxPosition = 800f,
                ghostMin = 180f,
                ghostMax = 820f,
                isActive = true,
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight()
            )

            // Eccentric phase (lowering) - Orange
            EnhancedCablePositionBar(
                label = "R",
                currentPosition = 400f,  // mm
                velocity = -100.0,  // Negative = eccentric
                minPosition = 200f,
                maxPosition = 800f,
                ghostMin = 180f,
                ghostMax = 820f,
                isActive = true,
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight()
            )

            // Static/holding - Blue
            EnhancedCablePositionBar(
                label = "H",
                currentPosition = 500f,  // mm
                velocity = 0.0,  // Near zero = static
                minPosition = 200f,
                maxPosition = 800f,
                isActive = true,
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight()
            )

            // Inactive - Grey
            EnhancedCablePositionBar(
                label = "X",
                currentPosition = 300f,  // mm
                velocity = 0.0,
                isActive = false,
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight()
            )
        }
    }
}

/**
 * Preview of WorkoutTab in disconnected state - shows scan button.
 */
@Preview(
    name = "WorkoutTab - Disconnected",
    showBackground = true,
    backgroundColor = 0xFFF8FAFC,
    widthDp = 400,
    heightDp = 600
)
@Composable
private fun WorkoutTabDisconnectedPreview() {
    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Disconnected,
            workoutState = WorkoutState.Idle,
            currentMetric = null,
            workoutParameters = WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 10
            ),
            repCount = RepCount(),
            repRanges = null,
            autoStopState = AutoStopUiState(isActive = false, secondsRemaining = 5, progress = 0f),
            weightUnit = WeightUnit.KG,
            enableVideoPlayback = false,
            exerciseRepository = PreviewExerciseRepository(),
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onResetForNewWorkout = {},
            onUpdateParameters = {}
        )
    }
}

/**
 * Preview of WorkoutTab in scanning state.
 */
@Preview(
    name = "WorkoutTab - Scanning",
    showBackground = true,
    backgroundColor = 0xFFF8FAFC,
    widthDp = 400,
    heightDp = 600
)
@Composable
private fun WorkoutTabScanningPreview() {
    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Scanning,
            workoutState = WorkoutState.Idle,
            currentMetric = null,
            workoutParameters = WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 10
            ),
            repCount = RepCount(),
            repRanges = null,
            autoStopState = AutoStopUiState(isActive = false, secondsRemaining = 5, progress = 0f),
            weightUnit = WeightUnit.KG,
            enableVideoPlayback = false,
            exerciseRepository = PreviewExerciseRepository(),
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onResetForNewWorkout = {},
            onUpdateParameters = {}
        )
    }
}

/**
 * Preview of WorkoutTab in connected/idle state - shows workout setup card.
 */
@Preview(
    name = "WorkoutTab - Connected Idle",
    showBackground = true,
    backgroundColor = 0xFFF8FAFC,
    widthDp = 400,
    heightDp = 600
)
@Composable
private fun WorkoutTabConnectedIdlePreview() {
    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Connected(
                deviceName = "Vee_Preview",
                deviceAddress = "00:11:22:33:44:55"
            ),
            workoutState = WorkoutState.Idle,
            currentMetric = null,
            workoutParameters = WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                weightPerCableKg = 20f,
                reps = 10
            ),
            repCount = RepCount(),
            repRanges = null,
            autoStopState = AutoStopUiState(isActive = false, secondsRemaining = 5, progress = 0f),
            weightUnit = WeightUnit.KG,
            enableVideoPlayback = false,
            exerciseRepository = PreviewExerciseRepository(),
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onResetForNewWorkout = {},
            onUpdateParameters = {},
            onShowWorkoutSetupDialog = {}
        )
    }
}

/**
 * Preview of WorkoutTab in countdown state - shows countdown before workout starts.
 */
@Preview(
    name = "WorkoutTab - Countdown",
    showBackground = true,
    backgroundColor = 0xFF0F172A,
    widthDp = 400,
    heightDp = 800
)
@Composable
private fun WorkoutTabCountdownPreview() {
    val mockParameters = WorkoutParameters(
        programMode = ProgramMode.Pump,
        weightPerCableKg = 30f,
        reps = 15,
        warmupReps = 3,
        isJustLift = false
    )

    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Connected(
                deviceName = "Vee_Preview",
                deviceAddress = "00:11:22:33:44:55"
            ),
            workoutState = WorkoutState.Countdown(secondsRemaining = 5),
            currentMetric = null,
            workoutParameters = mockParameters,
            repCount = RepCount(),
            repRanges = null,
            autoStopState = AutoStopUiState(isActive = false, secondsRemaining = 5, progress = 0f),
            weightUnit = WeightUnit.KG,
            enableVideoPlayback = false,
            exerciseRepository = PreviewExerciseRepository(),
            loadedRoutine = Routine(
                id = "preview-routine",
                name = "Preview Routine",
                exercises = listOf(
                    RoutineExercise(
                        id = "re-1",
                        exercise = Exercise(
                            name = "Bench Press",
                            muscleGroup = "Chest",
                            equipment = "Vitruvian",
                            id = "bench-press"
                        ),
                        cableConfig = CableConfiguration.DOUBLE,
                        orderIndex = 0,
                        weightPerCableKg = 30f,
                        setReps = listOf(12, 12, 12),
                        setWeightsPerCableKg = listOf(30f, 30f, 30f),
                        programMode = ProgramMode.Pump
                    )
                )
            ),
            currentExerciseIndex = 0,
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onResetForNewWorkout = {},
            onUpdateParameters = {},
            showConnectionCard = false,
            showWorkoutSetupCard = false
        )
    }
}

/**
 * Preview of WorkoutTab in resting state - shows rest timer between sets.
 */
@Preview(
    name = "WorkoutTab - Resting",
    showBackground = true,
    backgroundColor = 0xFF0F172A,
    widthDp = 400,
    heightDp = 800
)
@Composable
private fun WorkoutTabRestingPreview() {
    val mockParameters = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        weightPerCableKg = 25f,
        reps = 12,
        warmupReps = 3
    )

    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Connected(
                deviceName = "Vee_Preview",
                deviceAddress = "00:11:22:33:44:55"
            ),
            workoutState = WorkoutState.Resting(
                restSecondsRemaining = 45,
                nextExerciseName = "Bicep Curls",
                isLastExercise = false,
                currentSet = 2,
                totalSets = 4
            ),
            currentMetric = null,
            workoutParameters = mockParameters,
            repCount = RepCount(
                warmupReps = 3,
                workingReps = 12,
                isWarmupComplete = true
            ),
            repRanges = null,
            autoStopState = AutoStopUiState(isActive = false, secondsRemaining = 5, progress = 0f),
            weightUnit = WeightUnit.KG,
            enableVideoPlayback = false,
            exerciseRepository = PreviewExerciseRepository(),
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onResetForNewWorkout = {},
            onUpdateParameters = {},
            showConnectionCard = false,
            showWorkoutSetupCard = false
        )
    }
}

/**
 * Preview of WorkoutTab in set summary state - shows enhanced stats after completing a set.
 * Updated to showcase the new SetSummaryCard matching the official Vitruvian app design.
 */
@Preview(
    name = "WorkoutTab - Set Summary (Enhanced)",
    showBackground = true,
    backgroundColor = 0xFFF8FAFC,
    widthDp = 400,
    heightDp = 800
)
@Composable
private fun WorkoutTabSetSummaryPreview() {
    val mockParameters = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        weightPerCableKg = 25f,
        reps = 12
    )

    val mockMetrics = listOf(
        WorkoutMetric(
            timestamp = System.currentTimeMillis(),
            loadA = 25f, loadB = 25f,
            positionA = 500f, positionB = 500f,
            velocityA = 100.0, velocityB = 100.0,
            ticks = 1000, status = 0
        )
    )

    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Connected(
                deviceName = "Vee_Preview",
                deviceAddress = "00:11:22:33:44:55"
            ),
            workoutState = WorkoutState.SetSummary(
                metrics = mockMetrics,
                peakPower = 450f,
                averagePower = 320f,
                repCount = 12,
                durationMs = 95000L,  // 1:35
                totalVolumeKg = 600f,  // 12 reps * 25kg * 2 cables
                heaviestLiftKgPerCable = 27.5f,
                peakForceConcentricA = 28.5f,
                peakForceConcentricB = 27.8f,
                peakForceEccentricA = 26.2f,
                peakForceEccentricB = 25.9f,
                avgForceConcentricA = 25.0f,
                avgForceConcentricB = 24.8f,
                avgForceEccentricA = 24.5f,
                avgForceEccentricB = 24.2f,
                estimatedCalories = 18.5f
            ),
            currentMetric = null,
            workoutParameters = mockParameters,
            repCount = RepCount(
                warmupReps = 3,
                workingReps = 12,
                isWarmupComplete = true
            ),
            repRanges = null,
            autoStopState = AutoStopUiState(isActive = false, secondsRemaining = 5, progress = 0f),
            weightUnit = WeightUnit.KG,
            enableVideoPlayback = false,
            exerciseRepository = PreviewExerciseRepository(),
            autoplayEnabled = false,
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onProceedFromSummary = {},
            onResetForNewWorkout = {},
            onUpdateParameters = {},
            showConnectionCard = false,
            showWorkoutSetupCard = false
        )
    }
}

/**
 * Preview of Set Summary with autoplay enabled - shows countdown timer on Done button
 */
@Preview(
    name = "WorkoutTab - Set Summary (Autoplay)",
    showBackground = true,
    backgroundColor = 0xFFF8FAFC,
    widthDp = 400,
    heightDp = 800
)
@Composable
private fun WorkoutTabSetSummaryAutoplayPreview() {
    val mockParameters = WorkoutParameters(
        programMode = ProgramMode.Pump,
        weightPerCableKg = 20f,
        reps = 15
    )

    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Connected(
                deviceName = "Vee_Preview",
                deviceAddress = "00:11:22:33:44:55"
            ),
            workoutState = WorkoutState.SetSummary(
                metrics = emptyList(),
                peakPower = 380f,
                averagePower = 280f,
                repCount = 15,
                durationMs = 120000L,  // 2:00
                totalVolumeKg = 600f,
                heaviestLiftKgPerCable = 22.0f,
                peakForceConcentricA = 23.5f,
                peakForceConcentricB = 23.0f,
                peakForceEccentricA = 21.8f,
                peakForceEccentricB = 21.5f,
                avgForceConcentricA = 20.0f,
                avgForceConcentricB = 19.8f,
                avgForceEccentricA = 19.5f,
                avgForceEccentricB = 19.2f,
                estimatedCalories = 24.0f
            ),
            currentMetric = null,
            workoutParameters = mockParameters,
            repCount = RepCount(workingReps = 15, isWarmupComplete = true),
            repRanges = null,
            autoStopState = AutoStopUiState(isActive = false, secondsRemaining = 5, progress = 0f),
            weightUnit = WeightUnit.LB,  // Test with pounds
            enableVideoPlayback = false,
            exerciseRepository = PreviewExerciseRepository(),
            autoplayEnabled = true,  // Autoplay enabled - shows countdown
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onProceedFromSummary = {},
            onResetForNewWorkout = {},
            onUpdateParameters = {},
            showConnectionCard = false,
            showWorkoutSetupCard = false
        )
    }
}

/**
 * Preview of WorkoutTab in completed state - shows workout finished.
 */
@Preview(
    name = "WorkoutTab - Completed",
    showBackground = true,
    backgroundColor = 0xFFF8FAFC,
    widthDp = 400,
    heightDp = 600
)
@Composable
private fun WorkoutTabCompletedPreview() {
    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Connected(
                deviceName = "Vee_Preview",
                deviceAddress = "00:11:22:33:44:55"
            ),
            workoutState = WorkoutState.Completed,
            currentMetric = null,
            workoutParameters = WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                weightPerCableKg = 25f,
                reps = 12
            ),
            repCount = RepCount(
                warmupReps = 3,
                workingReps = 12,
                isWarmupComplete = true
            ),
            repRanges = null,
            autoStopState = AutoStopUiState(isActive = false, secondsRemaining = 5, progress = 0f),
            weightUnit = WeightUnit.KG,
            enableVideoPlayback = false,
            exerciseRepository = PreviewExerciseRepository(),
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onResetForNewWorkout = {},
            onUpdateParameters = {}
        )
    }
}

/**
 * Preview of WorkoutTab in completed state with more exercises in routine.
 */
@Preview(
    name = "WorkoutTab - Completed (More Exercises)",
    showBackground = true,
    backgroundColor = 0xFFF8FAFC,
    widthDp = 400,
    heightDp = 700
)
@Composable
private fun WorkoutTabCompletedWithNextExercisePreview() {
    val mockRoutine = Routine(
        id = "preview-routine",
        name = "Full Body Workout",
        exercises = listOf(
            RoutineExercise(
                id = "re-1",
                exercise = Exercise(
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    equipment = "Vitruvian",
                    id = "bench-press"
                ),
                cableConfig = CableConfiguration.DOUBLE,
                orderIndex = 0,
                weightPerCableKg = 30f,
                setReps = listOf(12, 12, 12),
                setWeightsPerCableKg = listOf(30f, 30f, 30f),
                programMode = ProgramMode.OldSchool
            ),
            RoutineExercise(
                id = "re-2",
                exercise = Exercise(
                    name = "Bent Over Rows",
                    muscleGroup = "Back",
                    equipment = "Vitruvian",
                    id = "rows"
                ),
                cableConfig = CableConfiguration.DOUBLE,
                orderIndex = 1,
                weightPerCableKg = 25f,
                setReps = listOf(10, 10, 10),
                setWeightsPerCableKg = listOf(25f, 25f, 25f),
                programMode = ProgramMode.OldSchool
            )
        )
    )

    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Connected(
                deviceName = "Vee_Preview",
                deviceAddress = "00:11:22:33:44:55"
            ),
            workoutState = WorkoutState.Completed,
            currentMetric = null,
            workoutParameters = WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                weightPerCableKg = 30f,
                reps = 12
            ),
            repCount = RepCount(
                warmupReps = 3,
                workingReps = 12,
                isWarmupComplete = true
            ),
            repRanges = null,
            autoStopState = AutoStopUiState(isActive = false, secondsRemaining = 5, progress = 0f),
            weightUnit = WeightUnit.KG,
            enableVideoPlayback = false,
            exerciseRepository = PreviewExerciseRepository(),
            loadedRoutine = mockRoutine,
            currentExerciseIndex = 0,  // First exercise done, second waiting
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onResetForNewWorkout = {},
            onStartNextExercise = {},
            onUpdateParameters = {}
        )
    }
}

/**
 * Preview of WorkoutTab in error state.
 */
@Preview(
    name = "WorkoutTab - Error",
    showBackground = true,
    backgroundColor = 0xFFF8FAFC,
    widthDp = 400,
    heightDp = 600
)
@Composable
private fun WorkoutTabErrorPreview() {
    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Connected(
                deviceName = "Vee_Preview",
                deviceAddress = "00:11:22:33:44:55"
            ),
            workoutState = WorkoutState.Error("Failed to start workout: Device not responding"),
            currentMetric = null,
            workoutParameters = WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 10
            ),
            repCount = RepCount(),
            repRanges = null,
            autoStopState = AutoStopUiState(isActive = false, secondsRemaining = 5, progress = 0f),
            weightUnit = WeightUnit.KG,
            enableVideoPlayback = false,
            exerciseRepository = PreviewExerciseRepository(),
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onResetForNewWorkout = {},
            onUpdateParameters = {}
        )
    }
}

/**
 * Preview of WorkoutTab in Just Lift mode with auto-stop active.
 */
@Preview(
    name = "WorkoutTab - Just Lift (Auto-Stop Active)",
    showBackground = true,
    backgroundColor = 0xFF0F172A,
    widthDp = 400,
    heightDp = 800
)
@Composable
private fun WorkoutTabJustLiftAutoStopPreview() {
    val mockMetric = WorkoutMetric(
        timestamp = System.currentTimeMillis(),
        loadA = 20f,
        loadB = 20f,
        positionA = 50f,  // Cables near bottom (user let go)
        positionB = 45f,
        velocityA = 0.0,
        velocityB = 0.0,
        ticks = 5000,
        status = 0
    )

    val mockParameters = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        weightPerCableKg = 20f,
        reps = 0,
        isJustLift = true
    )

    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Connected(
                deviceName = "Vee_Preview",
                deviceAddress = "00:11:22:33:44:55"
            ),
            workoutState = WorkoutState.Active,
            currentMetric = mockMetric,
            workoutParameters = mockParameters,
            repCount = RepCount(
                warmupReps = 0,
                workingReps = 8,
                isWarmupComplete = true
            ),
            repRanges = null,
            autoStopState = AutoStopUiState(
                isActive = true,
                secondsRemaining = 3,
                progress = 0.4f
            ),
            weightUnit = WeightUnit.KG,
            enableVideoPlayback = false,
            exerciseRepository = PreviewExerciseRepository(),
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onResetForNewWorkout = {},
            onUpdateParameters = {},
            showConnectionCard = false,
            showWorkoutSetupCard = false
        )
    }
}

/**
 * Preview of WorkoutTab in warmup phase.
 */
@Preview(
    name = "WorkoutTab - Warmup Phase",
    showBackground = true,
    backgroundColor = 0xFF0F172A,
    widthDp = 400,
    heightDp = 800
)
@Composable
private fun WorkoutTabWarmupPreview() {
    val mockMetric = WorkoutMetric(
        timestamp = System.currentTimeMillis(),
        loadA = 25f,
        loadB = 25f,
        positionA = 400f,
        positionB = 420f,
        velocityA = 80.0,
        velocityB = 85.0,
        ticks = 2000,
        status = 0
    )

    val mockParameters = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        weightPerCableKg = 25f,
        reps = 12,
        warmupReps = 3,
        isJustLift = false
    )

    MaterialTheme {
        WorkoutTab(
            connectionState = ConnectionState.Connected(
                deviceName = "Vee_Preview",
                deviceAddress = "00:11:22:33:44:55"
            ),
            workoutState = WorkoutState.Active,
            currentMetric = mockMetric,
            workoutParameters = mockParameters,
            repCount = RepCount(
                warmupReps = 2,
                workingReps = 0,
                isWarmupComplete = false,  // Still in warmup
                hasPendingRep = false
            ),
            repRanges = RepRanges(
                minPosA = 80f, maxPosA = 750f,
                minPosB = 85f, maxPosB = 760f,
                minRangeA = Pair(50f, 120f), maxRangeA = Pair(700f, 800f),
                minRangeB = Pair(55f, 115f), maxRangeB = Pair(710f, 810f)
            ),
            autoStopState = AutoStopUiState(isActive = false, secondsRemaining = 5, progress = 0f),
            weightUnit = WeightUnit.KG,
            enableVideoPlayback = false,
            exerciseRepository = PreviewExerciseRepository(),
            kgToDisplay = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
            displayToKg = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
            formatWeight = { weight, unit -> "${weight.toInt()} ${if (unit == WeightUnit.LB) "lbs" else "kg"}" },
            onScan = {},
            onCancelScan = {},
            onDisconnect = {},
            onStartWorkout = {},
            onStopWorkout = {},
            onSkipRest = {},
            onSkipCountdown = {},
            onResetForNewWorkout = {},
            onUpdateParameters = {},
            showConnectionCard = false,
            showWorkoutSetupCard = false
        )
    }
}

/**
 * Minimal ExerciseRepository for previews - returns empty data.
 */
private class PreviewExerciseRepository : ExerciseRepository {
    override fun getAllExercises(): Flow<List<Exercise>> = flowOf(emptyList())
    override fun searchExercises(query: String): Flow<List<Exercise>> = flowOf(emptyList())
    override fun filterByMuscleGroup(muscleGroup: String): Flow<List<Exercise>> = flowOf(emptyList())
    override fun filterByEquipment(equipment: String): Flow<List<Exercise>> = flowOf(emptyList())
    override fun getFavorites(): Flow<List<Exercise>> = flowOf(emptyList())
    override suspend fun toggleFavorite(id: String) {}
    override suspend fun getExerciseById(id: String): Exercise? = null
    override suspend fun getVideos(exerciseId: String): List<ExerciseVideoEntity> = emptyList()
    override suspend fun importExercises(): Result<Unit> = Result.success(Unit)
    override suspend fun isExerciseLibraryEmpty(): Boolean = true
    override suspend fun updateFromGitHub(): Result<Int> = Result.success(0)
    // Custom exercise methods
    override fun getCustomExercises(): Flow<List<Exercise>> = flowOf(emptyList())
    override suspend fun createCustomExercise(exercise: Exercise): Result<Exercise> = Result.success(exercise.copy(id = "preview_custom"))
    override suspend fun updateCustomExercise(exercise: Exercise): Result<Exercise> = Result.success(exercise)
    override suspend fun deleteCustomExercise(exerciseId: String): Result<Unit> = Result.success(Unit)
    // One Rep Max methods
    override suspend fun updateOneRepMax(exerciseId: String, oneRepMaxKg: Float?) {}
    override fun getExercisesWithOneRepMax(): Flow<List<Exercise>> = flowOf(emptyList())
    override suspend fun findByName(name: String): Exercise? = null
}

// ============================================================================
// COMPONENT PREVIEWS - AutoStop/AutoStart Overlays
// ============================================================================

/**
 * Standalone preview of the AutoStopOverlay component
 * Shows the pulsing card that appears when handles are released
 */
@Preview(
    name = "Component - AutoStop Overlay (Just Lift)",
    showBackground = true,
    backgroundColor = 0xFF0F172A,
    widthDp = 320,
    heightDp = 300
)
@Composable
private fun AutoStopOverlayJustLiftPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AutoStopOverlay(
                autoStopState = AutoStopUiState(
                    isActive = true,
                    secondsRemaining = 3,
                    progress = 0.4f
                ),
                isJustLift = true
            )
        }
    }
}

/**
 * AutoStopOverlay for regular workout (not Just Lift)
 */
@Preview(
    name = "Component - AutoStop Overlay (Regular)",
    showBackground = true,
    backgroundColor = 0xFF0F172A,
    widthDp = 320,
    heightDp = 300
)
@Composable
private fun AutoStopOverlayRegularPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AutoStopOverlay(
                autoStopState = AutoStopUiState(
                    isActive = true,
                    secondsRemaining = 2,
                    progress = 0.6f
                ),
                isJustLift = false
            )
        }
    }
}

/**
 * AutoStartOverlay - Shows when user picks up handles to start workout
 */
@Preview(
    name = "Component - AutoStart Overlay",
    showBackground = true,
    backgroundColor = 0xFF0F172A,
    widthDp = 320,
    heightDp = 300
)
@Composable
private fun AutoStartOverlayPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AutoStartOverlay(
                isActive = true,
                secondsRemaining = 3
            )
        }
    }
}
