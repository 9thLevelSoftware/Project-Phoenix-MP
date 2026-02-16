package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manages routine CRUD, exercise/set navigation, and superset navigation.
 *
 * Extracted from DefaultWorkoutSessionManager during Phase 2 (Manager Decomposition) Plan 03.
 *
 * Communication:
 * - Reads/writes all state through [coordinator] (WorkoutCoordinator)
 * - NEVER holds references to ActiveSessionEngine or DWSM
 * - For operations requiring BLE commands or startWorkout(), uses [WorkoutLifecycleDelegate]
 *
 * Scope: Receives the SAME CoroutineScope as DWSM for TestScope compatibility.
 */
class RoutineFlowManager(
    val coordinator: WorkoutCoordinator,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val resolveWeightsUseCase: ResolveRoutineWeightsUseCase,
    private val completedSetRepository: CompletedSetRepository,
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope
) {

    /**
     * Delegate interface for operations that require BLE commands or workout lifecycle control.
     * Implemented by DefaultWorkoutSessionManager to bridge RoutineFlowManager back to DWSM
     * without creating a direct reference.
     */
    interface WorkoutLifecycleDelegate {
        /** Reset the rep counter state */
        fun resetRepCounter()
        /** Start a workout with optional countdown skip */
        fun startWorkout(skipCountdown: Boolean = false)
        /** Send BLE stop command to clear fault state */
        suspend fun sendStopCommand()
        /** Send BLE stop/reset to put machine in BASELINE mode */
        suspend fun stopMachineWorkout()
        /** Update workout parameters (includes side-effect tracking) */
        fun updateWorkoutParameters(params: WorkoutParameters)
    }

    /**
     * Lifecycle delegate for operations that need BLE/workout control.
     * Set by DWSM after construction.
     */
    internal lateinit var lifecycleDelegate: WorkoutLifecycleDelegate

    // ===== Init Block: Routine-Related Collectors =====
    // These were collectors #1 and #2 in DWSM's init block.
    // RoutineFlowManager is constructed before other sub-managers in DWSM,
    // so its init block runs first, preserving the original ordering.

    init {
        // Collector #1: Load routines (filter out cycle template routines)
        scope.launch {
            workoutRepository.getAllRoutines().collect { routinesList ->
                coordinator._routines.value = routinesList.filter { !it.id.startsWith("cycle_routine_") }
            }
        }

        // Collector #2: Import exercises if not already imported
        scope.launch {
            try {
                val result = exerciseRepository.importExercises()
                if (result.isSuccess) {
                    Logger.d { "Exercise library initialized" }
                } else {
                    Logger.e { "Failed to initialize exercise library: ${result.exceptionOrNull()?.message}" }
                }
            } catch (e: Exception) {
                Logger.e(e) { "Error initializing exercise library" }
            }
        }
    }

    // ===== Superset Navigation Helpers (private) =====

    private fun getCurrentSupersetExercises(): List<RoutineExercise> {
        val routine = coordinator._loadedRoutine.value ?: return emptyList()
        val currentExercise = getCurrentExercise() ?: return emptyList()
        val supersetId = currentExercise.supersetId ?: return emptyList()

        return routine.exercises
            .filter { it.supersetId == supersetId }
            .sortedBy { it.orderInSuperset }
    }

    /**
     * Check if the current exercise is part of a superset.
     */
    internal fun isInSuperset(): Boolean {
        return getCurrentExercise()?.supersetId != null
    }

    /**
     * Get the next exercise index in the superset rotation.
     */
    private fun getNextSupersetExerciseIndex(): Int? {
        val routine = coordinator._loadedRoutine.value ?: return null
        val currentExercise = getCurrentExercise() ?: return null
        val supersetId = currentExercise.supersetId ?: return null

        val supersetExercises = getCurrentSupersetExercises()
        val currentPositionInSuperset = supersetExercises.indexOf(currentExercise)

        if (currentPositionInSuperset < supersetExercises.size - 1) {
            val nextSupersetExercise = supersetExercises[currentPositionInSuperset + 1]
            return routine.exercises.indexOf(nextSupersetExercise)
        }

        return null
    }

    /**
     * Get the first exercise in the current superset.
     */
    private fun getFirstSupersetExerciseIndex(): Int? {
        val routine = coordinator._loadedRoutine.value ?: return null
        val supersetExercises = getCurrentSupersetExercises()
        if (supersetExercises.isEmpty()) return null

        return routine.exercises.indexOf(supersetExercises.first())
    }

    /**
     * Check if we're at the end of a superset cycle.
     */
    internal fun isAtEndOfSupersetCycle(): Boolean {
        val currentExercise = getCurrentExercise() ?: return false
        if (currentExercise.supersetId == null) return false

        val supersetExercises = getCurrentSupersetExercises()
        return currentExercise == supersetExercises.lastOrNull()
    }

    /**
     * Get the superset rest time.
     */
    internal fun getSupersetRestSeconds(): Int {
        val routine = coordinator._loadedRoutine.value ?: return 10
        val supersetId = getCurrentExercise()?.supersetId ?: return 10
        return routine.supersets.find { it.id == supersetId }?.restBetweenSeconds ?: 10
    }

    /**
     * Find the next exercise after the current one (or after the current superset).
     */
    private fun findNextExerciseAfterCurrent(): Int? {
        val routine = coordinator._loadedRoutine.value ?: return null
        val currentExercise = getCurrentExercise() ?: return null
        val currentSupersetId = currentExercise.supersetId

        if (currentSupersetId != null) {
            val supersetExerciseIndices = routine.exercises
                .mapIndexedNotNull { index, ex ->
                    if (ex.supersetId == currentSupersetId) index else null
                }
            val lastSupersetIndex = supersetExerciseIndices.maxOrNull() ?: coordinator._currentExerciseIndex.value
            val nextIndex = lastSupersetIndex + 1
            return if (nextIndex < routine.exercises.size) nextIndex else null
        }

        val nextIndex = coordinator._currentExerciseIndex.value + 1
        return if (nextIndex < routine.exercises.size) nextIndex else null
    }

    // ===== Unified Navigation Logic =====

    /**
     * Determine the next step (Exercise Index, Set Index) in the workout sequence.
     */
    internal fun getNextStep(routine: Routine, currentExIndex: Int, currentSetIndex: Int): Pair<Int, Int>? {
        val currentExercise = routine.exercises.getOrNull(currentExIndex) ?: return null

        // 1. Superset Logic - interleaved progression (A1 -> B1 -> A2 -> B2)
        if (currentExercise.supersetId != null) {
            val supersetExercises = routine.exercises
                .filter { it.supersetId == currentExercise.supersetId }
                .sortedBy { it.orderInSuperset }

            val currentSupersetPos = supersetExercises.indexOf(currentExercise)

            // A. Check for next exercise in the SAME set cycle
            for (i in (currentSupersetPos + 1) until supersetExercises.size) {
                val nextEx = supersetExercises[i]
                if (currentSetIndex < nextEx.setReps.size) {
                    val nextExIndex = routine.exercises.indexOf(nextEx)
                    return nextExIndex to currentSetIndex
                }
            }

            // B. Check for the NEXT set cycle - loop back to first exercise with next set
            val nextSetIndex = currentSetIndex + 1
            for (ex in supersetExercises) {
                if (nextSetIndex < ex.setReps.size) {
                    val nextExIndex = routine.exercises.indexOf(ex)
                    return nextExIndex to nextSetIndex
                }
            }

            // C. Superset Complete -> Move to next exercise after superset
            val maxIndex = supersetExercises.maxOf { routine.exercises.indexOf(it) }
            val nextExIndex = maxIndex + 1
            if (nextExIndex < routine.exercises.size) {
                return nextExIndex to 0
            }
            return null
        }

        // 2. Standard Linear Logic
        if (currentSetIndex < currentExercise.setReps.size - 1) {
            return currentExIndex to (currentSetIndex + 1)
        } else if (currentExIndex < routine.exercises.size - 1) {
            return (currentExIndex + 1) to 0
        }

        return null
    }

    /**
     * Determine the previous step (Exercise Index, Set Index) in the workout sequence.
     */
    internal fun getPreviousStep(routine: Routine, currentExIndex: Int, currentSetIndex: Int): Pair<Int, Int>? {
        val currentExercise = routine.exercises.getOrNull(currentExIndex) ?: return null

        // 1. Superset Logic - interleaved progression
        if (currentExercise.supersetId != null) {
            val supersetExercises = routine.exercises
                .filter { it.supersetId == currentExercise.supersetId }
                .sortedBy { it.orderInSuperset }

            val currentSupersetPos = supersetExercises.indexOf(currentExercise)

            // A. Check for previous exercise in SAME set cycle
            for (i in (currentSupersetPos - 1) downTo 0) {
                val prevEx = supersetExercises[i]
                if (currentSetIndex < prevEx.setReps.size) {
                    val prevExIndex = routine.exercises.indexOf(prevEx)
                    return prevExIndex to currentSetIndex
                }
            }

            // B. Check for PREVIOUS set cycle - find last exercise that has prevSetIndex
            val prevSetIndex = currentSetIndex - 1
            if (prevSetIndex >= 0) {
                for (i in supersetExercises.indices.reversed()) {
                    val prevEx = supersetExercises[i]
                    if (prevSetIndex < prevEx.setReps.size) {
                        val prevExIndex = routine.exercises.indexOf(prevEx)
                        return prevExIndex to prevSetIndex
                    }
                }
            }

            // C. Start of Superset -> Go to previous exercise before superset
            val minIndex = supersetExercises.minOf { routine.exercises.indexOf(it) }
            val prevExIndex = minIndex - 1
            if (prevExIndex >= 0) {
                val prevEx = routine.exercises[prevExIndex]
                return prevExIndex to (prevEx.setReps.size - 1)
            }
            return null
        }

        // 2. Standard Linear Logic
        if (currentSetIndex > 0) {
            return currentExIndex to (currentSetIndex - 1)
        } else if (currentExIndex > 0) {
            val prevEx = routine.exercises[currentExIndex - 1]
            return (currentExIndex - 1) to (prevEx.setReps.size - 1)
        }

        return null
    }

    /**
     * Check if there is a next step in the routine from the given position.
     */
    fun hasNextStep(exerciseIndex: Int, setIndex: Int): Boolean {
        val routine = coordinator._loadedRoutine.value ?: return false
        return getNextStep(routine, exerciseIndex, setIndex) != null
    }

    /**
     * Check if there is a previous step in the routine from the given position.
     */
    fun hasPreviousStep(exerciseIndex: Int, setIndex: Int): Boolean {
        val routine = coordinator._loadedRoutine.value ?: return false
        return getPreviousStep(routine, exerciseIndex, setIndex) != null
    }

    /**
     * Calculate the name of the next exercise/set for display during rest.
     */
    internal fun calculateNextExerciseName(
        isSingleExercise: Boolean,
        currentExercise: RoutineExercise?,
        routine: Routine?
    ): String {
        if (isSingleExercise || currentExercise == null) {
            return currentExercise?.exercise?.name ?: "Next Set"
        }

        if (routine == null) return "Next Set"

        // Use getNextStep for superset-aware navigation (fixes Issue #193)
        val nextStep = getNextStep(routine, coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
        if (nextStep == null) {
            return "Routine Complete"
        }

        val (nextExIndex, nextSetIndex) = nextStep
        val nextExercise = routine.exercises.getOrNull(nextExIndex)

        return if (nextExercise != null) {
            "${nextExercise.exercise.name} - Set ${nextSetIndex + 1}"
        } else {
            "Routine Complete"
        }
    }

    /**
     * Check if current exercise is the last one in the routine.
     */
    internal fun calculateIsLastExercise(
        isSingleExercise: Boolean,
        currentExercise: RoutineExercise?,
        routine: Routine?
    ): Boolean {
        if (isSingleExercise) {
            return coordinator._currentSetIndex.value >= (currentExercise?.setReps?.size ?: 1) - 1
        }

        val isLastExerciseInRoutine = coordinator._currentExerciseIndex.value >= (routine?.exercises?.size ?: 1) - 1
        val isLastSetInExercise = coordinator._currentSetIndex.value >= (currentExercise?.setReps?.size ?: 1) - 1

        return isLastExerciseInRoutine && isLastSetInExercise
    }

    // ===== Routine CRUD =====

    fun getRoutineById(routineId: String): Routine? {
        return coordinator._routines.value.find { it.id == routineId }
    }

    fun saveRoutine(routine: Routine) {
        scope.launch { workoutRepository.saveRoutine(routine) }
    }

    fun updateRoutine(routine: Routine) {
        scope.launch { workoutRepository.updateRoutine(routine) }
    }

    fun deleteRoutine(routineId: String) {
        scope.launch { workoutRepository.deleteRoutine(routineId) }
    }

    /**
     * Batch delete multiple routines (for multi-select feature)
     */
    fun deleteRoutines(routineIds: Set<String>) {
        scope.launch {
            routineIds.forEach { id ->
                workoutRepository.deleteRoutine(id)
            }
        }
    }

    // ===== Routine Loading =====

    /**
     * Resolve PR percentage weights to absolute values for all exercises in a routine.
     */
    private suspend fun resolveRoutineWeights(routine: Routine): Routine {
        val resolvedExercises = routine.exercises.map { exercise ->
            if (exercise.usePercentOfPR) {
                val resolved = resolveWeightsUseCase(exercise, exercise.programMode)
                if (resolved.fallbackReason != null) {
                    Logger.w { "PR weight fallback for ${exercise.exercise.name}: ${resolved.fallbackReason}" }
                } else if (resolved.isFromPR) {
                    Logger.d { "Resolved ${exercise.exercise.name} weight from PR: ${resolved.percentOfPR}% of ${resolved.usedPR}kg = ${resolved.baseWeight}kg" }
                }
                exercise.copy(
                    weightPerCableKg = resolved.baseWeight,
                    setWeightsPerCableKg = resolved.setWeights
                )
            } else {
                exercise
            }
        }
        return routine.copy(exercises = resolvedExercises)
    }

    /**
     * Internal function to load a routine after weights have been resolved.
     */
    private fun loadRoutineInternal(routine: Routine) {
        coordinator._loadedRoutine.value = routine
        coordinator._currentExerciseIndex.value = 0
        coordinator._currentSetIndex.value = 0
        coordinator._skippedExercises.value = emptySet()
        coordinator._completedExercises.value = emptySet()

        // Issue #222 diagnostic: Reset bodyweight counter for new routine
        coordinator.bodyweightSetsCompletedInRoutine = 0
        // Issue #222 v8: Reset transition flag for new routine
        coordinator.previousExerciseWasBodyweight = false

        // Reset workout state to Idle when loading a routine
        // This fixes the bug where stale Resting state persists from a previous workout
        coordinator._workoutState.value = WorkoutState.Idle

        // Load parameters from first exercise (matching parent repo behavior)
        val firstExercise = routine.exercises[0]
        val firstSetReps = firstExercise.setReps.firstOrNull() // Can be null for AMRAP sets
        // Get per-set weight for first set, falling back to exercise default
        val firstSetWeight = firstExercise.setWeightsPerCableKg.getOrNull(0)
            ?: firstExercise.weightPerCableKg

        // Only bodyweight exercises should have warmupReps = 0
        val isFirstBodyweight = isBodyweightExercise(firstExercise)

        // Issue #188: Trace routine loading with println for reliable logcat output
        println("Issue188-Load: ╔══════════════════════════════════════════════════════════════")
        println("Issue188-Load: ║ LOADING ROUTINE: ${routine.name}")
        println("Issue188-Load: ╠══════════════════════════════════════════════════════════════")
        println("Issue188-Load: ║ First exercise: ${firstExercise.exercise.displayName}")
        println("Issue188-Load: ║ setReps list: ${firstExercise.setReps}")
        println("Issue188-Load: ║ firstSetReps (firstOrNull): $firstSetReps")
        println("Issue188-Load: ║ isAMRAP field on exercise: ${firstExercise.isAMRAP}")
        println("Issue188-Load: ║ progressionKg: ${firstExercise.progressionKg}kg")
        println("Issue188-Load: ║ weightPerCableKg: ${firstSetWeight}kg")
        println("Issue188-Load: ║ programMode: ${firstExercise.programMode.displayName}")
        println("Issue188-Load: ╚══════════════════════════════════════════════════════════════")

        // Issue #203: Fallback to exercise-level isAMRAP flag for legacy ExerciseEditDialog compatibility
        // Legacy "Last set AMRAP" only applies when we're on the last set (set index 0 for single-set exercises)
        val isFirstSetLastSet = firstExercise.setReps.size <= 1
        val firstIsAMRAP = firstSetReps == null || (firstExercise.isAMRAP && isFirstSetLastSet)

        val params = WorkoutParameters(
            programMode = firstExercise.programMode,
            echoLevel = firstExercise.echoLevel,
            eccentricLoad = firstExercise.eccentricLoad,
            reps = firstSetReps ?: 0, // AMRAP sets have null reps, use 0 as placeholder
            weightPerCableKg = firstSetWeight,
            progressionRegressionKg = firstExercise.progressionKg,
            isJustLift = false,  // CRITICAL: Routines are NOT just lift mode
            useAutoStart = false,
            stopAtTop = settingsManager.stopAtTop.value,
            warmupReps = if (isFirstBodyweight) 0 else Constants.DEFAULT_WARMUP_REPS,
            isAMRAP = firstIsAMRAP, // Issue #203: Check both per-set (null reps) and exercise-level flag
            selectedExerciseId = firstExercise.exercise.id,
            stallDetectionEnabled = firstExercise.stallDetectionEnabled
        )

        // Issue #188: Log computed params
        println("Issue188-Load: ║ COMPUTED WorkoutParameters:")
        println("Issue188-Load: ║   isAMRAP=${params.isAMRAP} (from firstSetReps == null || exercise.isAMRAP)")
        println("Issue188-Load: ║   reps=${params.reps}")
        println("Issue188-Load: ║   progressionRegressionKg=${params.progressionRegressionKg}kg")
        lifecycleDelegate.updateWorkoutParameters(params)
    }

    fun loadRoutine(routine: Routine) {
        if (routine.exercises.isEmpty()) {
            Logger.w { "Cannot load routine with no exercises" }
            return
        }

        scope.launch {
            val resolvedRoutine = resolveRoutineWeights(routine)
            loadRoutineInternal(resolvedRoutine)
        }
    }

    /**
     * Issue #2 Fix: Suspend version of loadRoutine that completes only after routine
     * is fully loaded, including PR-based weight resolution.
     *
     * Use this when you need to ensure the routine is loaded before starting a workout,
     * e.g., in SingleExerciseScreen where ensureConnection might fire immediately.
     */
    suspend fun loadRoutineAsync(routine: Routine): Boolean {
        if (routine.exercises.isEmpty()) {
            Logger.w { "Cannot load routine with no exercises" }
            return false
        }

        val resolvedRoutine = resolveRoutineWeights(routine)
        loadRoutineInternal(resolvedRoutine)
        return true
    }

    fun loadRoutineById(routineId: String) {
        val routine = coordinator._routines.value.find { it.id == routineId }
        if (routine != null) {
            clearCycleContext()
            loadRoutine(routine)
        }
    }

    /**
     * Enter routine overview mode.
     */
    fun enterRoutineOverview(routine: Routine) {
        scope.launch {
            val resolvedRoutine = resolveRoutineWeights(routine)
            coordinator._loadedRoutine.value = resolvedRoutine
            coordinator._currentExerciseIndex.value = 0
            coordinator._currentSetIndex.value = 0
            coordinator._skippedExercises.value = emptySet()
            coordinator._completedExercises.value = emptySet()
            coordinator._workoutState.value = WorkoutState.Idle
            coordinator._routineFlowState.value = RoutineFlowState.Overview(
                routine = resolvedRoutine,
                selectedExerciseIndex = 0
            )
        }
    }

    // ===== SetReady Navigation =====

    /**
     * Enter set-ready state for specific exercise and set.
     */
    fun enterSetReady(exerciseIndex: Int, setIndex: Int) {
        val routine = coordinator._loadedRoutine.value ?: return
        val exercise = routine.exercises.getOrNull(exerciseIndex) ?: return

        coordinator._currentExerciseIndex.value = exerciseIndex
        coordinator._currentSetIndex.value = setIndex

        // Get weight for this set
        val setWeight = exercise.setWeightsPerCableKg.getOrNull(setIndex)
            ?: exercise.weightPerCableKg
        // Issue #129: Check raw value for AMRAP before fallback
        val rawSetReps = exercise.setReps.getOrNull(setIndex)
        val setReps = rawSetReps ?: exercise.reps

        coordinator._routineFlowState.value = RoutineFlowState.SetReady(
            exerciseIndex = exerciseIndex,
            setIndex = setIndex,
            adjustedWeight = setWeight,
            adjustedReps = setReps,
            echoLevel = if (exercise.programMode is ProgramMode.Echo) exercise.echoLevel else null,
            eccentricLoadPercent = if (exercise.programMode is ProgramMode.Echo) exercise.eccentricLoad.percentage else null
        )

        // Issue #129: Determine if this specific set is AMRAP (null reps = AMRAP)
        val isSetAmrap = rawSetReps == null
        Logger.d { "enterSetReady: exercise=${exercise.exercise.name}, set=$setIndex, isAMRAP=$isSetAmrap, stallDetection=${exercise.stallDetectionEnabled}" }

        // Update workout parameters for this set
        // Issue #209: Explicitly set isJustLift=false and useAutoStart=false
        coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
            programMode = exercise.programMode,
            weightPerCableKg = setWeight,
            reps = setReps,
            echoLevel = exercise.echoLevel,
            eccentricLoad = exercise.eccentricLoad,
            selectedExerciseId = exercise.exercise.id,
            stallDetectionEnabled = exercise.stallDetectionEnabled,
            isAMRAP = isSetAmrap,
            progressionRegressionKg = exercise.progressionKg,
            isJustLift = false,
            useAutoStart = false
        )
    }

    /**
     * Enter SetReady state with pre-adjusted weight and reps from the overview screen.
     */
    fun enterSetReadyWithAdjustments(exerciseIndex: Int, setIndex: Int, adjustedWeight: Float, adjustedReps: Int) {
        val routine = coordinator._loadedRoutine.value ?: return
        val exercise = routine.exercises.getOrNull(exerciseIndex) ?: return

        coordinator._currentExerciseIndex.value = exerciseIndex
        coordinator._currentSetIndex.value = setIndex

        coordinator._routineFlowState.value = RoutineFlowState.SetReady(
            exerciseIndex = exerciseIndex,
            setIndex = setIndex,
            adjustedWeight = adjustedWeight,
            adjustedReps = adjustedReps,
            echoLevel = if (exercise.programMode is ProgramMode.Echo) exercise.echoLevel else null,
            eccentricLoadPercent = if (exercise.programMode is ProgramMode.Echo) exercise.eccentricLoad.percentage else null
        )

        // Issue #129: Check raw value for AMRAP - null reps in setReps list = AMRAP
        val rawSetReps = exercise.setReps.getOrNull(setIndex)
        val isSetAmrap = rawSetReps == null
        Logger.d { "enterSetReadyWithAdjustments: exercise=${exercise.exercise.name}, set=$setIndex, isAMRAP=$isSetAmrap, stallDetection=${exercise.stallDetectionEnabled}" }

        // Update workout parameters with adjusted values
        // Issue #209: Explicitly set isJustLift=false and useAutoStart=false
        coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
            programMode = exercise.programMode,
            weightPerCableKg = adjustedWeight,
            reps = adjustedReps,
            echoLevel = exercise.echoLevel,
            eccentricLoad = exercise.eccentricLoad,
            selectedExerciseId = exercise.exercise.id,
            stallDetectionEnabled = exercise.stallDetectionEnabled,
            isAMRAP = isSetAmrap,
            progressionRegressionKg = exercise.progressionKg,
            isJustLift = false,
            useAutoStart = false
        )
    }

    /**
     * Update weight in set-ready state.
     */
    fun updateSetReadyWeight(weight: Float) {
        val state = coordinator._routineFlowState.value
        if (state is RoutineFlowState.SetReady && weight >= 0f) {
            coordinator._routineFlowState.value = state.copy(adjustedWeight = weight)
            coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(weightPerCableKg = weight)
        }
    }

    /**
     * Update reps in set-ready state.
     */
    fun updateSetReadyReps(reps: Int) {
        val state = coordinator._routineFlowState.value
        if (state is RoutineFlowState.SetReady && reps >= 1) {
            coordinator._routineFlowState.value = state.copy(adjustedReps = reps)
            coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(reps = reps)
        }
    }

    /**
     * Update echo level in set-ready state for Echo mode.
     */
    fun updateSetReadyEchoLevel(level: EchoLevel) {
        val state = coordinator._routineFlowState.value
        if (state is RoutineFlowState.SetReady) {
            coordinator._routineFlowState.value = state.copy(echoLevel = level)
            coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(echoLevel = level)
        }
    }

    /**
     * Update eccentric load percentage in set-ready state for Echo mode.
     */
    fun updateSetReadyEccentricLoad(percent: Int) {
        // Defensive clamping: Machine hardware limit is 150% eccentric load
        val safePercent = percent.coerceIn(0, 150)
        val state = coordinator._routineFlowState.value
        if (state is RoutineFlowState.SetReady) {
            coordinator._routineFlowState.value = state.copy(eccentricLoadPercent = safePercent)
            val load = EccentricLoad.entries.minByOrNull { kotlin.math.abs(it.percentage - safePercent) }
                ?: EccentricLoad.LOAD_100
            coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(eccentricLoad = load)
        }
    }

    /**
     * Start the set from set-ready state.
     * Delegates BLE and workout lifecycle to DWSM via [WorkoutLifecycleDelegate].
     */
    fun startSetFromReady() {
        val state = coordinator._routineFlowState.value
        if (state !is RoutineFlowState.SetReady) return

        // Full reset before starting to ensure no stale state
        lifecycleDelegate.resetRepCounter()
        coordinator._repCount.value = RepCount()
        coordinator._repRanges.value = null
        resetAutoStopState()

        // Apply the adjusted values to workout parameters
        // Issue #209: Explicitly set isJustLift=false as a safety net
        coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
            weightPerCableKg = state.adjustedWeight,
            reps = state.adjustedReps,
            isJustLift = false
        )

        // Start the workout directly (skip countdown since user already configured on SetReady)
        lifecycleDelegate.startWorkout(skipCountdown = true)
    }

    /**
     * Return to routine overview from set-ready.
     */
    fun returnToOverview() {
        val routine = coordinator._loadedRoutine.value ?: return
        coordinator._routineFlowState.value = RoutineFlowState.Overview(
            routine = routine,
            selectedExerciseIndex = coordinator._currentExerciseIndex.value
        )
    }

    /**
     * Exit routine flow and return to routines list.
     */
    fun exitRoutineFlow() {
        coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
        coordinator._loadedRoutine.value = null
        coordinator._workoutState.value = WorkoutState.Idle
        coordinator.routineStartTime = 0
    }

    /**
     * Show routine complete screen.
     */
    fun showRoutineComplete() {
        val routine = coordinator._loadedRoutine.value ?: return
        // Issue #195: Use coordinator.routineStartTime (set on first set) for total duration
        val duration = if (coordinator.routineStartTime > 0) {
            currentTimeMillis() - coordinator.routineStartTime
        } else {
            0L
        }
        coordinator._routineFlowState.value = RoutineFlowState.Complete(
            routineName = routine.name,
            totalSets = routine.exercises.sumOf { it.setReps.size },
            totalExercises = routine.exercises.size,
            totalDurationMs = duration
        )
    }

    fun clearLoadedRoutine() {
        coordinator._loadedRoutine.value = null
        clearCycleContext()
        coordinator.routineStartTime = 0
    }

    // ===== Exercise Navigation =====

    /**
     * Navigate to specific exercise in overview carousel.
     */
    fun selectExerciseInOverview(index: Int) {
        val state = coordinator._routineFlowState.value
        if (state is RoutineFlowState.Overview && index in state.routine.exercises.indices) {
            coordinator._routineFlowState.value = state.copy(selectedExerciseIndex = index)
        }
    }

    /**
     * Internal helper to perform the actual exercise navigation.
     */
    private fun navigateToExerciseInternal(routine: Routine, index: Int) {
        coordinator._currentExerciseIndex.value = index
        coordinator._currentSetIndex.value = 0

        val exercise = routine.exercises[index]
        val setReps = exercise.setReps.getOrNull(0)
        val setWeight = exercise.setWeightsPerCableKg.getOrNull(0) ?: exercise.weightPerCableKg

        coordinator._workoutParameters.update { params ->
            params.copy(
                programMode = exercise.programMode,
                echoLevel = exercise.echoLevel,
                eccentricLoad = exercise.eccentricLoad,
                reps = setReps ?: exercise.reps,
                weightPerCableKg = setWeight,
                progressionRegressionKg = exercise.progressionKg,
                warmupReps = 3,
                selectedExerciseId = exercise.exercise.id
            )
        }

        coordinator._workoutState.value = WorkoutState.Idle
        coordinator._repCount.value = RepCount()
        lifecycleDelegate.resetRepCounter()

        Logger.i("RoutineFlowManager") { "Jumped to exercise $index: ${exercise.exercise.name}" }
    }

    fun advanceToNextExercise() {
        val routine = coordinator._loadedRoutine.value ?: return
        val nextIndex = coordinator._currentExerciseIndex.value + 1
        if (nextIndex < routine.exercises.size) {
            jumpToExercise(nextIndex)
        }
    }

    /**
     * Navigate to a specific exercise in the routine.
     * Uses [WorkoutLifecycleDelegate] for BLE stop commands before navigation.
     */
    fun jumpToExercise(index: Int) {
        val routine = coordinator._loadedRoutine.value ?: return
        if (index < 0 || index >= routine.exercises.size) return

        // Issue #125: Block exercise navigation during Active state
        if (coordinator._workoutState.value is WorkoutState.Active) {
            Logger.w("RoutineFlowManager") { "Cannot jump to exercise $index while workout is Active - stop workout first" }
            scope.launch {
                coordinator._userFeedbackEvents.emit("Stop the current set first")
            }
            return
        }

        // Save current exercise progress
        val currentRepCount = coordinator._repCount.value
        if (currentRepCount.workingReps > 0 && coordinator._workoutState.value !is WorkoutState.Completed) {
            coordinator._completedExercises.update { it + coordinator._currentExerciseIndex.value }
            Logger.d("RoutineFlowManager") { "Saving progress for exercise ${coordinator._currentExerciseIndex.value}: ${currentRepCount.workingReps} reps" }
        } else if (coordinator._workoutState.value !is WorkoutState.Completed) {
            coordinator._skippedExercises.update { it + coordinator._currentExerciseIndex.value }
            Logger.d("RoutineFlowManager") { "Skipping exercise ${coordinator._currentExerciseIndex.value}" }
        }

        // Cancel any active timers
        coordinator.restTimerJob?.cancel()
        coordinator.bodyweightTimerJob?.cancel()
        coordinator._timedExerciseRemainingSeconds.value = null
        resetAutoStopState()

        // Issue #172: Async navigation with proper BLE cleanup
        scope.launch {
            try {
                // Issue #205: Clear fault state with StopPacket (0x50)
                lifecycleDelegate.sendStopCommand()
                delay(100)

                // Full reset with RESET command (0x0A)
                lifecycleDelegate.stopMachineWorkout()
                delay(150)

                Logger.d("RoutineFlowManager") { "BLE stop sequence sent before navigation to exercise $index" }
            } catch (e: Exception) {
                Logger.w(e) { "Stop command before navigation failed (non-fatal): ${e.message}" }
            }

            navigateToExerciseInternal(routine, index)
            // Auto-start the next exercise with countdown
            lifecycleDelegate.startWorkout(skipCountdown = false)
        }
    }

    /**
     * Skip the current exercise and move to the next one.
     */
    fun skipCurrentExercise() {
        val routine = coordinator._loadedRoutine.value ?: return
        val nextIndex = coordinator._currentExerciseIndex.value + 1
        if (nextIndex < routine.exercises.size) {
            coordinator._skippedExercises.update { it + coordinator._currentExerciseIndex.value }
            jumpToExercise(nextIndex)
        }
    }

    /**
     * Go back to the previous exercise in the routine.
     */
    fun goToPreviousExercise() {
        val prevIndex = coordinator._currentExerciseIndex.value - 1
        if (prevIndex >= 0) {
            jumpToExercise(prevIndex)
        }
    }

    fun canGoBack(): Boolean {
        return coordinator._loadedRoutine.value != null && coordinator._currentExerciseIndex.value > 0
    }

    fun canSkipForward(): Boolean {
        val routine = coordinator._loadedRoutine.value ?: return false
        return coordinator._currentExerciseIndex.value < routine.exercises.size - 1
    }

    fun getRoutineExerciseNames(): List<String> {
        return coordinator._loadedRoutine.value?.exercises?.map { it.exercise.name } ?: emptyList()
    }

    /**
     * Navigate to previous set/exercise in set-ready.
     */
    fun setReadyPrev() {
        val state = coordinator._routineFlowState.value
        if (state !is RoutineFlowState.SetReady) return
        val routine = coordinator._loadedRoutine.value ?: return

        getPreviousStep(routine, state.exerciseIndex, state.setIndex)?.let { (exIdx, setIdx) ->
            enterSetReady(exIdx, setIdx)
        }
    }

    /**
     * Skip to next set/exercise in set-ready.
     */
    fun setReadySkip() {
        val state = coordinator._routineFlowState.value
        if (state !is RoutineFlowState.SetReady) return
        val routine = coordinator._loadedRoutine.value ?: return

        getNextStep(routine, state.exerciseIndex, state.setIndex)?.let { (exIdx, setIdx) ->
            enterSetReady(exIdx, setIdx)
        }
    }

    // ===== Superset CRUD =====

    /**
     * Create a new superset in a routine.
     */
    suspend fun createSuperset(
        routineId: String,
        name: String? = null,
        exercises: List<RoutineExercise> = emptyList()
    ): Superset {
        val routine = getRoutineById(routineId) ?: throw IllegalArgumentException("Routine not found")
        val existingColors = routine.supersets.map { it.colorIndex }.toSet()
        val colorIndex = SupersetColors.next(existingColors)
        val supersetCount = routine.supersets.size
        val autoName = name ?: "Superset ${'A' + supersetCount}"
        val orderIndex = routine.getItems().maxOfOrNull { it.orderIndex }?.plus(1) ?: 0

        val superset = Superset(
            id = generateSupersetId(),
            routineId = routineId,
            name = autoName,
            colorIndex = colorIndex,
            restBetweenSeconds = 10,
            orderIndex = orderIndex
        )

        val updatedSupersets = routine.supersets + superset
        val updatedExercises = exercises.mapIndexed { index, exercise ->
            exercise.copy(supersetId = superset.id, orderInSuperset = index)
        } + routine.exercises.filter { it.id !in exercises.map { e -> e.id } }

        val updatedRoutine = routine.copy(supersets = updatedSupersets, exercises = updatedExercises)
        workoutRepository.updateRoutine(updatedRoutine)

        return superset
    }

    /**
     * Update superset properties (name, rest time, color).
     */
    suspend fun updateSuperset(routineId: String, superset: Superset) {
        val routine = getRoutineById(routineId) ?: return
        val updatedSupersets = routine.supersets.map {
            if (it.id == superset.id) superset else it
        }
        val updatedRoutine = routine.copy(supersets = updatedSupersets)
        workoutRepository.updateRoutine(updatedRoutine)
    }

    /**
     * Delete a superset. Exercises become standalone.
     */
    suspend fun deleteSuperset(routineId: String, supersetId: String) {
        val routine = getRoutineById(routineId) ?: return
        val updatedSupersets = routine.supersets.filter { it.id != supersetId }
        val updatedExercises = routine.exercises.map { exercise ->
            if (exercise.supersetId == supersetId) {
                exercise.copy(supersetId = null, orderInSuperset = 0)
            } else {
                exercise
            }
        }
        val updatedRoutine = routine.copy(supersets = updatedSupersets, exercises = updatedExercises)
        workoutRepository.updateRoutine(updatedRoutine)
    }

    /**
     * Move an exercise into a superset.
     */
    suspend fun addExerciseToSuperset(routineId: String, exerciseId: String, supersetId: String) {
        val routine = getRoutineById(routineId) ?: return
        val superset = routine.supersets.find { it.id == supersetId } ?: return
        val currentExercisesInSuperset = routine.exercises.filter { it.supersetId == supersetId }
        val newOrderInSuperset = currentExercisesInSuperset.maxOfOrNull { it.orderInSuperset }?.plus(1) ?: 0

        val updatedExercises = routine.exercises.map { exercise ->
            if (exercise.id == exerciseId) {
                exercise.copy(supersetId = supersetId, orderInSuperset = newOrderInSuperset)
            } else {
                exercise
            }
        }
        val updatedRoutine = routine.copy(exercises = updatedExercises)
        workoutRepository.updateRoutine(updatedRoutine)
    }

    /**
     * Remove an exercise from a superset (becomes standalone).
     */
    suspend fun removeExerciseFromSuperset(routineId: String, exerciseId: String) {
        val routine = getRoutineById(routineId) ?: return
        val updatedExercises = routine.exercises.map { exercise ->
            if (exercise.id == exerciseId) {
                exercise.copy(supersetId = null, orderInSuperset = 0)
            } else {
                exercise
            }
        }
        val updatedRoutine = routine.copy(exercises = updatedExercises)
        workoutRepository.updateRoutine(updatedRoutine)
    }

    // ===== State Query Helpers =====

    fun getCurrentExercise(): RoutineExercise? {
        val routine = coordinator._loadedRoutine.value ?: return null
        return routine.exercises.getOrNull(coordinator._currentExerciseIndex.value)
    }

    /**
     * Check if there's resumable progress for a specific routine.
     */
    fun hasResumableProgress(routineId: String): Boolean {
        val loaded = coordinator._loadedRoutine.value ?: return false
        if (loaded.id != routineId) return false
        if (coordinator._currentSetIndex.value > 0 || coordinator._currentExerciseIndex.value > 0) {
            val exercise = loaded.exercises.getOrNull(coordinator._currentExerciseIndex.value) ?: return false
            return coordinator._currentSetIndex.value < exercise.setReps.size
        }
        return false
    }

    /**
     * Get information about resumable progress for display in dialog.
     */
    fun getResumableProgressInfo(): ResumableProgressInfo? {
        val routine = coordinator._loadedRoutine.value ?: return null
        val exercise = routine.exercises.getOrNull(coordinator._currentExerciseIndex.value) ?: return null
        return ResumableProgressInfo(
            exerciseName = exercise.exercise.displayName,
            currentSet = coordinator._currentSetIndex.value + 1,
            totalSets = exercise.setReps.size,
            currentExercise = coordinator._currentExerciseIndex.value + 1,
            totalExercises = routine.exercises.size
        )
    }

    /**
     * Log RPE (Rate of Perceived Exertion) for the current set.
     */
    fun logRpeForCurrentSet(rpe: Int) {
        coordinator._currentSetRpe.value = rpe
        Logger.d("RoutineFlowManager") { "RPE logged for current set: $rpe" }
    }

    // ===== Shared Helpers =====
    // These are used by both RoutineFlowManager and DWSM (ActiveSessionEngine in future).
    // Placed here as companion/internal functions accessible to both.

    /**
     * Fully reset auto-stop state for a new workout/set.
     * Operates directly on coordinator fields.
     */
    private fun resetAutoStopState() {
        coordinator.autoStopStartTime = null
        coordinator.autoStopTriggered = false
        coordinator.autoStopStopRequested = false
        coordinator.stallStartTime = null
        coordinator.isCurrentlyStalled = false
        coordinator._autoStopState.value = AutoStopUiState()
    }

    /**
     * Clear the active cycle context (e.g., when starting a non-cycle workout).
     */
    fun clearCycleContext() {
        coordinator.activeCycleId = null
        coordinator.activeCycleDayNumber = null
    }
}

/**
 * Check if the given exercise is a bodyweight exercise.
 *
 * Bodyweight = no cable accessories (HANDLES, BAR, ROPE, SHORT_BAR, BELT, STRAPS)
 * in the exercise's equipment list. Non-cable equipment like BENCH is allowed.
 *
 * Top-level function accessible to both RoutineFlowManager and DWSM/ActiveSessionEngine.
 */
internal fun isBodyweightExercise(exercise: RoutineExercise?): Boolean {
    return exercise?.let {
        val isBodyweight = !it.exercise.hasCableAccessory
        Logger.d { "isBodyweightExercise: exercise=${it.exercise.name}, equipment='${it.exercise.equipment}', hasCableAccessory=${it.exercise.hasCableAccessory}, result=$isBodyweight" }
        isBodyweight
    } ?: false
}

/**
 * Check if current workout is in single exercise mode.
 *
 * Top-level function accessible to both RoutineFlowManager and DWSM/ActiveSessionEngine.
 */
internal fun isSingleExerciseMode(coordinator: WorkoutCoordinator): Boolean {
    val routine = coordinator._loadedRoutine.value
    return routine == null || routine.id.startsWith(DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX)
}
