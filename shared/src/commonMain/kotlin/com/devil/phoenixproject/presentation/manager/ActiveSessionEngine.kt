package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.util.BlePacketFactory
import com.devil.phoenixproject.util.Constants
import com.devil.phoenixproject.util.KmpUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope
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

    // ===== Init Block: Workout-Related Collectors (moved from DWSM) =====

    init {
        // Collectors #3-8 (workout lifecycle collectors).
        // Collectors #1-2 are in RoutineFlowManager (constructed before ActiveSessionEngine).

        // #3: Hook up RepCounter
        repCounter.onRepEvent = { event ->
             scope.launch {
                 when (event.type) {
                     RepType.WORKING_COMPLETED -> {
                         // Check if audio rep count is enabled and rep is within announcement range (1-25)
                         // Use event.workingCount (not coordinator._repCount.value) - the state hasn't been updated yet
                         val prefs = settingsManager.userPreferences.value
                         if (prefs.audioRepCountEnabled && event.workingCount in 1..25) {
                             coordinator._hapticEvents.emit(HapticEvent.REP_COUNT_ANNOUNCED(event.workingCount))
                         } else {
                             coordinator._hapticEvents.emit(HapticEvent.REP_COMPLETED)
                         }
                     }
                     RepType.WARMUP_COMPLETED -> coordinator._hapticEvents.emit(HapticEvent.REP_COMPLETED)
                     RepType.WARMUP_COMPLETE -> coordinator._hapticEvents.emit(HapticEvent.WARMUP_COMPLETE)
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
                     else -> {}
                 }
             }
        }

        // #4: Handle activity state collector for auto-start functionality
        scope.launch {
            bleRepository.handleState.collect { activityState ->
                val params = coordinator._workoutParameters.value
                val currentState = coordinator._workoutState.value
                val isIdle = currentState is WorkoutState.Idle
                val isSummaryAndJustLift = currentState is WorkoutState.SetSummary && params.isJustLift

                // Handle auto-START when Idle and waiting for handles
                // Also allow auto-start from SetSummary if in Just Lift mode (interrupting the summary)
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

                // Handle auto-STOP when Active in Just Lift mode and handles released
                if (params.isJustLift && currentState is WorkoutState.Active) {
                    if (activityState == HandleState.Released) {
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
            }
        }

        // #5: Issue #98: Deload event collector for firmware-based auto-stop detection
        scope.launch {
            bleRepository.deloadOccurredEvents.collect {
                val params = coordinator._workoutParameters.value
                val currentState = coordinator._workoutState.value

                if (shouldEnableAutoStop(params) && currentState is WorkoutState.Active) {
                    Logger.d("DELOAD_OCCURRED: Machine detected cable release - starting auto-stop timer")

                    val hasMeaningfulRange = repCounter.hasMeaningfulRange(WorkoutCoordinator.MIN_RANGE_THRESHOLD)
                    val inGrace = isInAmrapStartupGrace(hasMeaningfulRange)

                    if (coordinator.stallStartTime == null && !inGrace && hasMeaningfulRange) {
                        coordinator.stallStartTime = currentTimeMillis()
                        coordinator.isCurrentlyStalled = true
                        Logger.d("Auto-stop stall timer STARTED via DELOAD_OCCURRED flag")
                    } else if (inGrace) {
                        Logger.d("DELOAD_OCCURRED ignored - in AMRAP startup grace period")
                    } else if (!hasMeaningfulRange) {
                        Logger.d("DELOAD_OCCURRED ignored - no meaningful ROM established yet (warmup incomplete)")
                    }
                }
            }
        }

        // #6: Rep events collector for handling machine rep notifications
        coordinator.repEventsCollectionJob = scope.launch {
            bleRepository.repEvents.collect { notification ->
                val state = coordinator._workoutState.value
                if (state is WorkoutState.Active) {
                    handleRepNotification(notification)
                }
            }
        }

        // #7: CRITICAL: Global metricsFlow collection (matches parent repo)
        coordinator.monitorDataCollectionJob = scope.launch {
            Logger.d("ActiveSessionEngine") { "Starting global metricsFlow collection..." }
            bleRepository.metricsFlow.collect { metric ->
                coordinator._currentMetric.value = metric
                handleMonitorMetric(metric)
            }
        }

        // #8: Heuristic data collection for Echo mode force feedback
        scope.launch {
            bleRepository.heuristicData.collect { stats ->
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
        isEchoMode: Boolean = false,
        warmupRepsCount: Int = 0,
        workingRepsCount: Int = 0,
        baselineLoadA: Float = 0f,
        baselineLoadB: Float = 0f
    ): WorkoutState.SetSummary {
        if (metrics.isEmpty()) {
            return WorkoutState.SetSummary(
                metrics = metrics,
                peakPower = 0f,
                averagePower = 0f,
                repCount = repCount,
                heaviestLiftKgPerCable = fallbackWeightKg
            )
        }

        val durationMs = metrics.last().timestamp - metrics.first().timestamp

        val blA = baselineLoadA.coerceAtLeast(0f)
        val blB = baselineLoadB.coerceAtLeast(0f)
        fun adjA(raw: Float) = (raw - blA).coerceAtLeast(0f)
        fun adjB(raw: Float) = (raw - blB).coerceAtLeast(0f)

        val heaviestLiftKgPerCable = metrics.maxOf { maxOf(adjA(it.loadA), adjB(it.loadB)) }

        val peakCableA = metrics.maxOf { adjA(it.loadA) }
        val peakCableB = metrics.maxOf { adjB(it.loadB) }
        val totalVolumeKg = (peakCableA + peakCableB) * repCount

        val concentricMetrics = metrics.filter { it.velocityA > 10 || it.velocityB > 10 }
        val eccentricMetrics = metrics.filter { it.velocityA < -10 || it.velocityB < -10 }

        val peakConcentricA = concentricMetrics.maxOfOrNull { adjA(it.loadA) } ?: 0f
        val peakConcentricB = concentricMetrics.maxOfOrNull { adjB(it.loadB) } ?: 0f
        val peakEccentricA = eccentricMetrics.maxOfOrNull { adjA(it.loadA) } ?: 0f
        val peakEccentricB = eccentricMetrics.maxOfOrNull { adjB(it.loadB) } ?: 0f

        val peakLoadA = metrics.maxOf { adjA(it.loadA) }
        val peakLoadB = metrics.maxOf { adjB(it.loadB) }
        val thresholdA = (peakLoadA * 0.1f).coerceAtLeast(1f)
        val thresholdB = (peakLoadB * 0.1f).coerceAtLeast(1f)

        val activeConcentricMetrics = concentricMetrics.filter {
            adjA(it.loadA) > thresholdA || adjB(it.loadB) > thresholdB
        }
        val activeEccentricMetrics = eccentricMetrics.filter {
            adjA(it.loadA) > thresholdA || adjB(it.loadB) > thresholdB
        }

        val avgConcentricA = if (activeConcentricMetrics.isNotEmpty())
            activeConcentricMetrics.map { adjA(it.loadA) }.average().toFloat() else 0f
        val avgConcentricB = if (activeConcentricMetrics.isNotEmpty())
            activeConcentricMetrics.map { adjB(it.loadB) }.average().toFloat() else 0f
        val avgEccentricA = if (activeEccentricMetrics.isNotEmpty())
            activeEccentricMetrics.map { adjA(it.loadA) }.average().toFloat() else 0f
        val avgEccentricB = if (activeEccentricMetrics.isNotEmpty())
            activeEccentricMetrics.map { adjB(it.loadB) }.average().toFloat() else 0f

        val estimatedCalories = (totalVolumeKg * 0.5f * 9.81f / 4184f).coerceAtLeast(1f)

        val peakPower = heaviestLiftKgPerCable
        val averagePower = metrics.map { (adjA(it.loadA) + adjB(it.loadB)) / 2f }.average().toFloat()

        // Echo Mode Phase-Aware Metrics
        var warmupAvgWeightKg = 0f
        var workingAvgWeightKg = 0f
        var burnoutAvgWeightKg = 0f
        var peakWeightKg = 0f
        var burnoutReps = 0

        if (isEchoMode && metrics.size > 10) {
            val weightSamples = metrics.map { maxOf(adjA(it.loadA), adjB(it.loadB)) }
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
            heaviestLiftKgPerCable = heaviestLiftKgPerCable,
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
        coordinator.collectedMetrics.add(metric)
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
     * Whether auto-stop logic should run for the current mode/state.
     */
    private fun shouldEnableAutoStop(params: WorkoutParameters): Boolean {
        val timedCableReadyForAutoStop = coordinator.isCurrentTimedCableExercise && coordinator._repCount.value.isWarmupComplete
        return params.isJustLift || params.isAMRAP || timedCableReadyForAutoStop
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

            if (shouldEnableAutoStop(params)) {
                Logger.d { "Issue203 DEBUG: checkAutoStop called - isJustLift=${params.isJustLift}, isAMRAP=${params.isAMRAP}, isTimedCable=$coordinator.isCurrentTimedCableExercise, setIndex=${coordinator._currentSetIndex.value}" }
                checkAutoStop(metric)
            } else {
                resetAutoStopTimer()
            }

            if (repCounter.shouldStopWorkout()) {
                handleSetCompletion()
            }

            // LED Biofeedback: update controller with current velocity and phase
            coordinator.ledFeedbackController?.let { led ->
                val maxVelocity = maxOf(kotlin.math.abs(metric.velocityA), kotlin.math.abs(metric.velocityB))
                val repCount = repCounter.getRepCount()
                val workoutMode = params.programMode.toWorkoutMode(params.echoLevel)
                led.updateMetrics(
                    velocity = maxVelocity,
                    repPhase = repCount.activeRepPhase,
                    workoutMode = workoutMode,
                    echoLoadRatio = calculateEchoLoadRatio(metric, params)
                )
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

        if (coordinator.isCurrentTimedCableExercise && !coordinator._repCount.value.isWarmupComplete) {
            resetAutoStopTimer()
            resetStallTimer()
            return
        }

        val hasMeaningfulRange = repCounter.hasMeaningfulRange(WorkoutCoordinator.MIN_RANGE_THRESHOLD)
        val params = coordinator._workoutParameters.value

        // ===== 1. VELOCITY-BASED STALL DETECTION =====
        if (params.stallDetectionEnabled) {
            val maxVelocity = maxOf(kotlin.math.abs(metric.velocityA), kotlin.math.abs(metric.velocityB))
            val isDefinitelyStalled = maxVelocity < WorkoutCoordinator.STALL_VELOCITY_LOW
            val isDefinitelyMoving = maxVelocity > WorkoutCoordinator.STALL_VELOCITY_HIGH

            val maxPosition = maxOf(metric.positionA, metric.positionB)
            val isActivelyUsing = maxPosition > WorkoutCoordinator.STALL_MIN_POSITION || hasMeaningfulRange
            val handlesAtRest = maxPosition < WorkoutCoordinator.HANDLE_REST_THRESHOLD

            val inGrace = isInAmrapStartupGrace(hasMeaningfulRange)
            if (isDefinitelyStalled && (isActivelyUsing || handlesAtRest) && coordinator.stallStartTime == null && !inGrace && hasMeaningfulRange) {
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

    // ===== LED Biofeedback Helpers =====

    /**
     * Calculate echo load ratio (actual/target) for Echo mode LED feedback.
     * Returns 0f for non-Echo modes.
     */
    private fun calculateEchoLoadRatio(metric: WorkoutMetric, params: WorkoutParameters): Float {
        if (!params.isEchoMode) return 0f
        val targetWeight = params.weightPerCableKg
        if (targetWeight <= 0f) return 1.0f
        val blA = coordinator._loadBaselineA.value.coerceAtLeast(0f)
        val blB = coordinator._loadBaselineB.value.coerceAtLeast(0f)
        val actualLoad = maxOf(metric.loadA - blA, metric.loadB - blB).coerceAtLeast(0f)
        return (actualLoad / targetWeight).coerceIn(0f, 2f)
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
     */
    fun adjustWeight(newWeightKg: Float, sendToMachine: Boolean = true) {
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
            scope.launch {
                sendWeightUpdateToMachine(clampedWeight)
            }
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
        return workoutRepository.getAllSessions()
            .first()
            .filter { it.exerciseId == exerciseId }
            .sortedByDescending { it.timestamp }
            .firstOrNull()
            ?.weightPerCableKg
    }

    suspend fun getPrWeightForExercise(exerciseId: String): Float? {
        return workoutRepository.getAllPersonalRecords()
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
            stallDetectionEnabled = prefsDefaults.stallDetectionEnabled
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
                stallDetectionEnabled = defaults.stallDetectionEnabled
            )
            preferencesManager.saveJustLiftDefaults(prefsDefaults)
            Logger.d("saveJustLiftDefaults: weight=${defaults.weightPerCableKg}kg, mode=${defaults.workoutModeId}")
        }
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
                stallDetectionEnabled = params.stallDetectionEnabled
            )
            preferencesManager.saveJustLiftDefaults(defaults)
            Logger.d { "Saved Just Lift defaults: mode=${params.programMode.modeValue}, weight=${params.weightPerCableKg}kg" }
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
                val updated = progress.markDayCompleted(dayNumber, cycle.days.size)
                trainingCycleRepository.updateCycleProgress(updated)

                val completedDay = cycle.days.find { it.dayNumber == dayNumber }
                val isRotationComplete = updated.rotationCount > progress.rotationCount
                coordinator._cycleDayCompletionEvent.value = CycleDayCompletionEvent(
                    dayNumber = dayNumber,
                    dayName = completedDay?.name,
                    isRotationComplete = isRotationComplete,
                    rotationCount = updated.rotationCount
                )

                Logger.d { "Cycle progress updated: day $dayNumber completed, now on day ${updated.currentDayNumber}" +
                    if (isRotationComplete) " (rotation ${updated.rotationCount} complete!)" else "" }
            }
        } catch (e: Exception) {
            Logger.e(e) { "Error updating cycle progress: ${e.message}" }
        }
    }

    // ===== Core Workout Lifecycle =====

    fun resetForNewWorkout() {
        coordinator._workoutState.value = WorkoutState.Idle
        coordinator._repCount.value = RepCount()
        coordinator._repRanges.value = null
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

    fun startWorkout(skipCountdown: Boolean = false, isJustLiftMode: Boolean = false) {
        Logger.d { "startWorkout called: skipCountdown=$skipCountdown, isJustLiftMode=$isJustLiftMode" }
        Logger.d { "startWorkout: loadedRoutine=${coordinator._loadedRoutine.value?.name}, params=${coordinator._workoutParameters.value}" }

        coordinator.stopWorkoutInProgress = false
        coordinator.setCompletionInProgress = false
        resetAutoStopState()
        coordinator.skipCountdownRequested = skipCountdown

        coordinator.workoutJob?.cancel()

        coordinator._workoutState.value = WorkoutState.Initializing

        coordinator.workoutJob = scope.launch {
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

                if (!coordinator.skipCountdownRequested) {
                    for (i in 5 downTo 1) {
                        if (coordinator.skipCountdownRequested) break
                        coordinator._workoutState.value = WorkoutState.Countdown(i)
                        delay(1000)
                    }
                }

                coordinator._workoutState.value = WorkoutState.Active
                coordinator.workoutStartTime = currentTimeMillis()
                if (coordinator._loadedRoutine.value != null && coordinator.routineStartTime == 0L) {
                    coordinator.routineStartTime = coordinator.workoutStartTime
                }
                coordinator.currentSessionId = KmpUtils.randomUUID()
                coordinator.collectedMetrics.clear()
                coordinator._hapticEvents.emit(HapticEvent.WORKOUT_START)

                // LED Biofeedback: configure controller for bodyweight workout
                coordinator.ledFeedbackController?.let { led ->
                    led.setEnabled(settingsManager.ledFeedbackEnabled.value)
                    led.setUserColorScheme(settingsManager.userPreferences.value.colorScheme)
                }

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

            println("Issue222: CABLE WORKOUT STARTING - DIAGNOSTIC STATE")
            println("Issue222: Bodyweight sets completed this routine: $coordinator.bodyweightSetsCompletedInRoutine")
            println("Issue222: Current exercise index: ${coordinator._currentExerciseIndex.value}")
            println("Issue222: Current set index: ${coordinator._currentSetIndex.value}")
            println("Issue222: isEchoMode: ${effectiveParams.isEchoMode}")
            println("Issue222: programMode: ${effectiveParams.programMode}")

            println("Issue188: PRE-BLE WORKOUT PARAMETERS")
            println("Issue188: Mode: ${effectiveParams.programMode.displayName}")
            println("Issue188: Weight: ${effectiveParams.weightPerCableKg}kg per cable")
            println("Issue188: Reps: ${effectiveParams.reps} (isAMRAP=${effectiveParams.isAMRAP})")
            Logger.d { "Issue203 DEBUG: Starting workout - setReps=${currentExercise?.setReps}, currentSetIndex=${coordinator._currentSetIndex.value}, isAMRAP=${effectiveParams.isAMRAP}" }
            println("Issue188: Warmup: ${effectiveParams.warmupReps}")
            println("Issue188: Progression: ${effectiveParams.progressionRegressionKg}kg per rep")
            println("Issue188: isJustLift: ${effectiveParams.isJustLift}")
            println("Issue188: isEchoMode: ${effectiveParams.isEchoMode}")
            println("Issue188: echoLevel: ${effectiveParams.echoLevel.displayName}")
            println("Issue188: eccentricLoad: ${effectiveParams.eccentricLoad.percentage}%")
            println("Issue188: stopAtTop: ${effectiveParams.stopAtTop}")
            println("Issue188: stallDetection: ${effectiveParams.stallDetectionEnabled}")

            val bleParams = if (isTimedCableExercise) {
                Logger.d { "Duration cable: overriding isAMRAP=true for BLE command (prevents machine rep limit)" }
                effectiveParams.copy(isAMRAP = true)
            } else {
                effectiveParams
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
                Logger.d { "Starting TIMED cable exercise: ${currentExercise?.exercise?.name} for ${exerciseDuration}s (no ROM calibration)" }
            }

            if (!coordinator.skipCountdownRequested && !isJustLiftMode) {
                for (i in 5 downTo 1) {
                    if (coordinator.skipCountdownRequested) break
                    coordinator._workoutState.value = WorkoutState.Countdown(i)
                    delay(1000)
                }
            }

            try {
                bleRepository.sendWorkoutCommand(command)
                Logger.i { "CONFIG command sent: ${command.size} bytes for ${effectiveParams.programMode}" }
                val preview = command.take(16).joinToString(" ") { it.toUByte().toString(16).padStart(2, '0').uppercase() }
                Logger.d { "Config preview: $preview ..." }
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
            coordinator.collectedMetrics.clear()
            coordinator._hapticEvents.emit(HapticEvent.WORKOUT_START)

            // LED Biofeedback: configure controller for this workout
            coordinator.ledFeedbackController?.let { led ->
                led.setEnabled(settingsManager.ledFeedbackEnabled.value)
                led.setUserColorScheme(settingsManager.userPreferences.value.colorScheme)
            }

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
        }
    }

    fun skipCountdown() {
        coordinator.skipCountdownRequested = true
        Logger.d { "skipCountdown: Countdown skip requested" }
    }

    fun stopWorkout(exitingWorkout: Boolean = false) {
        if (coordinator.stopWorkoutInProgress) return
        coordinator.stopWorkoutInProgress = true

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
             println("Issue222 TRACE: manual stop -> isBodyweight=$isBodyweight, exitingWorkout=$shouldExitToIdle")
             if (!isBodyweight) {
                 println("Issue222 TRACE: manual stop -> calling bleRepository.stopWorkout()")
                 bleRepository.stopWorkout()
             } else {
                 println("Issue222 TRACE: manual stop -> skipping BLE stop (bodyweight)")
                 Logger.d("Manual stop: bodyweight exercise - skipping BLE stop (parent-aligned)")
             }
             coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)

             val params = coordinator._workoutParameters.value
             val repCount = coordinator._repCount.value
             val isJustLift = params.isJustLift

             if (isJustLift) {
                 Logger.d("Just Lift: Restarting monitor polling to clear machine fault state")
                 bleRepository.restartMonitorPolling()
             }

             val exerciseName = params.selectedExerciseId?.let { exerciseId ->
                 exerciseRepository.getExerciseById(exerciseId)?.name
             }

             val metrics = coordinator.collectedMetrics.toList()
             val summary = calculateSetSummaryMetrics(
                 metrics = metrics,
                 repCount = repCount.totalReps,
                 fallbackWeightKg = params.weightPerCableKg,
                 isEchoMode = params.isEchoMode,
                 warmupRepsCount = repCount.warmupReps,
                 workingRepsCount = repCount.workingReps,
                 baselineLoadA = coordinator._loadBaselineA.value,
                 baselineLoadB = coordinator._loadBaselineB.value
             )

             val session = WorkoutSession(
                 timestamp = coordinator.workoutStartTime,
                 mode = params.programMode.displayName,
                 reps = params.reps,
                 weightPerCableKg = params.weightPerCableKg,
                 totalReps = repCount.totalReps,
                 workingReps = repCount.workingReps,
                 warmupReps = repCount.warmupReps,
                 duration = currentTimeMillis() - coordinator.workoutStartTime,
                 isJustLift = isJustLift,
                 exerciseId = params.selectedExerciseId,
                 exerciseName = exerciseName,
                 routineSessionId = coordinator.currentRoutineSessionId,
                 routineName = coordinator.currentRoutineName,
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
                 estimatedCalories = summary.estimatedCalories,
                 warmupAvgWeightKg = if (params.isEchoMode) summary.warmupAvgWeightKg else null,
                 workingAvgWeightKg = if (params.isEchoMode) summary.workingAvgWeightKg else null,
                 burnoutAvgWeightKg = if (params.isEchoMode) summary.burnoutAvgWeightKg else null,
                 peakWeightKg = if (params.isEchoMode) summary.peakWeightKg else null,
                 rpe = coordinator._currentSetRpe.value
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
                 measuredWeightKg = params.weightPerCableKg,
                 programMode = params.programMode,
                 isJustLift = isJustLift,
                 isEchoMode = params.isEchoMode
             )

             if (hasPR && completedSetId != null) {
                 completedSetRepository.markAsPr(completedSetId)
                 Logger.d("Marked CompletedSet $completedSetId as PR (manual stop)")
                 // LED Biofeedback: celebrate PR with rapid color flash
                 coordinator.ledFeedbackController?.triggerPRCelebration()
             }

             // LED Biofeedback: restore user's static color on workout end
             coordinator.ledFeedbackController?.onWorkoutEnd()

             scope.launch {
                 syncTriggerManager?.onWorkoutCompleted()
             }

             if (isJustLift) {
                 saveJustLiftDefaultsFromWorkout()
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
        coordinator.workoutJob?.cancel()
        coordinator.workoutJob = null
        coordinator.restTimerJob?.cancel()
        coordinator.restTimerJob = null
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null

        scope.launch {
            bleRepository.stopWorkout()

            repCounter.reset()
            coordinator._repCount.value = RepCount()
            coordinator._repRanges.value = null
            resetAutoStopState()
            coordinator._workoutState.value = WorkoutState.Idle

            val routine = coordinator._loadedRoutine.value
            if (routine != null) {
                flowDelegate?.enterSetReady(coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
            }

            Logger.d { "stopAndReturnToSetReady: Reset to SetReady for exercise=${coordinator._currentExerciseIndex.value}, set=${coordinator._currentSetIndex.value}" }
        }
    }

    fun pauseWorkout() {
        if (coordinator._workoutState.value is WorkoutState.Active) {
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
            bleRepository.metricsFlow.collect { metric ->
                coordinator._currentMetric.value = metric
                handleMonitorMetric(metric)
            }
        }

        coordinator.repEventsCollectionJob = scope.launch {
            bleRepository.repEvents.collect { notification ->
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
        val duration = currentTimeMillis() - coordinator.workoutStartTime

        val metricsSnapshot = coordinator.collectedMetrics.toList()

        val blA = coordinator._loadBaselineA.value.coerceAtLeast(0f)
        val blB = coordinator._loadBaselineB.value.coerceAtLeast(0f)
        val measuredPerCableKg = if (metricsSnapshot.isNotEmpty()) {
            metricsSnapshot.maxOf { maxOf(it.loadA - blA, it.loadB - blB).coerceAtLeast(0f) }
        } else {
            params.weightPerCableKg
        }

        val exerciseName = params.selectedExerciseId?.let { exerciseId ->
            exerciseRepository.getExerciseById(exerciseId)?.name
        }

        val summary = calculateSetSummaryMetrics(
            metrics = metricsSnapshot,
            repCount = working,
            fallbackWeightKg = params.weightPerCableKg,
            isEchoMode = params.isEchoMode,
            warmupRepsCount = warmup,
            workingRepsCount = working,
            baselineLoadA = coordinator._loadBaselineA.value,
            baselineLoadB = coordinator._loadBaselineB.value
        )

        val session = WorkoutSession(
            id = sessionId,
            timestamp = coordinator.workoutStartTime,
            mode = params.programMode.displayName,
            reps = params.reps,
            weightPerCableKg = measuredPerCableKg,
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
            estimatedCalories = summary.estimatedCalories,
            warmupAvgWeightKg = if (params.isEchoMode) summary.warmupAvgWeightKg else null,
            workingAvgWeightKg = if (params.isEchoMode) summary.workingAvgWeightKg else null,
            burnoutAvgWeightKg = if (params.isEchoMode) summary.burnoutAvgWeightKg else null,
            peakWeightKg = if (params.isEchoMode) summary.peakWeightKg else null,
            rpe = coordinator._currentSetRpe.value
        )

        workoutRepository.saveSession(session)

        scope.launch {
            syncTriggerManager?.onWorkoutCompleted()
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
                actualWeightKg = measuredPerCableKg,
                loggedRpe = coordinator._currentSetRpe.value,
                isPr = false,
                completedAt = currentTimeMillis()
            )
            completedSetRepository.saveCompletedSet(completedSet)
            Logger.d("Saved CompletedSet: set #$setIndex, ${working} reps @ ${measuredPerCableKg}kg${if (matchedPlannedSetId != null) " (linked to PlannedSet)" else ""}")
        }

        val hasPR = gamificationManager.processPostSaveEvents(
            exerciseId = params.selectedExerciseId,
            workingReps = working,
            measuredWeightKg = measuredPerCableKg,
            programMode = params.programMode,
            isJustLift = params.isJustLift,
            isEchoMode = params.isEchoMode
        )

        if (hasPR && completedSetId != null) {
            completedSetRepository.markAsPr(completedSetId)
            Logger.d("Marked CompletedSet $completedSetId as PR")
            // LED Biofeedback: celebrate PR with rapid color flash
            coordinator.ledFeedbackController?.triggerPRCelebration()
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
        if (coordinator.setCompletionInProgress) {
            Logger.d("handleSetCompletion: already in progress - ignoring")
            return
        }
        coordinator.setCompletionInProgress = true
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
                println("Issue222: Bodyweight set #$coordinator.bodyweightSetsCompletedInRoutine completed (exercise=${currentExercise?.exercise?.name})")
            }

            coordinator.previousExerciseWasBodyweight = wasBodyweight
            Logger.d { "Issue #222 v8: Set coordinator.previousExerciseWasBodyweight=$wasBodyweight" }

            if (!wasBodyweight) {
                bleRepository.stopWorkout()
                Logger.d("handleSetCompletion: Called stopWorkout() (auto-complete)")
            } else {
                Logger.d("handleSetCompletion: Skipping BLE stop (bodyweight exercise)")
            }
            coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)

            saveWorkoutSession()

            val completedReps = coordinator._repCount.value.workingReps
            val warmupReps = coordinator._repCount.value.warmupReps
            val metricsList = coordinator.collectedMetrics.toList()

            val summary = calculateSetSummaryMetrics(
                metrics = metricsList,
                repCount = completedReps,
                fallbackWeightKg = params.weightPerCableKg,
                isEchoMode = params.isEchoMode,
                warmupRepsCount = warmupReps,
                workingRepsCount = completedReps,
                baselineLoadA = coordinator._loadBaselineA.value,
                baselineLoadB = coordinator._loadBaselineB.value
            )

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

                bleRepository.restartMonitorPolling()

                enableHandleDetection()
                bleRepository.enableJustLiftWaitingMode()

                Logger.d("Just Lift: Machine armed & ready. summaryCountdownSeconds=$summaryCountdownSeconds, skipSummary=$skipSummary")

                if (skipSummary) {
                    Logger.d("Just Lift: Summary OFF - skipping summary, immediately ready")
                    resetForNewWorkout()
                    coordinator._workoutState.value = WorkoutState.Idle
                } else if (summaryDelayMs > 0) {
                    delay(summaryDelayMs)

                    if (coordinator._workoutState.value is WorkoutState.SetSummary) {
                        Logger.d("Just Lift: Summary complete, UI transitioning to Idle")
                        resetForNewWorkout()
                        coordinator._workoutState.value = WorkoutState.Idle
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
            if (exerciseId != null) {
                val lastWeight = getLastWeightForExercise(exerciseId)
                val prWeight = getPrWeightForExercise(exerciseId)
                coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
                    lastUsedWeightKg = lastWeight,
                    prWeightKg = prWeight
                )
            }

            val completedSetIndex = coordinator._currentSetIndex.value

            val nextStep = if (routine != null) {
                flowDelegate?.getNextStep(routine, coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
            } else null
            val nextExerciseFromStep = if (nextStep != null && routine != null) {
                routine.exercises.getOrNull(nextStep.first)
            } else null
            val nextSetIdxFromStep = nextStep?.second

            val isInSupersetTransition = (flowDelegate?.isInSuperset() == true) && (flowDelegate?.isAtEndOfSupersetCycle() != true)
            val isStillInSupersetWorkout = (flowDelegate?.isInSuperset() == true) && nextExerciseFromStep != null &&
                nextExerciseFromStep.supersetId == currentExercise?.supersetId
            val restDuration = if (isInSupersetTransition || isStillInSupersetWorkout) {
                (flowDelegate?.getSupersetRestSeconds() ?: 5).coerceAtLeast(5)
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

            val supersetLabel = if (isInSupersetTransition || isStillInSupersetWorkout) {
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
                        echoLevel = exerciseForNextSet.echoLevel,
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

            // LED Biofeedback: show blue during rest
            coordinator.ledFeedbackController?.onRestPeriodStart()

            val startTime = currentTimeMillis()
            val endTimeMs = startTime + (restDuration * 1000L)

            while (currentTimeMillis() < endTimeMs && isActive) {
                val remainingMs = endTimeMs - currentTimeMillis()
                val remainingSeconds = (remainingMs / 1000L).toInt().coerceAtLeast(0)

                val nextName = flowDelegate?.calculateNextExerciseName(isSingleExercise, currentExercise, routine) ?: ""

                coordinator._workoutState.value = WorkoutState.Resting(
                    restSecondsRemaining = remainingSeconds,
                    nextExerciseName = nextName,
                    isLastExercise = isLastExerciseOverall,
                    currentSet = displaySetIndex,
                    totalSets = displayTotalSets,
                    isSupersetTransition = isInSupersetTransition || isStillInSupersetWorkout,
                    supersetLabel = supersetLabel
                )

                delay(100)
            }

            // LED Biofeedback: resume normal feedback after rest
            coordinator.ledFeedbackController?.onRestPeriodEnd()

            if (autoplay) {
                Logger.d("ActiveSessionEngine") { "autoplay rest complete: advancing to next set (no BLE stop - already sent at set end)" }
                if (isSingleExercise) {
                    advanceToNextSetInSingleExercise()
                } else {
                    startNextSetOrExercise()
                }
            } else {
                coordinator._workoutState.value = WorkoutState.Resting(
                    restSecondsRemaining = 0,
                    nextExerciseName = flowDelegate?.calculateNextExerciseName(isSingleExercise, currentExercise, routine) ?: "",
                    isLastExercise = isLastExerciseOverall,
                    currentSet = displaySetIndex,
                    totalSets = displayTotalSets,
                    isSupersetTransition = isInSupersetTransition || isStillInSupersetWorkout,
                    supersetLabel = supersetLabel
                )
            }
        }
    }

    /**
     * Advance to the next set within a single exercise (non-routine mode).
     */
    private fun advanceToNextSetInSingleExercise() {
        val routine = coordinator._loadedRoutine.value
        if (routine == null) {
            coordinator.ledFeedbackController?.onWorkoutEnd()
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
            coordinator.ledFeedbackController?.onWorkoutEnd()
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
                nextExercise.echoLevel
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
            } else {
                repCounter.resetCountsOnly()
            }
            resetAutoStopState()
            startWorkoutOrSetReady()
        } else {
            Logger.d { "startNextSetOrExercise: No more steps - showing routine complete" }
            coordinator.ledFeedbackController?.onWorkoutEnd()
            flowDelegate?.showRoutineComplete()
            coordinator._workoutState.value = WorkoutState.Idle
            coordinator._currentSetIndex.value = 0
            coordinator._currentExerciseIndex.value = 0
            coordinator.currentRoutineSessionId = null
            coordinator.currentRoutineName = null
            repCounter.reset()
            resetAutoStopState()
        }
    }

    fun skipRest() {
        if (coordinator._workoutState.value is WorkoutState.Resting) {
            coordinator.restTimerJob?.cancel()
            coordinator.restTimerJob = null

            Logger.d("ActiveSessionEngine") { "skipRest: advancing to next set (no BLE stop - already sent at set end)" }

            if (isSingleExerciseMode(coordinator)) {
                advanceToNextSetInSingleExercise()
            } else {
                startNextSetOrExercise()
            }
        }
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
