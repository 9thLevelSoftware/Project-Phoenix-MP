package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutMode
import com.devil.phoenixproject.domain.model.toWorkoutMode
// import dagger.hilt.android.lifecycle.HiltViewModel
// import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class ExerciseConfigViewModel constructor() : ViewModel() {

    private val log = Logger.withTag("ExerciseConfigViewModel")
    private val _initialized = MutableStateFlow(false)

    // Dependencies that need to be passed in
    private lateinit var originalExercise: RoutineExercise
    private lateinit var weightUnit: WeightUnit
    private lateinit var kgToDisplay: (Float, WeightUnit) -> Float
    private lateinit var displayToKg: (Float, WeightUnit) -> Float

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
            stallDetectionEnabled = _stallDetectionEnabled.value
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
