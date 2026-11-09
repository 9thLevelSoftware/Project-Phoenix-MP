package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.getBestVolumePRForWorkoutMode
import com.devil.phoenixproject.data.repository.getBestWeightPRForWorkoutMode
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutMode
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.domain.model.toWorkoutMode
import com.devil.phoenixproject.domain.usecase.ResolveRoutineScalingBaselineUseCase
import com.devil.phoenixproject.domain.usecase.RoutineScalingBaseline
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// These are tightly coupled with the ExerciseEditDialog, so keeping them here is reasonable.
// They could be moved to a dedicated file in the `presentation.screen` package if used elsewhere.
enum class ExerciseType {
    BODYWEIGHT,
    STANDARD,
}

enum class SetMode {
    REPS,
    DURATION,
}

enum class ScalingBaselineLoadStatus {
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
}

data class SetConfiguration(
    val id: String = generateUUID(), // Stable ID for Compose keys
    val setNumber: Int,
    val reps: Int? = 10, // Nullable to support AMRAP (null = AMRAP)
    val weightPerCable: Float = 15.0f,
    val duration: Int = 30,
    val restSeconds: Int = 60, // Add this
    val repeatCount: Int = 1, // 1 = no repeat, 2-20 = repeat N times (Issue #667)
    val echoLevel: EchoLevel? = null, // Per-set echo override (Issue #667 review fix)
)

class ExerciseConfigViewModel constructor(
    private val personalRecordRepository: PersonalRecordRepository? = null,
    private val velocityOneRepMaxRepository: VelocityOneRepMaxRepository? = null,
    private val baselineRepository: ProfileExerciseBaselineRepository,
    private val completedSetRepository: CompletedSetRepository,
) : ViewModel() {

    private val scalingBaselineResolver: ResolveRoutineScalingBaselineUseCase? =
        if (personalRecordRepository != null && velocityOneRepMaxRepository != null) {
            ResolveRoutineScalingBaselineUseCase(
                personalRecordRepository,
                baselineRepository,
                velocityOneRepMaxRepository,
                completedSetRepository,
            )
        } else {
            null
        }

    private val log = Logger.withTag("ExerciseConfigViewModel")
    private val _initialized = MutableStateFlow(false)
    private var activeProfileId: String = "default"
    private var baselineRequestId = 0L

    private data class EstimatedBaselineLookupContext(
        val exerciseId: String,
        val profileId: String,
        val mode: ProgramMode,
    )

    private data class CachedEstimatedBaseline(
        val context: EstimatedBaselineLookupContext,
        val baseline: RoutineScalingBaseline?,
    )

    private var estimatedBaselineLookupContext: EstimatedBaselineLookupContext? = null
    private var cachedEstimatedBaseline: CachedEstimatedBaseline? = null

    // Dependencies that need to be passed in
    private lateinit var originalExercise: RoutineExercise
    private lateinit var weightUnit: WeightUnit
    private lateinit var kgToDisplay: (Float, WeightUnit) -> Float
    private lateinit var displayToKg: (Float, WeightUnit) -> Float
    // Keep the last user-authored/saved absolute weights separate from any baseline-scaled preview.
    private var savedAbsoluteSetWeightsKg: List<Float> = emptyList()

    // PR state for the current exercise/mode combination
    private val _currentExercisePR = MutableStateFlow<PersonalRecord?>(null)
    val currentExercisePR: StateFlow<PersonalRecord?> = _currentExercisePR.asStateFlow()

    // Max-volume PR for the current exercise/mode (used when scalingBasis == MAX_VOLUME_PR)
    private val _currentMaxVolumePR = MutableStateFlow<PersonalRecord?>(null)
    val currentMaxVolumePR: StateFlow<PersonalRecord?> = _currentMaxVolumePR.asStateFlow()

    // Shared baseline resolver output, including provenance and cross-mode fallback metadata.
    private val _routineScalingBaseline = MutableStateFlow<RoutineScalingBaseline?>(null)
    val routineScalingBaseline: StateFlow<RoutineScalingBaseline?> = _routineScalingBaseline.asStateFlow()

    private val _baselineLoadStatus = MutableStateFlow(ScalingBaselineLoadStatus.UNAVAILABLE)
    val baselineLoadStatus: StateFlow<ScalingBaselineLoadStatus> = _baselineLoadStatus.asStateFlow()
    private val _baselineResolutionReason = MutableStateFlow<String?>(null)
    val baselineResolutionReason: StateFlow<String?> = _baselineResolutionReason.asStateFlow()
    // Basis-independent availability lets the editor expose Est. 1RM when it is the only
    // source, even while another scaling basis is selected.
    private val _estimatedOneRepMaxBaselineAvailable = MutableStateFlow(false)
    val estimatedOneRepMaxBaselineAvailable: StateFlow<Boolean> =
        _estimatedOneRepMaxBaselineAvailable.asStateFlow()

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

    private val _repCountTiming = MutableStateFlow(RepCountTiming.TOP)
    val repCountTiming: StateFlow<RepCountTiming> = _repCountTiming.asStateFlow()

    private val _stopAtTop = MutableStateFlow(false)
    val stopAtTop: StateFlow<Boolean> = _stopAtTop.asStateFlow()

    private val _dropSetEnabled = MutableStateFlow(false)
    val dropSetEnabled: StateFlow<Boolean> = _dropSetEnabled.asStateFlow()

    private val _dropSetMinWeightKg = MutableStateFlow<Float?>(null)
    val dropSetMinWeightKg: StateFlow<Float?> = _dropSetMinWeightKg.asStateFlow()

    private val _isDropSetMinWeightValid = MutableStateFlow(true)
    val isDropSetMinWeightValid: StateFlow<Boolean> = _isDropSetMinWeightValid.asStateFlow()

    // PR percentage scaling state (Issue #57)
    private val _usePercentOfPR = MutableStateFlow(false)
    val usePercentOfPR: StateFlow<Boolean> = _usePercentOfPR.asStateFlow()

    private val _weightPercentOfPR = MutableStateFlow(80)
    val weightPercentOfPR: StateFlow<Int> = _weightPercentOfPR.asStateFlow()

    private val _prTypeForScaling = MutableStateFlow(PRType.MAX_WEIGHT)
    val prTypeForScaling: StateFlow<PRType> = _prTypeForScaling.asStateFlow()

    // Issue #517: Velocity 1RM scaling basis (null = derive from prTypeForScaling for back-compat)
    private val _scalingBasis = MutableStateFlow<ScalingBasis?>(null)
    val scalingBasis: StateFlow<ScalingBasis?> = _scalingBasis.asStateFlow()

    private var setWeightsPercentOfPR: List<Int> = emptyList()
    private val pendingPercentOfPREditWeights = mutableMapOf<String, Float>()

    // Warm-up sets state (Issue #30)
    private val _warmupSets = MutableStateFlow<List<WarmupSet>>(emptyList())
    val warmupSets: StateFlow<List<WarmupSet>> = _warmupSets.asStateFlow()

    private val _defaultRackItemIds = MutableStateFlow<List<String>>(emptyList())
    val defaultRackItemIds: StateFlow<List<String>> = _defaultRackItemIds.asStateFlow()

    private val _rackBehaviorOverrides = MutableStateFlow<Map<String, RackItemBehavior>>(emptyMap())
    val rackBehaviorOverrides: StateFlow<Map<String, RackItemBehavior>> = _rackBehaviorOverrides.asStateFlow()

    fun initialize(
        exercise: RoutineExercise,
        unit: WeightUnit,
        toDisplay: (Float, WeightUnit) -> Float,
        toKg: (Float, WeightUnit) -> Float,
        prWeightKg: Float? = null, // Optional PR weight to use as default
        profileId: String = "default",
    ) {
        if (_initialized.value && originalExercise.id == exercise.id && activeProfileId == profileId) {
            return
        }

        originalExercise = exercise
        weightUnit = unit
        kgToDisplay = toDisplay
        displayToKg = toKg
        activeProfileId = profileId
        baselineRequestId += 1
        _routineScalingBaseline.value = null
        _currentExercisePR.value = null
        _currentMaxVolumePR.value = null

        _exerciseType.value = if (exercise.exercise.isBodyweight) {
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
            val perSetEcho = exercise.setEchoLevels.getOrNull(index) // Preserve per-set Echo overrides
            SetConfiguration(
                id = generateUUID(),
                setNumber = index + 1,
                reps = reps, // Preserve null for AMRAP sets
                weightPerCable = kgToDisplay(perSetWeightKg, weightUnit),
                duration = defaultDuration,
                restSeconds = perSetRest,
                echoLevel = perSetEcho,
            )
        }.ifEmpty {
            listOf(
                SetConfiguration(id = generateUUID(), setNumber = 1, reps = 10, weightPerCable = kgToDisplay(defaultWeightKg, weightUnit), restSeconds = 60),
                SetConfiguration(id = generateUUID(), setNumber = 2, reps = 10, weightPerCable = kgToDisplay(defaultWeightKg, weightUnit), restSeconds = 60),
                SetConfiguration(id = generateUUID(), setNumber = 3, reps = 10, weightPerCable = kgToDisplay(defaultWeightKg, weightUnit), restSeconds = 60),
            )
        }

        _sets.value = initialSets
        savedAbsoluteSetWeightsKg = initialSets.indices.map { index ->
            if (exercise.setReps.isNotEmpty()) {
                exercise.setWeightsPerCableKg.getOrNull(index) ?: exercise.weightPerCableKg
            } else {
                defaultWeightKg
            }
        }

        _selectedMode.value = exercise.programMode.toWorkoutMode(exercise.echoLevel)
        _weightChange.value = kgToDisplay(exercise.progressionKg, weightUnit).toInt()
        logDebug("Issue #164: Loaded progressionKg=${exercise.progressionKg}kg → display=${_weightChange.value}")
        _rest.value = exercise.setRestSeconds.firstOrNull()?.coerceIn(0, 300) ?: 60 // Use first rest time or default
        _perSetRestTime.value = exercise.perSetRestTime
        _eccentricLoad.value = exercise.eccentricLoad
        _echoLevel.value = exercise.echoLevel
        _stallDetectionEnabled.value = exercise.stallDetectionEnabled
        _repCountTiming.value = exercise.repCountTiming
        _stopAtTop.value = exercise.stopAtTop
        _dropSetEnabled.value = exercise.dropSetEnabled
        _dropSetMinWeightKg.value = exercise.dropSetMinWeightKg
        refreshDropSetValidation()

        // PR percentage scaling fields (Issue #57)
        _usePercentOfPR.value = exercise.usePercentOfPR
        _weightPercentOfPR.value = exercise.weightPercentOfPR
        _prTypeForScaling.value = exercise.prTypeForScaling
        // Issue #517: load explicit scalingBasis (null preserved for back-compat derivation)
        _scalingBasis.value = exercise.scalingBasis
        pendingPercentOfPREditWeights.clear()
        setWeightsPercentOfPR = normalizeSetWeightPercentages(
            source = exercise.setWeightsPercentOfPR,
            setCount = initialSets.size,
            fallbackPercent = exercise.weightPercentOfPR,
        )

        // Warm-up sets (Issue #30)
        _warmupSets.value = exercise.warmupSets
        _defaultRackItemIds.value = exercise.defaultRackItemIds.filter { it.isNotBlank() }.distinct()
        _rackBehaviorOverrides.value = exercise.rackBehaviorOverrides

        // Load PR and typed baseline for the current exercise and mode.
        exercise.exercise.id?.let { exerciseId ->
            loadPRForExercise(exerciseId, _selectedMode.value.displayName)
            loadRoutineScalingBaseline(exerciseId, _selectedMode.value)
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
        _currentExercisePR.value = null
        _currentMaxVolumePR.value = null
        refreshDropSetValidation()
        // Load PR for the new mode
        if (::originalExercise.isInitialized) {
            originalExercise.exercise.id?.let { exerciseId ->
                loadPRForExercise(exerciseId, mode.displayName)
                loadRoutineScalingBaseline(exerciseId, mode)
            }
        }
    }

    /**
     * Load the personal record for the given exercise and workout mode.
     * This updates currentExercisePR (max-weight) and currentMaxVolumePR,
     * both of which are used by PRIndicator components and the scaling preview.
     */
    fun loadPRForExercise(exerciseId: String, workoutMode: String) {
        val repository = personalRecordRepository ?: run {
            logDebug("No PersonalRecordRepository available - skipping PR lookup")
            return
        }
        val profileId = activeProfileId
        viewModelScope.launch {
            val weightPR = try {
                repository.getBestWeightPRForWorkoutMode(exerciseId, workoutMode, profileId)
            } catch (e: Exception) {
                logWarning("Failed to load PR for exercise=$exerciseId, mode=$workoutMode, profile=$profileId: ${e.message}")
                null
            }
            if (!prRequestMatchesCurrentContext(exerciseId, workoutMode, profileId)) return@launch
            _currentExercisePR.value = weightPR
            syncSetWeightsToPercentOfPR()
            logDebug("Loaded PR for exercise=$exerciseId, mode=$workoutMode, profile=$profileId: ${weightPR?.weightPerCableKg ?: "none"}")

            // Also load max-volume PR (mode-dependent, so reload on mode change).
            val volumePR = try {
                repository.getBestVolumePRForWorkoutMode(exerciseId, workoutMode, profileId)
            } catch (e: Exception) {
                logWarning("Failed to load volume PR for exercise=$exerciseId, mode=$workoutMode, profile=$profileId: ${e.message}")
                null
            }
            if (!prRequestMatchesCurrentContext(exerciseId, workoutMode, profileId)) return@launch
            _currentMaxVolumePR.value = volumePR
            logDebug("Loaded volume PR for exercise=$exerciseId, mode=$workoutMode, profile=$profileId: ${volumePR?.weightPerCableKg ?: "none"}")

            // The shared baseline resolver is loaded with the exercise/mode context; do not
            // issue a second request here and invalidate that in-flight result.
            resyncSetWeightsIfBaselineReady()
        }
    }

    private fun prRequestMatchesCurrentContext(exerciseId: String, workoutMode: String, profileId: String): Boolean =
        activeProfileId == profileId &&
            ::originalExercise.isInitialized &&
            originalExercise.exercise.id == exerciseId &&
            _selectedMode.value.displayName == workoutMode

    private fun loadRoutineScalingBaselineForCurrentExercise() {
        if (!::originalExercise.isInitialized) return
        originalExercise.exercise.id?.let { exerciseId ->
            loadRoutineScalingBaseline(exerciseId, _selectedMode.value)
        }
    }

    private fun loadRoutineScalingBaseline(exerciseId: String, mode: WorkoutMode) {
        val requestId = ++baselineRequestId
        val profileId = activeProfileId
        val basis = effectiveScalingBasis()
        val programMode = mode.toProgramMode()
        val lookupContext = EstimatedBaselineLookupContext(exerciseId, profileId, programMode)
        if (estimatedBaselineLookupContext != lookupContext) {
            estimatedBaselineLookupContext = lookupContext
            cachedEstimatedBaseline = null
        }
        val resolver = scalingBaselineResolver
        _routineScalingBaseline.value = null
        _baselineLoadStatus.value = ScalingBaselineLoadStatus.LOADING
        _baselineResolutionReason.value = null
        _estimatedOneRepMaxBaselineAvailable.value = cachedEstimatedBaseline
            ?.takeIf { it.context == lookupContext }
            ?.baseline != null
        resyncSetWeightsIfBaselineReady()
        if (resolver == null) {
            _baselineLoadStatus.value = ScalingBaselineLoadStatus.UNAVAILABLE
            _baselineResolutionReason.value = "A current baseline lookup is unavailable; saved absolute weights will be kept."
            resyncSetWeightsIfBaselineReady()
            return
        }

        fun requestStillMatchesCurrentContext(): Boolean =
            requestId == baselineRequestId &&
                activeProfileId == profileId &&
                ::originalExercise.isInitialized &&
                originalExercise.exercise.id == exerciseId &&
                _selectedMode.value.toProgramMode() == programMode &&
                effectiveScalingBasis() == basis

        if (basis != ScalingBasis.ESTIMATED_1RM) {
            viewModelScope.launch {
                val estimatedBaselineAvailable = try {
                    resolveEstimatedBaselineForContext(resolver, lookupContext) != null
                } catch (e: Exception) {
                    logWarning("Failed to check estimated 1RM availability for exercise=$exerciseId, mode=${mode.displayName}, profile=$profileId: ${e.message}")
                    false
                }
                if (requestStillMatchesCurrentContext()) {
                    _estimatedOneRepMaxBaselineAvailable.value = estimatedBaselineAvailable
                }
            }
        }

        viewModelScope.launch {
            try {
                val baseline = if (basis == ScalingBasis.ESTIMATED_1RM) {
                    resolveEstimatedBaselineForContext(resolver, lookupContext)
                } else {
                    resolver(
                        exerciseId = exerciseId,
                        mode = programMode,
                        profileId = profileId,
                        basis = basis,
                    )
                }
                if (requestStillMatchesCurrentContext()) {
                    val sourceName = baseline?.source?.name ?: "unavailable"
                    val ageDays = baseline?.sourceAgeDays?.toString() ?: "unknown"
                    val reason = baseline?.resolutionReason ?: when (basis) {
                        ScalingBasis.ESTIMATED_1RM -> "No current velocity, eligible working-set, or stored 1RM source was found."
                        else -> "No eligible baseline was found for the selected scaling basis."
                    }
                    logDebug("Resolved scaling baseline: source=$sourceName ageDays=$ageDays reason=$reason")
                    _routineScalingBaseline.value = baseline
                    _baselineLoadStatus.value = if (baseline == null) {
                        ScalingBaselineLoadStatus.UNAVAILABLE
                    } else {
                        ScalingBaselineLoadStatus.AVAILABLE
                    }
                    if (basis == ScalingBasis.ESTIMATED_1RM) {
                        _estimatedOneRepMaxBaselineAvailable.value = baseline != null
                    }
                    _baselineResolutionReason.value = reason
                    resyncSetWeightsIfBaselineReady()
                }
            } catch (e: Exception) {
                logWarning("Failed to resolve routine scaling baseline for exercise=$exerciseId, mode=${mode.displayName}, profile=$profileId: ${e.message}")
                if (requestStillMatchesCurrentContext()) {
                    _routineScalingBaseline.value = null
                    _baselineLoadStatus.value = ScalingBaselineLoadStatus.UNAVAILABLE
                    _baselineResolutionReason.value = "Baseline lookup failed; saved absolute weights will be kept."
                    resyncSetWeightsIfBaselineReady()
                }
            }
        }
    }

    private suspend fun resolveEstimatedBaselineForContext(
        resolver: ResolveRoutineScalingBaselineUseCase,
        context: EstimatedBaselineLookupContext,
    ): RoutineScalingBaseline? {
        cachedEstimatedBaseline?.takeIf { it.context == context }?.let { return it.baseline }
        val baseline = resolver(
            exerciseId = context.exerciseId,
            mode = context.mode,
            profileId = context.profileId,
            basis = ScalingBasis.ESTIMATED_1RM,
        )
        if (estimatedBaselineLookupContext == context) {
            cachedEstimatedBaseline = CachedEstimatedBaseline(context, baseline)
        }
        return baseline
    }

    /**
     * Re-resolve visible set weights against the current basis baseline. When percentage
     * scaling is enabled but the new context has no baseline, restore saved absolute loads
     * instead of leaving a preview derived from the previous context in place.
     */
    private fun resyncSetWeightsIfBaselineReady() {
        if (!_usePercentOfPR.value) return
        if (baselineKgForCurrentBasis() != null) {
            syncSetWeightsToPercentOfPR()
        } else {
            restoreSavedAbsoluteSetWeights()
        }
    }

    private fun restoreSavedAbsoluteSetWeights() {
        if (_sets.value.isEmpty()) return
        val saved = savedAbsoluteSetWeightsKg
        val pendingEdits = pendingPercentOfPREditWeights
        val restoredWeights = mutableListOf<Float>()
        _sets.value = _sets.value.mapIndexed { index, set ->
            val pendingWeight = pendingEdits[set.id]
            val absoluteWeightKg = saved.getOrNull(index)
                ?: saved.lastOrNull()
                ?: displayToKg(set.weightPerCable, weightUnit)
            restoredWeights += pendingWeight?.let { displayToKg(it, weightUnit) } ?: absoluteWeightKg
            set.copy(weightPerCable = pendingWeight ?: kgToDisplay(absoluteWeightKg, weightUnit))
        }
        savedAbsoluteSetWeightsKg = restoredWeights
    }

    private fun rememberAbsoluteSetWeight(index: Int, displayWeight: Float) {
        val absoluteWeights = savedAbsoluteSetWeightsKg.toMutableList()
        val fallbackWeight = absoluteWeights.lastOrNull() ?: displayToKg(displayWeight, weightUnit)
        while (absoluteWeights.size <= index) absoluteWeights += fallbackWeight
        absoluteWeights[index] = displayToKg(displayWeight, weightUnit)
        savedAbsoluteSetWeightsKg = absoluteWeights
    }

    /**
     * The basis the editor/workout-start actually use. Mirrors
     * RoutineExercise.effectiveScalingBasis: when no explicit scalingBasis is set
     * (legacy/back-compat rows), derive it from prTypeForScaling so the editor matches
     * ResolveRoutineWeightsUseCase (e.g. a legacy row with prTypeForScaling=MAX_VOLUME
     * resolves to MAX_VOLUME_PR, not the default MAX_WEIGHT_PR).
     */
    fun effectiveScalingBasis(): ScalingBasis = _scalingBasis.value ?: ScalingBasis.fromPrType(_prTypeForScaling.value)

    /**
     * Resolves the baseline weight (kg/cable) for the currently-selected scaling basis,
     * mirroring ResolveRoutineWeightsUseCase's resolution order:
     *   MAX_WEIGHT_PR  → max-weight PR
     *   MAX_VOLUME_PR  → max-volume PR
     *   ESTIMATED_1RM → current velocity estimate → stored baseline
     *
     * Returns null when no data is available for the selected basis (controls preview and gating).
     */
    fun baselineKgForCurrentBasis(): Float? {
        _routineScalingBaseline.value
            ?.takeIf { it.basis == effectiveScalingBasis() }
            ?.weightPerCableKg
            ?.takeIf { it > 0 }
            ?.let { return it }

        return when (effectiveScalingBasis()) {
            ScalingBasis.MAX_WEIGHT_PR ->
                _currentExercisePR.value?.weightPerCableKg?.takeIf { it > 0 }

            ScalingBasis.MAX_VOLUME_PR ->
                _currentMaxVolumePR.value?.weightPerCableKg?.takeIf { it > 0 }

            ScalingBasis.ESTIMATED_1RM -> null
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

    fun onRepCountTimingChange(timing: RepCountTiming) {
        _repCountTiming.value = timing
    }

    fun onStopAtTopChange(enabled: Boolean) {
        _stopAtTop.value = enabled
    }

    fun onDropSetEnabledChange(enabled: Boolean) {
        _dropSetEnabled.value = enabled
        refreshDropSetValidation()
    }

    fun onDropSetMinWeightKgChange(weightKg: Float?) {
        _dropSetMinWeightKg.value = weightKg
        refreshDropSetValidation()
    }

    private fun refreshDropSetValidation() {
        val enabled = _dropSetEnabled.value
        val minimum = _dropSetMinWeightKg.value
        _isDropSetMinWeightValid.value = when {
            !enabled -> true
            _selectedMode.value !is WorkoutMode.OldSchool -> true
            else -> minimum != null && minimum.isFinite() && minimum > 0f
        }
    }

    // PR percentage scaling handlers (Issue #57)
    fun onUsePercentOfPRChange(enabled: Boolean) {
        _usePercentOfPR.value = enabled
        if (!enabled) {
            if (::displayToKg.isInitialized && ::weightUnit.isInitialized) {
                savedAbsoluteSetWeightsKg = _sets.value.map { set -> displayToKg(set.weightPerCable, weightUnit) }
            }
            pendingPercentOfPREditWeights.clear()
        }
        if (enabled) {
            ensureSetWeightPercentages()
            syncSetWeightsToPercentOfPR()
        }
    }

    fun onWeightPercentOfPRChange(percent: Int) {
        val previousPercent = _weightPercentOfPR.value.coerceIn(50, 120)
        val coercedPercent = percent.coerceIn(50, 120)
        _weightPercentOfPR.value = coercedPercent
        if (_usePercentOfPR.value) {
            ensureSetWeightPercentages()
            if (setWeightsPercentOfPR.all { it == previousPercent }) {
                setWeightsPercentOfPR = List(_sets.value.size) { coercedPercent }
                pendingPercentOfPREditWeights.clear()
            }
            syncSetWeightsToPercentOfPR()
        }
    }

    // Issue #517: explicit 3-way scaling basis selector
    fun onScalingBasisChange(basis: ScalingBasis) {
        _scalingBasis.value = basis
        loadRoutineScalingBaselineForCurrentExercise()
        // Re-resolve visible set weights against the newly-selected basis baseline
        // so the editor table matches what ResolveRoutineWeightsUseCase will use.
        if (_usePercentOfPR.value) {
            syncSetWeightsToPercentOfPR()
        }
    }

    // Warm-up set handlers (Issue #30)

    /**
     * Add a new warm-up set with default values.
     * Defaults to 12 reps @ 50% for first set, then 8 @ 70%, then 4 @ 85%.
     */
    fun addWarmupSet() {
        val current = _warmupSets.value
        val newSet = when (current.size) {
            0 -> WarmupSet(reps = 12, percentOfWorking = 50)
            1 -> WarmupSet(reps = 8, percentOfWorking = 70)
            2 -> WarmupSet(reps = 4, percentOfWorking = 85)
            else -> WarmupSet(reps = 4, percentOfWorking = 90) // Additional sets default to 4 @ 90%
        }
        _warmupSets.value = current + newSet
    }

    /**
     * Remove a warm-up set by index.
     */
    fun removeWarmupSet(index: Int) {
        val current = _warmupSets.value
        if (index in current.indices) {
            _warmupSets.value = current.filterIndexed { i, _ -> i != index }
        }
    }

    /**
     * Update the reps for a warm-up set.
     */
    fun updateWarmupSetReps(index: Int, reps: Int) {
        val current = _warmupSets.value.toMutableList()
        if (index in current.indices) {
            current[index] = current[index].copy(reps = reps.coerceIn(1, 20))
            _warmupSets.value = current
        }
    }

    /**
     * Update the percentage for a warm-up set.
     */
    fun updateWarmupSetPercent(index: Int, percent: Int) {
        val current = _warmupSets.value.toMutableList()
        if (index in current.indices) {
            current[index] = current[index].copy(percentOfWorking = percent.coerceIn(10, 100))
            _warmupSets.value = current
        }
    }

    /**
     * Clear all warm-up sets.
     */
    fun clearWarmupSets() {
        _warmupSets.value = emptyList()
    }

    fun onDefaultRackItemIdsChange(itemIds: List<String>) {
        _defaultRackItemIds.value = itemIds.filter { it.isNotBlank() }.distinct()
    }

    fun onRackBehaviorOverridesChange(overrides: Map<String, RackItemBehavior>) {
        _rackBehaviorOverrides.value = overrides
    }

    /**
     * Apply the classic 12-8-4 warm-up preset (Issue #30 suggestion).
     * 12 reps @ 50%, 8 reps @ 70%, 4 reps @ 85%.
     */
    fun applyClassicWarmupPreset() {
        _warmupSets.value = listOf(
            WarmupSet(reps = 12, percentOfWorking = 50),
            WarmupSet(reps = 8, percentOfWorking = 70),
            WarmupSet(reps = 4, percentOfWorking = 85),
        )
    }

    private fun Float.roundToHalfKg(): Float = (this * 2).roundToInt() / 2f

    private fun normalizeSetWeightPercentages(source: List<Int>, setCount: Int, fallbackPercent: Int): List<Int> {
        if (setCount <= 0) return emptyList()
        val fallback = fallbackPercent.coerceIn(50, 120)
        return List(setCount) { index ->
            (source.getOrNull(index) ?: fallback).coerceIn(50, 120)
        }
    }

    private fun ensureSetWeightPercentages() {
        setWeightsPercentOfPR = normalizeSetWeightPercentages(
            source = setWeightsPercentOfPR,
            setCount = _sets.value.size,
            fallbackPercent = _weightPercentOfPR.value,
        )
    }

    // Issue #517: per-set weights resolve against the SELECTED scaling basis baseline
    // (baselineKgForCurrentBasis), not just the max-weight PR. When the basis is
    // MAX_WEIGHT_PR the baseline IS the max-weight PR, so behavior is unchanged.
    private fun resolvedSetWeightsKgFromCurrentBasis(): List<Float>? {
        val baselineKg = baselineKgForCurrentBasis() ?: return null
        if (!_usePercentOfPR.value || baselineKg <= 0f) return null
        applyPendingPercentOfPREdits(baselineKg)
        ensureSetWeightPercentages()
        return setWeightsPercentOfPR.map { percent ->
            (baselineKg * percent / 100f).roundToHalfKg()
        }
    }

    private fun syncSetWeightsToPercentOfPR() {
        val resolvedWeightsKg = resolvedSetWeightsKgFromCurrentBasis() ?: return
        _sets.value = _sets.value.mapIndexed { index, set ->
            val resolvedWeightKg = resolvedWeightsKg.getOrNull(index) ?: return@mapIndexed set
            set.copy(weightPerCable = kgToDisplay(resolvedWeightKg, weightUnit))
        }
    }

    private fun percentFromDisplayWeight(displayWeight: Float, baselineKg: Float): Int {
        val weightKg = displayToKg(displayWeight, weightUnit)
        return ((weightKg / baselineKg) * 100f).roundToInt().coerceIn(50, 120)
    }

    private fun displayWeightFromPercent(percent: Int, baselineKg: Float): Float {
        val resolvedWeightKg = (baselineKg * percent / 100f).roundToHalfKg()
        return kgToDisplay(resolvedWeightKg, weightUnit)
    }

    private fun applyPendingPercentOfPREdits(baselineKg: Float) {
        if (pendingPercentOfPREditWeights.isEmpty()) return
        ensureSetWeightPercentages()
        val setsById = _sets.value.mapIndexed { index, set -> set.id to index }.toMap()

        pendingPercentOfPREditWeights.forEach { (setId, displayWeight) ->
            val setIndex = setsById[setId] ?: return@forEach
            val percent = percentFromDisplayWeight(displayWeight, baselineKg)
            setWeightsPercentOfPR = setWeightsPercentOfPR.mapIndexed { index, existing ->
                if (index == setIndex) percent else existing
            }
            rememberAbsoluteSetWeight(setIndex, displayWeight)
        }
        pendingPercentOfPREditWeights.clear()
    }

    private fun updateSetPercentFromDisplayWeight(setIndex: Int, displayWeight: Float, baselineKg: Float): Int {
        ensureSetWeightPercentages()
        val percent = percentFromDisplayWeight(displayWeight, baselineKg)
        setWeightsPercentOfPR = setWeightsPercentOfPR.mapIndexed { index, existing ->
            if (index == setIndex) percent else existing
        }
        return percent
    }

    fun updateReps(setId: String, reps: Int?) {
        _sets.value = _sets.value.map { set ->
            if (set.id == setId) set.copy(reps = reps) else set
        }
    }

    fun updateWeight(setId: String, weight: Float) {
        val setIndex = _sets.value.indexOfFirst { it.id == setId }
        if (setIndex < 0) return

        val baselineKg = baselineKgForCurrentBasis()
        val nextWeight = if (_usePercentOfPR.value && baselineKg != null && baselineKg > 0f) {
            pendingPercentOfPREditWeights.remove(setId)
            val percent = updateSetPercentFromDisplayWeight(setIndex, weight, baselineKg)
            displayWeightFromPercent(percent, baselineKg)
        } else {
            if (_usePercentOfPR.value) {
                pendingPercentOfPREditWeights[setId] = weight
            } else {
                pendingPercentOfPREditWeights.remove(setId)
            }
            weight
        }

        _sets.value = _sets.value.mapIndexed { index, set ->
            if (index == setIndex) set.copy(weightPerCable = nextWeight) else set
        }
        rememberAbsoluteSetWeight(setIndex, weight)
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
        // Issue #774: "Add Set" is enabled from the sheet's first frame, before initialize()
        // has supplied the unit converters; a tap then hit an uninitialized lateinit.
        if (!::kgToDisplay.isInitialized || !::weightUnit.isInitialized) return
        val lastSet = _sets.value.lastOrNull()
        val newSet = SetConfiguration(
            setNumber = _sets.value.size + 1,
            reps = lastSet?.reps ?: 10,
            weightPerCable = lastSet?.weightPerCable ?: kgToDisplay(15f, weightUnit),
            duration = lastSet?.duration ?: 30,
            restSeconds = lastSet?.restSeconds ?: 60,
        )
        _sets.value = _sets.value + newSet
        val fallbackWeightKg = savedAbsoluteSetWeightsKg.lastOrNull()
            ?: displayToKg(newSet.weightPerCable, weightUnit)
        savedAbsoluteSetWeightsKg = savedAbsoluteSetWeightsKg + fallbackWeightKg
        if (_usePercentOfPR.value) {
            setWeightsPercentOfPR = setWeightsPercentOfPR + _weightPercentOfPR.value.coerceIn(50, 120)
            syncSetWeightsToPercentOfPR()
        }
    }

    fun deleteSet(index: Int) {
        val removedSetId = _sets.value.getOrNull(index)?.id
        val newSets = _sets.value.filterIndexed { i, _ -> i != index }
            .mapIndexed { i, set -> set.copy(setNumber = i + 1) }
        _sets.value = newSets
        savedAbsoluteSetWeightsKg = savedAbsoluteSetWeightsKg.filterIndexed { i, _ -> i != index }
        if (removedSetId != null) {
            pendingPercentOfPREditWeights.remove(removedSetId)
        }
        val remainingSetIds = newSets.map { it.id }.toSet()
        pendingPercentOfPREditWeights.keys.retainAll(remainingSetIds)
        setWeightsPercentOfPR = setWeightsPercentOfPR.filterIndexed { i, _ -> i != index }
        if (_usePercentOfPR.value) {
            syncSetWeightsToPercentOfPR()
        }
    }

    // Issue #667: Set repetition support
    private data class ExpandedSets(
        val reps: List<Int?>,
        val weights: List<Float>,
        val rest: List<Int>,
        val echo: List<EchoLevel?>,
    )

    private fun expandSets(sets: List<SetConfiguration>, resolvedWeightsKg: List<Float>): ExpandedSets {
        val expandedReps = mutableListOf<Int?>()
        val expandedWeights = mutableListOf<Float>()
        val expandedRest = mutableListOf<Int>()
        val expandedEcho = mutableListOf<EchoLevel?>()

        for ((index, set) in sets.withIndex()) {
            val count = set.repeatCount.coerceIn(1, 20)
            repeat(count) {
                expandedReps.add(set.reps)
                expandedWeights.add(resolvedWeightsKg[index])
                expandedRest.add(set.restSeconds)
                expandedEcho.add(set.echoLevel) // Preserve per-set Echo override (Issue #667 review fix)
            }
        }
        return ExpandedSets(expandedReps, expandedWeights, expandedRest, expandedEcho)
    }

    fun onRepeatCountChange(setId: String, count: Int) {
        val coerced = count.coerceIn(1, 20)
        _sets.value = _sets.value.map { set ->
            if (set.id == setId) set.copy(repeatCount = coerced) else set
        }
    }

    fun onSave(onSaveCallback: (RoutineExercise) -> Unit) {
        if (_sets.value.isEmpty() || !_isDropSetMinWeightValid.value) return

        // Debug logging for AMRAP exercise data saving
        logDebug("━━━━━ ExerciseConfigViewModel.onSave() ━━━━━")
        logDebug("Exercise: ${originalExercise.exercise.name}")
        logDebug("perSetRestTime toggle: ${_perSetRestTime.value}")
        logDebug("Current sets before expansion:")
        _sets.value.forEach { set ->
            logDebug("  Set ${set.setNumber}: reps=${set.reps}, weight=${set.weightPerCable}, rest=${set.restSeconds}, repeat=${set.repeatCount}")
        }

        // Resolve weights BEFORE expansion (Issue #667)
        val resolvedSetWeightsKg = resolvedSetWeightsKgFromCurrentBasis()
            ?: _sets.value.map { displayToKg(it.weightPerCable, weightUnit) }
        val resolvedSetWeightsPercentOfPR = if (_usePercentOfPR.value) {
            normalizeSetWeightPercentages(
                source = setWeightsPercentOfPR,
                setCount = _sets.value.size,
                fallbackPercent = _weightPercentOfPR.value,
            )
        } else {
            emptyList()
        }

        // Expand repeated sets into flat arrays (Issue #667)
        val expanded = expandSets(_sets.value, resolvedSetWeightsKg)

        // Expand rest times: after per-set/uniform decision
        val expandedRestTimes = if (_perSetRestTime.value) {
            expanded.rest // per-set rest already expanded
        } else {
            List(expanded.reps.size) { _rest.value }
        }

        // Expand PR percentage weights in parallel
        val expandedPercentOfPR = if (_usePercentOfPR.value && resolvedSetWeightsPercentOfPR.isNotEmpty()) {
            val expandedPercent = mutableListOf<Int>()
            for ((index, set) in _sets.value.withIndex()) {
                val count = set.repeatCount.coerceIn(1, 20)
                val percent = resolvedSetWeightsPercentOfPR.getOrElse(index) { _weightPercentOfPR.value.coerceIn(50, 120) }
                repeat(count) { expandedPercent.add(percent) }
            }
            expandedPercent
        } else {
            resolvedSetWeightsPercentOfPR
        }

        // Determine if exercise is AMRAP (all expanded reps are null)
        val isAMRAP = expanded.reps.all { it == null }

        logDebug("Expanded sets: ${expanded.reps.size} (from ${_sets.value.size} editor sets)")
        logDebug("Expanded reps: ${expanded.reps}")
        logDebug("Expanded weights: ${expanded.weights}")
        logDebug("Expanded rest: $expandedRestTimes")
        logDebug("isAMRAP computed: $isAMRAP")

        val updatedExercise = originalExercise.copy(
            setReps = expanded.reps,
            weightPerCableKg = expanded.weights.first(),
            setWeightsPerCableKg = expanded.weights,
            programMode = _selectedMode.value.toProgramMode(),
            eccentricLoad = _eccentricLoad.value,
            echoLevel = _echoLevel.value,
            progressionKg = displayToKg(_weightChange.value.toFloat(), weightUnit),
            setRestSeconds = expandedRestTimes,
            setEchoLevels = expanded.echo,
            duration = if (_setMode.value == SetMode.DURATION) {
                _sets.value.firstOrNull()?.duration ?: 30 // Default to 30 seconds if not set
            } else {
                null
            },
            perSetRestTime = _perSetRestTime.value,
            isAMRAP = isAMRAP,
            stallDetectionEnabled = _stallDetectionEnabled.value,
            repCountTiming = _repCountTiming.value,
            stopAtTop = _stopAtTop.value,
            dropSetEnabled = _dropSetEnabled.value,
            dropSetMinWeightKg = _dropSetMinWeightKg.value,
            // PR percentage scaling fields (Issue #57)
            usePercentOfPR = _usePercentOfPR.value,
            weightPercentOfPR = _weightPercentOfPR.value,
            prTypeForScaling = _prTypeForScaling.value,
            setWeightsPercentOfPR = expandedPercentOfPR,
            // Issue #517: persist explicit scaling basis (null = back-compat derivation from prTypeForScaling)
            scalingBasis = _scalingBasis.value,
            // Warm-up sets (Issue #30)
            warmupSets = _warmupSets.value,
            defaultRackItemIds = _defaultRackItemIds.value,
            rackBehaviorOverrides = _rackBehaviorOverrides.value,
        )

        logDebug("Updated exercise to save:")
        logDebug("  setReps: ${updatedExercise.setReps}")
        logDebug("  setWeightsPerCableKg: ${updatedExercise.setWeightsPerCableKg}")
        logDebug("  weightPerCableKg: ${updatedExercise.weightPerCableKg}")
        logDebug("  setRestSeconds: ${updatedExercise.setRestSeconds}")
        logDebug("  perSetRestTime: ${updatedExercise.perSetRestTime}")
        logDebug("  isAMRAP: ${updatedExercise.isAMRAP}")
        logDebug("  Issue #164: progressionKg: ${updatedExercise.progressionKg}kg (from display: ${_weightChange.value})")
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

    private fun logDebug(message: String) {
        log.d { message }
    }

    private fun logWarning(message: String) {
        log.w { message }
    }
}
