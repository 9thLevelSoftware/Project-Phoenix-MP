package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.usecase.RepRanges

/**
 * UI State holder for WorkoutTab.
 * Consolidates all display-only state into a single immutable data class
 * to reduce parameter explosion and optimize recomposition.
 *
 * @property connectionState Current BLE connection status
 * @property workoutState Current workout lifecycle state
 * @property currentMetric Real-time workout metrics from machine
 * @property workoutParameters User-configured workout settings
 * @property repCount Current rep counts (warmup + working)
 * @property repRanges Detected min/max positions for rep counting
 * @property autoStopState Auto-stop countdown state for Just Lift mode
 * @property autoStartCountdown Countdown seconds when auto-starting
 * @property weightUnit User's preferred weight display unit
 * @property enableVideoPlayback Whether to show exercise demo videos
 * @property loadedRoutine Currently loaded routine (null for single exercise)
 * @property currentExerciseIndex Index in routine's exercise list
 * @property currentSetIndex Index of current set (0-based) within current exercise
 * @property skippedExercises Indices of exercises user skipped
 * @property completedExercises Indices of completed exercises
 * @property autoplayEnabled Whether to auto-advance after set summary
 * @property canGoBack Whether user can navigate to previous exercise
 * @property canSkipForward Whether user can skip to next exercise
 * @property isWorkoutSetupDialogVisible Whether setup dialog is shown
 * @property showConnectionCard Whether to show connection status card
 * @property showWorkoutSetupCard Whether to show workout setup button
 * @property loadBaselineA Load baseline for cable A (base tension to subtract, ~4kg)
 * @property loadBaselineB Load baseline for cable B (base tension to subtract, ~4kg)
 */
data class WorkoutUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val workoutState: WorkoutState = WorkoutState.Idle,
    val currentMetric: WorkoutMetric? = null,
    val currentHeuristicKgMax: Float = 0f, // Echo mode: actual measured force per cable (kg)
    val workoutParameters: WorkoutParameters = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        reps = 10
    ),
    val repCount: RepCount = RepCount(),
    val repRanges: RepRanges? = null,
    val autoStopState: AutoStopUiState = AutoStopUiState(),
    val autoStartCountdown: Int? = null,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val enableVideoPlayback: Boolean = true,
    val loadedRoutine: Routine? = null,
    val currentExerciseIndex: Int = 0,
    val currentSetIndex: Int = 0,
    val skippedExercises: Set<Int> = emptySet(),
    val completedExercises: Set<Int> = emptySet(),
    val autoplayEnabled: Boolean = false,
    val summaryCountdownSeconds: Int = 10,  // Countdown duration for SetSummary auto-continue (0 = Off)
    val canGoBack: Boolean = false,
    val canSkipForward: Boolean = false,
    val isWorkoutSetupDialogVisible: Boolean = false,
    val showConnectionCard: Boolean = true,
    val showWorkoutSetupCard: Boolean = true,
    val loadBaselineA: Float = 0f,
    val loadBaselineB: Float = 0f
)

/**
 * Action callbacks for WorkoutTab.
 * Separates UI events from state to keep the composable clean
 * and make testing easier.
 */
interface WorkoutActions {
    /** Start BLE scanning for Vitruvian machines */
    fun onScan()

    /** Cancel ongoing scan or connection attempt */
    fun onCancelScan()

    /** Disconnect from current machine */
    fun onDisconnect()

    /** Start the workout (may trigger connection first) */
    fun onStartWorkout()

    /** Stop the current workout */
    fun onStopWorkout()

    /** Skip rest timer and proceed immediately */
    fun onSkipRest()

    /** Proceed from set summary to next set/exercise */
    fun onProceedFromSummary()

    /** Log RPE (Rate of Perceived Exertion) for current set */
    fun onRpeLogged(rpe: Int)

    /** Reset state to start a new workout */
    fun onResetForNewWorkout()

    /** Advance to next exercise in routine */
    fun onStartNextExercise()

    /** Jump to specific exercise by index */
    fun onJumpToExercise(index: Int)

    /** Update workout parameters */
    fun onUpdateParameters(params: WorkoutParameters)

    /** Show workout setup dialog */
    fun onShowWorkoutSetupDialog()

    /** Hide workout setup dialog */
    fun onHideWorkoutSetupDialog()

    /** Convert kg to display unit */
    fun kgToDisplay(kg: Float, unit: WeightUnit): Float

    /** Convert display unit to kg */
    fun displayToKg(display: Float, unit: WeightUnit): Float

    /** Format weight with unit */
    fun formatWeight(weight: Float, unit: WeightUnit): String
}

/**
 * Default no-op implementation of WorkoutActions for previews.
 */
object PreviewWorkoutActions : WorkoutActions {
    override fun onScan() {}
    override fun onCancelScan() {}
    override fun onDisconnect() {}
    override fun onStartWorkout() {}
    override fun onStopWorkout() {}
    override fun onSkipRest() {}
    override fun onProceedFromSummary() {}
    override fun onRpeLogged(rpe: Int) {}
    override fun onResetForNewWorkout() {}
    override fun onStartNextExercise() {}
    override fun onJumpToExercise(index: Int) {}
    override fun onUpdateParameters(params: WorkoutParameters) {}
    override fun onShowWorkoutSetupDialog() {}
    override fun onHideWorkoutSetupDialog() {}
    override fun kgToDisplay(kg: Float, unit: WeightUnit): Float = kg
    override fun displayToKg(display: Float, unit: WeightUnit): Float = display
    override fun formatWeight(weight: Float, unit: WeightUnit): String = "${weight.toInt()} kg"
}

/**
 * Creates WorkoutActions implementation that delegates to provided lambdas.
 * Used to bridge between ViewModel and composable.
 */
fun workoutActions(
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onDisconnect: () -> Unit,
    onStartWorkout: () -> Unit,
    onStopWorkout: () -> Unit,
    onSkipRest: () -> Unit,
    onProceedFromSummary: () -> Unit,
    onRpeLogged: (Int) -> Unit,
    onResetForNewWorkout: () -> Unit,
    onStartNextExercise: () -> Unit,
    onJumpToExercise: (Int) -> Unit,
    onUpdateParameters: (WorkoutParameters) -> Unit,
    onShowWorkoutSetupDialog: () -> Unit,
    onHideWorkoutSetupDialog: () -> Unit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    formatWeight: (Float, WeightUnit) -> String
): WorkoutActions = object : WorkoutActions {
    override fun onScan() = onScan()
    override fun onCancelScan() = onCancelScan()
    override fun onDisconnect() = onDisconnect()
    override fun onStartWorkout() = onStartWorkout()
    override fun onStopWorkout() = onStopWorkout()
    override fun onSkipRest() = onSkipRest()
    override fun onProceedFromSummary() = onProceedFromSummary()
    override fun onRpeLogged(rpe: Int) = onRpeLogged(rpe)
    override fun onResetForNewWorkout() = onResetForNewWorkout()
    override fun onStartNextExercise() = onStartNextExercise()
    override fun onJumpToExercise(index: Int) = onJumpToExercise(index)
    override fun onUpdateParameters(params: WorkoutParameters) = onUpdateParameters(params)
    override fun onShowWorkoutSetupDialog() = onShowWorkoutSetupDialog()
    override fun onHideWorkoutSetupDialog() = onHideWorkoutSetupDialog()
    override fun kgToDisplay(kg: Float, unit: WeightUnit) = kgToDisplay(kg, unit)
    override fun displayToKg(display: Float, unit: WeightUnit) = displayToKg(display, unit)
    override fun formatWeight(weight: Float, unit: WeightUnit) = formatWeight(weight, unit)
}
