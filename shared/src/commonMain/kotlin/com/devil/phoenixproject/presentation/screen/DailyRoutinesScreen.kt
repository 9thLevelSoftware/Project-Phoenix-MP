package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.csv.RoutineCsvFormat
import com.devil.phoenixproject.presentation.components.RoutineCsvExportBlockedDialog
import com.devil.phoenixproject.presentation.components.RoutineCsvImportDialog
import com.devil.phoenixproject.presentation.components.RoutineRecoveryHost
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvExportUiState
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvViewModel
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeEntryPoint
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import com.devil.phoenixproject.util.BoundedUriContent
import com.devil.phoenixproject.util.readUriContentUpTo
import com.devil.phoenixproject.util.rememberFilePicker
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Daily Routines screen - view and manage pre-built routines.
 * This screen wraps the existing RoutinesTab functionality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyRoutinesScreen(
    navController: NavController,
    viewModel: MainViewModel,
) {
    val routines by viewModel.routines.collectAsState()
    val routineGroups by viewModel.routineGroups.collectAsState()

    val connectionError by viewModel.connectionError.collectAsState()

    // Profile data for move/copy to profile feature (#330)
    val profileRepository: UserProfileRepository = koinInject()
    val profiles by profileRepository.allProfiles.collectAsState()
    val activeProfile by profileRepository.activeProfile.collectAsState()
    val routineResume = rememberRoutineResumeLauncher()
    val scope = rememberCoroutineScope()

    // Issue #130: Block routine editing during active workout
    var showWorkoutActiveDialog by remember { mutableStateOf(false) }

    // Issue #772: routine CSV import and export
    val routineCsvViewModel: RoutineCsvViewModel = koinViewModel()
    val csvImportState by routineCsvViewModel.importState.collectAsState()
    val csvExportState by routineCsvViewModel.exportState.collectAsState()
    var pickRoutineCsv by remember { mutableStateOf(false) }

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
        Column(modifier = Modifier.fillMaxSize()) {
            // Issue #1162: recoverable routines entry (explicit restore-as-copy). It
            // renders nothing until a snapshot exists, never blocks the list, and the
            // sheet opens only on explicit request.
            RoutineRecoveryHost(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp, end = 20.dp),
            )
            // Reuse RoutinesTab content
            RoutinesTab(
                routines = routines,
                onStartWorkout = { routine -> routineResume.launchDailyRoutine(routine) },
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
                // Issue #1223: Create with AI. Same workout-active guard dialog as
                // onCreateRoutine; navigates to the ai_routine prompt+preview flow
                // (never the "new" editor — the AI draft is staged in memory and
                // consumed by the editor only after explicit Edit).
                onCreateWithAi = {
                    // Issue #130: Block creating during active workout
                    if (viewModel.isWorkoutActive) {
                        showWorkoutActiveDialog = true
                    } else {
                        navController.navigate(NavigationRoutes.AiRoutine.route)
                    }
                },
                onExportRoutineCsv = { routine -> routineCsvViewModel.exportRoutine(routine) },
                onImportRoutinesCsv = {
                    // Same rule as editing: routines are not replaced during a workout.
                    if (viewModel.isWorkoutActive) {
                        showWorkoutActiveDialog = true
                    } else {
                        pickRoutineCsv = true
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }

        if (pickRoutineCsv) {
            val filePicker = rememberFilePicker()
            filePicker.LaunchCsvFilePicker { uri ->
                pickRoutineCsv = false
                if (uri != null) {
                    scope.launch {
                        when (val read = readUriContentUpTo(uri, RoutineCsvFormat.MAX_BYTES)) {
                            is BoundedUriContent.Read -> routineCsvViewModel.previewImport(read.content)
                            BoundedUriContent.TooLarge -> routineCsvViewModel.onFileTooLarge()
                            BoundedUriContent.Unreadable -> routineCsvViewModel.onFileUnreadable()
                        }
                    }
                }
            }
        }

        csvImportState?.let { state ->
            RoutineCsvImportDialog(
                state = state,
                onSelectMode = routineCsvViewModel::selectMode,
                onConfirm = routineCsvViewModel::confirmImport,
                onDismiss = routineCsvViewModel::dismissImport,
            )
        }

        when (val export = csvExportState) {
            is RoutineCsvExportUiState.Ready -> {
                val filePicker = rememberFilePicker()
                filePicker.LaunchCsvFileSaver(export.fileName, export.content) {
                    routineCsvViewModel.clearExport()
                }
            }

            is RoutineCsvExportUiState.Blocked -> RoutineCsvExportBlockedDialog(
                state = export,
                onDismiss = routineCsvViewModel::clearExport,
            )

            null -> Unit
        }

        // Connection error dialog
        connectionError?.let { error ->
            com.devil.phoenixproject.presentation.components.ConnectionErrorDialog(
                message = error,
                onDismiss = { viewModel.clearConnectionError() },
            )
        }

        // Daily routines resume into the overview, the active workout, or set-ready.
        RoutineResumeDialogHost(
            launcher = routineResume,
            viewModel = viewModel,
            entryPoint = RoutineResumeEntryPoint.DAILY_ROUTINES,
            onNavigateActiveWorkout = {
                navController.navigate(NavigationRoutes.ActiveWorkout.route)
            },
            onNavigateSetReady = {
                navController.navigate(NavigationRoutes.SetReady.route)
            },
            onNavigateOverview = {
                navController.navigate(NavigationRoutes.RoutineOverview.route)
            },
        )

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
