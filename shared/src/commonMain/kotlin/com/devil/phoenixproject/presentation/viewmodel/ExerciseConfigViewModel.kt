package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutMode
import com.devil.phoenixproject.domain.model.toWorkoutMode
// import dagger.hilt.android.lifecycle.HiltViewModel
// import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.devil.phoenixproject.util.KmpUtils
import co.touchlab.kermit.Logger


// These are tightly coupled with the ExerciseEditDialog, so keeping them here is reasonable.
// They could be moved to a dedicated file in the `presentation.screen` package if used elsewhere.
enum class ExerciseType {
    BODYWEIGHT,
    STANDARD
}

enum class SetMode {
    REPS,
    DURATION
}

data class SetConfiguration(
    val id: String = com.devil.phoenixproject.domain.model.generateUUID(), // Stable ID for Compose keys
    val setNumber: Int,
    val reps: Int? = 10,  // Nullable to support AMRAP (null = AMRAP)
    val weightPerCable: Float = 15.0f,
    val duration: Int = 30,
    val restSeconds: Int = 60 // Add this
)

class ExerciseConfigViewModel constructor(
    private val personalRecordRepository: PersonalRecordRepository? = null
) : ViewModel() {

    private val log = Logger.withTag("ExerciseConfigViewModel")
    private val _initialized = MutableStateFlow(false)

    // Dependencies that need to be passed in
    private lateinit var originalExercise: RoutineExercise
    private lateinit var weightUnit: WeightUnit
    private lateinit var kgToDisplay: (Float, WeightUnit) -> Float
    private lateinit var displayToKg: (Float, WeightUnit) -> Float

    // PR state for the current exercise/mode combination
    private val _currentExercisePR = MutableStateFlow<PersonalRecord?>(null)
    val currentExercisePR: StateFlow<PersonalRecord?> = _currentExercisePR.asStateFlow()

    // Convenience accessor for just the PR weight (useful for PRIndicator component)
    val currentExercisePRWeight: Float?
        get() = _currentExercisePR.value?.weightPerCableKg

    private val _exerciseType = MutableStateFlow(ExerciseType.STANDARD)
    val exerciseType: StateFlow<ExerciseType> = _exerciseType.asStateFlow()

    private val _setMode = MutableStateFlow(SetMode.REPS)
    val setMode: StateFlow<SetMode> = _setMode.asStateFlow()

    private val _sets = MutableStateFlow<List<SetConfiguration>>(emptyList())
    val sets: StateFlow<List<SetConfiguration>> = _sets.asStateFlow()

    private val _selectedMode = MutableStateFlow<WorkoutMode>(WorkoutMode.OldSchool)
    val selectedMode: StateFlow<WorkoutMode> = _selectedMode.asStateFlow()

    private val _weightChange = MutableStateFlow(0)
    val weightChange: StateFlow<Int> = _weightChange.asStateFlow()

    private val _rest = MutableStateFlow(60)
    val rest: StateFlow<Int> = _rest.asStateFlow()

    private val _perSetRestTime = MutableStateFlow(false)
    val perSetRestTime: StateFlow<Boolean> = _perSetRestTime.asStateFlow()

    private val _eccentricLoad = MutableStateFlow(EccentricLoad.LOAD_100)
    val eccentricLoad: StateFlow<EccentricLoad> = _eccentricLoad.asStateFlow()

    private val _echoLevel = MutableStateFlow(EchoLevel.HARDER)
    val echoLevel: StateFlow<EchoLevel> = _echoLevel.asStateFlow()

    private val _stallDetectionEnabled = MutableStateFlow(true)
    val stallDetectionEnabled: StateFlow<Boolean> = _stallDetectionEnabled.asStateFlow()

    // PR percentage scaling state (Issue #57)
    private val _usePercentOfPR = MutableStateFlow(false)
    val usePercentOfPR: StateFlow<Boolean> = _usePercentOfPR.asStateFlow()

    private val _weightPercentOfPR = MutableStateFlow(80)
    val weightPercentOfPR: StateFlow<Int> = _weightPercentOfPR.asStateFlow()

    private val _prTypeForScaling = MutableStateFlow(PRType.MAX_WEIGHT)
    val prTypeForScaling: StateFlow<PRType> = _prTypeForScaling.asStateFlow()

    init {

    }

    fun initialize(
        exercise: RoutineExercise,
        unit: WeightUnit,
        toDisplay: (Float, WeightUnit) -> Float,
        toKg: (Float, WeightUnit) -> Float,
        prWeightKg: Float? = null  // Optional PR weight to use as default
    ) {
        if (_initialized.value && originalExercise.id == exercise.id) {
            return
        }

        originalExercise = exercise
        weightUnit = unit
        kgToDisplay = toDisplay
        displayToKg = toKg

        _exerciseType.value = if (exercise.exercise.equipment.isEmpty() ||
            exercise.exercise.equipment.equals("bodyweight", ignoreCase = true)) {
            ExerciseType.BODYWEIGHT
        } else {
            ExerciseType.STANDARD
        }

        // Force DURATION mode for bodyweight exercises, otherwise use existing duration setting
        _setMode.value = if (_exerciseType.value == ExerciseType.BODYWEIGHT) {
            SetMode.DURATION
        } else if (exercise.duration != null) {
            SetMode.DURATION
        } else {
            SetMode.REPS
        }

        // Use PR weight as default if available, otherwise use 15kg
        val defaultWeightKg = prWeightKg ?: 15f

        // Determine default duration and log if defaulting for bodyweight exercises
        val defaultDuration = if (exercise.duration != null) {
            exercise.duration
        } else {
            if (_exerciseType.value == ExerciseType.BODYWEIGHT) {
                logWarning("Bodyweight exercise '${exercise.exercise.name}' missing duration - defaulting to 30s")
            }
            30
        }

        val initialSets = exercise.setReps.mapIndexed { index, reps ->
            val perSetWeightKg = exercise.setWeightsPerCableKg.getOrNull(index) ?: exercise.weightPerCableKg
            val perSetRest = exercise.setRestSeconds.getOrNull(index) ?: 60
            SetConfiguration(
                id = generateUUID(),
                setNumber = index + 1,
                reps = reps, // Preserve null for AMRAP sets
                weightPerCable = kgToDisplay(perSetWeightKg, weightUnit),
                duration = defaultDuration,
                restSeconds = perSetRest
            )
        }.ifEmpty {
            listOf(
                SetConfiguration(id = generateUUID(), setNumber = 1, reps = 10, weightPerCable = kgToDisplay(defaultWeightKg, weightUnit), restSeconds = 60),
                SetConfiguration(id = generateUUID(), setNumber = 2, reps = 10, weightPerCable = kgToDisplay(defaultWeightKg, weightUnit), restSeconds = 60),
                SetConfiguration(id = generateUUID(), setNumber = 3, reps = 10, weightPerCable = kgToDisplay(defaultWeightKg, weightUnit), restSeconds = 60)
            )
        }

        // Debug logging for AMRAP exercise data loading
        logDebug("━━━━━ ExerciseConfigViewModel.initialize() ━━━━━")
        logDebug("Exercise: ${exercise.exercise.name}")
        logDebug("isAMRAP flag: ${exercise.isAMRAP}")
        logDebug("perSetRestTime flag: ${exercise.perSetRestTime}")
        logDebug("setReps: ${exercise.setReps}")
        logDebug("setWeightsPerCableKg: ${exercise.setWeightsPerCableKg}")
        logDebug("weightPerCableKg: ${exercise.weightPerCableKg}")
        logDebug("setRestSeconds: ${exercise.setRestSeconds}")
        logDebug("Loaded sets:")
        initialSets.forEach { set ->
            logDebug("  Set ${set.setNumber}: reps=${set.reps}, weight=${set.weightPerCable}, rest=${set.restSeconds}")
        }
        logDebug("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        _sets.value = initialSets

        _selectedMode.value = exercise.programMode.toWorkoutMode(exercise.echoLevel)
        _weightChange.value = kgToDisplay(exercise.progressionKg, weightUnit).toInt()
        _rest.value = exercise.setRestSeconds.firstOrNull()?.coerceIn(0, 300) ?: 60 // Use first rest time or default
        _perSetRestTime.value = exercise.perSetRestTime
        _eccentricLoad.value = exercise.eccentricLoad
        _echoLevel.value = exercise.echoLevel
        _stallDetectionEnabled.value = exercise.stallDetectionEnabled

        // PR percentage scaling fields (Issue #57)
        _usePercentOfPR.value = exercise.usePercentOfPR
        _weightPercentOfPR.value = exercise.weightPercentOfPR
        _prTypeForScaling.value = exercise.prTypeForScaling

        // Load PR for the current exercise and mode
        exercise.exercise.id?.let { exerciseId ->
            loadPRForExercise(exerciseId, _selectedMode.value.displayName)
        }

        _initialized.value = true
    }

    fun onSetModeChange(mode: SetMode) {
        // Bodyweight exercises must always use DURATION mode (cannot count reps)
        if (_exerciseType.value == ExerciseType.BODYWEIGHT && mode == SetMode.REPS) {
            logWarning("Cannot switch to REPS mode for bodyweight exercises - staying in DURATION mode")
            return
        }
        _setMode.value = mode
    }

    fun onSelectedModeChange(mode: WorkoutMode) {
        _selectedMode.value = mode
        // Load PR for the new mode
        if (::originalExercise.isInitialized) {
            originalExercise.exercise.id?.let { exerciseId ->
                loadPRForExercise(exerciseId, mode.displayName)
            }
        }
    }

    /**
     * Load the personal record for the given exercise and workout mode.
     * This updates currentExercisePR which can be used by PRIndicator components.
     */
    fun loadPRForExercise(exerciseId: String, workoutMode: String) {
        if (personalRecordRepository == null) {
            logDebug("No PersonalRecordRepository available - skipping PR lookup")
            return
        }
        viewModelScope.launch {
            try {
                val pr = personalRecordRepository.getBestWeightPR(exerciseId, workoutMode)
                _currentExercisePR.value = pr
                logDebug("Loaded PR for exercise=$exerciseId, mode=$workoutMode: ${pr?.weightPerCableKg ?: "none"}")
            } catch (e: Exception) {
                logWarning("Failed to load PR for exercise=$exerciseId, mode=$workoutMode: ${e.message}")
                _currentExercisePR.value = null
            }
        }
    }

    fun onWeightChange(change: Int) {
        _weightChange.value = change
    }

    fun onRestChange(newRest: Int) {
        _rest.value = newRest
    }

    fun onPerSetRestTimeChange(enabled: Boolean) {
        _perSetRestTime.value = enabled
        // When switching to single rest time, update all sets to use the current rest value
        if (!enabled) {
            _sets.value = _sets.value.map { it.copy(restSeconds = _rest.value) }
        }
    }

    fun onEccentricLoadChange(load: EccentricLoad) {
        _eccentricLoad.value = load
    }

    fun onEchoLevelChange(level: EchoLevel) {
        _echoLevel.value = level
        // Also update _selectedMode if it's Echo mode to keep them in sync
        // This ensures the workoutType uses the correct level when saving
        val current = _selectedMode.value
        if (current is WorkoutMode.Echo) {
            _selectedMode.value = WorkoutMode.Echo(level)
        }
    }

    fun onStallDetectionEnabledChange(enabled: Boolean) {
        _stallDetectionEnabled.value = enabled
    }

    // PR percentage scaling handlers (Issue #57)
    fun onUsePercentOfPRChange(enabled: Boolean) {
        _usePercentOfPR.value = enabled
    }

    fun onWeightPercentOfPRChange(percent: Int) {
        _weightPercentOfPR.value = percent.coerceIn(50, 120)
    }

    fun onPRTypeForScalingChange(prType: PRType) {
        _prTypeForScaling.value = prType
    }

    /**
     * Calculate the resolved weight based on current PR and percentage settings.
     * Returns null if PR is not available or percentage scaling is disabled.
     */
    fun calculateResolvedWeight(): Float? {
        val pr = _currentExercisePR.value ?: return null
        if (!_usePercentOfPR.value) return null
        val percent = _weightPercentOfPR.value
        if (percent <= 0) return null
        return (pr.weightPerCableKg * percent / 100f).roundToHalfKg()
    }

    private fun Float.roundToHalfKg(): Float {
        return (this * 2).toInt() / 2f
    }

    fun updateReps(setId: String, reps: Int?) {
        _sets.value = _sets.value.map { set ->
            if (set.id == setId) set.copy(reps = reps) else set
        }
    }

    fun updateWeight(setId: String, weight: Float) {
        _sets.value = _sets.value.map { set ->
            if (set.id == setId) set.copy(weightPerCable = weight) else set
        }
    }

    fun updateDuration(setId: String, duration: Int) {
        _sets.value = _sets.value.map { set ->
            if (set.id == setId) set.copy(duration = duration) else set
        }
    }

    fun updateRestTime(setId: String, restSeconds: Int) {
        _sets.value = _sets.value.map { set ->
            if (set.id == setId) set.copy(restSeconds = restSeconds) else set
        }
    }

    fun addSet() {
        val lastSet = _sets.value.lastOrNull()
        val newSet = SetConfiguration(
            setNumber = _sets.value.size + 1,
            reps = lastSet?.reps ?: 10,
            weightPerCable = lastSet?.weightPerCable ?: kgToDisplay(15f, weightUnit),
            duration = lastSet?.duration ?: 30,
            restSeconds = lastSet?.restSeconds ?: 60
        )
        _sets.value = _sets.value + newSet
    }

    fun deleteSet(index: Int) {
        val newSets = _sets.value.filterIndexed { i, _ -> i != index }
            .mapIndexed { i, set -> set.copy(setNumber = i + 1) }
        _sets.value = newSets
    }

    fun onSave(onSaveCallback: (RoutineExercise) -> Unit) {
        if (_sets.value.isEmpty()) return

        // Determine rest times based on perSetRestTime toggle
        val restTimes = if (_perSetRestTime.value) {
            // Per-set rest times: use each set's rest time
            _sets.value.map { it.restSeconds }
        } else {
            // Single rest time: use the bottom rest time picker value for all sets
            List(_sets.value.size) { _rest.value }
        }

        // Determine if exercise is AMRAP (all sets have null reps)
        val isAMRAP = _sets.value.all { it.reps == null }

        // Debug logging for AMRAP exercise data saving
        logDebug("━━━━━ ExerciseConfigViewModel.onSave() ━━━━━")
        logDebug("Exercise: ${originalExercise.exercise.name}")
        logDebug("isAMRAP computed: $isAMRAP")
        logDebug("perSetRestTime toggle: ${_perSetRestTime.value}")
        logDebug("Current sets before save:")
        _sets.value.forEach { set ->
            logDebug("  Set ${set.setNumber}: reps=${set.reps}, weight=${set.weightPerCable}, rest=${set.restSeconds}")
        }
        logDebug("Rest times to save: $restTimes")
        logDebug("Weights to save: ${_sets.value.map { displayToKg(it.weightPerCable, weightUnit) }}")

        val existingPercentages = originalExercise.setWeightsPercentOfPR
        val hasCustomPercentages = existingPercentages.isNotEmpty() &&
            existingPercentages.any { it != originalExercise.weightPercentOfPR }
        val resolvedSetWeightsPercentOfPR = if (_usePercentOfPR.value) {
            if (hasCustomPercentages) {
                List(_sets.value.size) { index ->
                    existingPercentages.getOrNull(index) ?: _weightPercentOfPR.value
                }
            } else {
                List(_sets.value.size) { _weightPercentOfPR.value }
            }
        } else {
            emptyList()
        }

        val updatedExercise = originalExercise.copy(
            setReps = _sets.value.map { it.reps },
            weightPerCableKg = displayToKg(_sets.value.first().weightPerCable, weightUnit),
            setWeightsPerCableKg = _sets.value.map { displayToKg(it.weightPerCable, weightUnit) },
            programMode = _selectedMode.value.toProgramMode(),
            eccentricLoad = _eccentricLoad.value,
            echoLevel = _echoLevel.value,
            progressionKg = displayToKg(_weightChange.value.toFloat(), weightUnit),
            setRestSeconds = restTimes,
            duration = if (_setMode.value == SetMode.DURATION) {
                _sets.value.firstOrNull()?.duration ?: 30 // Default to 30 seconds if not set
            } else null,
            perSetRestTime = _perSetRestTime.value,
            isAMRAP = isAMRAP,
            stallDetectionEnabled = _stallDetectionEnabled.value,
            // PR percentage scaling fields (Issue #57)
            usePercentOfPR = _usePercentOfPR.value,
            weightPercentOfPR = _weightPercentOfPR.value,
            prTypeForScaling = _prTypeForScaling.value,
            setWeightsPercentOfPR = resolvedSetWeightsPercentOfPR
        )

        logDebug("Updated exercise to save:")
        logDebug("  setReps: ${updatedExercise.setReps}")
        logDebug("  setWeightsPerCableKg: ${updatedExercise.setWeightsPerCableKg}")
        logDebug("  weightPerCableKg: ${updatedExercise.weightPerCableKg}")
        logDebug("  setRestSeconds: ${updatedExercise.setRestSeconds}")
        logDebug("  perSetRestTime: ${updatedExercise.perSetRestTime}")
        logDebug("  isAMRAP: ${updatedExercise.isAMRAP}")
        logDebug("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        onSaveCallback(updatedExercise)
        _initialized.value = false // Reset for next use
    }

    fun onDismiss() {
        _initialized.value = false // Reset for next use
    }

    override fun onCleared() {
        super.onCleared()
    }

    private fun generateUUID(): String {
        // Fallback UUID generation using timestamp and random number
        return "uuid-${KmpUtils.currentTimeMillis()}-${(0..999).random()}"
    }

    private fun logDebug(message: String) {
        log.d { message }
    }

    private fun logWarning(message: String) {
        log.w { message }
    }
}
