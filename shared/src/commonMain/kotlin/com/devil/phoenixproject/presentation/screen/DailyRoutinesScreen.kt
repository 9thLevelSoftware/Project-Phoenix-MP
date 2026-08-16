package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineGroup
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.presentation.components.ResumeRoutineDialog
import com.devil.phoenixproject.presentation.components.StartGateLabel
import com.devil.phoenixproject.presentation.components.WorkoutStartGateNotice
import com.devil.phoenixproject.presentation.components.toStartGatePresentation
import com.devil.phoenixproject.presentation.manager.RoutineResumeDiscovery
import com.devil.phoenixproject.presentation.manager.RoutineResumeHandle
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeActionAuthority
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeCompletionDisposition
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeEntryPoint
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeOperationGate
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeRetryAction
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeUiOperation
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeUiOutcome
import com.devil.phoenixproject.presentation.viewmodel.classifyRoutineResumeCompletion
import com.devil.phoenixproject.presentation.viewmodel.runRoutineResumeUiOperation
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Daily Routines screen - view and manage pre-built routines.
 * This screen wraps the existing RoutinesTab functionality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyRoutinesScreen(
    navController: NavController,
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository,
    themeMode: com.devil.phoenixproject.ui.theme.ThemeMode,
) {
    val routines by viewModel.routines.collectAsState()
    val routineGroups by viewModel.routineGroups.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val enableVideoPlayback by viewModel.enableVideoPlayback.collectAsState()
    val machineTeardownState by viewModel.machineTeardownState.collectAsState()

    val connectionError by viewModel.connectionError.collectAsState()

    // Profile data for move/copy to profile feature (#330)
    val profileRepository: UserProfileRepository = koinInject()
    val profiles by profileRepository.allProfiles.collectAsState()
    val activeProfile by profileRepository.activeProfile.collectAsState()
    val activeProfileContext by profileRepository.activeProfileContext.collectAsState()

    // Resume/Restart dialog state (Issue #101)
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

    LaunchedEffect(activeProfileContext) {
        val ready = activeProfileContext as? ActiveProfileContext.Ready ?: return@LaunchedEffect
        val handle = pendingResumeHandle ?: return@LaunchedEffect
        if (ready.profile.id != handle.selectedProfileId) {
            resumeOperationGate.supersede()
            clearResumeDialog()
        }
    }

    fun enterFreshRoutine(routine: Routine) {
        clearResumeDialog()
        viewModel.enterRoutineOverview(routine)
        navController.navigate(NavigationRoutes.RoutineOverview.route)
    }

    fun launchResumeOperation(operation: RoutineResumeUiOperation) {
        resumeOperationInFlight = true
        resumeOperationGate.launch(scope) { actionToken ->
            val authority = RoutineResumeActionAuthority(
                entryPoint = RoutineResumeEntryPoint.DAILY_ROUTINES,
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
            val currentOutcome = when (
                val disposition = classifyRoutineResumeCompletion(
                    tokenCurrent = authority.tokenIsCurrent(),
                    contextCurrent = authority.contextIsCurrent(),
                    outcome = outcome,
                )
            ) {
                RoutineResumeCompletionDisposition.IgnoreStaleToken -> return@launch

                RoutineResumeCompletionDisposition.UnlockRetainedDialog -> {
                    resumeOperationInFlight = false
                    return@launch
                }

                is RoutineResumeCompletionDisposition.Apply -> disposition.outcome
            }
            when (currentOutcome) {
                RoutineResumeUiOutcome.NavigateActiveWorkout -> {
                    clearResumeDialog()
                    navController.navigate(NavigationRoutes.ActiveWorkout.route)
                }

                RoutineResumeUiOutcome.StartAndNavigateActiveWorkout -> {
                    viewModel.startWorkout()
                    clearResumeDialog()
                    navController.navigate(NavigationRoutes.ActiveWorkout.route)
                }

                is RoutineResumeUiOutcome.EnterSetReady -> {
                    viewModel.enterSetReady(currentOutcome.exerciseIndex, currentOutcome.setIndex)
                    clearResumeDialog()
                    navController.navigate(NavigationRoutes.SetReady.route)
                }

                is RoutineResumeUiOutcome.EnterDailyOverview -> enterFreshRoutine(currentOutcome.routine)

                is RoutineResumeUiOutcome.RetainDialog -> {
                    resumeOperationInFlight = false
                    discardRetryPending = currentOutcome.retryAction == RoutineResumeRetryAction.DISCARD
                }

                RoutineResumeUiOutcome.DismissDialog -> clearResumeDialog()

                RoutineResumeUiOutcome.ConnectionFailed,
                -> resumeOperationInFlight = false

                is RoutineResumeUiOutcome.LoadFailed -> {
                    manualLoadRetry = currentOutcome.retryOperation
                    resumeOperationInFlight = false
                }

                RoutineResumeUiOutcome.StaleNoOp -> Unit
            }
        }
    }

    // Issue #130: Block routine editing during active workout
    var showWorkoutActiveDialog by remember { mutableStateOf(false) }

    // Set global title
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("Daily Routines")
    }

    val backgroundGradient = screenBackgroundBrush()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient),
    ) {
        // Reuse RoutinesTab content
        RoutinesTab(
            routines = routines,
            exerciseRepository = exerciseRepository,
            personalRecordRepository = viewModel.personalRecordRepository,
            formatWeight = viewModel::formatWeight,
            weightUnit = weightUnit,
            enableVideoPlayback = enableVideoPlayback,
            kgToDisplay = viewModel::kgToDisplay,
            displayToKg = viewModel::displayToKg,
            onStartWorkout = { routine ->
                pendingResumeHandle = null
                resumeOperationInFlight = true
                discardRetryPending = false
                manualLoadRetry = null
                resumeOperationGate.launch(scope) { selectionToken ->
                    when (
                        val discovery = viewModel.discoverRoutineResume(
                            routine = routine,
                            launchOrigin = RoutineLaunchOrigin.DAILY_ROUTINES,
                        )
                    ) {
                        is RoutineResumeDiscovery.Candidate -> if (resumeOperationGate.currentToken == selectionToken) {
                            pendingResumeHandle = discovery.handle
                            resumeOperationInFlight = false
                        }

                        RoutineResumeDiscovery.Missing -> if (resumeOperationGate.currentToken == selectionToken) {
                            enterFreshRoutine(routine)
                        }

                        RoutineResumeDiscovery.RetryableFailure -> if (resumeOperationGate.currentToken == selectionToken) {
                            resumeOperationInFlight = false
                        }

                        RoutineResumeDiscovery.Superseded -> if (resumeOperationGate.currentToken == selectionToken) {
                            clearResumeDialog()
                        }
                    }
                }
            },
            onStartWorkoutWithModifier = { routine, modifier ->
                viewModel.enterRoutineOverview(routine, modifier)
                navController.navigate(NavigationRoutes.RoutineOverview.route)
            },
            onDeleteRoutine = { routineId -> viewModel.deleteRoutine(routineId) },
            onDeleteRoutines = { routineIds -> viewModel.deleteRoutines(routineIds) },
            onSaveRoutine = { routine -> viewModel.saveRoutine(routine) },
            profiles = profiles,
            activeProfileId = activeProfile?.id ?: "default",
            onMoveToProfile = { routineIds, targetProfileId ->
                viewModel.moveRoutinesToProfile(routineIds, targetProfileId)
            },
            onSaveRoutineToProfile = { routine, targetProfileId ->
                viewModel.saveRoutineToProfile(routine, targetProfileId)
            },
            // Routine group support
            routineGroups = routineGroups,
            onCreateGroup = { name -> viewModel.createGroup(name) },
            onRenameGroup = { groupId, newName -> viewModel.renameGroup(groupId, newName) },
            onDeleteGroup = { groupId -> viewModel.deleteGroup(groupId) },
            onMoveToGroup = { routineIds, groupId -> viewModel.moveRoutinesToGroup(routineIds, groupId) },
            onEditRoutine = { routineId ->
                // Issue #130: Block editing during active workout
                if (viewModel.isWorkoutActive) {
                    showWorkoutActiveDialog = true
                } else {
                    navController.navigate(NavigationRoutes.RoutineEditor.createRoute(routineId))
                }
            },
            onCreateRoutine = {
                // Issue #130: Block creating during active workout
                if (viewModel.isWorkoutActive) {
                    showWorkoutActiveDialog = true
                } else {
                    navController.navigate(NavigationRoutes.RoutineEditor.createRoute("new"))
                }
            },
            themeMode = themeMode,
            modifier = Modifier.fillMaxSize(),
        )

        // Connection error dialog (ConnectingOverlay removed - status shown in top bar button)
        connectionError?.let { error ->
            com.devil.phoenixproject.presentation.components.ConnectionErrorDialog(
                message = error,
                onDismiss = { viewModel.clearConnectionError() },
            )
        }

        // Resume/Restart Dialog (Issue #101)
        pendingResumeHandle?.let { handle ->
            val info = handle.progressInfo
            val inMemoryHandle = handle as? RoutineResumeHandle.InMemory
            val startGate = machineTeardownState.toStartGatePresentation(
                requiresMachine = inMemoryHandle?.let { captured ->
                    captured.activeRoutineSnapshot.exercises
                        .getOrNull(captured.exerciseIndex)
                        ?.exercise?.isBodyweight != true
                } ?: false,
            )
            ResumeRoutineDialog(
                progressInfo = info,
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

        // Issue #130: Workout Active Dialog - blocks routine editing during workout
        if (showWorkoutActiveDialog) {
            AlertDialog(
                onDismissRequest = { showWorkoutActiveDialog = false },
                title = { Text(stringResource(Res.string.workout_in_progress)) },
                text = { Text(stringResource(Res.string.stop_before_editing)) },
                confirmButton = {
                    TextButton(onClick = { showWorkoutActiveDialog = false }) {
                        Text(stringResource(Res.string.action_ok))
                    }
                },
            )
        }
    }
}
