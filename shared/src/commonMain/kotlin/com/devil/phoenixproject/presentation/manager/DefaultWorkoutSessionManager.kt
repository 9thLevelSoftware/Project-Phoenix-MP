package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

// ===== Data classes that move with DefaultWorkoutSessionManager =====

/**
 * Data class for storing Just Lift session defaults.
 */
data class JustLiftDefaults(
    val weightPerCableKg: Float,
    val weightChangePerRep: Int, // In display units (kg or lbs based on user preference)
    val workoutModeId: Int, // 0=OldSchool, 1=Pump, 10=Echo
    val eccentricLoadPercentage: Int = 100,
    val echoLevelValue: Int = 1, // 0=Hard, 1=Harder, 2=Hardest, 3=Epic
    val stallDetectionEnabled: Boolean = true // Stall detection auto-stop toggle
) {
    /**
     * Convert stored mode ID to ProgramMode
     */
    fun toProgramMode(): ProgramMode = when (workoutModeId) {
        0 -> ProgramMode.OldSchool
        2 -> ProgramMode.Pump
        3 -> ProgramMode.TUT
        4 -> ProgramMode.TUTBeast
        6 -> ProgramMode.EccentricOnly
        10 -> ProgramMode.Echo
        else -> ProgramMode.OldSchool
    }

    /**
     * Get EccentricLoad from stored percentage
     */
    fun getEccentricLoad(): EccentricLoad = when (eccentricLoadPercentage) {
        0 -> EccentricLoad.LOAD_0
        50 -> EccentricLoad.LOAD_50
        75 -> EccentricLoad.LOAD_75
        100 -> EccentricLoad.LOAD_100
        110 -> EccentricLoad.LOAD_110
        120 -> EccentricLoad.LOAD_120
        130 -> EccentricLoad.LOAD_130
        140 -> EccentricLoad.LOAD_140
        150 -> EccentricLoad.LOAD_150
        else -> EccentricLoad.LOAD_100
    }

    /**
     * Get EchoLevel from stored value
     */
    fun getEchoLevel(): EchoLevel = EchoLevel.entries.getOrElse(echoLevelValue) { EchoLevel.HARDER }
}

/**
 * Data class for resumable workout progress information.
 * Used to display progress in the Resume/Restart dialog.
 */
data class ResumableProgressInfo(
    val exerciseName: String,
    val currentSet: Int,
    val totalSets: Int,
    val currentExercise: Int,
    val totalExercises: Int
)

/**
 * Event emitted when a training cycle day is completed after a workout.
 * Consumed by TrainingCyclesScreen to show completion feedback.
 */
data class CycleDayCompletionEvent(
    val dayNumber: Int,
    val dayName: String?,
    val isRotationComplete: Boolean,
    val rotationCount: Int
)

// ===== DefaultWorkoutSessionManager =====

/**
 * Orchestration layer for the workout system. Delegates to sub-managers:
 * - [WorkoutCoordinator]: Shared state bus (zero business logic)
 * - [RoutineFlowManager]: Routine CRUD, navigation, supersets
 * - [ActiveSessionEngine]: Workout lifecycle, BLE commands, auto-stop, rest timer, session persistence
 *
 * This class wires the sub-managers together and provides the public API consumed by MainViewModel.
 * After Phase 2 decomposition, this is a thin delegation layer (~300 lines).
 */
class DefaultWorkoutSessionManager(
    private val bleRepository: BleRepository,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val repCounter: RepCounterFromMachine,
    private val preferencesManager: PreferencesManager,
    private val gamificationManager: GamificationManager,
    private val trainingCycleRepository: TrainingCycleRepository,
    private val completedSetRepository: CompletedSetRepository,
    private val syncTriggerManager: SyncTriggerManager?,
    private val repMetricRepository: RepMetricRepository,
    private val resolveWeightsUseCase: ResolveRoutineWeightsUseCase,
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope,
    private val _hapticEvents: MutableSharedFlow<HapticEvent> = MutableSharedFlow(
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
) : WorkoutStateProvider {

    // ===== Coordinator: Shared state bus for all workout state =====
    val coordinator = WorkoutCoordinator(_hapticEvents)

    // ===== RoutineFlowManager: Handles routine CRUD, navigation, superset logic =====
    val routineFlowManager = RoutineFlowManager(
        coordinator = coordinator,
        workoutRepository = workoutRepository,
        exerciseRepository = exerciseRepository,
        resolveWeightsUseCase = resolveWeightsUseCase,
        completedSetRepository = completedSetRepository,
        settingsManager = settingsManager,
        scope = scope
    ).also { rfm ->
        rfm.lifecycleDelegate = object : RoutineFlowManager.WorkoutLifecycleDelegate {
            override fun resetRepCounter() { repCounter.reset() }
            override fun startWorkout(skipCountdown: Boolean) { this@DefaultWorkoutSessionManager.startWorkout(skipCountdown = skipCountdown) }
            override suspend fun sendStopCommand() { bleRepository.sendStopCommand() }
            override suspend fun stopMachineWorkout() { bleRepository.stopWorkout() }
            override fun updateWorkoutParameters(params: WorkoutParameters) { this@DefaultWorkoutSessionManager.updateWorkoutParameters(params) }
        }
    }

    // ===== ActiveSessionEngine: Handles workout lifecycle, BLE, auto-stop, rest timer =====
    val activeSessionEngine = ActiveSessionEngine(
        coordinator = coordinator,
        bleRepository = bleRepository,
        workoutRepository = workoutRepository,
        exerciseRepository = exerciseRepository,
        personalRecordRepository = personalRecordRepository,
        repCounter = repCounter,
        preferencesManager = preferencesManager,
        gamificationManager = gamificationManager,
        trainingCycleRepository = trainingCycleRepository,
        completedSetRepository = completedSetRepository,
        syncTriggerManager = syncTriggerManager,
        repMetricRepository = repMetricRepository,
        settingsManager = settingsManager,
        scope = scope
    )

    // ===== LED Biofeedback Controller =====
    init {
        coordinator.ledFeedbackController = LedFeedbackController(
            bleRepository = bleRepository,
            scope = scope
        )
    }

    companion object {
        /** Prefix for temporary single exercise routines to identify them for cleanup */
        const val TEMP_SINGLE_EXERCISE_PREFIX = "temp_single_"
    }

    init {
        Logger.d("DefaultWorkoutSessionManager initialized")
        // Collectors #1-2 run in RoutineFlowManager (constructed first).
        // Collectors #3-8 run in ActiveSessionEngine (constructed second).
        // Construction order preserves original collector ordering.

        // Wire ActiveSessionEngine's flow delegate to RoutineFlowManager.
        // Done in init block (not .also) so `this` is DefaultWorkoutSessionManager,
        // which has access to RoutineFlowManager's internal members.
        activeSessionEngine.flowDelegate = object : ActiveSessionEngine.WorkoutFlowDelegate {
            override fun loadRoutine(routine: Routine) = routineFlowManager.loadRoutine(routine)
            override fun enterSetReady(exerciseIndex: Int, setIndex: Int) = routineFlowManager.enterSetReady(exerciseIndex, setIndex)
            override fun showRoutineComplete() = routineFlowManager.showRoutineComplete()
            override fun getCurrentExercise(): RoutineExercise? = routineFlowManager.getCurrentExercise()
            override fun getNextStep(routine: Routine, exerciseIndex: Int, setIndex: Int): Pair<Int, Int>? =
                routineFlowManager.getNextStep(routine, exerciseIndex, setIndex)
            override fun isInSuperset(): Boolean = routineFlowManager.isInSuperset()
            override fun isAtEndOfSupersetCycle(): Boolean = routineFlowManager.isAtEndOfSupersetCycle()
            override fun getSupersetRestSeconds(): Int = routineFlowManager.getSupersetRestSeconds()
            override fun calculateNextExerciseName(isSingleExercise: Boolean, currentExercise: RoutineExercise?, routine: Routine?): String? =
                routineFlowManager.calculateNextExerciseName(isSingleExercise, currentExercise, routine)
            override fun calculateIsLastExercise(isSingleExercise: Boolean, currentExercise: RoutineExercise?, routine: Routine?): Boolean =
                routineFlowManager.calculateIsLastExercise(isSingleExercise, currentExercise, routine)
            override fun clearCycleContext() = routineFlowManager.clearCycleContext()
        }
    }

    fun clearCycleDayCompletionEvent() {
        coordinator._cycleDayCompletionEvent.value = null
    }

    // ===== WorkoutStateProvider Implementation =====

    override val isWorkoutActiveForConnectionAlert: Boolean
        get() = when (coordinator._workoutState.value) {
            is WorkoutState.Active, is WorkoutState.Countdown, is WorkoutState.Resting -> true
            else -> false
        }

    // ===== Routine CRUD — delegated to RoutineFlowManager =====

    fun getRoutineById(routineId: String): Routine? = routineFlowManager.getRoutineById(routineId)
    fun saveRoutine(routine: Routine) = routineFlowManager.saveRoutine(routine)
    fun updateRoutine(routine: Routine) = routineFlowManager.updateRoutine(routine)
    fun deleteRoutine(routineId: String) = routineFlowManager.deleteRoutine(routineId)
    fun deleteRoutines(routineIds: Set<String>) = routineFlowManager.deleteRoutines(routineIds)
    fun loadRoutine(routine: Routine) = routineFlowManager.loadRoutine(routine)
    fun loadRoutineById(routineId: String) = routineFlowManager.loadRoutineById(routineId)
    fun enterRoutineOverview(routine: Routine) = routineFlowManager.enterRoutineOverview(routine)

    // ===== SetReady Navigation — delegated to RoutineFlowManager =====

    fun selectExerciseInOverview(index: Int) = routineFlowManager.selectExerciseInOverview(index)
    fun enterSetReady(exerciseIndex: Int, setIndex: Int) = routineFlowManager.enterSetReady(exerciseIndex, setIndex)
    fun enterSetReadyWithAdjustments(exerciseIndex: Int, setIndex: Int, adjustedWeight: Float, adjustedReps: Int) =
        routineFlowManager.enterSetReadyWithAdjustments(exerciseIndex, setIndex, adjustedWeight, adjustedReps)
    fun updateSetReadyWeight(weight: Float) = routineFlowManager.updateSetReadyWeight(weight)
    fun updateSetReadyReps(reps: Int) = routineFlowManager.updateSetReadyReps(reps)
    fun updateSetReadyEchoLevel(level: EchoLevel) = routineFlowManager.updateSetReadyEchoLevel(level)
    fun updateSetReadyEccentricLoad(percent: Int) = routineFlowManager.updateSetReadyEccentricLoad(percent)
    fun startSetFromReady() = routineFlowManager.startSetFromReady()
    fun returnToOverview() = routineFlowManager.returnToOverview()
    fun exitRoutineFlow() = routineFlowManager.exitRoutineFlow()
    fun showRoutineComplete() = routineFlowManager.showRoutineComplete()
    fun clearLoadedRoutine() = routineFlowManager.clearLoadedRoutine()

    // ===== Exercise Navigation — delegated to RoutineFlowManager =====

    fun getCurrentExercise(): RoutineExercise? = routineFlowManager.getCurrentExercise()
    fun hasResumableProgress(routineId: String): Boolean = routineFlowManager.hasResumableProgress(routineId)
    fun getResumableProgressInfo(): ResumableProgressInfo? = routineFlowManager.getResumableProgressInfo()
    fun advanceToNextExercise() = routineFlowManager.advanceToNextExercise()
    fun jumpToExercise(index: Int) = routineFlowManager.jumpToExercise(index)
    fun skipCurrentExercise() = routineFlowManager.skipCurrentExercise()
    fun goToPreviousExercise() = routineFlowManager.goToPreviousExercise()
    fun canGoBack(): Boolean = routineFlowManager.canGoBack()
    fun canSkipForward(): Boolean = routineFlowManager.canSkipForward()
    fun getRoutineExerciseNames(): List<String> = routineFlowManager.getRoutineExerciseNames()
    fun logRpeForCurrentSet(rpe: Int) = routineFlowManager.logRpeForCurrentSet(rpe)
    fun setReadyPrev() = routineFlowManager.setReadyPrev()
    fun setReadySkip() = routineFlowManager.setReadySkip()

    // ===== Step Navigation — delegated to RoutineFlowManager =====

    fun hasNextStep(exerciseIndex: Int, setIndex: Int): Boolean = routineFlowManager.hasNextStep(exerciseIndex, setIndex)
    fun hasPreviousStep(exerciseIndex: Int, setIndex: Int): Boolean = routineFlowManager.hasPreviousStep(exerciseIndex, setIndex)

    // ===== Superset CRUD — delegated to RoutineFlowManager =====

    suspend fun createSuperset(
        routineId: String,
        name: String? = null,
        exercises: List<RoutineExercise> = emptyList()
    ): Superset = routineFlowManager.createSuperset(routineId, name, exercises)

    suspend fun updateSuperset(routineId: String, superset: Superset) = routineFlowManager.updateSuperset(routineId, superset)
    suspend fun deleteSuperset(routineId: String, supersetId: String) = routineFlowManager.deleteSuperset(routineId, supersetId)
    suspend fun addExerciseToSuperset(routineId: String, exerciseId: String, supersetId: String) = routineFlowManager.addExerciseToSuperset(routineId, exerciseId, supersetId)
    suspend fun removeExerciseFromSuperset(routineId: String, exerciseId: String) = routineFlowManager.removeExerciseFromSuperset(routineId, exerciseId)

    // ===== Workout Lifecycle — delegated to ActiveSessionEngine =====

    fun resetForNewWorkout() = activeSessionEngine.resetForNewWorkout()
    fun recaptureLoadBaseline() = activeSessionEngine.recaptureLoadBaseline()
    fun resetLoadBaseline() = activeSessionEngine.resetLoadBaseline()
    fun updateWorkoutParameters(params: WorkoutParameters) = activeSessionEngine.updateWorkoutParameters(params)
    fun startWorkout(skipCountdown: Boolean = false, isJustLiftMode: Boolean = false) = activeSessionEngine.startWorkout(skipCountdown, isJustLiftMode)
    fun skipCountdown() = activeSessionEngine.skipCountdown()
    fun stopWorkout(exitingWorkout: Boolean = false) = activeSessionEngine.stopWorkout(exitingWorkout)
    fun stopAndReturnToSetReady() = activeSessionEngine.stopAndReturnToSetReady()
    fun pauseWorkout() = activeSessionEngine.pauseWorkout()
    fun resumeWorkout() = activeSessionEngine.resumeWorkout()

    // ===== Weight Adjustment — delegated to ActiveSessionEngine =====

    fun adjustWeight(newWeightKg: Float, sendToMachine: Boolean = true) = activeSessionEngine.adjustWeight(newWeightKg, sendToMachine)
    fun incrementWeight(amount: Float = 0.5f) = activeSessionEngine.incrementWeight(amount)
    fun decrementWeight(amount: Float = 0.5f) = activeSessionEngine.decrementWeight(amount)
    fun setWeightPreset(presetWeightKg: Float) = activeSessionEngine.setWeightPreset(presetWeightKg)
    suspend fun getLastWeightForExercise(exerciseId: String): Float? = activeSessionEngine.getLastWeightForExercise(exerciseId)
    suspend fun getPrWeightForExercise(exerciseId: String): Float? = activeSessionEngine.getPrWeightForExercise(exerciseId)

    // ===== Just Lift — delegated to ActiveSessionEngine =====

    fun enableHandleDetection() = activeSessionEngine.enableHandleDetection()
    fun disableHandleDetection() = activeSessionEngine.disableHandleDetection()
    fun prepareForJustLift() = activeSessionEngine.prepareForJustLift()
    suspend fun getJustLiftDefaults(): JustLiftDefaults = activeSessionEngine.getJustLiftDefaults()
    fun saveJustLiftDefaults(defaults: JustLiftDefaults) = activeSessionEngine.saveJustLiftDefaults(defaults)
    suspend fun getSingleExerciseDefaults(exerciseId: String) = activeSessionEngine.getSingleExerciseDefaults(exerciseId)
    fun saveSingleExerciseDefaults(defaults: com.devil.phoenixproject.data.preferences.SingleExerciseDefaults) = activeSessionEngine.saveSingleExerciseDefaults(defaults)

    // ===== Training Cycles — delegated to ActiveSessionEngine =====

    fun loadRoutineFromCycle(routineId: String, cycleId: String, dayNumber: Int) = activeSessionEngine.loadRoutineFromCycle(routineId, cycleId, dayNumber)
    fun clearCycleContext() = activeSessionEngine.clearCycleContext()

    // ===== Rest/Flow Control — delegated to ActiveSessionEngine =====

    fun skipRest() = activeSessionEngine.skipRest()
    fun startNextSet() = activeSessionEngine.startNextSet()

    // ===== Orchestration: proceedFromSummary (cross-cutting, stays in DWSM) =====

    /**
     * Proceed from set summary to next step.
     * This is orchestration: reads both routine state AND workout state to decide the next action.
     * Stays in DWSM because it coordinates between RoutineFlowManager and ActiveSessionEngine.
     */
    fun proceedFromSummary() {
        scope.launch {
            val routine = coordinator._loadedRoutine.value
            val autoplay = settingsManager.autoplayEnabled.value

            // Issue #209: If we have a loaded routine, force isJustLift = false
            val isJustLift = if (routine != null) {
                coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(isJustLift = false)
                false
            } else {
                coordinator._workoutParameters.value.isJustLift
            }

            Logger.d { "proceedFromSummary: routine=${routine?.name ?: "NULL"}, isJustLift=$isJustLift, autoplay=$autoplay" }
            Logger.d { "  currentExerciseIndex=${coordinator._currentExerciseIndex.value}, currentSetIndex=${coordinator._currentSetIndex.value}" }

            // Check if routine is complete (for routine mode, not Just Lift)
            if (routine != null && !isJustLift) {
                val currentExercise = routine.exercises.getOrNull(coordinator._currentExerciseIndex.value)
                val isLastSetOfExercise = coordinator._currentSetIndex.value >= (currentExercise?.setReps?.size ?: 1) - 1

                // Mark exercise as completed if this was the last set of THIS exercise
                if (isLastSetOfExercise) {
                    coordinator._completedExercises.value = coordinator._completedExercises.value + coordinator._currentExerciseIndex.value
                }

                // Check if there are ANY more steps using superset-aware navigation
                val nextStep = routineFlowManager.getNextStep(routine, coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)

                // If no more steps in the entire routine, show completion screen
                if (nextStep == null) {
                    Logger.d { "proceedFromSummary: No more steps - showing routine complete" }
                    showRoutineComplete()
                    return@launch
                }

                // Autoplay OFF: go directly to SetReady for manual control (no rest timer)
                if (!autoplay) {
                    Logger.d { "proceedFromSummary: Autoplay OFF - going to SetReady for next step" }
                    val (nextExIdx, nextSetIdx) = nextStep

                    // Advance to next step
                    coordinator._currentExerciseIndex.value = nextExIdx
                    coordinator._currentSetIndex.value = nextSetIdx

                    // Clear RPE for next set
                    coordinator._currentSetRpe.value = null

                    // Get next exercise and update parameters
                    val nextExercise = routine.exercises[nextExIdx]
                    val nextSetWeight = nextExercise.setWeightsPerCableKg.getOrNull(nextSetIdx)
                        ?: nextExercise.weightPerCableKg
                    val nextSetReps = nextExercise.setReps.getOrNull(nextSetIdx)
                    val isNextSetLastSet = nextSetIdx >= nextExercise.setReps.size - 1
                    val nextIsAMRAP = nextSetReps == null || (nextExercise.isAMRAP && isNextSetLastSet)

                    coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
                        weightPerCableKg = nextSetWeight,
                        reps = nextSetReps ?: 0,
                        programMode = nextExercise.programMode,
                        echoLevel = nextExercise.echoLevel,
                        eccentricLoad = nextExercise.eccentricLoad,
                        progressionRegressionKg = nextExercise.progressionKg,
                        selectedExerciseId = nextExercise.exercise.id,
                        isAMRAP = nextIsAMRAP,
                        stallDetectionEnabled = nextExercise.stallDetectionEnabled
                    )
                    Logger.d { "proceedFromSummary: Issue #203 - Updated params for next set: ${nextExercise.exercise.name}, setIdx=$nextSetIdx, isAMRAP=$nextIsAMRAP" }

                    // Reset counters for next set
                    repCounter.resetCountsOnly()
                    activeSessionEngine.resetAutoStopState()

                    // Navigate to SetReady screen
                    enterSetReady(nextExIdx, nextSetIdx)
                    return@launch
                }
            }

            // Check if there are more sets or exercises remaining (for rest timer logic)
            val hasMoreSets = routine?.let {
                val currentExercise = it.exercises.getOrNull(coordinator._currentExerciseIndex.value)
                val isAMRAPExercise = currentExercise?.isAMRAP == true

                if (isAMRAPExercise) {
                    true // AMRAP always has "more sets" - user decides when to move on
                } else {
                    currentExercise != null && coordinator._currentSetIndex.value < currentExercise.setReps.size - 1
                }
            } ?: false

            val hasMoreExercises = routine?.let {
                coordinator._currentExerciseIndex.value < it.exercises.size - 1
            } ?: false

            // Single Exercise mode (not Just Lift, includes temp routines from SingleExerciseScreen)
            val isSingleExercise = isSingleExerciseMode(coordinator) && !isJustLift
            // Show rest timer if autoplay ON and more sets/exercises remaining
            val shouldShowRestTimer = (hasMoreSets || hasMoreExercises) && !isJustLift

            Logger.d { "proceedFromSummary: hasMoreSets=$hasMoreSets, hasMoreExercises=$hasMoreExercises" }
            Logger.d { "  isSingleExercise=$isSingleExercise, shouldShowRestTimer=$shouldShowRestTimer" }

            // Clear RPE for next set
            coordinator._currentSetRpe.value = null

            // Show rest timer if there are more sets/exercises (autoplay ON path)
            if (shouldShowRestTimer) {
                Logger.d { "proceedFromSummary: Starting rest timer..." }
                activeSessionEngine.startRestTimer()
            } else {
                Logger.d { "proceedFromSummary: No rest timer - marking as completed/idle" }
                repCounter.reset()
                activeSessionEngine.resetAutoStopState()

                // Auto-reset for Just Lift mode to enable immediate restart
                if (isJustLift) {
                    Logger.d { "Just Lift mode: Auto-resetting to Idle" }
                    activeSessionEngine.resetForNewWorkout()
                    coordinator._workoutState.value = WorkoutState.Idle
                    activeSessionEngine.enableHandleDetection()
                    bleRepository.enableJustLiftWaitingMode()
                    Logger.d { "Just Lift mode: Ready for next exercise" }
                } else {
                    coordinator._workoutState.value = WorkoutState.Completed
                }
            }
        }
    }

    // ===== Cleanup =====

    fun cleanup() {
        activeSessionEngine.cleanup()
    }
}
