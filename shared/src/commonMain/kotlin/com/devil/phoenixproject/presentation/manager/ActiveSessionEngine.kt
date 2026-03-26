package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.BiomechanicsRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.getPlatform
import com.devil.phoenixproject.domain.premium.RepQualityScorer
import com.devil.phoenixproject.domain.replay.RepBoundaryDetector
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.util.BlePacketFactory
import com.devil.phoenixproject.util.Constants
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.KmpUtils
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Handles all workout lifecycle logic: start/stop, rep processing, auto-stop,
 * BLE commands, rest timer, session persistence, weight adjustment, Just Lift,
 * and training cycles.
 *
 * Extracted from DefaultWorkoutSessionManager during Phase 2 (Manager Decomposition) Plan 04.
 *
 * Communication:
 * - Reads/writes all state through [coordinator] (WorkoutCoordinator)
 * - NEVER holds references to RoutineFlowManager
 * - For operations requiring routine navigation, uses [WorkoutFlowDelegate]
 *
 * Scope: Receives the SAME CoroutineScope as DWSM for TestScope compatibility.
 */
class ActiveSessionEngine(
    val coordinator: WorkoutCoordinator,
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
    private val biomechanicsRepository: BiomechanicsRepository,
    private val settingsManager: SettingsManager,
    private val userProfileRepository: UserProfileRepository,
    private val scope: CoroutineScope,
    private val detectionManager: ExerciseDetectionManager? = null,
    private val dataBackupManager: DataBackupManager? = null,
    private val healthIntegration: HealthIntegration? = null,
    private val externalActivityRepository: ExternalActivityRepository? = null
) {

    /**
     * Delegate interface for operations that require routine navigation or
     * cross-cutting orchestration from DWSM.
     */
    interface WorkoutFlowDelegate {
        /** Load a routine by object (delegates to RoutineFlowManager) */
        fun loadRoutine(routine: Routine)
        /** Enter SetReady screen for a specific exercise/set */
        fun enterSetReady(exerciseIndex: Int, setIndex: Int)
        /** Skip the current exercise and move to the next available routine step in SetReady */
        fun skipCurrentExerciseAndEnterNextStep(): Boolean
        /** Show routine complete screen */
        fun showRoutineComplete()
        /** Get current exercise from loaded routine */
        fun getCurrentExercise(): RoutineExercise?
        /** Get next step in routine navigation */
        fun getNextStep(routine: Routine, exerciseIndex: Int, setIndex: Int): Pair<Int, Int>?
        /** Check if currently in a superset */
        fun isInSuperset(): Boolean
        /** Check if at end of superset cycle */
        fun isAtEndOfSupersetCycle(): Boolean
        /** Get superset rest seconds */
        fun getSupersetRestSeconds(): Int
        /** Calculate next exercise name for rest timer display */
        fun calculateNextExerciseName(isSingleExercise: Boolean, currentExercise: RoutineExercise?, routine: Routine?): String?
        /** Calculate if this is the last exercise */
        fun calculateIsLastExercise(isSingleExercise: Boolean, currentExercise: RoutineExercise?, routine: Routine?): Boolean
        /** Clear cycle context */
        fun clearCycleContext()
    }

    /**
     * Flow delegate for operations that need routine navigation.
     * Set by DWSM after construction.
     */
    var flowDelegate: WorkoutFlowDelegate? = null

    /** Detector for identifying rep phase boundaries from position data */
    private val repBoundaryDetector = RepBoundaryDetector()

    /** Issue #237: Motion-triggered set start detector (reused across sets) */
    private val motionStartDetector = MotionStartDetector()
    private var motionStartListenerJob: Job? = null

    // ===== Init Block: Workout-Related Collectors (moved from DWSM) =====

    init {
        // Collectors #3-8 (workout lifecycle collectors).
        // Collectors #1-2 are in RoutineFlowManager (constructed before ActiveSessionEngine).

        // #3: Hook up RepCounter
        repCounter.onRepEvent = { event ->
             scope.launch {
                 val timing = coordinator._workoutParameters.value.repCountTiming
                 when (event.type) {
                     RepType.WORKING_PENDING -> {
                         // TOP timing: announce rep number at concentric peak
                         if (timing == RepCountTiming.TOP) {
                             val repNumber = event.workingCount + 1  // PENDING has pre-increment count
                             val prefs = settingsManager.userPreferences.value
                             if (prefs.audioRepCountEnabled && repNumber in 1..25) {
                                 coordinator._hapticEvents.emit(HapticEvent.REP_COUNT_ANNOUNCED(repNumber))
                             } else {
                                 coordinator._hapticEvents.emit(HapticEvent.REP_COMPLETED)
                             }
                         }
                         // BOTTOM timing: silent grey preview only, no announcement
                     }
                     RepType.WORKING_COMPLETED -> {
                         // BOTTOM timing: announce at eccentric valley (traditional)
                         if (timing == RepCountTiming.BOTTOM) {
                             val prefs = settingsManager.userPreferences.value
                             if (prefs.audioRepCountEnabled && event.workingCount in 1..25) {
                                 coordinator._hapticEvents.emit(HapticEvent.REP_COUNT_ANNOUNCED(event.workingCount))
                             } else {
                                 coordinator._hapticEvents.emit(HapticEvent.REP_COMPLETED)
                             }
                         }
                         // TOP timing: already announced on PENDING, no double-announce
                     }
                     RepType.WARMUP_COMPLETED -> coordinator._hapticEvents.emit(HapticEvent.REP_COMPLETED)
                     RepType.WARMUP_COMPLETE -> {
                         coordinator._hapticEvents.emit(HapticEvent.WARMUP_COMPLETE)
                         // Issue #100: Distinct transition sound from warmup to working sets
                         coordinator._hapticEvents.emit(HapticEvent.WARMUP_TO_WORKING)
                     }
                     RepType.WORKOUT_COMPLETE -> {
                         // Note: WORKOUT_COMPLETE sound removed - WORKOUT_END in handleSetCompletion
                         // provides sufficient feedback, and celebration sounds (PR/badge) may also play.
                         // Playing both was causing multiple sounds to fire at once (sound stacking bug).
                         // Issue #182: Trigger set completion immediately on WORKOUT_COMPLETE event.
                         if (coordinator._workoutState.value is WorkoutState.Active) {
                             Logger.d("WORKOUT_COMPLETE event received - triggering immediate set completion")
                             handleSetCompletion()
                         }
                     }
                 }
             }
        }

        // #4: Handle activity state collector for auto-start functionality
        scope.launch {
            // handleState is a StateFlow — use try-catch in collect body (SharedFlow.catch is no-op)
            bleRepository.handleState.collect { activityState ->
                try {
                val params = coordinator._workoutParameters.value
                val currentState = coordinator._workoutState.value
                val isIdle = currentState is WorkoutState.Idle
                val isSummaryAndJustLift = currentState is WorkoutState.SetSummary && params.isJustLift

                // Handle auto-START when Idle and waiting for handles
                // Also allow auto-start from SetSummary if in Just Lift mode (interrupting to start next set)
                if (params.useAutoStart && (isIdle || isSummaryAndJustLift)) {
                    when (activityState) {
                        HandleState.Grabbed -> {
                            Logger.d("Handles grabbed! Starting auto-start timer (State: ${coordinator._workoutState.value})")
                            startAutoStartTimer()
                        }
                        HandleState.Moving -> {
                            // Moving = position extended but no velocity yet
                            // Don't start countdown yet, but also don't cancel if already running
                        }
                        HandleState.Released -> {
                            Logger.d("Handles released! Canceling auto-start timer")
                            cancelAutoStartTimer()
                        }
                        HandleState.WaitingForRest -> {
                            cancelAutoStartTimer()
                        }
                    }
                }

                // Handle auto-STOP when Active in Just Lift mode and handles released.
                // Warmup/ROM gate: auto-stop must remain disabled until warmup is complete.
                if (params.isJustLift && currentState is WorkoutState.Active) {
                    if (!isWarmupGateOpenForAutoStop()) {
                        resetAutoStopTimer()
                    } else if (activityState == HandleState.Released) {
                        Logger.d("Just Lift: Handles RELEASED - starting auto-stop timer")
                        if (coordinator.autoStopStartTime == null) {
                            coordinator.autoStopStartTime = currentTimeMillis()
                            Logger.d("Auto-stop timer STARTED (Just Lift) - handles released")
                        }
                    } else if (activityState == HandleState.Grabbed || activityState == HandleState.Moving) {
                        resetAutoStopTimer()
                    }
                }

                // Track handle activity state for UI
                coordinator.currentHandleState = activityState
                } catch (e: Exception) {
                    Logger.e(e) { "handleState collector error" }
                }
            }
        }

        // #5: Issue #98: Deload event collector for firmware-based auto-stop detection
        scope.launch {
            bleRepository.deloadOccurredEvents
                .catch { e -> Logger.e(e) { "deloadOccurredEvents collector error" } }
                .collect {
                val params = coordinator._workoutParameters.value
                val currentState = coordinator._workoutState.value

                if (params.stallDetectionEnabled && currentState is WorkoutState.Active) {
                    if (!isWarmupGateOpenForAutoStop()) {
                        Logger.d("DELOAD_OCCURRED ignored - warmup/ROM not established yet")
                        return@collect
                    }
                    val repCount = coordinator._repCount.value
                    if (shouldDeferStandardSetStall(params, repCount)) {
                        Logger.d(
                            "DELOAD_OCCURRED ignored - standard set stall guard " +
                                "(workingReps=${repCount.workingReps}, pending=${repCount.hasPendingRep})"
                        )
                        resetStallTimer()
                        return@collect
                    }
                    if (!shouldEnableAutoStop(params)) return@collect
                    Logger.d("DELOAD_OCCURRED: Machine detected cable release - starting auto-stop timer")

                    val hasMeaningfulRange = repCounter.hasMeaningfulRange(WorkoutCoordinator.MIN_RANGE_THRESHOLD)
                    val inGrace = isInAmrapStartupGrace(hasMeaningfulRange)

                    if (coordinator.stallStartTime == null && !inGrace) {
                        coordinator.stallStartTime = currentTimeMillis()
                        coordinator.isCurrentlyStalled = true
                        Logger.d("Auto-stop stall timer STARTED via DELOAD_OCCURRED flag")
                    } else if (inGrace) {
                        Logger.d("DELOAD_OCCURRED ignored - in AMRAP startup grace period")
                    }
                }
            }
        }

        // #6: Rep events collector for handling machine rep notifications
        coordinator.repEventsCollectionJob = scope.launch {
            bleRepository.repEvents
                .catch { e -> Logger.e(e) { "repEvents collector error" } }
                .collect { notification ->
                val state = coordinator._workoutState.value
                if (state is WorkoutState.Active) {
                    handleRepNotification(notification)
                }
            }
        }

        // #7: CRITICAL: Global metricsFlow collection (matches parent repo)
        coordinator.monitorDataCollectionJob = scope.launch {
            Logger.d("ActiveSessionEngine") { "Starting global metricsFlow collection..." }
            bleRepository.metricsFlow
                .catch { e -> Logger.e(e) { "metricsFlow collector error" } }
                .collect { metric ->
                coordinator._currentMetric.value = metric
                handleMonitorMetric(metric)
            }
        }

        // #8: Heuristic data collection for Echo mode force feedback
        scope.launch {
            // heuristicData is a StateFlow — use try-catch in collect body (SharedFlow.catch is no-op)
            bleRepository.heuristicData.collect { stats ->
                try {
                    if (stats != null && coordinator._workoutState.value is WorkoutState.Active) {
                        val concentricMax = stats.concentric.kgMax
                        val eccentricMax = stats.eccentric.kgMax
                        val currentMax = maxOf(concentricMax, eccentricMax)

                        coordinator._currentHeuristicKgMax.value = currentMax

                        if (currentMax > coordinator.maxHeuristicKgMax) {
                            coordinator.maxHeuristicKgMax = currentMax
                            Logger.v("ActiveSessionEngine") { "Echo force telemetry: kgMax=$currentMax (concentric=$concentricMax, eccentric=$eccentricMax)" }
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(e) { "heuristicData collector error" }
                }
            }
        }
    }

    // ===== Calculation Helpers =====

    /**
     * Calculate enhanced metrics for the set summary display.
     */
    internal fun calculateSetSummaryMetrics(
        metrics: List<WorkoutMetric>,
        repCount: Int,
        fallbackWeightKg: Float,
        configuredWeightKgPerCable: Float,
        isEchoMode: Boolean = false,
        warmupRepsCount: Int = 0,
        workingRepsCount: Int = 0,
        warmupCompleteTimeMs: Long = 0L
    ): WorkoutState.SetSummary {
        if (metrics.isEmpty()) {
            return WorkoutState.SetSummary(
                metrics = metrics,
                peakPower = 0f,
                averagePower = 0f,
                repCount = repCount,
                cableCount = 1,
                heaviestLiftKgPerCable = fallbackWeightKg,
                configuredWeightKgPerCable = configuredWeightKgPerCable
            )
        }

        // Issue #252: Exclude warmup time from set duration
        val effectiveStart = if (warmupCompleteTimeMs > 0L) {
            warmupCompleteTimeMs.coerceAtLeast(metrics.first().timestamp)
        } else {
            metrics.first().timestamp
        }
        val durationMs = metrics.last().timestamp - effectiveStart

        val peakCableA = metrics.maxOf { it.loadA }
        val peakCableB = metrics.maxOf { it.loadB }

        // Issue #6 Fix: Detect single-cable (unilateral) exercises and don't halve the weight.
        // Heuristic: if one cable's peak load is > 5x the other's, treat as single-cable.
        // For single-cable, use the max of the active cable. For double-cable, use totalLoad/2.
        val isSingleCable = (peakCableA > 0f && peakCableB > 0f &&
            (peakCableA / peakCableB > 5f || peakCableB / peakCableA > 5f)) ||
            (peakCableA > 0f && peakCableB == 0f) ||
            (peakCableB > 0f && peakCableA == 0f)

        val heaviestLiftKgPerCable = if (isSingleCable) {
            // Single-cable: use the active cable's load (don't halve)
            metrics.maxOf { maxOf(it.loadA, it.loadB) }
        } else {
            // Double-cable: raw totalLoad / 2, no baseline subtraction (parent-aligned)
            metrics.maxOf { it.totalLoad / 2f }
        }

        val volumeWeightKgPerCable = if (isEchoMode) {
            heaviestLiftKgPerCable
        } else {
            configuredWeightKgPerCable
        }
        // Fixed-load modes should log the prescribed working load, while Echo uses measured force.
        val cableCount = if (isSingleCable) 1 else 2
        val totalVolumeKg = volumeWeightKgPerCable * cableCount.toFloat() * repCount

        val concentricMetrics = metrics.filter { it.velocityA > 10 || it.velocityB > 10 }
        val eccentricMetrics = metrics.filter { it.velocityA < -10 || it.velocityB < -10 }

        val peakConcentricA = concentricMetrics.maxOfOrNull { it.loadA } ?: 0f
        val peakConcentricB = concentricMetrics.maxOfOrNull { it.loadB } ?: 0f
        val peakEccentricA = eccentricMetrics.maxOfOrNull { it.loadA } ?: 0f
        val peakEccentricB = eccentricMetrics.maxOfOrNull { it.loadB } ?: 0f

        val peakLoadA = metrics.maxOf { it.loadA }
        val peakLoadB = metrics.maxOf { it.loadB }
        val thresholdA = (peakLoadA * 0.1f).coerceAtLeast(1f)
        val thresholdB = (peakLoadB * 0.1f).coerceAtLeast(1f)

        val activeConcentricMetrics = concentricMetrics.filter {
            it.loadA > thresholdA || it.loadB > thresholdB
        }
        val activeEccentricMetrics = eccentricMetrics.filter {
            it.loadA > thresholdA || it.loadB > thresholdB
        }

        val avgConcentricA = if (activeConcentricMetrics.isNotEmpty())
            activeConcentricMetrics.map { it.loadA }.average().toFloat() else 0f
        val avgConcentricB = if (activeConcentricMetrics.isNotEmpty())
            activeConcentricMetrics.map { it.loadB }.average().toFloat() else 0f
        val avgEccentricA = if (activeEccentricMetrics.isNotEmpty())
            activeEccentricMetrics.map { it.loadA }.average().toFloat() else 0f
        val avgEccentricB = if (activeEccentricMetrics.isNotEmpty())
            activeEccentricMetrics.map { it.loadB }.average().toFloat() else 0f

        // Physics-based calorie estimation using work-energy theorem:
        // W = sum(force_i * delta_distance_i) for each consecutive sample pair
        // kcal = (W_joules / 4184) * 5 (metabolic efficiency multiplier ~20%)
        val estimatedCalories = run {
            if (metrics.size < 2) {
                // Fallback for insufficient samples
                (totalVolumeKg * 0.5f * 9.81f / 4184f).coerceAtLeast(1f)
            } else {
                var totalWorkJoules = 0.0
                for (i in 1 until metrics.size) {
                    val prev = metrics[i - 1]
                    val curr = metrics[i]
                    // Average force in N across both cables
                    val avgForceN = ((prev.totalLoad + curr.totalLoad) / 2f) * 9.81f
                    // Distance in meters (position is in mm)
                    val deltaA = kotlin.math.abs(curr.positionA - prev.positionA) / 1000f
                    val deltaB = kotlin.math.abs(curr.positionB - prev.positionB) / 1000f
                    val avgDelta = if (isSingleCable) maxOf(deltaA, deltaB) else (deltaA + deltaB) / 2f
                    totalWorkJoules += avgForceN * avgDelta
                }
                ((totalWorkJoules / 4184.0) * 5.0).toFloat().coerceAtLeast(1f)
            }
        }

        val peakPower = heaviestLiftKgPerCable
        val averagePower = if (isSingleCable) {
            metrics.map { maxOf(it.loadA, it.loadB) }.average().toFloat()
        } else {
            metrics.map { it.totalLoad / 2f }.average().toFloat()
        }

        // Echo Mode Phase-Aware Metrics
        var warmupAvgWeightKg = 0f
        var workingAvgWeightKg = 0f
        var burnoutAvgWeightKg = 0f
        var peakWeightKg = 0f
        var burnoutReps = 0

        if (isEchoMode && metrics.size > 10) {
            val weightSamples = metrics.map { maxOf(it.loadA, it.loadB) }
            peakWeightKg = weightSamples.maxOrNull() ?: 0f
            val peakThreshold = peakWeightKg * 0.9f

            val peakIndices = weightSamples.indices.filter { weightSamples[it] >= peakThreshold }

            if (peakIndices.isNotEmpty()) {
                val firstPeakIndex = peakIndices.first()
                val lastPeakIndex = peakIndices.last()

                val warmupSamples = weightSamples.take(firstPeakIndex)
                warmupAvgWeightKg = if (warmupSamples.isNotEmpty())
                    warmupSamples.average().toFloat() else 0f

                val workingSamples = weightSamples.subList(firstPeakIndex, (lastPeakIndex + 1).coerceAtMost(weightSamples.size))
                workingAvgWeightKg = if (workingSamples.isNotEmpty())
                    workingSamples.average().toFloat() else peakWeightKg

                val burnoutSamples = if (lastPeakIndex < weightSamples.lastIndex)
                    weightSamples.drop(lastPeakIndex + 1) else emptyList()
                burnoutAvgWeightKg = if (burnoutSamples.isNotEmpty())
                    burnoutSamples.average().toFloat() else 0f

                val totalReps = warmupRepsCount + workingRepsCount
                if (burnoutSamples.isNotEmpty() && totalReps > 0) {
                    val burnoutRatio = burnoutSamples.size.toFloat() / weightSamples.size.toFloat()
                    burnoutReps = (totalReps * burnoutRatio).toInt().coerceAtLeast(0)
                }
            } else {
                workingAvgWeightKg = weightSamples.average().toFloat()
                peakWeightKg = workingAvgWeightKg
            }
        }

        return WorkoutState.SetSummary(
            metrics = metrics,
            peakPower = peakPower,
            averagePower = averagePower,
            repCount = repCount,
            durationMs = durationMs,
            totalVolumeKg = totalVolumeKg,
            cableCount = cableCount,
            heaviestLiftKgPerCable = heaviestLiftKgPerCable,
            configuredWeightKgPerCable = configuredWeightKgPerCable,
            peakForceConcentricA = peakConcentricA,
            peakForceConcentricB = peakConcentricB,
            peakForceEccentricA = peakEccentricA,
            peakForceEccentricB = peakEccentricB,
            avgForceConcentricA = avgConcentricA,
            avgForceConcentricB = avgConcentricB,
            avgForceEccentricA = avgEccentricA,
            avgForceEccentricB = avgEccentricB,
            estimatedCalories = estimatedCalories,
            isEchoMode = isEchoMode,
            warmupReps = warmupRepsCount,
            workingReps = workingRepsCount,
            burnoutReps = burnoutReps,
            warmupAvgWeightKg = warmupAvgWeightKg,
            workingAvgWeightKg = workingAvgWeightKg,
            burnoutAvgWeightKg = burnoutAvgWeightKg,
            peakWeightKg = peakWeightKg
        )
    }

    /**
     * Collect metric for history recording.
     */
    private fun collectMetricForHistory(metric: WorkoutMetric) {
        coordinator.collectedMetrics.update { it + metric }
    }

    // ===== Auto-Stop Helpers =====

    /**
     * Reset auto-stop timer without resetting the triggered flag.
     */
    private fun resetAutoStopTimer() {
        coordinator.autoStopStartTime = null
        if (!coordinator.autoStopTriggered && !coordinator.isCurrentlyStalled) {
            coordinator._autoStopState.value = AutoStopUiState()
        }
    }

    /**
     * Reset stall detection timer.
     */
    private fun resetStallTimer() {
        coordinator.stallStartTime = null
        coordinator.isCurrentlyStalled = false
        if (coordinator.autoStopStartTime == null && !coordinator.autoStopTriggered) {
            coordinator._autoStopState.value = AutoStopUiState()
        }
    }

    /**
     * Fully reset auto-stop state for a new workout/set.
     */
    internal fun resetAutoStopState() {
        coordinator.autoStopStartTime = null
        coordinator.autoStopTriggered = false
        coordinator.autoStopStopRequested = false
        coordinator.stallStartTime = null
        coordinator.isCurrentlyStalled = false
        coordinator._autoStopState.value = AutoStopUiState()
    }

    /**
     * Issue #204: Returns true if we're in the startup grace period for auto-stop modes.
     */
    private fun isInAmrapStartupGrace(hasMeaningfulRange: Boolean): Boolean {
        val params = coordinator._workoutParameters.value
        if (!params.isAMRAP && !params.isJustLift) return false
        if (hasMeaningfulRange) return false
        if (coordinator.workoutStartTime == 0L) return true
        val elapsed = currentTimeMillis() - coordinator.workoutStartTime
        return elapsed < WorkoutCoordinator.AMRAP_STARTUP_GRACE_MS
    }

    /**
     * Auto-stop and stall detection are only active once warmup reps are complete.
     */
    private fun isWarmupGateOpenForAutoStop(): Boolean = coordinator._repCount.value.isWarmupComplete

    /**
     * Whether 2.5s position-based auto-stop should run.
     */
    private fun shouldRunPositionBasedAutoStop(params: WorkoutParameters): Boolean {
        if (!isWarmupGateOpenForAutoStop()) return false
        val timedCableReadyForAutoStop = coordinator.isCurrentTimedCableExercise
        return params.isJustLift || params.isAMRAP || timedCableReadyForAutoStop
    }

    /**
     * Whether any auto-stop evaluation should run.
     * - 5s velocity/deload stall path: controlled by stallDetectionEnabled.
     * - 2.5s position path: Just Lift / AMRAP / timed cable (post-warmup).
     */
    private fun shouldEnableAutoStop(params: WorkoutParameters): Boolean {
        if (!isWarmupGateOpenForAutoStop()) return false
        return params.stallDetectionEnabled || shouldRunPositionBasedAutoStop(params)
    }

    /**
     * Standard set stall guard:
     * - Defer stall detection only when no working reps are confirmed AND no rep is pending.
     *
     * Issue #256: Removed hasPendingRep guard from deferral. A stalled pending rep IS the
     * failure scenario (e.g., failed bench press at TOP of first rep). The velocity hysteresis
     * band (STALL_VELOCITY_LOW=2.5, STALL_VELOCITY_HIGH=10.0 mm/s) already provides adequate
     * protection against false triggers during brief pauses. When workingReps == 0 but
     * hasPendingRep is true, the user is mid-first-rep and must be protected.
     */
    private fun shouldDeferStandardSetStall(params: WorkoutParameters, repCount: RepCount): Boolean {
        val isStandardSet = !params.isJustLift && !params.isAMRAP && !coordinator.isCurrentTimedCableExercise
        if (!isStandardSet) return false
        return repCount.workingReps == 0 && !repCount.hasPendingRep
    }

    /**
     * Request auto-stop (thread-safe, only triggers once).
     */
    private fun requestAutoStop() {
        if (coordinator.autoStopStopRequested) return
        coordinator.autoStopStopRequested = true
        triggerAutoStop()
    }

    /**
     * Trigger auto-stop and handle set completion.
     */
    private fun triggerAutoStop() {
        Logger.d("triggerAutoStop() called")
        coordinator.autoStopTriggered = true

        if (coordinator._workoutParameters.value.isJustLift || coordinator._workoutParameters.value.isAMRAP || coordinator.isCurrentTimedCableExercise) {
            coordinator._autoStopState.value = coordinator._autoStopState.value.copy(
                progress = 1f,
                secondsRemaining = 0,
                isActive = true
            )
        } else {
            coordinator._autoStopState.value = AutoStopUiState()
        }

        handleSetCompletion()
    }

    // ===== Rep Processing =====

    /**
     * Handle rep notification from the machine.
     */
    private fun handleRepNotification(notification: RepNotification) {
        if (coordinator._isCurrentExerciseBodyweight.value) {
            return
        }

        val currentPositions = coordinator._currentMetric.value
        val rawPosA = currentPositions?.positionA ?: 0f
        val rawPosB = currentPositions?.positionB ?: 0f

        val repCountBefore = repCounter.getRepCount().totalReps

        // Seed ROM from machine (only has effect on first notification with valid data)
        if (!notification.isLegacyFormat) {
            repCounter.seedRomBoundaries(notification.rangeTop, notification.rangeBottom)
        }

        repCounter.process(
            repsRomCount = notification.repsRomCount,
            repsRomTotal = notification.repsRomTotal,
            repsSetCount = notification.repsSetCount,
            repsSetTotal = notification.repsSetTotal,
            up = notification.topCounter,
            down = notification.completeCounter,
            posA = rawPosA,
            posB = rawPosB,
            isLegacyFormat = notification.isLegacyFormat
        )

        repCounter.updatePhaseFromPosition(rawPosA, rawPosB)

        coordinator._repCount.value = repCounter.getRepCount()
        coordinator._repRanges.value = repCounter.getRepRanges()

        // Score the rep if rep count actually incremented
        val repCountAfter = repCounter.getRepCount().totalReps
        if (repCountAfter > repCountBefore) {
            // Capture rep boundary timestamp BEFORE scoring so scoreCurrentRep()
            // and processBiomechanicsForRep() both see the correct metric window.
            val now = KmpUtils.currentTimeMillis()
            coordinator.repBoundaryTimestamps.update { it + now }

            scoreCurrentRep(repCountAfter)

            // Segment metrics for this rep and process biomechanics (GATE-04: unconditional capture)
            processBiomechanicsForRep(repCountAfter, now)

            // Exercise auto-detection: trigger after MIN_REPS working reps
            // Only for working reps (warmup complete) when no exercise is assigned
            val repCount = repCounter.getRepCount()
            if (repCount.isWarmupComplete) {
                val params = coordinator._workoutParameters.value
                // Check if exercise already assigned (routine mode has selectedExerciseId)
                val hasExerciseAssigned = !params.selectedExerciseId.isNullOrBlank()

                detectionManager?.onRepCompleted(
                    repNumber = repCount.workingReps,
                    metrics = coordinator.collectedMetrics.value,
                    scope = scope,
                    hasExerciseAssigned = hasExerciseAssigned
                )
            }

        }
    }

    /**
     * Score the current rep using real metrics from collected WorkoutMetric data.
     * Uses RepBoundaryDetector to segment position data into concentric/eccentric phases,
     * then extracts force, velocity, and position arrays for each phase.
     */
    private fun scoreCurrentRep(repNumber: Int) {
        val metrics = coordinator.collectedMetrics.value
        if (metrics.isEmpty()) return

        // Get all metrics for this rep (use rep boundary timestamps if available)
        val boundaries = coordinator.repBoundaryTimestamps.value
        val prevBoundary = if (boundaries.size >= 2) boundaries[boundaries.size - 2] else 0L
        val currentBoundary = if (boundaries.isNotEmpty()) boundaries.last() else KmpUtils.currentTimeMillis()

        val repMetrics = if (boundaries.size >= 2) {
            metrics.filter { it.timestamp in (prevBoundary + 1)..currentBoundary }
        } else {
            metrics.takeLast(50) // Fallback for first rep
        }

        if (repMetrics.isEmpty()) return

        // Extract position array for phase detection (use max position of A/B)
        val positions = repMetrics.map { maxOf(it.positionA, it.positionB) }.toFloatArray()

        // Detect rep phases using valley detection
        val phaseBoundaries = repBoundaryDetector.detectBoundaries(positions)

        // If boundary detection found a rep, use its phase indices; otherwise use velocity-based split
        val (concentricIndices, eccentricIndices) = if (phaseBoundaries.isNotEmpty()) {
            // Use detected boundary (usually only 1 rep worth of data at capture time)
            val boundary = phaseBoundaries.first()
            Pair(boundary.concentricIndices, boundary.eccentricIndices)
        } else {
            // Fallback: split by velocity direction
            val velocitySplitIndex = repMetrics.indexOfFirst { it.velocityA < 0 || it.velocityB < 0 }
                .takeIf { it > 0 } ?: (repMetrics.size / 2)
            Pair(0 until velocitySplitIndex, velocitySplitIndex until repMetrics.size)
        }

        // Extract concentric phase data
        val concentricMetrics = concentricIndices.mapNotNull { repMetrics.getOrNull(it) }
        val concentricLoadsA = concentricMetrics.map { it.loadA }.toFloatArray()
        val concentricLoadsB = concentricMetrics.map { it.loadB }.toFloatArray()
        val concentricPositions = concentricMetrics.map { maxOf(it.positionA, it.positionB) }.toFloatArray()
        val concentricVelocities = concentricMetrics.map { maxOf(kotlin.math.abs(it.velocityA.toFloat()), kotlin.math.abs(it.velocityB.toFloat())) }.toFloatArray()
        val concentricTimestamps = concentricMetrics.map { it.timestamp - repMetrics.first().timestamp }.toLongArray()
        val concentricDurationMs = if (concentricMetrics.size >= 2) {
            concentricMetrics.last().timestamp - concentricMetrics.first().timestamp
        } else 0L

        // Extract eccentric phase data
        val eccentricMetrics = eccentricIndices.mapNotNull { repMetrics.getOrNull(it) }
        val eccentricLoadsA = eccentricMetrics.map { it.loadA }.toFloatArray()
        val eccentricLoadsB = eccentricMetrics.map { it.loadB }.toFloatArray()
        val eccentricPositions = eccentricMetrics.map { maxOf(it.positionA, it.positionB) }.toFloatArray()
        val eccentricVelocities = eccentricMetrics.map { maxOf(kotlin.math.abs(it.velocityA.toFloat()), kotlin.math.abs(it.velocityB.toFloat())) }.toFloatArray()
        val eccentricTimestamps = eccentricMetrics.map { it.timestamp - repMetrics.first().timestamp }.toLongArray()
        val eccentricDurationMs = if (eccentricMetrics.size >= 2) {
            eccentricMetrics.last().timestamp - eccentricMetrics.first().timestamp
        } else 0L

        // Calculate summary metrics
        val peakForceA = maxOf(concentricLoadsA.maxOrNull() ?: 0f, eccentricLoadsA.maxOrNull() ?: 0f)
        val peakForceB = maxOf(concentricLoadsB.maxOrNull() ?: 0f, eccentricLoadsB.maxOrNull() ?: 0f)
        val avgForceConcentricA = concentricLoadsA.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        val avgForceConcentricB = concentricLoadsB.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        val avgForceEccentricA = eccentricLoadsA.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        val avgForceEccentricB = eccentricLoadsB.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f

        // ROM and velocity
        val rom = positions.max() - positions.min()
        val peakVelocity = concentricVelocities.maxOrNull() ?: 0f
        val avgVelocityConcentric = concentricVelocities.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        val avgVelocityEccentric = eccentricVelocities.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f

        // Power calculations (power = force * velocity, converting units)
        // Force in kg, velocity in mm/s -> W = kg * m/s^2 * m/s = kg * mm/s / 1000 * 9.81
        val concentricPowers = concentricMetrics.map { m ->
            val force = m.loadA + m.loadB // total load in kg
            val velocity = maxOf(kotlin.math.abs(m.velocityA), kotlin.math.abs(m.velocityB)) / 1000.0 // m/s
            (force * velocity * 9.81).toFloat() // watts
        }
        val peakPowerWatts = concentricPowers.maxOrNull() ?: 0f
        val avgPowerWatts = concentricPowers.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f

        val totalDurationMs = if (repMetrics.size >= 2) {
            repMetrics.last().timestamp - repMetrics.first().timestamp
        } else 1000L

        val repData = RepMetricData(
            repNumber = repNumber,
            isWarmup = !repCounter.getRepCount().isWarmupComplete,
            startTimestamp = repMetrics.firstOrNull()?.timestamp ?: 0L,
            endTimestamp = repMetrics.lastOrNull()?.timestamp ?: 0L,
            durationMs = totalDurationMs,
            concentricDurationMs = concentricDurationMs,
            concentricPositions = concentricPositions,
            concentricLoadsA = concentricLoadsA,
            concentricLoadsB = concentricLoadsB,
            concentricVelocities = concentricVelocities,
            concentricTimestamps = concentricTimestamps,
            eccentricDurationMs = eccentricDurationMs,
            eccentricPositions = eccentricPositions,
            eccentricLoadsA = eccentricLoadsA,
            eccentricLoadsB = eccentricLoadsB,
            eccentricVelocities = eccentricVelocities,
            eccentricTimestamps = eccentricTimestamps,
            peakForceA = peakForceA,
            peakForceB = peakForceB,
            avgForceConcentricA = avgForceConcentricA,
            avgForceConcentricB = avgForceConcentricB,
            avgForceEccentricA = avgForceEccentricA,
            avgForceEccentricB = avgForceEccentricB,
            peakVelocity = peakVelocity,
            avgVelocityConcentric = avgVelocityConcentric,
            avgVelocityEccentric = avgVelocityEccentric,
            rangeOfMotionMm = rom,
            peakPowerWatts = peakPowerWatts,
            avgPowerWatts = avgPowerWatts
        )

        // Accumulate rep metric data for persistence at set completion
        coordinator.setRepMetrics.update { it + repData }

        val score = coordinator.repQualityScorer.scoreRep(repData)
        coordinator._latestRepQuality.value = score
        Logger.d { "Rep quality scored: rep=$repNumber, score=${score.composite}, concentricSamples=${concentricLoadsA.size}, eccentricSamples=${eccentricLoadsA.size}" }
    }

    /**
     * Process biomechanics analysis for a completed rep.
     *
     * Segments collectedMetrics using rep boundary timestamps, then processes
     * through BiomechanicsEngine on Dispatchers.Default (DATA-03 compliance).
     *
     * @param repNumber 1-indexed rep number
     * @param timestamp Rep completion timestamp
     */
    private fun processBiomechanicsForRep(repNumber: Int, timestamp: Long) {
        scope.launch(Dispatchers.Default) {
            val allMetrics = coordinator.collectedMetrics.value
            val boundaries = coordinator.repBoundaryTimestamps.value

            // Segment: metrics between previous boundary and current boundary
            val prevBoundary = if (boundaries.size >= 2) boundaries[boundaries.size - 2] else 0L
            val currentBoundary = boundaries.last()

            val repMetrics = allMetrics.filter { it.timestamp in (prevBoundary + 1)..currentBoundary }
            if (repMetrics.isEmpty()) {
                Logger.d { "Biomechanics: no metrics for rep $repNumber (boundary $prevBoundary..$currentBoundary)" }
                return@launch
            }

            // Split into concentric/eccentric using velocity direction
            // Concentric = lifting (positive velocity), Eccentric = lowering (negative velocity)
            // Approximate: use first half as concentric if we can't determine from velocity
            val concentricMetrics = repMetrics.filter {
                it.velocityA > 0 || it.velocityB > 0
            }.takeIf { it.isNotEmpty() } ?: run {
                // Fallback: first half is concentric
                val midpoint = repMetrics.size / 2
                if (midpoint > 0) repMetrics.take(midpoint) else repMetrics
            }

            coordinator.biomechanicsEngine.processRep(
                repNumber = repNumber,
                concentricMetrics = concentricMetrics,
                allRepMetrics = repMetrics,
                timestamp = timestamp
            )

            Logger.d { "Biomechanics processed: rep=$repNumber, metrics=${repMetrics.size}, concentric=${concentricMetrics.size}" }
        }
    }

    // ===== Auto-Stop Detection =====

    /**
     * Handle monitor metric data (matches parent repo logic).
     * Called on every metric from the machine, regardless of workout state.
     */
    internal fun handleMonitorMetric(metric: WorkoutMetric) {
        val params = coordinator._workoutParameters.value
        val state = coordinator._workoutState.value

        if (params.useAutoStart && state is WorkoutState.Idle) {
            repCounter.updatePositionRangesContinuously(metric.positionA, metric.positionB)
            coordinator._repRanges.value = repCounter.getRepRanges()
        }

        if (state is WorkoutState.Active) {
            collectMetricForHistory(metric)

            Logger.d { "Issue221: handleMonitorMetric Active - isJustLift=${params.isJustLift}, isAMRAP=${params.isAMRAP}, isTimedCable=$coordinator.isCurrentTimedCableExercise, posA=${metric.positionA}, posB=${metric.positionB}" }
            if (params.isJustLift || params.isAMRAP || coordinator.isCurrentTimedCableExercise) {
                Logger.d { "Issue221: Calling updatePositionRangesContinuously" }
                repCounter.updatePositionRangesContinuously(metric.positionA, metric.positionB)
            }

            repCounter.updatePhaseFromPosition(metric.positionA, metric.positionB)
            coordinator._repCount.value = repCounter.getRepCount()
            coordinator._repRanges.value = repCounter.getRepRanges()

            // Issue #252: Record the moment warmup completes (once per set)
            if (coordinator.warmupCompleteTimeMs == 0L && coordinator._repCount.value.isWarmupComplete) {
                coordinator.warmupCompleteTimeMs = currentTimeMillis()
            }

            if (shouldEnableAutoStop(params)) {
                Logger.d { "Issue203 DEBUG: checkAutoStop called - isJustLift=${params.isJustLift}, isAMRAP=${params.isAMRAP}, isTimedCable=$coordinator.isCurrentTimedCableExercise, setIndex=${coordinator._currentSetIndex.value}" }
                checkAutoStop(metric)
            } else {
                resetAutoStopTimer()
                resetStallTimer()
            }

            if (repCounter.shouldStopWorkout()) {
                handleSetCompletion()
            }

        } else {
            resetAutoStopTimer()
        }
    }

    /**
     * Check if auto-stop should be triggered based on velocity stall detection OR position-based detection.
     */
    private fun checkAutoStop(metric: WorkoutMetric) {
        if (coordinator._workoutState.value !is WorkoutState.Active) {
            resetAutoStopTimer()
            resetStallTimer()
            return
        }

        if (!isWarmupGateOpenForAutoStop()) {
            resetAutoStopTimer()
            resetStallTimer()
            return
        }

        val hasMeaningfulRange = repCounter.hasMeaningfulRange(WorkoutCoordinator.MIN_RANGE_THRESHOLD)
        val params = coordinator._workoutParameters.value
        val repCount = coordinator._repCount.value

        // ===== 1. VELOCITY-BASED STALL DETECTION =====
        if (params.stallDetectionEnabled && !shouldDeferStandardSetStall(params, repCount)) {
            val maxVelocity = maxOf(kotlin.math.abs(metric.velocityA), kotlin.math.abs(metric.velocityB))
            val isDefinitelyStalled = maxVelocity < WorkoutCoordinator.STALL_VELOCITY_LOW
            val isDefinitelyMoving = maxVelocity > WorkoutCoordinator.STALL_VELOCITY_HIGH

            val maxPosition = maxOf(metric.positionA, metric.positionB)
            val isActivelyUsing = maxPosition > WorkoutCoordinator.STALL_MIN_POSITION || hasMeaningfulRange

            val inGrace = isInAmrapStartupGrace(hasMeaningfulRange)
            if (isDefinitelyStalled && isActivelyUsing && coordinator.stallStartTime == null && !inGrace) {
                coordinator.stallStartTime = currentTimeMillis()
                coordinator.isCurrentlyStalled = true
            } else if (isDefinitelyMoving && coordinator.stallStartTime != null) {
                resetStallTimer()
            }

            val startTime = coordinator.stallStartTime
            if (startTime != null) {
                val stallElapsed = (currentTimeMillis() - startTime) / 1000f

                if (stallElapsed >= WorkoutCoordinator.STALL_DURATION_SECONDS && !coordinator.autoStopTriggered) {
                    requestAutoStop()
                    return
                }

                if (stallElapsed >= 1.0f) {
                    val progress = (stallElapsed / WorkoutCoordinator.STALL_DURATION_SECONDS).coerceIn(0f, 1f)
                    val remaining = (WorkoutCoordinator.STALL_DURATION_SECONDS - stallElapsed).coerceAtLeast(0f)

                    coordinator._autoStopState.value = AutoStopUiState(
                        isActive = true,
                        progress = progress,
                        secondsRemaining = ceil(remaining).toInt()
                    )
                }
            }
        } else {
            resetStallTimer()
        }

        if (!shouldRunPositionBasedAutoStop(params)) {
            resetAutoStopTimer()
            return
        }

        // ===== 2. POSITION-BASED DETECTION =====
        val maxPosition = maxOf(metric.positionA, metric.positionB)
        val handlesCompletelyAtRest = maxPosition < WorkoutCoordinator.HANDLE_REST_THRESHOLD

        val inGraceForPositionBased = isInAmrapStartupGrace(repCounter.hasMeaningfulRange(WorkoutCoordinator.MIN_RANGE_THRESHOLD))
        if (handlesCompletelyAtRest && !inGraceForPositionBased) {
            val startTime = coordinator.autoStopStartTime ?: run {
                coordinator.autoStopStartTime = currentTimeMillis()
                currentTimeMillis()
            }

            val elapsed = (currentTimeMillis() - startTime) / 1000f

            if (!coordinator.isCurrentlyStalled) {
                val progress = (elapsed / WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS).coerceIn(0f, 1f)
                val remaining = (WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS - elapsed).coerceAtLeast(0f)

                coordinator._autoStopState.value = AutoStopUiState(
                    isActive = true,
                    progress = progress,
                    secondsRemaining = ceil(remaining).toInt()
                )
            }

            if (elapsed >= WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS && !coordinator.autoStopTriggered) {
                requestAutoStop()
            }
            return
        } else if (handlesCompletelyAtRest && inGraceForPositionBased) {
            Logger.v("AutoStop: Handles at rest but in startup grace period - waiting")
            resetAutoStopTimer()
        } else {
            resetAutoStopTimer()
        }

        val inDangerZone = repCounter.isInDangerZone(metric.positionA, metric.positionB, WorkoutCoordinator.MIN_RANGE_THRESHOLD)
        val repRanges = repCounter.getRepRanges()

        var cableAppearsReleased = false

        repRanges.minPosA?.let { minA ->
            repRanges.maxPosA?.let { maxA ->
                val rangeA = maxA - minA
                if (rangeA > WorkoutCoordinator.MIN_RANGE_THRESHOLD) {
                    val thresholdA = minA + (rangeA * 0.05f)
                    val cableAInDanger = metric.positionA <= thresholdA
                    val cableAReleased = metric.positionA < WorkoutCoordinator.HANDLE_REST_THRESHOLD ||
                            (metric.positionA - minA) < 10
                    if (cableAInDanger && cableAReleased) {
                        cableAppearsReleased = true
                    }
                }
            }
        }

        if (!cableAppearsReleased) {
            repRanges.minPosB?.let { minB ->
                repRanges.maxPosB?.let { maxB ->
                    val rangeB = maxB - minB
                    if (rangeB > WorkoutCoordinator.MIN_RANGE_THRESHOLD) {
                        val thresholdB = minB + (rangeB * 0.05f)
                        val cableBInDanger = metric.positionB <= thresholdB
                        val cableBReleased = metric.positionB < WorkoutCoordinator.HANDLE_REST_THRESHOLD ||
                                (metric.positionB - minB) < 10
                        if (cableBInDanger && cableBReleased) {
                            cableAppearsReleased = true
                        }
                    }
                }
            }
        }

        if (inDangerZone && cableAppearsReleased) {
            val startTime = coordinator.autoStopStartTime ?: run {
                coordinator.autoStopStartTime = currentTimeMillis()
                currentTimeMillis()
            }

            val elapsed = (currentTimeMillis() - startTime) / 1000f

            if (!coordinator.isCurrentlyStalled) {
                val progress = (elapsed / WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS).coerceIn(0f, 1f)
                val remaining = (WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS - elapsed).coerceAtLeast(0f)

                coordinator._autoStopState.value = AutoStopUiState(
                    isActive = true,
                    progress = progress,
                    secondsRemaining = ceil(remaining).toInt()
                )
            }

            if (elapsed >= WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS && !coordinator.autoStopTriggered) {
                requestAutoStop()
            }
        } else {
            resetAutoStopTimer()
        }
    }

    // ===== Weight Adjustment =====

    /**
     * Send weight update command to the machine.
     */
    private suspend fun sendWeightUpdateToMachine(weightKg: Float) {
        try {
            val params = coordinator._workoutParameters.value

            val command = if (!params.isEchoMode) {
                BlePacketFactory.createWorkoutCommand(
                    params.programMode,
                    weightKg,
                    params.reps
                )
            } else {
                return
            }

            bleRepository.sendWorkoutCommand(command)
            Logger.d("Weight update sent to machine: $weightKg kg")
        } catch (e: Exception) {
            Logger.e(e) { "Failed to send weight update: ${e.message}" }
        }
    }

    /**
     * Adjust the weight during an active workout or rest period.
     *
     * If called during an active set, the BLE command is deferred until the next
     * set boundary. sendWeightUpdateToMachine() sends a full REGULAR_COMMAND packet
     * which resets the exercise on the machine (BLE exercise packet lifecycle constraint:
     * machine can't receive new exercise packet until active one fully ends).
     */
    fun adjustWeight(newWeightKg: Float, sendToMachine: Boolean = true) {
        // Upper bound is 110kg per cable to support both hardware variants:
        //   V-Form (VIT-200): 100kg max per cable
        //   Trainer+:         110kg max per cable
        // Do NOT replace with Constants.MAX_WEIGHT_KG (100f) — that would regress Trainer+ users.
        val clampedWeight = newWeightKg.coerceIn(0f, 110f)

        Logger.d("ActiveSessionEngine: Adjusting weight to $clampedWeight kg (sendToMachine=$sendToMachine)")

        val currentState = coordinator._workoutState.value
        if (currentState is WorkoutState.Idle ||
            currentState is WorkoutState.Resting ||
            currentState is WorkoutState.SetSummary) {
            coordinator._userAdjustedWeightDuringRest = true
            Logger.d("ActiveSessionEngine: User adjusted weight in ${currentState::class.simpleName} - will preserve on next set")
        }

        coordinator._workoutParameters.update { params ->
            params.copy(weightPerCableKg = clampedWeight)
        }

        if (sendToMachine && coordinator._workoutState.value is WorkoutState.Active) {
            // Defer BLE weight change to next set boundary. Sending a full workout
            // command (REGULAR_COMMAND) mid-set would fault the machine.
            coordinator.pendingWeightChangeKg = clampedWeight
            Logger.d("ActiveSessionEngine: Deferred weight change to $clampedWeight kg (mid-set, will apply at next set start)")
        }
    }

    fun incrementWeight(amount: Float = 0.5f) {
        val currentWeight = coordinator._workoutParameters.value.weightPerCableKg
        adjustWeight(currentWeight + amount)
    }

    fun decrementWeight(amount: Float = 0.5f) {
        val currentWeight = coordinator._workoutParameters.value.weightPerCableKg
        adjustWeight(currentWeight - amount)
    }

    fun setWeightPreset(presetWeightKg: Float) {
        adjustWeight(presetWeightKg)
    }

    suspend fun getLastWeightForExercise(exerciseId: String): Float? {
        val profileId = userProfileRepository.activeProfile.value?.id ?: "default"
        return workoutRepository.getAllSessions(profileId = profileId)
            .first()
            .filter { it.exerciseId == exerciseId }
            .sortedByDescending { it.timestamp }
            .firstOrNull()
            ?.weightPerCableKg
    }

    suspend fun getPrWeightForExercise(exerciseId: String): Float? {
        val profileId = userProfileRepository.activeProfile.value?.id ?: "default"
        return workoutRepository.getAllPersonalRecords(profileId)
            .first()
            .filter { it.exerciseId == exerciseId }
            .maxOfOrNull { it.weightPerCableKg }
    }

    // ===== Just Lift =====

    fun enableHandleDetection() {
        val now = currentTimeMillis()
        if (now - coordinator.handleDetectionEnabledTimestamp < coordinator.HANDLE_DETECTION_DEBOUNCE_MS) {
            Logger.d("ActiveSessionEngine: Handle detection already enabled recently, skipping (idempotent)")
            return
        }
        coordinator.handleDetectionEnabledTimestamp = now
        Logger.d("ActiveSessionEngine: Enabling handle detection for auto-start")
        bleRepository.enableHandleDetection(true)
    }

    fun disableHandleDetection() {
        Logger.d("ActiveSessionEngine: Disabling handle detection")
        bleRepository.enableHandleDetection(false)
    }

    fun prepareForJustLift() {
        scope.launch {
            val currentState = coordinator._workoutState.value
            val currentWeight = coordinator._workoutParameters.value.weightPerCableKg
            Logger.d("prepareForJustLift: BEFORE - weight=$currentWeight kg")

            if (currentState !is WorkoutState.Idle) {
                Logger.d("Preparing for Just Lift: Resetting from ${currentState::class.simpleName} to Idle")
                resetForNewWorkout()
                coordinator._workoutState.value = WorkoutState.Idle
            } else {
                Logger.d("Just Lift already in Idle state, ensuring auto-start is enabled")
            }

            coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
                isJustLift = true,
                useAutoStart = true,
                selectedExerciseId = null
            )

            enableHandleDetection()
            val newWeight = coordinator._workoutParameters.value.weightPerCableKg
            Logger.d("prepareForJustLift: AFTER - weight=$newWeight kg")
            Logger.d("Just Lift ready: State=Idle, AutoStart=enabled, waiting for handle grab")
        }
    }

    suspend fun getJustLiftDefaults(): JustLiftDefaults {
        val prefsDefaults = preferencesManager.getJustLiftDefaults()
        return JustLiftDefaults(
            weightPerCableKg = prefsDefaults.weightPerCableKg,
            weightChangePerRep = kotlin.math.round(prefsDefaults.weightChangePerRep).toInt(),
            workoutModeId = prefsDefaults.workoutModeId,
            eccentricLoadPercentage = prefsDefaults.eccentricLoadPercentage,
            echoLevelValue = prefsDefaults.echoLevelValue,
            stallDetectionEnabled = prefsDefaults.stallDetectionEnabled,
            repCountTimingName = prefsDefaults.repCountTimingName,
            restSeconds = prefsDefaults.restSeconds
        )
    }

    fun saveJustLiftDefaults(defaults: JustLiftDefaults) {
        scope.launch {
            val prefsDefaults = com.devil.phoenixproject.data.preferences.JustLiftDefaults(
                weightPerCableKg = defaults.weightPerCableKg,
                weightChangePerRep = defaults.weightChangePerRep.toFloat(),
                workoutModeId = defaults.workoutModeId,
                eccentricLoadPercentage = defaults.eccentricLoadPercentage,
                echoLevelValue = defaults.echoLevelValue,
                stallDetectionEnabled = defaults.stallDetectionEnabled,
                repCountTimingName = defaults.repCountTimingName,
                restSeconds = defaults.restSeconds
            )
            preferencesManager.saveJustLiftDefaults(prefsDefaults)
            Logger.d("saveJustLiftDefaults: weight=${defaults.weightPerCableKg}kg, mode=${defaults.workoutModeId}, restSeconds=${defaults.restSeconds}")
        }
    }

    /**
     * Applies an auto-accepted exercise detection (if any) to the workout parameters.
     * Called before saving a session so the detected exerciseId is persisted.
     * Returns true if an auto-accepted detection was applied.
     */
    private suspend fun applyAutoAcceptedDetection(): Boolean {
        val detection = detectionManager?.detectionState?.value ?: return false
        if (!detection.isAutoAccepted || detection.classification == null) return false
        val cls = detection.classification
        val confirmedId = cls.exerciseId
        if (confirmedId.isNullOrBlank()) return false
        detectionManager.onExerciseConfirmed(confirmedId, cls.exerciseName)
        coordinator._workoutParameters.update { p -> p.copy(selectedExerciseId = confirmedId) }
        coordinator._userFeedbackEvents.emit("Exercise detected: ${cls.exerciseName}")
        Logger.d("Just Lift: Auto-accepted exercise '${cls.exerciseName}' (id=$confirmedId)")
        return true
    }

    private suspend fun saveJustLiftDefaultsFromWorkout() {
        val params = coordinator._workoutParameters.value
        if (!params.isJustLift) return

        val eccentricLoadPct = if (params.isEchoMode) params.eccentricLoad.percentage else 100
        val echoLevelVal = if (params.isEchoMode) params.echoLevel.levelValue else 2

        try {
            val defaults = com.devil.phoenixproject.data.preferences.JustLiftDefaults(
                workoutModeId = params.programMode.modeValue,
                weightPerCableKg = params.weightPerCableKg.coerceAtLeast(0.1f),
                weightChangePerRep = params.progressionRegressionKg,
                eccentricLoadPercentage = eccentricLoadPct,
                echoLevelValue = echoLevelVal,
                stallDetectionEnabled = params.stallDetectionEnabled,
                repCountTimingName = params.repCountTiming.name,
                restSeconds = params.justLiftRestSeconds
            )
            preferencesManager.saveJustLiftDefaults(defaults)
            Logger.d { "Saved Just Lift defaults: mode=${params.programMode.modeValue}, weight=${params.weightPerCableKg}kg, restSeconds=${params.justLiftRestSeconds}" }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to save Just Lift defaults: ${e.message}" }
        }
    }

    suspend fun getSingleExerciseDefaults(exerciseId: String): com.devil.phoenixproject.data.preferences.SingleExerciseDefaults? {
        return preferencesManager.getSingleExerciseDefaults(exerciseId)
    }

    fun saveSingleExerciseDefaults(defaults: com.devil.phoenixproject.data.preferences.SingleExerciseDefaults) {
        scope.launch {
            preferencesManager.saveSingleExerciseDefaults(defaults)
            Logger.d("saveSingleExerciseDefaults: exerciseId=${defaults.exerciseId}")
        }
    }

    private suspend fun saveSingleExerciseDefaultsFromWorkout() {
        val routine = coordinator._loadedRoutine.value ?: return

        if (!routine.id.startsWith(DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX)) return

        val currentExercise = routine.exercises.getOrNull(coordinator._currentExerciseIndex.value) ?: return
        val exerciseId = currentExercise.exercise.id ?: return

        val isEchoExercise = currentExercise.programMode == ProgramMode.Echo
        val eccentricLoadPct = if (isEchoExercise) currentExercise.eccentricLoad.percentage else 100
        val echoLevelVal = if (isEchoExercise) currentExercise.echoLevel.levelValue else 1

        try {
            val setReps = currentExercise.setReps.ifEmpty { listOf(10) }
            val numSets = setReps.size

            val normalizedSetWeights = when {
                currentExercise.setWeightsPerCableKg.isEmpty() -> emptyList()
                currentExercise.setWeightsPerCableKg.size == numSets -> currentExercise.setWeightsPerCableKg
                else -> emptyList()
            }

            val normalizedSetRest = when {
                currentExercise.setRestSeconds.isEmpty() -> emptyList()
                currentExercise.setRestSeconds.size == numSets -> currentExercise.setRestSeconds
                else -> emptyList()
            }

            val defaults = com.devil.phoenixproject.data.preferences.SingleExerciseDefaults(
                exerciseId = exerciseId,
                setReps = setReps,
                weightPerCableKg = currentExercise.weightPerCableKg.coerceAtLeast(0f),
                setWeightsPerCableKg = normalizedSetWeights,
                progressionKg = currentExercise.progressionKg.coerceIn(-50f, 50f),
                setRestSeconds = normalizedSetRest,
                workoutModeId = currentExercise.programMode.modeValue,
                eccentricLoadPercentage = eccentricLoadPct,
                echoLevelValue = echoLevelVal,
                duration = currentExercise.duration?.takeIf { it > 0 } ?: 0,
                isAMRAP = currentExercise.isAMRAP,
                perSetRestTime = currentExercise.perSetRestTime
            )
            preferencesManager.saveSingleExerciseDefaults(defaults)
            Logger.d { "Saved Single Exercise defaults for ${currentExercise.exercise.name}" }
        } catch (e: IllegalArgumentException) {
            Logger.e(e) { "Failed to save Single Exercise defaults - validation error" }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to save Single Exercise defaults: ${e.message}" }
        }
    }

    // ===== Training Cycles =====

    fun loadRoutineFromCycle(routineId: String, cycleId: String, dayNumber: Int) {
        val routine = coordinator._routines.value.find { it.id == routineId }
        if (routine != null) {
            coordinator.activeCycleId = cycleId
            coordinator.activeCycleDayNumber = dayNumber
            Logger.d { "Loading routine from cycle: cycleId=$cycleId, dayNumber=$dayNumber" }
            flowDelegate?.loadRoutine(routine)
        }
    }

    fun clearCycleContext() {
        flowDelegate?.clearCycleContext()
    }

    private suspend fun updateCycleProgressIfNeeded() {
        val cycleId = coordinator.activeCycleId ?: return
        val dayNumber = coordinator.activeCycleDayNumber ?: return

        coordinator.activeCycleId = null
        coordinator.activeCycleDayNumber = null

        try {
            val cycle = trainingCycleRepository.getCycleById(cycleId)
            val progress = trainingCycleRepository.getCycleProgress(cycleId)

            if (cycle != null && progress != null) {
                val updated = progress.markDayCompleted(dayNumber)
                trainingCycleRepository.updateCycleProgress(updated)

                val completedDay = cycle.days.find { it.dayNumber == dayNumber }
                // Rotation detection: check if this was the last day in the cycle
                val isRotationComplete = dayNumber >= cycle.days.size
                coordinator._cycleDayCompletionEvent.value = CycleDayCompletionEvent(
                    dayNumber = dayNumber,
                    dayName = completedDay?.name,
                    isRotationComplete = isRotationComplete,
                    rotationCount = if (isRotationComplete) progress.rotationCount + 1 else progress.rotationCount
                )

                Logger.d { "Cycle progress updated: day $dayNumber completed, now on day ${updated.currentDayNumber}" +
                    if (isRotationComplete) " (rotation ${updated.rotationCount} complete!)" else "" }
            }
        } catch (e: Exception) {
            Logger.e(e) { "Error updating cycle progress: ${e.message}" }
        }
    }

    // ===== Form Check =====

    // ===== Core Workout Lifecycle =====

    fun resetForNewWorkout() {
        coordinator._workoutState.value = WorkoutState.Idle
        coordinator._repCount.value = RepCount()
        coordinator._repRanges.value = null
        coordinator.setRepMetrics.value = emptyList()
        // Reset biomechanics engine and rep boundary timestamps
        coordinator.biomechanicsEngine.reset()
        coordinator.repBoundaryTimestamps.value = emptyList()
        coordinator.warmupCompleteTimeMs = 0
        // Reset variable warm-up state
        coordinator._currentWarmupSetIndex.value = -1
        coordinator._totalWarmupSets.value = 0
    }

    fun recaptureLoadBaseline() {
        coordinator._currentMetric.value?.let { metric ->
            coordinator._loadBaselineA.value = metric.loadA
            coordinator._loadBaselineB.value = metric.loadB
            Logger.d("ActiveSessionEngine") { "LOAD BASELINE: Manually recaptured loadA=${metric.loadA}kg, loadB=${metric.loadB}kg" }
        }
    }

    fun resetLoadBaseline() {
        coordinator._loadBaselineA.value = 0f
        coordinator._loadBaselineB.value = 0f
        Logger.d("ActiveSessionEngine") { "LOAD BASELINE: Reset to 0 (disabled)" }
    }

    fun updateWorkoutParameters(params: WorkoutParameters) {
        val currentState = coordinator._workoutState.value
        if (currentState is WorkoutState.Idle ||
            currentState is WorkoutState.Resting ||
            currentState is WorkoutState.SetSummary) {
            coordinator._userAdjustedWeightDuringRest = true
            Logger.d("updateWorkoutParameters: User edited params in ${currentState::class.simpleName} - will preserve on transition")
        }
        coordinator._workoutParameters.value = params
    }

    /**
     * Internal parameter updates used by manager-driven transitions.
     *
     * Unlike [updateWorkoutParameters], this intentionally does NOT mark the
     * parameters as user-adjusted during rest.
     */
    fun setWorkoutParametersInternal(params: WorkoutParameters) {
        coordinator._workoutParameters.value = params
    }

    fun startWorkout(skipCountdown: Boolean = false, isJustLiftMode: Boolean = false) {
        Logger.d { "startWorkout called: skipCountdown=$skipCountdown, isJustLiftMode=$isJustLiftMode" }
        Logger.d { "startWorkout: loadedRoutine=${coordinator._loadedRoutine.value?.name}, params=${coordinator._workoutParameters.value}" }

        coordinator.stopWorkoutInProgress.value = false
        coordinator.setCompletionInProgress.value = false
        resetAutoStopState()
        coordinator.skipCountdownRequested = skipCountdown

        // Reset rep quality scorer for fresh set
        coordinator.repQualityScorer.reset()
        coordinator._latestRepQuality.value = null
        // Reset quality streak only at actual workout start, not between sets.
        // skipCountdown=true indicates a set-to-set transition within the same workout.
        if (!skipCountdown) {
            gamificationManager.resetQualityStreak()
        }

        coordinator.workoutJob?.cancel()

        coordinator._workoutState.value = WorkoutState.Initializing
        syncRoutineSessionContext()

        coordinator.workoutJob = scope.launch {
            try {
            val params = coordinator._workoutParameters.value

            val currentExercise = coordinator._loadedRoutine.value?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
            val isBodyweight = isBodyweightExercise(currentExercise)
            val exerciseDuration = currentExercise?.duration?.takeIf { it > 0 }
            val bodyweightDuration = if (isBodyweight) exerciseDuration else null

            val isTimedCableExercise = !isBodyweight && exerciseDuration != null
            coordinator.isCurrentWorkoutTimed = exerciseDuration != null
            coordinator.isCurrentTimedCableExercise = isTimedCableExercise
            coordinator._isCurrentExerciseBodyweight.value = isBodyweight

            Logger.d { "Issue227: startWorkout exercise type detection:" }
            Logger.d { "  - Exercise: ${currentExercise?.exercise?.name}" }
            Logger.d { "  - Equipment: '${currentExercise?.exercise?.equipment}'" }
            Logger.d { "  - Weight: ${currentExercise?.weightPerCableKg}kg" }
            Logger.d { "  - Duration: ${exerciseDuration}s" }
            Logger.d { "  - isBodyweight: $isBodyweight" }
            Logger.d { "  - isTimedCableExercise: $isTimedCableExercise" }

            // Issue #222: For ALL bodyweight exercises, skip machine commands
            if (isBodyweight) {
                val effectiveDuration = bodyweightDuration ?: 30
                Logger.d("Starting bodyweight exercise: ${currentExercise?.exercise?.name} for ${effectiveDuration}s (bodyweightDuration=${bodyweightDuration})")

                Logger.d("ActiveSessionEngine") { "Issue #222 v6: Bodyweight start - keeping existing polling state (matching parent repo)" }

                repCounter.reset()
                repCounter.configure(
                    warmupTarget = 0,
                    workingTarget = 0,
                    isJustLift = false,
                    stopAtTop = params.stopAtTop,
                    isAMRAP = false
                )
                coordinator._repCount.value = RepCount()
                coordinator.warmupCompleteTimeMs = 0

                if (!coordinator.skipCountdownRequested) {
                    startMotionStartDetection()
                    for (i in 5 downTo 1) {
                        if (coordinator.skipCountdownRequested) break
                        coordinator._workoutState.value = WorkoutState.Countdown(i)
                        delay(1000)
                    }
                    stopMotionStartDetection()
                }

                coordinator._workoutState.value = WorkoutState.Active
                coordinator.workoutStartTime = currentTimeMillis()
                if (coordinator._loadedRoutine.value != null && coordinator.routineStartTime == 0L) {
                    coordinator.routineStartTime = coordinator.workoutStartTime
                }
                coordinator.currentSessionId = KmpUtils.randomUUID()
                coordinator.collectedMetrics.value = emptyList()
                coordinator._hapticEvents.emit(HapticEvent.WORKOUT_START)

                coordinator.bodyweightTimerJob?.cancel()
                coordinator.bodyweightTimerJob = scope.launch {
                    coordinator._timedExerciseRemainingSeconds.value = effectiveDuration
                    for (remaining in effectiveDuration downTo 1) {
                        coordinator._timedExerciseRemainingSeconds.value = remaining
                        delay(1000L)
                    }
                    coordinator._timedExerciseRemainingSeconds.value = 0
                    handleSetCompletion()
                }

                return@launch
            }

            // Normal cable-based exercise
            if (coordinator.previousExerciseWasBodyweight) {
                coordinator.previousExerciseWasBodyweight = false
            }

            val effectiveWarmupReps = Constants.DEFAULT_WARMUP_REPS
            val effectiveParams = if (params.warmupReps != effectiveWarmupReps) {
                Logger.d("ActiveSessionEngine") { "Issue #222: Forcing warmupReps=$effectiveWarmupReps for cable exercise (was ${params.warmupReps})" }
                val updated = params.copy(warmupReps = effectiveWarmupReps)
                coordinator._workoutParameters.value = updated
                updated
            } else {
                params
            }

            // Clear any deferred mid-set weight change flag (weight already applied to _workoutParameters)
            if (coordinator.pendingWeightChangeKg != null) {
                Logger.d("ActiveSessionEngine: Deferred weight change of ${coordinator.pendingWeightChangeKg} kg applied at set start")
                coordinator.pendingWeightChangeKg = null
            }

            Logger.d("ActiveSessionEngine") { "Cable workout starting - bodyweightSetsInRoutine=${coordinator.bodyweightSetsCompletedInRoutine}, exerciseIdx=${coordinator._currentExerciseIndex.value}, setIdx=${coordinator._currentSetIndex.value}, echo=${effectiveParams.isEchoMode}, mode=${effectiveParams.programMode}" }
            Logger.d("ActiveSessionEngine") { "BLE params: mode=${effectiveParams.programMode.displayName}, weight=${effectiveParams.weightPerCableKg}kg, reps=${effectiveParams.reps} (AMRAP=${effectiveParams.isAMRAP}), warmup=${effectiveParams.warmupReps}, progression=${effectiveParams.progressionRegressionKg}kg/rep" }
            Logger.d("ActiveSessionEngine") { "BLE params (cont): justLift=${effectiveParams.isJustLift}, echo=${effectiveParams.isEchoMode}, echoLevel=${effectiveParams.echoLevel.displayName}, eccentricLoad=${effectiveParams.eccentricLoad.percentage}%, stopAtTop=${effectiveParams.stopAtTop}, stallDetection=${effectiveParams.stallDetectionEnabled}" }
            Logger.d("ActiveSessionEngine") { "Issue203: setReps=${currentExercise?.setReps}, currentSetIndex=${coordinator._currentSetIndex.value}, isAMRAP=${effectiveParams.isAMRAP}" }

            // ===== Variable Warm-up Sets (Phase 35C: Issue #30) =====
            // When the exercise has warmupSets defined, override weight/reps for warm-up phase.
            // Each warm-up set is a separate BLE stop/start cycle at a percentage of working weight.
            val warmupSetIndex = coordinator._currentWarmupSetIndex.value
            val isInWarmupPhase = warmupSetIndex >= 0
            val warmupOverrideParams = if (isInWarmupPhase && currentExercise != null) {
                val warmupSet = currentExercise.warmupSets.getOrNull(warmupSetIndex)
                if (warmupSet != null) {
                    val warmupWeight = (effectiveParams.weightPerCableKg * warmupSet.percentOfWorking / 100f)
                        .coerceIn(0f, 110f)
                    Logger.d { "WarmupSet ${warmupSetIndex + 1}/${currentExercise.warmupSets.size}: " +
                        "${warmupSet.reps} reps @ ${warmupSet.percentOfWorking}% = ${warmupWeight}kg (working=${effectiveParams.weightPerCableKg}kg)" }
                    effectiveParams.copy(
                        weightPerCableKg = warmupWeight,
                        reps = warmupSet.reps,
                        warmupReps = 0, // No firmware warmup for variable warm-up sets
                        isAMRAP = false  // Warm-up sets are always fixed reps
                    )
                } else {
                    Logger.w { "WarmupSet index $warmupSetIndex out of bounds for ${currentExercise.warmupSets.size} warmup sets - falling through to working set" }
                    coordinator._currentWarmupSetIndex.value = -1
                    effectiveParams
                }
            } else {
                effectiveParams
            }
            // Update coordinator params if warm-up override applied
            if (isInWarmupPhase && warmupOverrideParams !== effectiveParams) {
                coordinator._workoutParameters.value = warmupOverrideParams
            }

            val bleParams = if (isTimedCableExercise) {
                Logger.d { "Duration cable: overriding isAMRAP=true for BLE command (prevents machine rep limit)" }
                warmupOverrideParams.copy(isAMRAP = true)
            } else {
                warmupOverrideParams
            }

            val command = if (bleParams.isEchoMode) {
                BlePacketFactory.createEchoControl(
                    level = bleParams.echoLevel,
                    warmupReps = bleParams.warmupReps,
                    targetReps = bleParams.reps,
                    isJustLift = isJustLiftMode || bleParams.isJustLift,
                    isAMRAP = bleParams.isAMRAP,
                    eccentricPct = bleParams.eccentricLoad.percentage
                )
            } else {
                BlePacketFactory.createProgramParams(bleParams)
            }
            Logger.d { "Built ${command.size}-byte workout command for ${bleParams.programMode}" }

            coordinator.currentSessionId = KmpUtils.randomUUID()
            coordinator._repCount.value = RepCount()
            coordinator.warmupCompleteTimeMs = 0
            coordinator._currentHeuristicKgMax.value = 0f
            if (isJustLiftMode) {
                repCounter.resetCountsOnly()
            } else {
                repCounter.reset()
            }
            repCounter.configure(
                warmupTarget = effectiveParams.warmupReps,
                workingTarget = if (isTimedCableExercise) 0 else effectiveParams.reps,
                isJustLift = isJustLiftMode,
                stopAtTop = effectiveParams.stopAtTop,
                isAMRAP = if (isTimedCableExercise) true else effectiveParams.isAMRAP
            )

            if (isTimedCableExercise) {
                Logger.d { "Starting TIMED cable exercise: ${currentExercise.exercise.name} for ${exerciseDuration}s" }
            }

            if (!coordinator.skipCountdownRequested && !isJustLiftMode) {
                startMotionStartDetection()
                for (i in 5 downTo 1) {
                    if (coordinator.skipCountdownRequested) break
                    coordinator._workoutState.value = WorkoutState.Countdown(i)
                    delay(1000)
                }
                stopMotionStartDetection()
            }

            try {
                bleRepository.sendWorkoutCommand(command)
                Logger.i { "CONFIG command sent: ${command.size} bytes for ${effectiveParams.programMode}" }
                val preview = command.take(16).joinToString(" ") { it.toUByte().toString(16).padStart(2, '0').uppercase() }
                Logger.d { "Config preview: $preview ..." }
                if (!effectiveParams.isEchoMode && command.size >= 0x60) {
                    val activationTailDump = command
                        .copyOfRange(0x48, 0x60)
                        .joinToString(" ") { it.toUByte().toString(16).padStart(2, '0').uppercase() }
                    Logger.w {
                        "BLE-ACTIVATION-VERIFY (temporary): offsets 0x48..0x5F => $activationTailDump"
                    }
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to send config command" }
                coordinator._bleErrorEvents.tryEmit("Failed to send command: ${e.message}")
                return@launch
            }

            if (!effectiveParams.isEchoMode) {
                delay(100)
                try {
                    val startCommand = BlePacketFactory.createStartCommand()
                    bleRepository.sendWorkoutCommand(startCommand)
                    Logger.i { "START command sent (0x03)" }
                } catch (e: Exception) {
                    Logger.e(e) { "Failed to send START command" }
                    coordinator._bleErrorEvents.tryEmit("Failed to start workout: ${e.message}")
                    return@launch
                }
            }

            bleRepository.startActiveWorkoutPolling()

            coordinator._workoutState.value = WorkoutState.Active
            coordinator.workoutStartTime = currentTimeMillis()
            if (coordinator._loadedRoutine.value != null && coordinator.routineStartTime == 0L) {
                coordinator.routineStartTime = coordinator.workoutStartTime
            }
            coordinator.collectedMetrics.value = emptyList()
            coordinator._hapticEvents.emit(HapticEvent.WORKOUT_START)

            // exerciseDuration != null is logically redundant (implied by isTimedCableExercise)
            // but required for Kotlin smart-cast so exerciseDuration can be used as non-null below
            @Suppress("SENSELESS_COMPARISON")
            if (isTimedCableExercise && exerciseDuration != null) {
                coordinator.bodyweightTimerJob?.cancel()
                coordinator.bodyweightTimerJob = scope.launch {
                    if (effectiveParams.warmupReps > 0) {
                        Logger.d { "Duration cable: waiting for ${effectiveParams.warmupReps} warmup reps before starting ${exerciseDuration}s timer" }
                        coordinator._repCount.first { it.isWarmupComplete }
                        Logger.d { "Duration cable: warmup complete, starting ${exerciseDuration}s duration timer" }
                    }

                    coordinator._timedExerciseRemainingSeconds.value = exerciseDuration
                    for (remaining in exerciseDuration downTo 1) {
                        coordinator._timedExerciseRemainingSeconds.value = remaining
                        delay(1000L)
                    }
                    coordinator._timedExerciseRemainingSeconds.value = 0
                    handleSetCompletion()
                }
            }

            coordinator._currentMetric.value?.let { metric ->
                repCounter.setInitialBaseline(metric.positionA, metric.positionB)
                coordinator._repRanges.value = repCounter.getRepRanges()
                Logger.d("ActiveSessionEngine") { "POSITION BASELINE: Set initial baseline posA=${metric.positionA}, posB=${metric.positionB}" }

                coordinator._loadBaselineA.value = metric.loadA
                coordinator._loadBaselineB.value = metric.loadB
                Logger.d("ActiveSessionEngine") { "LOAD BASELINE: Set initial baseline loadA=${metric.loadA}kg, loadB=${metric.loadB}kg" }
            }
            } catch (e: CancellationException) {
                throw e // let cancellation propagate
            } catch (e: Exception) {
                Logger.e(e) { "workoutJob: uncaught exception" }
                coordinator._bleErrorEvents.tryEmit("Workout error: ${e.message}")
                coordinator._workoutState.value = WorkoutState.Idle
            }
        }
    }

    /**
     * Ensure routine session metadata is present for routine workouts so persisted
     * sessions (and backups) can be grouped back to the originating routine run.
     * Single-exercise temp routines and Just Lift sessions intentionally stay null.
     */
    private fun syncRoutineSessionContext() {
        val loadedRoutine = coordinator._loadedRoutine.value
        val isTrackedRoutine = loadedRoutine != null &&
            !loadedRoutine.id.startsWith(DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX)

        if (!isTrackedRoutine) {
            coordinator.currentRoutineSessionId = null
            coordinator.currentRoutineName = null
            coordinator.currentRoutineId = null
            return
        }

        if (coordinator.currentRoutineSessionId.isNullOrBlank()) {
            coordinator.currentRoutineSessionId = KmpUtils.randomUUID()
        }
        coordinator.currentRoutineName = loadedRoutine.name
        coordinator.currentRoutineId = loadedRoutine.id
    }

    fun skipCountdown() {
        coordinator.skipCountdownRequested = true
        Logger.d { "skipCountdown: Countdown skip requested" }
    }

    /**
     * Issue #237: Start motion-start detection during countdown.
     * Collects metricsFlow and feeds load values to the MotionStartDetector.
     * On [MotionStartEvent.Started], calls [skipCountdown] to begin the set immediately.
     * On [MotionStartEvent.CountdownTick], updates hold progress for the ring-fill UI.
     * On [MotionStartEvent.Cancelled], resets progress.
     */
    private fun startMotionStartDetection() {
        val prefs = settingsManager.userPreferences.value
        if (!prefs.motionStartEnabled) return

        motionStartDetector.reset()
        coordinator._motionStartHoldProgress.value = 0f

        // Listen for detector events
        motionStartListenerJob?.cancel()
        motionStartListenerJob = scope.launch {
            // Event listener coroutine
            launch {
                motionStartDetector.events.collect { event ->
                    when (event) {
                        is MotionStartEvent.Started -> {
                            coordinator._motionStartHoldProgress.value = 1f
                            Logger.d { "MotionStart: Cable hold complete, skipping countdown" }
                            skipCountdown()
                        }
                        is MotionStartEvent.CountdownTick -> {
                            val progress = 1f - (event.remainingMs.toFloat() / 1500f)
                            coordinator._motionStartHoldProgress.value = progress.coerceIn(0f, 1f)
                        }
                        is MotionStartEvent.Cancelled -> {
                            coordinator._motionStartHoldProgress.value = 0f
                        }
                    }
                }
            }
            // Metric feeder coroutine
            launch {
                bleRepository.metricsFlow
                    .catch { e -> Logger.e(e) { "metricsFlow collector error (motionStart)" } }
                    .collect { metric ->
                        // Use average of both cables for load detection
                        val load = (metric.loadA + metric.loadB) / 2f
                        motionStartDetector.onMetricReceived(load, metric.timestamp)
                    }
            }
        }
    }

    /** Issue #237: Stop motion-start detection and clear UI state. */
    private fun stopMotionStartDetection() {
        motionStartListenerJob?.cancel()
        motionStartListenerJob = null
        motionStartDetector.reset()
        coordinator._motionStartHoldProgress.value = null
    }

    fun stopWorkout(exitingWorkout: Boolean = false) {
        // C1: Atomic compareAndSet prevents TOCTOU race — only the first caller proceeds
        if (!coordinator.stopWorkoutInProgress.compareAndSet(expect = false, update = true)) return

        val shouldExitToIdle = exitingWorkout

        coordinator.workoutJob?.cancel()
        coordinator.workoutJob = null

        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null

        coordinator.restTimerJob?.cancel()
        coordinator.restTimerJob = null

        scope.launch {
            coordinator.isCurrentWorkoutTimed = false
            coordinator.isCurrentTimedCableExercise = false
            coordinator._isCurrentExerciseBodyweight.value = false

             val currentExercise = coordinator._loadedRoutine.value?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
             val isBodyweight = isBodyweightExercise(currentExercise)
             Logger.d("ActiveSessionEngine") { "Manual stop: isBodyweight=$isBodyweight, exitingWorkout=$shouldExitToIdle" }
             if (!isBodyweight) {
                 Logger.d("ActiveSessionEngine") { "Manual stop: calling bleRepository.stopWorkout()" }
                 bleRepository.stopWorkout()
             } else {
                 Logger.d("ActiveSessionEngine") { "Manual stop: bodyweight exercise - skipping BLE stop" }
             }
             coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)

             val repCount = coordinator._repCount.value
             val isJustLift = coordinator._workoutParameters.value.isJustLift

             if (isJustLift) {
                 Logger.d("Just Lift: Restarting monitor polling to clear machine fault state")
                 bleRepository.restartMonitorPolling()
                 applyAutoAcceptedDetection()
             }

             // Re-read params after potential auto-accept update
             val params = coordinator._workoutParameters.value

             val exerciseName = params.selectedExerciseId?.let { exerciseId ->
                 exerciseRepository.getExerciseById(exerciseId)?.name
             }

             val metrics = coordinator.collectedMetrics.value
             Logger.i { "WEIGHT_DEBUG[Session]: At set completion - params.weightPerCableKg=${params.weightPerCableKg} kg" }
             val summary = calculateSetSummaryMetrics(
                 metrics = metrics,
                 repCount = repCount.totalReps,
                 fallbackWeightKg = params.weightPerCableKg,
                 configuredWeightKgPerCable = params.weightPerCableKg,
                 isEchoMode = params.isEchoMode,
                 warmupRepsCount = repCount.warmupReps,
                 workingRepsCount = repCount.workingReps,
                 warmupCompleteTimeMs = coordinator.warmupCompleteTimeMs
             )

             // Issue #252: Exclude warmup time from session duration
             val effectiveStart = if (coordinator.warmupCompleteTimeMs > 0L) coordinator.warmupCompleteTimeMs else coordinator.workoutStartTime
             val session = WorkoutSession(
                 timestamp = coordinator.workoutStartTime,
                 mode = params.programMode.displayName,
                 reps = params.reps,
                 weightPerCableKg = params.weightPerCableKg,
                 totalReps = repCount.totalReps,
                 workingReps = repCount.workingReps,
                 warmupReps = repCount.warmupReps,
                 duration = currentTimeMillis() - effectiveStart,
                 isJustLift = isJustLift,
                 exerciseId = params.selectedExerciseId,
                 exerciseName = exerciseName,
                 routineSessionId = coordinator.currentRoutineSessionId,
                 routineName = coordinator.currentRoutineName,
                 routineId = coordinator.currentRoutineId,
                 peakForceConcentricA = summary.peakForceConcentricA,
                 peakForceConcentricB = summary.peakForceConcentricB,
                 peakForceEccentricA = summary.peakForceEccentricA,
                 peakForceEccentricB = summary.peakForceEccentricB,
                 avgForceConcentricA = summary.avgForceConcentricA,
                 avgForceConcentricB = summary.avgForceConcentricB,
                 avgForceEccentricA = summary.avgForceEccentricA,
                 avgForceEccentricB = summary.avgForceEccentricB,
                 heaviestLiftKg = summary.heaviestLiftKgPerCable,
                 totalVolumeKg = summary.totalVolumeKg,
                 cableCount = summary.cableCount,
                 estimatedCalories = summary.estimatedCalories,
                 warmupAvgWeightKg = if (params.isEchoMode) summary.warmupAvgWeightKg else null,
                 workingAvgWeightKg = if (params.isEchoMode) summary.workingAvgWeightKg else null,
                 burnoutAvgWeightKg = if (params.isEchoMode) summary.burnoutAvgWeightKg else null,
                 peakWeightKg = if (params.isEchoMode) summary.peakWeightKg else null,
                 rpe = coordinator._currentSetRpe.value,
                 // C4: profileId was missing from manual-stop path — matches saveWorkoutSession() pattern
                 profileId = userProfileRepository.activeProfile.value?.id ?: "default"
             )
             workoutRepository.saveSession(session)

             var completedSetId: String? = null
             if (params.selectedExerciseId != null && repCount.workingReps > 0) {
                 val setIndex = coordinator._currentSetIndex.value
                 val setId = generateUUID()
                 completedSetId = setId
                 val matchedPlannedSetId = findPlannedSetId(setIndex)
                 val completedSet = CompletedSet(
                     id = setId,
                     sessionId = session.id,
                     plannedSetId = matchedPlannedSetId,
                     setNumber = setIndex,
                     setType = if (params.isAMRAP) SetType.AMRAP else SetType.STANDARD,
                     actualReps = repCount.workingReps,
                     actualWeightKg = params.weightPerCableKg,
                     loggedRpe = coordinator._currentSetRpe.value,
                     isPr = false,
                     completedAt = currentTimeMillis()
                 )
                 completedSetRepository.saveCompletedSet(completedSet)
                 Logger.d("Saved CompletedSet (manual stop): set #$setIndex, ${repCount.workingReps} reps${if (matchedPlannedSetId != null) " (linked to PlannedSet)" else ""}")
             }

             val hasPR = gamificationManager.processPostSaveEvents(
                 exerciseId = params.selectedExerciseId,
                 workingReps = repCount.workingReps,
                 achievedWeightKg = summary.heaviestLiftKgPerCable,
                 volumeWeightKg = params.weightPerCableKg,
                 programMode = params.programMode,
                 isJustLift = isJustLift,
                 isEchoMode = params.isEchoMode,
                 peakConcentricForceKg = maxOf(summary.peakForceConcentricA, summary.peakForceConcentricB),
                 peakEccentricForceKg = maxOf(summary.peakForceEccentricA, summary.peakForceEccentricB),
                 profileId = userProfileRepository.activeProfile.value?.id ?: "default"
             )

             if (hasPR && completedSetId != null) {
                 completedSetRepository.markAsPr(completedSetId)
                 Logger.d("Marked CompletedSet $completedSetId as PR (manual stop)")
             }

             scope.launch {
                 syncTriggerManager?.onWorkoutCompleted()
             }

             if (isJustLift) {
                 saveJustLiftDefaultsFromWorkout()
                 // Reset detection for the next Just Lift set
                 detectionManager?.resetForNewSet()
                 coordinator._workoutParameters.update { p ->
                     p.copy(selectedExerciseId = null)
                 }
             } else if (isSingleExerciseMode(coordinator)) {
                 saveSingleExerciseDefaultsFromWorkout()
             }

             if (shouldExitToIdle) {
                 coordinator._workoutState.value = WorkoutState.Idle
                 coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
                 coordinator._loadedRoutine.value = null
                 coordinator.routineStartTime = 0
             } else {
                 coordinator._workoutState.value = summary
             }
        }
    }

    fun stopAndReturnToSetReady() {
        // C1: Atomic compareAndSet prevents TOCTOU race
        if (!coordinator.stopWorkoutInProgress.compareAndSet(expect = false, update = true)) return

        coordinator.workoutJob?.cancel()
        coordinator.workoutJob = null
        coordinator.restTimerJob?.cancel()
        coordinator.restTimerJob = null
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null

        scope.launch {
            try {
                bleRepository.stopWorkout()

                repCounter.reset()
                coordinator._repCount.value = RepCount()
                coordinator._repRanges.value = null
                coordinator.warmupCompleteTimeMs = 0
                resetAutoStopState()
                coordinator._workoutState.value = WorkoutState.Idle

                val routine = coordinator._loadedRoutine.value
                if (routine != null) {
                    flowDelegate?.enterSetReady(coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
                }

                Logger.d { "stopAndReturnToSetReady: Reset to SetReady for exercise=${coordinator._currentExerciseIndex.value}, set=${coordinator._currentSetIndex.value}" }
            } finally {
                coordinator.stopWorkoutInProgress.value = false
            }
        }
    }

    fun stopAndSkipCurrentExercise() {
        // C1: Atomic compareAndSet prevents TOCTOU race
        if (!coordinator.stopWorkoutInProgress.compareAndSet(expect = false, update = true)) return

        val skippedExerciseIndex = coordinator._currentExerciseIndex.value
        val skippedSetIndex = coordinator._currentSetIndex.value

        coordinator.workoutJob?.cancel()
        coordinator.workoutJob = null
        coordinator.restTimerJob?.cancel()
        coordinator.restTimerJob = null
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null

        scope.launch {
            try {
                bleRepository.stopWorkout()

                repCounter.reset()
                coordinator._repCount.value = RepCount()
                coordinator._repRanges.value = null
                coordinator.warmupCompleteTimeMs = 0
                resetAutoStopState()
                coordinator._workoutState.value = WorkoutState.Idle

                val routine = coordinator._loadedRoutine.value
                if (routine != null) {
                    val movedToNextStep = flowDelegate?.skipCurrentExerciseAndEnterNextStep() == true
                    if (!movedToNextStep) {
                        flowDelegate?.showRoutineComplete()
                    }
                }

                Logger.d {
                    "stopAndSkipCurrentExercise: Skipped exercise=$skippedExerciseIndex, set=$skippedSetIndex"
                }
            } finally {
                coordinator.stopWorkoutInProgress.value = false
            }
        }
    }

    fun pauseWorkout() {
        if (coordinator._workoutState.value is WorkoutState.Active) {
            // Send BLE stop BEFORE cancelling collection jobs.
            // The machine must be stopped first; cancelling collection without stopping
            // leaves the machine running unmonitored (project memory: mid-set BLE faults).
            scope.launch {
                try {
                    bleRepository.stopWorkout()
                    Logger.d { "ActiveSessionEngine: BLE stop sent before pause" }
                } catch (e: Exception) {
                    Logger.e(e) { "ActiveSessionEngine: Failed to send BLE stop during pause: ${e.message}" }
                }
            }

            coordinator.monitorDataCollectionJob?.cancel()
            coordinator.repEventsCollectionJob?.cancel()

            coordinator._workoutState.value = WorkoutState.Paused
            Logger.d { "ActiveSessionEngine: Workout paused, collection jobs cancelled" }
        }
    }

    fun resumeWorkout() {
        if (coordinator._workoutState.value is WorkoutState.Paused) {
            coordinator._workoutState.value = WorkoutState.Active

            restartCollectionJobs()
            Logger.d { "ActiveSessionEngine: Workout resumed, collection jobs restarted" }
        }
    }

    private fun restartCollectionJobs() {
        coordinator.monitorDataCollectionJob = scope.launch {
            Logger.d("ActiveSessionEngine") { "Restarting global metricsFlow collection after resume..." }
            bleRepository.metricsFlow
                .catch { e -> Logger.e(e) { "metricsFlow collector error (restart)" } }
                .collect { metric ->
                    coordinator._currentMetric.value = metric
                    handleMonitorMetric(metric)
                }
        }

        coordinator.repEventsCollectionJob = scope.launch {
            bleRepository.repEvents
                .catch { e -> Logger.e(e) { "repEvents collector error (restart)" } }
                .collect { notification ->
                val state = coordinator._workoutState.value
                if (state is WorkoutState.Active) {
                    handleRepNotification(notification)
                }
            }
        }
    }

    // ===== Session Persistence =====

    /**
     * Look up the PlannedSet ID for the current routine exercise and set index.
     */
    private suspend fun findPlannedSetId(setIndex: Int): String? {
        val routineExercise = flowDelegate?.getCurrentExercise() ?: return null
        val plannedSets = completedSetRepository.getPlannedSets(routineExercise.id)
        return plannedSets.find { it.setNumber == setIndex }?.id
    }

    /**
     * Save workout session to database and check for personal records.
     */
    private suspend fun saveWorkoutSession() {
        val sessionId = coordinator.currentSessionId ?: return
        val params = coordinator._workoutParameters.value
        val warmup = coordinator._repCount.value.warmupReps
        val working = coordinator._repCount.value.workingReps
        // Issue #252: Exclude warmup time from session duration
        val effectiveStart = if (coordinator.warmupCompleteTimeMs > 0L) coordinator.warmupCompleteTimeMs else coordinator.workoutStartTime
        val duration = currentTimeMillis() - effectiveStart

        val metricsSnapshot = coordinator.collectedMetrics.value

        val exerciseName = params.selectedExerciseId?.let { exerciseId ->
            exerciseRepository.getExerciseById(exerciseId)?.name
        }

        val summary = calculateSetSummaryMetrics(
            metrics = metricsSnapshot,
            repCount = working,
            fallbackWeightKg = params.weightPerCableKg,
            configuredWeightKgPerCable = params.weightPerCableKg,
            isEchoMode = params.isEchoMode,
            warmupRepsCount = warmup,
            workingRepsCount = working,
            warmupCompleteTimeMs = coordinator.warmupCompleteTimeMs
        )

        // Capture biomechanics summary for WorkoutSession fields.
        // Safe to call here: runs BEFORE biomechanicsEngine.reset() in handleSetCompletion.
        // getSetSummary() is read-only/idempotent.
        val bioSummary = coordinator.biomechanicsEngine.getSetSummary()

        val session = WorkoutSession(
            id = sessionId,
            timestamp = coordinator.workoutStartTime,
            mode = params.programMode.displayName,
            reps = params.reps,
            weightPerCableKg = params.weightPerCableKg,
            progressionKg = params.progressionRegressionKg,
            duration = duration,
            totalReps = working,
            warmupReps = warmup,
            workingReps = working,
            isJustLift = params.isJustLift,
            stopAtTop = params.stopAtTop,
            exerciseId = params.selectedExerciseId,
            exerciseName = exerciseName,
            routineSessionId = coordinator.currentRoutineSessionId,
            routineName = coordinator.currentRoutineName,
            routineId = coordinator.currentRoutineId,
            peakForceConcentricA = summary.peakForceConcentricA,
            peakForceConcentricB = summary.peakForceConcentricB,
            peakForceEccentricA = summary.peakForceEccentricA,
            peakForceEccentricB = summary.peakForceEccentricB,
            avgForceConcentricA = summary.avgForceConcentricA,
            avgForceConcentricB = summary.avgForceConcentricB,
            avgForceEccentricA = summary.avgForceEccentricA,
            avgForceEccentricB = summary.avgForceEccentricB,
            heaviestLiftKg = summary.heaviestLiftKgPerCable,
            totalVolumeKg = summary.totalVolumeKg,
            cableCount = summary.cableCount,
            estimatedCalories = summary.estimatedCalories,
            warmupAvgWeightKg = if (params.isEchoMode) summary.warmupAvgWeightKg else null,
            workingAvgWeightKg = if (params.isEchoMode) summary.workingAvgWeightKg else null,
            burnoutAvgWeightKg = if (params.isEchoMode) summary.burnoutAvgWeightKg else null,
            peakWeightKg = if (params.isEchoMode) summary.peakWeightKg else null,
            rpe = coordinator._currentSetRpe.value,
            // Biomechanics summary (Phase 13 - captured for all tiers)
            avgMcvMmS = bioSummary?.avgMcvMmS,
            avgAsymmetryPercent = bioSummary?.avgAsymmetryPercent,
            totalVelocityLossPercent = bioSummary?.totalVelocityLossPercent,
            dominantSide = bioSummary?.dominantSide,
            strengthProfile = bioSummary?.strengthProfile?.name,
            profileId = userProfileRepository.activeProfile.value?.id ?: "default"
        )

        workoutRepository.saveSession(session)

        // Fire-and-forget health push: auto-push to Health Connect (Android) or HealthKit (iOS)
        // after the session is persisted. Failure is non-fatal and never blocks workout completion.
        if (healthIntegration != null && externalActivityRepository != null) {
            val profileId = session.profileId
            scope.launch {
                try {
                    val provider = if (getPlatform().name.startsWith("iOS")) {
                        IntegrationProvider.APPLE_HEALTH
                    } else {
                        IntegrationProvider.GOOGLE_HEALTH
                    }
                    val status = externalActivityRepository.getIntegrationStatus(provider, profileId).first()
                    if (status?.status == ConnectionStatus.CONNECTED) {
                        healthIntegration.writeWorkout(session)
                        Logger.i("ActiveSessionEngine") { "Auto-pushed workout to ${provider.displayName}" }
                    }
                } catch (e: Exception) {
                    Logger.w("ActiveSessionEngine") { "Health auto-push failed (non-fatal): ${e.message}" }
                }
            }
        }

        if (metricsSnapshot.isNotEmpty()) {
            workoutRepository.saveMetrics(sessionId, metricsSnapshot)
        }

        Logger.d("Saved workout session: $sessionId with ${metricsSnapshot.size} metrics")

        var completedSetId: String? = null
        if (params.selectedExerciseId != null && working > 0) {
            val setIndex = coordinator._currentSetIndex.value
            val setId = generateUUID()
            completedSetId = setId
            val matchedPlannedSetId = findPlannedSetId(setIndex)
            val completedSet = CompletedSet(
                id = setId,
                sessionId = sessionId,
                plannedSetId = matchedPlannedSetId,
                setNumber = setIndex,
                setType = if (params.isAMRAP) SetType.AMRAP else SetType.STANDARD,
                actualReps = working,
                actualWeightKg = params.weightPerCableKg,
                loggedRpe = coordinator._currentSetRpe.value,
                isPr = false,
                completedAt = currentTimeMillis()
            )
            completedSetRepository.saveCompletedSet(completedSet)
            Logger.d("Saved CompletedSet: set #$setIndex, ${working} reps @ ${params.weightPerCableKg}kg${if (matchedPlannedSetId != null) " (linked to PlannedSet)" else ""}")
        }

        val hasPR = gamificationManager.processPostSaveEvents(
            exerciseId = params.selectedExerciseId,
            workingReps = working,
            achievedWeightKg = summary.heaviestLiftKgPerCable,
            volumeWeightKg = params.weightPerCableKg,
            programMode = params.programMode,
            isJustLift = params.isJustLift,
            isEchoMode = params.isEchoMode,
            peakConcentricForceKg = maxOf(summary.peakForceConcentricA, summary.peakForceConcentricB),
            peakEccentricForceKg = maxOf(summary.peakForceEccentricA, summary.peakForceEccentricB),
            profileId = userProfileRepository.activeProfile.value?.id ?: "default"
        )

        if (hasPR && completedSetId != null) {
            completedSetRepository.markAsPr(completedSetId)
            Logger.d("Marked CompletedSet $completedSetId as PR")
        }

        // Sync trigger AFTER all persistence (session, metrics, CompletedSet, PR marking)
        // so the push sends complete data. Previously fired before saveCompletedSet/markAsPr.
        scope.launch {
            syncTriggerManager?.onWorkoutCompleted()
        }

        // Per-session auto-backup AFTER all persistence (including CompletedSet and PR).
        // Fire-and-forget, never blocks the save flow.
        if (preferencesManager.preferencesFlow.value.autoBackupEnabled && dataBackupManager != null) {
            scope.launch {
                dataBackupManager.exportSession(sessionId)
                    .onFailure { e -> Logger.w(e) { "Auto-backup failed for session $sessionId" } }
            }
        }

        if (params.isJustLift) {
            saveJustLiftDefaultsFromWorkout()
        } else if (isSingleExerciseMode(coordinator)) {
            saveSingleExerciseDefaultsFromWorkout()
        }

        updateCycleProgressIfNeeded()
    }

    // ===== Set Completion (cross-cutting) =====

    /**
     * Handle automatic set completion (when rep target is reached via auto-stop).
     * Phase A: Stop BLE, save session, emit haptics, show summary.
     * Phase B: Rest timer, navigation advancement (delegated back to DWSM via startRestTimer).
     */
    internal fun handleSetCompletion() {
        // 1.2: Atomic compareAndSet prevents duplicate set completion across dispatchers
        if (!coordinator.setCompletionInProgress.compareAndSet(expect = false, update = true)) {
            Logger.d("handleSetCompletion: already in progress - ignoring")
            return
        }
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null

        scope.launch {
            val params = coordinator._workoutParameters.value
            val isJustLift = params.isJustLift

            Logger.d("handleSetCompletion: isJustLift=$isJustLift")

            coordinator.isCurrentWorkoutTimed = false
            coordinator.isCurrentTimedCableExercise = false
            coordinator._isCurrentExerciseBodyweight.value = false

            val currentExercise = coordinator._loadedRoutine.value?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
            val wasBodyweight = isBodyweightExercise(currentExercise)

            if (wasBodyweight) {
                coordinator.bodyweightSetsCompletedInRoutine++
                Logger.d("ActiveSessionEngine") { "Bodyweight set #${coordinator.bodyweightSetsCompletedInRoutine} completed (exercise=${currentExercise?.exercise?.name})" }
            }

            coordinator.previousExerciseWasBodyweight = wasBodyweight
            Logger.d { "Issue #222 v8: Set coordinator.previousExerciseWasBodyweight=$wasBodyweight" }

            if (!wasBodyweight) {
                bleRepository.stopWorkout()
                Logger.d("handleSetCompletion: Called stopWorkout() (auto-complete)")
            } else {
                Logger.d("handleSetCompletion: Skipping BLE stop (bodyweight exercise)")
            }

            // ===== Phase 35C: Variable Warm-up Set Fast Path =====
            // If we just completed a warm-up set, skip session save/summary/rest
            // and immediately advance to the next warm-up set or working phase.
            val warmupSetIdx = coordinator._currentWarmupSetIndex.value
            if (warmupSetIdx >= 0 && currentExercise != null) {
                val nextWarmupIdx = warmupSetIdx + 1
                if (nextWarmupIdx < currentExercise.warmupSets.size) {
                    // More warm-up sets remaining
                    Logger.d { "Phase 35C: Warm-up set ${warmupSetIdx + 1}/${currentExercise.warmupSets.size} complete, advancing to warm-up ${nextWarmupIdx + 1}" }
                    coordinator._currentWarmupSetIndex.value = nextWarmupIdx
                    coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)
                    // Reset for next warm-up set
                    repCounter.resetCountsOnly()
                    resetAutoStopState()
                    coordinator.setCompletionInProgress.value = false
                    coordinator.stopWorkoutInProgress.value = false
                    // Restore working weight in params before startWorkout overrides it
                    val workingWeight = currentExercise.setWeightsPerCableKg.getOrNull(0)
                        ?: currentExercise.weightPerCableKg
                    coordinator._workoutParameters.update { p ->
                        p.copy(weightPerCableKg = workingWeight, reps = currentExercise.setReps.firstOrNull() ?: 10)
                    }
                    startWorkout(skipCountdown = true)
                    return@launch
                } else {
                    // All warm-up sets done — transition to working phase
                    Logger.d { "Phase 35C: All ${currentExercise.warmupSets.size} warm-up sets complete, transitioning to working sets" }
                    coordinator._currentWarmupSetIndex.value = -1
                    coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)
                    // Reset for first working set
                    repCounter.reset()
                    resetAutoStopState()
                    coordinator.setCompletionInProgress.value = false
                    coordinator.stopWorkoutInProgress.value = false
                    // Restore working weight/reps
                    val workingWeight = currentExercise.setWeightsPerCableKg.getOrNull(0)
                        ?: currentExercise.weightPerCableKg
                    coordinator._workoutParameters.update { p ->
                        p.copy(
                            weightPerCableKg = workingWeight,
                            reps = currentExercise.setReps.firstOrNull() ?: 10,
                            warmupReps = Constants.DEFAULT_WARMUP_REPS
                        )
                    }
                    startWorkout(skipCountdown = true)
                    return@launch
                }
            }

            coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)

            // Just Lift: apply auto-accepted exercise detection before saving session
            // so the exerciseId is populated in the persisted WorkoutSession
            if (isJustLift) {
                applyAutoAcceptedDetection()
            }

            saveWorkoutSession()

            // Persist per-rep metric data (GATE-04: captured for all tiers)
            val sessionId = coordinator.currentSessionId
            val repMetricsToSave = coordinator.setRepMetrics.value
            if (sessionId != null && repMetricsToSave.isNotEmpty()) {
                try {
                    repMetricRepository.saveRepMetrics(sessionId, repMetricsToSave)
                    Logger.d { "Persisted ${repMetricsToSave.size} rep metrics for session $sessionId" }
                } catch (e: Exception) {
                    Logger.e(e) { "Failed to persist rep metrics for session $sessionId" }
                }
            }
            coordinator.setRepMetrics.value = emptyList()

            // Persist per-rep biomechanics data (GATE-04: captured for all tiers)
            val biomechanicsSummary = coordinator.biomechanicsEngine.getSetSummary()
            if (sessionId != null && biomechanicsSummary != null) {
                try {
                    biomechanicsRepository.saveRepBiomechanics(
                        sessionId,
                        biomechanicsSummary.repResults
                    )
                    Logger.d { "Persisted ${biomechanicsSummary.repResults.size} rep biomechanics for session $sessionId" }
                } catch (e: Exception) {
                    Logger.e(e) { "Failed to persist rep biomechanics for session $sessionId" }
                }
            }

            // Capture rep quality summary BEFORE reset (null if no reps were scored)
            val qualitySummary = try {
                coordinator.repQualityScorer.getSetSummary()
            } catch (_: IllegalStateException) {
                // No reps scored (e.g., bodyweight exercise, scorer not active)
                null
            }

            // Reset rep quality scorer for next set
            coordinator.repQualityScorer.reset()
            coordinator._latestRepQuality.value = null

            // Reuse biomechanics summary captured above for SetSummary display (no second engine call needed)
            // biomechanicsSummary is already captured and engine hasn't been reset yet

            // Reset biomechanics engine and rep boundary timestamps for next set
            coordinator.biomechanicsEngine.reset()
            coordinator.repBoundaryTimestamps.value = emptyList()

            val completedReps = coordinator._repCount.value.workingReps
            val warmupReps = coordinator._repCount.value.warmupReps
            val metricsList = coordinator.collectedMetrics.value

            val baseSummary = calculateSetSummaryMetrics(
                metrics = metricsList,
                repCount = completedReps,
                fallbackWeightKg = params.weightPerCableKg,
                configuredWeightKgPerCable = params.weightPerCableKg,
                isEchoMode = params.isEchoMode,
                warmupRepsCount = warmupReps,
                workingRepsCount = completedReps
            )

            // Attach quality and biomechanics summaries to the set summary
            val summary = baseSummary.copy(
                qualitySummary = qualitySummary,
                biomechanicsSummary = biomechanicsSummary
            )

            // Process quality event for Form Master badge tracking
            qualitySummary?.let { qs ->
                gamificationManager.processSetQualityEvent(qs.averageScore, userProfileRepository.activeProfile.value?.id ?: "default")
            }

            Logger.d("Set summary: heaviest=${summary.heaviestLiftKgPerCable}kg, reps=$completedReps, duration=${summary.durationMs}ms")

            val summaryCountdownSeconds = settingsManager.userPreferences.value.summaryCountdownSeconds
            val skipSummary = summaryCountdownSeconds < 0
            val summaryDelayMs = if (skipSummary) 0L else summaryCountdownSeconds * 1000L

            val skipSummaryForBodyweight = wasBodyweight
            val effectiveSkipSummary = skipSummary || skipSummaryForBodyweight

            Logger.d("handleSetCompletion: summaryCountdownSeconds=$summaryCountdownSeconds, skipSummary=$skipSummary, wasBodyweight=$wasBodyweight, effectiveSkipSummary=$effectiveSkipSummary, isJustLift=$isJustLift, isAMRAP=${params.isAMRAP}")

            if (!effectiveSkipSummary) {
                Logger.d("handleSetCompletion: Setting state to SetSummary (effectiveSkipSummary=false)")
                coordinator._workoutState.value = summary
            } else {
                Logger.d("handleSetCompletion: Skipping SetSummary state (effectiveSkipSummary=true, wasBodyweight=$wasBodyweight)")
            }

            if (isJustLift) {
                Logger.d("Just Lift: IMMEDIATE reset for next set (while showing summary)")

                repCounter.reset()
                resetAutoStopState()

                // Reset detection state for the next Just Lift set so detection
                // can re-trigger fresh (each set may be a different exercise)
                detectionManager?.resetForNewSet()
                // Clear exercise attribution so detection has a clean slate
                coordinator._workoutParameters.update { p ->
                    p.copy(selectedExerciseId = null)
                }

                bleRepository.restartMonitorPolling()

                enableHandleDetection()
                bleRepository.enableJustLiftWaitingMode()

                val justLiftRestSeconds = params.justLiftRestSeconds
                Logger.d("Just Lift: Machine armed & ready. summaryCountdownSeconds=$summaryCountdownSeconds, skipSummary=$skipSummary, restSeconds=$justLiftRestSeconds")

                if (skipSummary) {
                    Logger.d("Just Lift: Summary OFF - skipping summary")
                    resetForNewWorkout()
                    coordinator._workoutState.value = WorkoutState.Idle
                    if (justLiftRestSeconds > 0) {
                        startJustLiftEggTimer(justLiftRestSeconds)
                    }
                } else if (summaryDelayMs > 0) {
                    delay(summaryDelayMs)

                    if (coordinator._workoutState.value is WorkoutState.SetSummary) {
                        Logger.d("Just Lift: Summary complete, transitioning to Idle")
                        resetForNewWorkout()
                        coordinator._workoutState.value = WorkoutState.Idle
                        if (justLiftRestSeconds > 0) {
                            Logger.d("Just Lift: Starting egg timer ($justLiftRestSeconds s)")
                            startJustLiftEggTimer(justLiftRestSeconds)
                        }
                    } else {
                        Logger.d("Just Lift: Summary interrupted by user action (state is ${coordinator._workoutState.value})")
                    }
                } else {
                    Logger.d("Just Lift: Summary Unlimited - waiting for user action")
                }
            } else if (params.isAMRAP) {
                Logger.d("AMRAP: Auto-advancing to rest timer")

                repCounter.reset()
                resetAutoStopState()

                bleRepository.restartMonitorPolling()

                enableHandleDetection()
                bleRepository.enableJustLiftWaitingMode()

                Logger.d("AMRAP: Machine armed & ready. summaryCountdownSeconds=$summaryCountdownSeconds, skipSummary=$skipSummary")

                if (skipSummary) {
                    Logger.d("AMRAP: Summary OFF - skipping summary, proceeding to rest timer")
                    startRestTimer()
                } else if (summaryDelayMs > 0) {
                    delay(summaryDelayMs)

                    if (coordinator._workoutState.value is WorkoutState.SetSummary) {
                        startRestTimer()
                    }
                } else {
                    Logger.d("AMRAP: Summary Unlimited - waiting for user action")
                }
            } else {
                Logger.d("Routine/SingleExercise mode: skipSummary=$skipSummary, effectiveSkipSummary=$effectiveSkipSummary, wasBodyweight=$wasBodyweight, summaryCountdownSeconds=$summaryCountdownSeconds")
                if (effectiveSkipSummary) {
                    Logger.d("Routine mode: Summary skipped (effectiveSkipSummary=true, wasBodyweight=$wasBodyweight) - calling startRestTimer()")

                    repCounter.reset()
                    resetAutoStopState()

                    Logger.d("Routine mode: Parent-aligned - no polling restart/auto-start during rest")

                    startRestTimer()
                } else {
                    Logger.d("Routine mode: Waiting for UI countdown or user action to proceed from summary")
                }
            }
        }
    }

    // ===== Rest Timer and Flow Control =====

    /**
     * Start the rest timer between sets.
     */
    internal fun startRestTimer() {
        coordinator.restTimerJob?.cancel()

        coordinator.restTimerJob = scope.launch {
            val routine = coordinator._loadedRoutine.value
            val currentExercise = routine?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
            val exerciseId = currentExercise?.exercise?.id ?: coordinator._workoutParameters.value.selectedExerciseId

            val completedSetIndex = coordinator._currentSetIndex.value

            val nextStep = if (routine != null) {
                flowDelegate?.getNextStep(routine, coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
            } else null
            val nextExerciseFromStep = if (nextStep != null && routine != null) {
                routine.exercises.getOrNull(nextStep.first)
            } else null
            val nextSetIdxFromStep = nextStep?.second

            val isAtEndOfSupersetCycle = flowDelegate?.isAtEndOfSupersetCycle() == true
            val isInSupersetTransition = (flowDelegate?.isInSuperset() == true) && !isAtEndOfSupersetCycle
            val isStillInSupersetWorkout = (flowDelegate?.isInSuperset() == true) && nextExerciseFromStep != null &&
                nextExerciseFromStep.supersetId == currentExercise?.supersetId
            // Use quick superset rest only when moving to another exercise inside the same cycle.
            // At the end of a superset cycle (last exercise of the group), use the current exercise's
            // configured rest time so users can recover before starting the next round.
            val useSupersetQuickRest = isInSupersetTransition || (isStillInSupersetWorkout && !isAtEndOfSupersetCycle)
            // Issue #259: When an exercise has perSetRestTime enabled, use its own rest
            // times even within a superset transition, instead of the superset default.
            val restDuration = if (useSupersetQuickRest) {
                if (currentExercise?.perSetRestTime == true) {
                    currentExercise.getRestForSet(completedSetIndex)
                } else {
                    (flowDelegate?.getSupersetRestSeconds() ?: 5).coerceAtLeast(5)
                }
            } else {
                currentExercise?.getRestForSet(completedSetIndex) ?: 90
            }
            val autoplay = settingsManager.autoplayEnabled.value
            val isSingleExercise = isSingleExerciseMode(coordinator)

            Logger.d("startRestTimer: restDuration=$restDuration, autoplay=$autoplay, isSingleExercise=$isSingleExercise, summaryCountdownSeconds=${settingsManager.userPreferences.value.summaryCountdownSeconds}")

            if (restDuration == 0) {
                Logger.d { "Rest duration is 0 - skipping rest timer, advancing immediately (no BLE stop - already sent at set end)" }
                if (isSingleExerciseMode(coordinator)) {
                    advanceToNextSetInSingleExercise()
                } else {
                    startNextSetOrExercise()
                }
                return@launch
            }

            val supersetLabel = if (useSupersetQuickRest) {
                val supersetIds = routine?.supersets?.map { it.id } ?: emptyList()
                val groupIndex = supersetIds.indexOf(currentExercise?.supersetId)
                if (groupIndex >= 0) "Superset ${('A' + groupIndex)}" else "Superset"
            } else null

            val isLastSetOfCurrentExercise = coordinator._currentSetIndex.value >= (currentExercise?.setReps?.size ?: 1) - 1
            val isLastExerciseOverall = flowDelegate?.calculateIsLastExercise(isSingleExercise, currentExercise, routine) ?: false
            val isTransitioningToNextExercise = isLastSetOfCurrentExercise && !isLastExerciseOverall && !isSingleExercise

            val nextExercise = nextExerciseFromStep

            val exerciseForNextSet = nextExerciseFromStep ?: currentExercise
            val nextExerciseIsBodyweight = isBodyweightExercise(exerciseForNextSet)

            if (exerciseForNextSet != null && !nextExerciseIsBodyweight) {
                val nextSetIdx = nextSetIdxFromStep ?: (completedSetIndex + 1)

                val hasNextSet = nextSetIdx < exerciseForNextSet.setReps.size
                if (hasNextSet) {
                    val nextSetReps = exerciseForNextSet.setReps.getOrNull(nextSetIdx)
                    val nextSetWeight = exerciseForNextSet.setWeightsPerCableKg.getOrNull(nextSetIdx)
                        ?: exerciseForNextSet.weightPerCableKg
                    val isNextSetLastSet = nextSetIdx >= exerciseForNextSet.setReps.size - 1
                    val nextIsAMRAP = nextSetReps == null || (exerciseForNextSet.isAMRAP && isNextSetLastSet)

                    coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
                        weightPerCableKg = nextSetWeight,
                        reps = nextSetReps ?: 0,
                        programMode = exerciseForNextSet.programMode,
                        echoLevel = exerciseForNextSet.getEchoLevelForSet(nextSetIdx),
                        eccentricLoad = exerciseForNextSet.eccentricLoad,
                        progressionRegressionKg = exerciseForNextSet.progressionKg,
                        selectedExerciseId = exerciseForNextSet.exercise.id,
                        isAMRAP = nextIsAMRAP,
                        stallDetectionEnabled = exerciseForNextSet.stallDetectionEnabled,
                        warmupReps = if (nextExerciseIsBodyweight) 0 else Constants.DEFAULT_WARMUP_REPS
                    )
                    Logger.d { "startRestTimer: Issue #203 - Updated params for next set: ${exerciseForNextSet.exercise.name}, setIdx=$nextSetIdx, isAMRAP=$nextIsAMRAP, nextSetReps=$nextSetReps" }
                }
            } else if (nextExerciseIsBodyweight) {
                Logger.d { "startRestTimer: Issue #222 - Skipping params update for bodyweight exercise: ${nextExerciseFromStep?.exercise?.name}" }
            }

            val displaySetIndex = nextSetIdxFromStep ?: (coordinator._currentSetIndex.value + 1)
            val displayTotalSets = nextExerciseFromStep?.setReps?.size ?: currentExercise?.setReps?.size ?: 0
            val initialNextName = flowDelegate?.calculateNextExerciseName(isSingleExercise, currentExercise, routine) ?: ""

            // Issue #297, #228: Initialize rest timer control state
            coordinator._restOriginalDuration.value = restDuration
            coordinator._restSecondsRemaining.value = restDuration
            coordinator._isRestPaused.value = false

            // Emit Resting immediately so the UI timer starts without waiting on repository lookups.
            coordinator._workoutState.value = WorkoutState.Resting(
                restSecondsRemaining = restDuration,
                nextExerciseName = initialNextName,
                isLastExercise = isLastExerciseOverall,
                currentSet = displaySetIndex,
                totalSets = displayTotalSets,
                isSupersetTransition = useSupersetQuickRest,
                supersetLabel = supersetLabel
            )

            if (exerciseId != null) {
                launch {
                    val lastWeight = getLastWeightForExercise(exerciseId)
                    val prWeight = getPrWeightForExercise(exerciseId)
                    coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
                        lastUsedWeightKg = lastWeight,
                        prWeightKg = prWeight
                    )
                }
            }

            var lastTickedSecond = -1  // Issue #100: track emitted ticks to fire once per second
            var lastDecrementTime = currentTimeMillis()

            // Issue #297, #228: Tick-based loop that respects pause/extend/reset via StateFlow.
            // Each 100ms tick checks _isRestPaused; when paused, time does not decrement.
            // _restSecondsRemaining is the source of truth — extendRestTime() and resetRestTimer()
            // mutate it directly and the loop picks up the new value on the next tick.
            //
            // In non-autoplay mode, the loop continues at 0 so +30s/Reset remain functional.
            // It exits only when autoplay advances, or skipRest/startNextSet is called externally.
            var hasReachedZero = false
            while (isActive) {
                val remainingSeconds = coordinator._restSecondsRemaining.value

                // Issue #100: Emit countdown tick during last 10 seconds of rest
                if (remainingSeconds in 1..10 && remainingSeconds != lastTickedSecond) {
                    lastTickedSecond = remainingSeconds
                    val prefs = settingsManager.userPreferences.value
                    if (prefs.beepsEnabled && prefs.countdownBeepsEnabled) {
                        coordinator._hapticEvents.emit(HapticEvent.COUNTDOWN_TICK(remainingSeconds))
                    }
                }

                val nextName = flowDelegate?.calculateNextExerciseName(isSingleExercise, currentExercise, routine) ?: ""

                coordinator._workoutState.value = WorkoutState.Resting(
                    restSecondsRemaining = remainingSeconds,
                    nextExerciseName = nextName,
                    isLastExercise = isLastExerciseOverall,
                    currentSet = displaySetIndex,
                    totalSets = displayTotalSets,
                    isSupersetTransition = useSupersetQuickRest,
                    supersetLabel = supersetLabel
                )

                // In autoplay mode, break when timer hits 0 to auto-advance
                if (remainingSeconds <= 0 && autoplay) break
                // Track zero-crossing for beeps reset
                if (remainingSeconds <= 0) {
                    if (!hasReachedZero) {
                        hasReachedZero = true
                    }
                } else {
                    hasReachedZero = false
                }

                delay(100)

                // Decrement once per second, but only when not paused and remaining > 0
                if (remainingSeconds > 0 && !coordinator._isRestPaused.value) {
                    val now = currentTimeMillis()
                    if (now - lastDecrementTime >= 1000L) {
                        coordinator._restSecondsRemaining.value =
                            (coordinator._restSecondsRemaining.value - 1).coerceAtLeast(0)
                        lastDecrementTime = now
                    }
                } else {
                    // While paused (or at zero), keep resetting the decrement anchor so we don't
                    // get a burst of decrements when unpaused or extended.
                    lastDecrementTime = currentTimeMillis()
                }
            }

            // Clean up rest timer control state
            coordinator._isRestPaused.value = false

            if (autoplay) {
                Logger.d("ActiveSessionEngine") { "autoplay rest complete: advancing to next set (no BLE stop - already sent at set end)" }
                if (isSingleExercise) {
                    advanceToNextSetInSingleExercise()
                } else {
                    startNextSetOrExercise()
                }
            }
        }
    }

    /**
     * Start a visual-only "egg timer" for Just Lift rest.
     *
     * Unlike the routine rest timer, this does NOT change WorkoutState — the workout
     * stays in Idle and the auto-start handle-grab detection remains active. The timer
     * is purely informational: it counts down on screen and is canceled when the user
     * picks up the handles (triggering auto-start for the next set).
     *
     * Issue #113: Configurable rest timer for Just Lift mode.
     */
    private fun startJustLiftEggTimer(restSeconds: Int) {
        coordinator.justLiftRestTimerJob?.cancel()
        coordinator._justLiftRestCountdown.value = restSeconds

        coordinator.justLiftRestTimerJob = scope.launch {
            Logger.d("startJustLiftEggTimer: starting $restSeconds s visual countdown")

            var remaining = restSeconds
            while (remaining > 0 && isActive) {
                coordinator._justLiftRestCountdown.value = remaining

                // Beeps in last 10 seconds
                if (remaining in 1..10) {
                    val prefs = settingsManager.userPreferences.value
                    if (prefs.beepsEnabled && prefs.countdownBeepsEnabled) {
                        coordinator._hapticEvents.emit(HapticEvent.COUNTDOWN_TICK(remaining))
                    }
                }

                delay(1000)
                remaining--
            }

            coordinator._justLiftRestCountdown.value = 0
            Logger.d("startJustLiftEggTimer: countdown complete")
        }
    }

    /** Cancel the Just Lift egg timer (called when auto-start fires). */
    internal fun cancelJustLiftEggTimer() {
        coordinator.justLiftRestTimerJob?.cancel()
        coordinator.justLiftRestTimerJob = null
        coordinator._justLiftRestCountdown.value = null
    }

    /**
     * Advance to the next set within a single exercise (non-routine mode).
     */
    private fun advanceToNextSetInSingleExercise() {
        val routine = coordinator._loadedRoutine.value
        if (routine == null) {
            coordinator._workoutState.value = WorkoutState.Completed
            coordinator._currentSetIndex.value = 0
            coordinator._currentExerciseIndex.value = 0
            repCounter.reset()
            resetAutoStopState()
            return
        }
        val currentExercise = routine.exercises.getOrNull(coordinator._currentExerciseIndex.value) ?: return

        if (coordinator._currentSetIndex.value < currentExercise.setReps.size - 1) {
            coordinator._currentSetIndex.value++
            val targetReps = currentExercise.setReps[coordinator._currentSetIndex.value]
            val currentParams = coordinator._workoutParameters.value

            val setWeight = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.weightPerCableKg
            } else {
                currentExercise.setWeightsPerCableKg.getOrNull(coordinator._currentSetIndex.value)
                    ?: currentExercise.weightPerCableKg
            }
            val setReps = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.reps
            } else {
                targetReps ?: 0
            }
            coordinator._userAdjustedWeightDuringRest = false

            val isLastSet = coordinator._currentSetIndex.value >= currentExercise.setReps.size - 1
            val nextIsAMRAP = targetReps == null || (currentExercise.isAMRAP && isLastSet)

            coordinator._workoutParameters.value = currentParams.copy(
                reps = setReps,
                weightPerCableKg = setWeight,
                isAMRAP = nextIsAMRAP,
                stallDetectionEnabled = currentExercise.stallDetectionEnabled,
                progressionRegressionKg = currentExercise.progressionKg
            )
            Logger.d { "advanceToNextSetInSingleExercise: Issue #203 - setIdx=${coordinator._currentSetIndex.value}, isAMRAP=$nextIsAMRAP" }

            repCounter.resetCountsOnly()
            resetAutoStopState()
            startWorkout(skipCountdown = true)
        } else {
            coordinator._workoutState.value = WorkoutState.Completed
            coordinator._loadedRoutine.value = null
            coordinator.routineStartTime = 0
            coordinator._currentSetIndex.value = 0
            coordinator._currentExerciseIndex.value = 0
            repCounter.reset()
            resetAutoStopState()
        }
    }

    /**
     * Start workout or enter SetReady based on autoplay preference.
     */
    private fun startWorkoutOrSetReady() {
        val autoplay = settingsManager.autoplayEnabled.value
        if (autoplay) {
            startWorkout(skipCountdown = true)
        } else {
            flowDelegate?.enterSetReady(coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
        }
    }

    /**
     * Progress to the next set or exercise in a routine.
     */
    private fun startNextSetOrExercise() {
        val currentState = coordinator._workoutState.value
        if (currentState is WorkoutState.Completed) return
        if (currentState !is WorkoutState.Resting &&
            currentState !is WorkoutState.SetSummary &&
            currentState !is WorkoutState.Active) return

        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null

        val routine = coordinator._loadedRoutine.value ?: return

        val nextStep = flowDelegate?.getNextStep(routine, coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)

        Logger.d { "startNextSetOrExercise: current=(${coordinator._currentExerciseIndex.value}, ${coordinator._currentSetIndex.value}), nextStep=$nextStep" }

        if (nextStep != null) {
            val (nextExIdx, nextSetIdx) = nextStep
            val nextExercise = routine.exercises[nextExIdx]

            val isChangingExercise = nextExIdx != coordinator._currentExerciseIndex.value

            coordinator._currentExerciseIndex.value = nextExIdx
            coordinator._currentSetIndex.value = nextSetIdx

            val nextSetReps = nextExercise.setReps.getOrNull(nextSetIdx)
            val currentParams = coordinator._workoutParameters.value

            val nextSetWeight = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.weightPerCableKg
            } else {
                nextExercise.setWeightsPerCableKg.getOrNull(nextSetIdx)
                    ?: nextExercise.weightPerCableKg
            }
            val nextReps = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.reps
            } else {
                nextSetReps ?: 0
            }
            val nextEchoLevel = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.echoLevel
            } else {
                nextExercise.getEchoLevelForSet(nextSetIdx)
            }
            val nextEccentricLoad = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.eccentricLoad
            } else {
                nextExercise.eccentricLoad
            }
            coordinator._userAdjustedWeightDuringRest = false

            val nextIsBodyweight = isBodyweightExercise(nextExercise)

            val isNextSetLastSet = nextSetIdx >= nextExercise.setReps.size - 1
            val nextIsAMRAP = nextSetReps == null || (nextExercise.isAMRAP && isNextSetLastSet)

            coordinator._workoutParameters.value = currentParams.copy(
                weightPerCableKg = nextSetWeight,
                reps = nextReps,
                programMode = nextExercise.programMode,
                echoLevel = nextEchoLevel,
                eccentricLoad = nextEccentricLoad,
                progressionRegressionKg = nextExercise.progressionKg,
                selectedExerciseId = nextExercise.exercise.id,
                isAMRAP = nextIsAMRAP,
                stallDetectionEnabled = nextExercise.stallDetectionEnabled,
                warmupReps = if (nextIsBodyweight) 0 else Constants.DEFAULT_WARMUP_REPS
            )
            Logger.d { "startNextSetOrExercise: Issue #203 - progressionKg=${nextExercise.progressionKg}kg for ${nextExercise.exercise.displayName}, isBodyweight=$nextIsBodyweight, isAMRAP=$nextIsAMRAP" }

            if (isChangingExercise) {
                repCounter.reset()
                // Phase 35C: Initialize warm-up phase for new exercise with warmupSets
                if (nextSetIdx == 0 && nextExercise.warmupSets.isNotEmpty() && !nextIsBodyweight) {
                    coordinator._currentWarmupSetIndex.value = 0
                    coordinator._totalWarmupSets.value = nextExercise.warmupSets.size
                    Logger.d { "Phase 35C: Entering warm-up phase for ${nextExercise.exercise.displayName}: ${nextExercise.warmupSets.size} warm-up sets" }
                } else {
                    coordinator._currentWarmupSetIndex.value = -1
                    coordinator._totalWarmupSets.value = 0
                }
            } else {
                repCounter.resetCountsOnly()
            }
            resetAutoStopState()
            startWorkoutOrSetReady()
        } else {
            Logger.d { "startNextSetOrExercise: No more steps - showing routine complete" }
            flowDelegate?.showRoutineComplete()
            coordinator._workoutState.value = WorkoutState.Idle
            coordinator._currentSetIndex.value = 0
            coordinator._currentExerciseIndex.value = 0
            coordinator.currentRoutineSessionId = null
            coordinator.currentRoutineName = null
            coordinator.currentRoutineId = null
            repCounter.reset()
            resetAutoStopState()
        }
    }

    fun skipRest() {
        if (coordinator._workoutState.value is WorkoutState.Resting) {
            coordinator._isRestPaused.value = false
            coordinator.restTimerJob?.cancel()
            coordinator.restTimerJob = null

            val isJustLift = coordinator._workoutParameters.value.isJustLift
            Logger.d("ActiveSessionEngine") { "skipRest: isJustLift=$isJustLift, advancing (no BLE stop - already sent at set end)" }

            if (isJustLift) {
                // Just Lift rest returns to Idle (ready for next auto-start set), not Completed
                coordinator._workoutState.value = WorkoutState.Idle
            } else if (isSingleExerciseMode(coordinator)) {
                advanceToNextSetInSingleExercise()
            } else {
                startNextSetOrExercise()
            }
        }
    }

    // ===== Rest Timer Controls (Issue #297, #228) =====

    /**
     * Extend the rest timer by the given number of seconds.
     * Updates both the original duration (for reset) and the current remaining time.
     */
    fun extendRestTime(seconds: Int) {
        if (coordinator._workoutState.value !is WorkoutState.Resting) return
        coordinator._restOriginalDuration.value += seconds
        coordinator._restSecondsRemaining.value += seconds
        Logger.d("ActiveSessionEngine") { "extendRestTime: +${seconds}s, remaining=${coordinator._restSecondsRemaining.value}, original=${coordinator._restOriginalDuration.value}" }
    }

    /**
     * Toggle rest timer pause/resume.
     * When paused, the countdown loop skips decrementing. Beeps also stop.
     */
    fun toggleRestPause() {
        if (coordinator._workoutState.value !is WorkoutState.Resting) return
        val newPaused = !coordinator._isRestPaused.value
        coordinator._isRestPaused.value = newPaused
        Logger.d("ActiveSessionEngine") { "toggleRestPause: paused=$newPaused" }
    }

    /**
     * Reset the rest timer to its original duration (including any extensions).
     * Also unpauses if currently paused.
     */
    fun resetRestTimer() {
        if (coordinator._workoutState.value !is WorkoutState.Resting) return
        coordinator._isRestPaused.value = false
        coordinator._restSecondsRemaining.value = coordinator._restOriginalDuration.value
        Logger.d("ActiveSessionEngine") { "resetRestTimer: reset to ${coordinator._restOriginalDuration.value}s" }
    }

    fun startNextSet() {
        val state = coordinator._workoutState.value
        if (state is WorkoutState.Resting && state.restSecondsRemaining == 0) {
            Logger.d("ActiveSessionEngine") { "startNextSet: advancing (no BLE stop - already sent at set end)" }

            if (isSingleExerciseMode(coordinator)) {
                advanceToNextSetInSingleExercise()
            } else {
                startNextSetOrExercise()
            }
        }
    }

    // ===== Auto-Start Timer =====

    private fun startAutoStartTimer() {
        if (coordinator.autoStartJob != null) return
        val currentState = coordinator._workoutState.value
        if (currentState !is WorkoutState.Idle && currentState !is WorkoutState.SetSummary) {
            return
        }

        coordinator.autoStartJob = scope.launch {
            val countdownSeconds = settingsManager.userPreferences.value.autoStartCountdownSeconds
            for (i in countdownSeconds downTo 1) {
                coordinator._autoStartCountdown.value = i
                delay(1000)
            }
            coordinator._autoStartCountdown.value = null

            if (coordinator.autoStartJob?.isActive != true) {
                Logger.d("Auto-start aborted: job cancelled during countdown")
                return@launch
            }

            val currentHandle = bleRepository.handleState.value
            if (currentHandle != HandleState.Grabbed && currentHandle != HandleState.Moving) {
                Logger.d("Auto-start aborted: handles no longer grabbed (state=$currentHandle)")
                return@launch
            }

            val params = coordinator._workoutParameters.value
            if (!params.useAutoStart) {
                Logger.d("Auto-start aborted: autoStart disabled in parameters")
                return@launch
            }

            val state = coordinator._workoutState.value
            if (state !is WorkoutState.Idle && state !is WorkoutState.SetSummary) {
                Logger.d("Auto-start aborted: workout state changed (state=$state)")
                return@launch
            }

            // Cancel Just Lift egg timer when user grabs handles to start next set
            if (params.isJustLift) {
                cancelJustLiftEggTimer()
            }

            Logger.d { "Issue221: Auto-start timer complete - params.isJustLift=${params.isJustLift}, params.useAutoStart=${params.useAutoStart}" }
            Logger.d { "Issue221: Starting workout with isJustLiftMode=true (auto-start implies Just Lift mode)" }
            startWorkout(skipCountdown = true, isJustLiftMode = true)
        }
    }

    private fun cancelAutoStartTimer() {
        coordinator.autoStartJob?.cancel()
        coordinator.autoStartJob = null
        coordinator._autoStartCountdown.value = null
    }

    // ===== Cleanup =====

    fun cleanup() {
        coordinator.monitorDataCollectionJob?.cancel()
        coordinator.autoStartJob?.cancel()
        coordinator.restTimerJob?.cancel()
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.repEventsCollectionJob?.cancel()
        coordinator.workoutJob?.cancel()
    }
}
