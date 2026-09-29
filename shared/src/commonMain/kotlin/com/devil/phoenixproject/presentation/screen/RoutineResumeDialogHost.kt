package com.devil.phoenixproject.presentation.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.presentation.components.ResumeRoutineDialog
import com.devil.phoenixproject.presentation.components.StartGateLabel
import com.devil.phoenixproject.presentation.components.WorkoutStartGateNotice
import com.devil.phoenixproject.presentation.components.toStartGatePresentation
import com.devil.phoenixproject.presentation.manager.RoutineResumeDiscovery
import com.devil.phoenixproject.presentation.manager.RoutineResumeHandle
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeActionAuthority
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeCompletionDisposition
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeEntryPoint
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeOperationGate
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeRetryAction
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeUiOperation
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeUiOutcome
import com.devil.phoenixproject.presentation.viewmodel.classifyRoutineResumeCompletion
import com.devil.phoenixproject.presentation.viewmodel.runFreshCycleUiOperation
import com.devil.phoenixproject.presentation.viewmodel.runRoutineResumeUiOperation
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.workout_teardown_finishing

/**
 * Handle a screen uses to start routine-resume discovery.
 *
 * The dialog host binds the real work during composition. Clicks land after that,
 * so callers can remember the handle above the host call and keep the dialog in its
 * original place in the composition.
 */
internal class RoutineResumeLauncher {
    private var launchDaily: (Routine) -> Unit = {}
    private var launchCycle: (Routine, String, Int) -> Unit = { _, _, _ -> }

    fun launchDailyRoutine(routine: Routine) = launchDaily(routine)

    fun launchCycleRoutine(routine: Routine, cycleId: String, dayNumber: Int) =
        launchCycle(routine, cycleId, dayNumber)

    internal fun bind(
        daily: (Routine) -> Unit,
        cycle: (Routine, String, Int) -> Unit,
    ) {
        launchDaily = daily
        launchCycle = cycle
    }
}

@Composable
internal fun rememberRoutineResumeLauncher(): RoutineResumeLauncher =
    remember { RoutineResumeLauncher() }

/**
 * Shared resume/restart dialog (issue #101) for Daily Routines, Home, and Training Cycles.
 *
 * Owns the pending handle, in-flight and retry flags, profile-switch supersede, gate launch,
 * and [ResumeRoutineDialog]. Callers supply [entryPoint] and where a successful outcome navigates.
 * Daily Routines is the only entry that opens the routine overview, starts an in-memory workout,
 * or applies the machine-teardown gate. Training Cycles is the only entry that reports connection
 * and load failures through [onConnectionFailed] and [onWorkoutLoadFailed].
 */
@Composable
internal fun RoutineResumeDialogHost(
    launcher: RoutineResumeLauncher,
    viewModel: MainViewModel,
    entryPoint: RoutineResumeEntryPoint,
    onNavigateActiveWorkout: () -> Unit,
    onNavigateSetReady: () -> Unit,
    onNavigateOverview: () -> Unit = {},
    onConnectionFailed: suspend () -> Unit = {},
    onWorkoutLoadFailed: suspend () -> Unit = {},
) {
    val profileRepository: UserProfileRepository = koinInject()
    val activeProfileContext by profileRepository.activeProfileContext.collectAsState()
    val machineTeardownState by viewModel.machineTeardownState.collectAsState()

    var pendingResumeHandle by remember { mutableStateOf<RoutineResumeHandle?>(null) }
    var resumeOperationInFlight by remember { mutableStateOf(false) }
    var discardRetryPending by remember { mutableStateOf(false) }
    var manualLoadRetry by remember { mutableStateOf<RoutineResumeUiOperation.RetryManualLoad?>(null) }
    val resumeOperationGate = remember { RoutineResumeOperationGate() }
    val scope = rememberCoroutineScope()

    fun clearResumeDialog() {
        pendingResumeHandle = null
        resumeOperationInFlight = false
        discardRetryPending = false
        manualLoadRetry = null
    }

    fun openDailyOverview(routine: Routine) {
        clearResumeDialog()
        viewModel.enterRoutineOverview(routine)
        onNavigateOverview()
    }

    suspend fun dispatchResumeOutcome(outcome: RoutineResumeUiOutcome) {
        when (outcome) {
            RoutineResumeUiOutcome.NavigateActiveWorkout -> {
                clearResumeDialog()
                onNavigateActiveWorkout()
            }

            RoutineResumeUiOutcome.StartAndNavigateActiveWorkout -> {
                if (entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) {
                    viewModel.startWorkout()
                    clearResumeDialog()
                    onNavigateActiveWorkout()
                } else {
                    clearResumeDialog()
                }
            }

            is RoutineResumeUiOutcome.EnterSetReady -> {
                viewModel.enterSetReady(outcome.exerciseIndex, outcome.setIndex)
                clearResumeDialog()
                onNavigateSetReady()
            }

            is RoutineResumeUiOutcome.EnterDailyOverview -> {
                if (entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) {
                    openDailyOverview(outcome.routine)
                } else {
                    clearResumeDialog()
                }
            }

            is RoutineResumeUiOutcome.RetainDialog -> {
                resumeOperationInFlight = false
                discardRetryPending = outcome.retryAction == RoutineResumeRetryAction.DISCARD
            }

            RoutineResumeUiOutcome.ConnectionFailed -> {
                resumeOperationInFlight = false
                onConnectionFailed()
            }

            is RoutineResumeUiOutcome.LoadFailed -> {
                manualLoadRetry = outcome.retryOperation
                resumeOperationInFlight = false
                onWorkoutLoadFailed()
            }

            RoutineResumeUiOutcome.DismissDialog -> clearResumeDialog()

            RoutineResumeUiOutcome.StaleNoOp -> Unit
        }
    }

    fun launchResumeOperation(operation: RoutineResumeUiOperation) {
        resumeOperationInFlight = true
        resumeOperationGate.launch(scope) { actionToken ->
            val authority = RoutineResumeActionAuthority(
                entryPoint = entryPoint,
                actionToken = actionToken,
                currentToken = { resumeOperationGate.currentToken },
                contextIsCurrent = {
                    viewModel.isRoutineResumeProfileCurrent(operation.handle.selectedProfileId)
                },
            )
            val outcome = runRoutineResumeUiOperation(
                operation = operation,
                authority = authority,
                port = viewModel.routineResumeUiPort(),
            )
            when (
                val disposition = classifyRoutineResumeCompletion(
                    tokenCurrent = authority.tokenIsCurrent(),
                    contextCurrent = authority.contextIsCurrent(),
                    outcome = outcome,
                )
            ) {
                RoutineResumeCompletionDisposition.IgnoreStaleToken -> return@launch
                RoutineResumeCompletionDisposition.UnlockRetainedDialog -> resumeOperationInFlight = false
                is RoutineResumeCompletionDisposition.Apply -> dispatchResumeOutcome(disposition.outcome)
            }
        }
    }

    // Daily discovery keeps the token-only check. Cycle entries also require the profile
    // context to still match, which is what authority.isCurrent() adds on top of the token.
    suspend fun discoverDaily(routine: Routine, selectionToken: Int) {
        fun tokenMatches() = resumeOperationGate.currentToken == selectionToken
        when (
            val discovery = viewModel.discoverRoutineResume(
                routine = routine,
                launchOrigin = RoutineLaunchOrigin.DAILY_ROUTINES,
            )
        ) {
            is RoutineResumeDiscovery.Candidate -> if (tokenMatches()) {
                pendingResumeHandle = discovery.handle
                resumeOperationInFlight = false
            }

            RoutineResumeDiscovery.Missing -> if (tokenMatches()) {
                openDailyOverview(routine)
            }

            RoutineResumeDiscovery.RetryableFailure -> if (tokenMatches()) {
                resumeOperationInFlight = false
            }

            RoutineResumeDiscovery.Superseded -> if (tokenMatches()) {
                clearResumeDialog()
            }
        }
    }

    suspend fun discoverCycle(
        routine: Routine,
        cycleId: String,
        dayNumber: Int,
        selectionToken: Int,
    ) {
        val authority = RoutineResumeActionAuthority(
            entryPoint = entryPoint,
            actionToken = selectionToken,
            currentToken = { resumeOperationGate.currentToken },
            contextIsCurrent = { viewModel.isRoutineResumeProfileCurrent(routine.profileId) },
        )
        when (
            val discovery = viewModel.discoverRoutineResume(
                routine = routine,
                launchOrigin = RoutineLaunchOrigin.TRAINING_CYCLES,
                cycleId = cycleId,
                cycleDayNumber = dayNumber,
            )
        ) {
            is RoutineResumeDiscovery.Candidate -> if (authority.isCurrent()) {
                pendingResumeHandle = discovery.handle
                resumeOperationInFlight = false
            }

            RoutineResumeDiscovery.Missing -> if (authority.isCurrent()) {
                dispatchResumeOutcome(
                    runFreshCycleUiOperation(
                        routine = routine,
                        cycleId = cycleId,
                        dayNumber = dayNumber,
                        authority = authority,
                        port = viewModel.routineResumeUiPort(),
                    ),
                )
            }

            RoutineResumeDiscovery.RetryableFailure -> if (authority.isCurrent()) {
                resumeOperationInFlight = false
                onWorkoutLoadFailed()
            }

            RoutineResumeDiscovery.Superseded -> if (authority.isCurrent()) {
                clearResumeDialog()
            }
        }
    }

    fun beginSelection(
        routine: Routine,
        origin: RoutineLaunchOrigin,
        cycleId: String?,
        dayNumber: Int?,
    ) {
        pendingResumeHandle = null
        resumeOperationInFlight = true
        discardRetryPending = false
        manualLoadRetry = null
        resumeOperationGate.launch(scope) { selectionToken ->
            if (origin == RoutineLaunchOrigin.DAILY_ROUTINES) {
                discoverDaily(routine, selectionToken)
            } else {
                val exactCycleId = cycleId ?: return@launch
                val exactDayNumber = dayNumber ?: return@launch
                discoverCycle(
                    routine = routine,
                    cycleId = exactCycleId,
                    dayNumber = exactDayNumber,
                    selectionToken = selectionToken,
                )
            }
        }
    }

    SideEffect {
        launcher.bind(
            daily = { routine ->
                beginSelection(
                    routine = routine,
                    origin = RoutineLaunchOrigin.DAILY_ROUTINES,
                    cycleId = null,
                    dayNumber = null,
                )
            },
            cycle = { routine, cycleId, dayNumber ->
                beginSelection(
                    routine = routine,
                    origin = RoutineLaunchOrigin.TRAINING_CYCLES,
                    cycleId = cycleId,
                    dayNumber = dayNumber,
                )
            },
        )
    }

    LaunchedEffect(activeProfileContext) {
        val ready = activeProfileContext as? ActiveProfileContext.Ready ?: return@LaunchedEffect
        val handle = pendingResumeHandle ?: return@LaunchedEffect
        if (ready.profile.id != handle.selectedProfileId) {
            resumeOperationGate.supersede()
            clearResumeDialog()
        }
    }

    pendingResumeHandle?.let { handle ->
        val inMemoryHandle = (handle as? RoutineResumeHandle.InMemory)?.takeIf {
            entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES
        }
        val startGate = machineTeardownState.toStartGatePresentation(
            requiresMachine = inMemoryHandle?.let { captured ->
                captured.activeRoutineSnapshot.exercises
                    .getOrNull(captured.exerciseIndex)
                    ?.exercise?.isBodyweight != true
            } ?: false,
        )
        ResumeRoutineDialog(
            progressInfo = handle.progressInfo,
            onResume = {
                if (resumeOperationInFlight || discardRetryPending) return@ResumeRoutineDialog
                launchResumeOperation(manualLoadRetry ?: RoutineResumeUiOperation.Resume(handle))
            },
            onRestart = {
                if (resumeOperationInFlight) return@ResumeRoutineDialog
                manualLoadRetry = null
                launchResumeOperation(RoutineResumeUiOperation.Restart(handle))
            },
            onDismiss = {
                if (!resumeOperationInFlight) {
                    resumeOperationGate.supersede()
                    clearResumeDialog()
                }
            },
            confirmEnabled = !resumeOperationInFlight &&
                !discardRetryPending &&
                (inMemoryHandle == null || startGate.startEnabled),
            confirmLabel = if (inMemoryHandle != null &&
                startGate.label == StartGateLabel.FINISHING_PREVIOUS_WORKOUT
            ) {
                stringResource(Res.string.workout_teardown_finishing)
            } else {
                null
            },
            supportingContent = if (inMemoryHandle != null) {
                {
                    WorkoutStartGateNotice(
                        state = machineTeardownState,
                        onRetry = { viewModel.retryWorkoutTeardown() },
                        onReconnect = { viewModel.reconnectWorkoutTeardown() },
                    )
                }
            } else {
                null
            },
        )
    }
}
