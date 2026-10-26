package com.devil.phoenixproject.presentation.tv

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.manager.BleConnectionManager
import com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager
import com.devil.phoenixproject.presentation.manager.MachineTeardownState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** TV orchestration only. Validation, command limits, persistence and teardown stay in the shared core. */
class TvSessionController(
    private val session: DefaultWorkoutSessionManager,
    private val connection: BleConnectionManager,
    private val scope: CoroutineScope,
    private val admitTraining: () -> Boolean = { true },
    private val releaseTraining: () -> Unit = {},
    private val prepareManualStarts: suspend () -> Unit,
) {
    // Preserve the original four-positional-argument form as well as trailing lambdas.
    constructor(
        session: DefaultWorkoutSessionManager,
        connection: BleConnectionManager,
        scope: CoroutineScope,
        prepareManualStarts: suspend () -> Unit,
    ) : this(session, connection, scope, { true }, {}, prepareManualStarts)

    private val coordinator get() = session.coordinator
    private var preparation: Job? = null
    private var exitWait: Job? = null
    private var foreground = true
    private var opened = false
    private var stopRequested = false
    private var watchingTelemetry = false
    private var metricTimestamp: Long? = null
    private var metricAt = 0L
    private var lastStartedRoutineStep: Pair<Int, Int>? = null
    var completionFailed = false
        private set

    val currentSetIsBodyweight: Boolean
        get() = coordinator.loadedRoutine.value?.exercises?.getOrNull(coordinator.currentExerciseIndex.value)?.exercise?.isBodyweight == true

    val bodyweightEntry: WorkoutState.BodyweightRepEntry?
        get() = coordinator.workoutState.value as? WorkoutState.BodyweightRepEntry

    val exerciseSecondsRemaining: Int?
        get() = coordinator.timedExerciseRemainingSeconds.value

    // The shared manual-advance path publishes a new SetReady without necessarily
    // clearing the preceding SetSummary. Render the newly selected core step, not
    // the stale summary, while keeping starts explicit and teardown-gated.
    val routineReadyToStart: Boolean
        get() {
            val ready = coordinator.routineFlowState.value as? RoutineFlowState.SetReady ?: return false
            if (completionFailed || bodyweightEntry != null || machineMayBeRunning || session.machineTeardownState.value !is MachineTeardownState.Ready) return false
            return coordinator.workoutState.value is WorkoutState.Idle ||
                lastStartedRoutineStep != (ready.exerciseIndex to ready.setIndex)
        }
    val manualRoutineRestSeconds: Int?
        get() {
            if (!routineReadyToStart) return null
            val (exerciseIndex, setIndex) = lastStartedRoutineStep ?: return null
            return coordinator.loadedRoutine.value?.exercises?.getOrNull(exerciseIndex)?.setRestSeconds?.getOrNull(setIndex)
        }

    var preparing = false
        private set
    var exitConfirmationPending = false
        private set
    var error: String? = null
        private set

    val machineMayBeRunning: Boolean
        get() = preparing || if (completionFailed) session.machineTeardownState.value !is MachineTeardownState.Ready
            else coordinator.workoutState.value.tvNeedsStop()
    val sessionOpen: Boolean
        get() = opened || coordinator.loadedRoutine.value != null || machineMayBeRunning ||
            coordinator.workoutState.value is WorkoutState.SetSummary ||
            coordinator.workoutState.value is WorkoutState.Resting

    fun connectSelectedTrainer(address: String): Boolean {
        if (machineMayBeRunning || session.machineTeardownState.value !is MachineTeardownState.Ready) {
            return fail("Stop the set and complete recovery before changing trainers.")
        }
        if (connection.scannedDevices.value.none { it.address == address }) {
            return fail("Select a trainer shown in the current scan results.")
        }
        error = null
        connection.connectToDevice(address)
        return true
    }

    fun startJustLift(): Boolean {
        if (coordinator.loadedRoutine.value != null) return fail("End the routine before starting Just Lift.")
        if (coordinator.workoutParameters.value.selectedExerciseId.isNullOrBlank()) {
            return fail("Select an exercise before starting Just Lift.")
        }
        return prepare {
            if (coordinator.workoutState.value is WorkoutState.Resting) session.skipRest()
            session.startWorkout(skipCountdown = false, isJustLiftMode = true)
        }
    }

    fun beginRoutine(routine: Routine): Boolean {
        if (routine.exercises.isEmpty()) return fail("This routine has no exercises.")
        if (sessionOpen) return fail("End the current session before loading another routine.")
        return prepare(requiresTrainer = false) {
            if (!session.loadRoutineAsync(routine)) error("Phoenix could not prepare this routine.")
            lastStartedRoutineStep = null
            session.enterSetReady(0, 0)
        }
    }

    fun startRoutineSet(): Boolean {
        val ready = coordinator.routineFlowState.value as? RoutineFlowState.SetReady
        if (ready == null || !routineReadyToStart) {
            return fail("Wait for the next set to be ready.")
        }
        return prepare(requiresTrainer = !currentSetIsBodyweight) {
            lastStartedRoutineStep = ready.exerciseIndex to ready.setIndex
            session.startSetFromReady()
        }
    }

    fun confirmBodyweightReps(reps: Int, variantIndex: Int): Boolean {
        if (completionFailed || !foreground) return fail("Return to the session and resolve recovery first.")
        val entry = bodyweightEntry ?: return fail("Wait for bodyweight rep entry.")
        val variant = entry.variants.getOrNull(variantIndex) ?: return fail("Select one of the displayed variants.")
        if (reps < 0) return fail("Reps cannot be negative.")
        return completionAction { session.confirmBodyweightSetResult(reps, variant) }
    }

    private fun prepare(requiresTrainer: Boolean = true, action: suspend () -> Unit): Boolean {
        if (completionFailed) return fail("This set was not saved. End the session after safe stop recovery before starting again.")
        if (!foreground) return fail("Return to Phoenix before explicitly starting a set.")
        if (machineMayBeRunning || stopRequested && session.machineTeardownState.value !is MachineTeardownState.Ready) {
            return fail("Wait for the current start or stop to finish.")
        }
        if (session.machineTeardownState.value !is MachineTeardownState.Ready) return fail("Complete safe stop recovery before starting.")
        if (requiresTrainer && connection.connectionState.value !is ConnectionState.Connected) return fail("Connect to a selected trainer before starting.")
        if (!admitTraining()) return fail("Wait for the account operation to finish before training.")
        val alreadyOpen = sessionOpen
        var actionStarted = false
        var preparationFailed = false
        opened = true
        preparing = true
        stopRequested = false
        watchingTelemetry = false
        error = null
        exitConfirmationPending = false
        preparation = scope.launch {
            try {
                // Await shared profile/settings publication before the engine may see a start.
                prepareManualStarts()
                if (!foreground) return@launch
                disableHandleAutoStart()
                actionStarted = true
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                preparationFailed = true
                error = failure.message ?: "Phoenix could not prepare the set."
                // Never turn a failed start into a UI-only error while load may be applied.
                if (actionStarted) completionAction { session.stopWorkout(exitingWorkout = false) }
            }
        }.also { pending ->
            pending.invokeOnCompletion { cause ->
                preparing = false
                // A cancelled/pre-failed launch may never enter its body. Release only
                // a new reservation with no surviving core session and confirmed Ready.
                if (!alreadyOpen && (cause != null || preparationFailed || !actionStarted) &&
                    coordinator.loadedRoutine.value == null && coordinator.workoutState.value is WorkoutState.Idle &&
                    session.machineTeardownState.value is MachineTeardownState.Ready
                ) {
                    opened = false
                    releaseTraining()
                }
            }
        }
        return true
    }

    fun continueRoutine(): Boolean {
        if (completionFailed || coordinator.workoutSaveFailureOffer.value != null) return fail("Resolve the failed set before continuing.")
        if (routineReadyToStart) return fail("The next set is ready. Select Start Set when you have rested.")
        if (machineMayBeRunning || session.machineTeardownState.value !is MachineTeardownState.Ready) return fail("Wait for a safe set stop.")
        when (coordinator.workoutState.value) {
            is WorkoutState.SetSummary -> session.proceedFromSummary()
            is WorkoutState.Resting -> session.skipRest()
            is WorkoutState.RoutineComplete -> session.showRoutineComplete()
            else -> return fail("Wait for the current Phoenix set transition to finish.")
        }
        error = null
        return true
    }

    /** Idempotent at the TV boundary; the core owns cancellation and the durable stop invariant. */
    fun stopSet() {
        preparation?.cancel()
        // Keep preparation visible until cancellation actually completes; a start
        // continuation must not outlive confirmed exit and its admission release.
        disableHandleAutoStart()
        session.cancelSummaryAutoAdvance()
        if (stopRequested || !coordinator.workoutState.value.tvNeedsStop()) return
        stopRequested = true
        completionAction { session.stopWorkout(exitingWorkout = false) }
    }

    fun handleRemote(input: TvRemoteInput) {
        if (input == TvRemoteInput.PLAY_PAUSE) {
            stopSet()
        } else if (sessionOpen) {
            requestExit()
        }
    }

    fun requestExit() {
        stopSet()
        exitConfirmationPending = false
        exitWait?.cancel()
        exitWait = scope.launch {
            val (_, teardown) = combine(coordinator.workoutState, session.machineTeardownState) { workout, stop -> workout to stop }
                .first { (workout, stop) -> (completionFailed || !workout.tvNeedsStop()) && stop !is MachineTeardownState.TearingDown }
            if (teardown is MachineTeardownState.Ready) exitConfirmationPending = true
            else error = "Phoenix could not confirm a safe machine stop. Retry stop recovery before leaving."
        }
    }

    fun confirmExit(): Boolean {
        if (!exitConfirmationPending || machineMayBeRunning || session.machineTeardownState.value !is MachineTeardownState.Ready) {
            return fail("The machine has not confirmed a safe stop. Retry recovery before exiting.")
        }
        if (!completionFailed && !completionAction { session.stopWorkout(exitingWorkout = true) }) return false
        // A completed manual stop intentionally keeps the core's stop latch armed.
        // Only AFTER verified teardown-ready, abandon rejected completion facts using
        // the shared flow-exit API; never retry invalid metadata or manufacture a save.
        if ((completionFailed || !coordinator.workoutState.value.tvNeedsStop()) && session.machineTeardownState.value is MachineTeardownState.Ready) {
            session.exitRoutineFlow()
        }
        if (coordinator.loadedRoutine.value != null || coordinator.workoutState.value !is WorkoutState.Idle ||
            session.machineTeardownState.value !is MachineTeardownState.Ready
        ) return fail("Wait for Phoenix to finish the session exit and confirm a safe stop.")
        opened = false
        exitConfirmationPending = false
        completionFailed = false
        error = null
        releaseTraining()
        return true
    }

    fun cancelExit() { exitWait?.cancel(); exitConfirmationPending = false }
    fun onForeground() { foreground = true } // Deliberately no resume/start.
    fun onBackground() { foreground = false; stopSet() }

    /** Called by the native host's watchdog; a repeated or backwards timestamp is not fresh telemetry. */
    fun checkTelemetry(nowMs: Long) {
        if (completionFailed || currentSetIsBodyweight || coordinator.workoutState.value !is WorkoutState.Active) {
            watchingTelemetry = false
            return
        }
        val timestamp = coordinator.currentMetric.value?.timestamp
        if (!watchingTelemetry) {
            watchingTelemetry = true
            metricTimestamp = timestamp
            metricAt = nowMs
        } else if (timestamp != null && (metricTimestamp == null || timestamp > metricTimestamp!!)) {
            metricTimestamp = timestamp
            metricAt = nowMs
        }
        if (!stopRequested && resolveTvTelemetryAction(true, (nowMs - metricAt).coerceAtLeast(0)) == TvTelemetryAction.STOP_ACTIVE_SET) {
            error = "Workout telemetry timed out; Phoenix is stopping the set through its shared safety path."
            stopSet()
        }
    }

    private fun disableHandleAutoStart() {
        val params = coordinator.workoutParameters.value
        if (params.useAutoStart) session.updateWorkoutParameters(params.copy(useAutoStart = false))
    }

    private fun completionAction(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Shared stop already requested durable teardown before rejecting completion.
        // Keep its latch/recovery intact, but never let the exception escape to Swift.
        completionFailed = true
        fail("This set was not saved: ${failure.message ?: "invalid completion"}. Complete safe stop recovery, then end the session.")
    }

    private fun fail(message: String): Boolean { error = message; return false }
}

fun WorkoutState.tvNeedsStop(): Boolean = this is WorkoutState.Active || this is WorkoutState.Initializing ||
    this is WorkoutState.Countdown || this is WorkoutState.Paused
