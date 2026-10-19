package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.devil.phoenixproject.data.csv.CsvImportDeliveryResult
import com.devil.phoenixproject.data.csv.CsvImportInbox
import com.devil.phoenixproject.data.csv.CsvImportOffer
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.RoutineIdentity
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.presentation.components.RoutineCsvImportDialog
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvImportUiState
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_ok
import projectphoenix.shared.generated.resources.action_open
import projectphoenix.shared.generated.resources.routine_csv_import_busy
import projectphoenix.shared.generated.resources.routine_csv_imported_builder
import projectphoenix.shared.generated.resources.routine_csv_imported_builder_more
import projectphoenix.shared.generated.resources.routine_csv_saved_daily
import projectphoenix.shared.generated.resources.stop_before_editing
import projectphoenix.shared.generated.resources.workout_in_progress

/**
 * In-app side of a routine CSV shared to or opened with the app (#1242).
 *
 * The file always goes through the existing import preview, so nothing is written until the
 * user taps Import there, a file from a web link included. After the write the first imported
 * routine opens in the routine builder. Every way into the builder, the snackbar's Open
 * included, goes through [openImportedRoutine], which rechecks the profile, the workout and
 * the committed routine right before navigating. A dirty routine editor is asked before an
 * import replaces its routine and before the app leaves it.
 */
@Composable
internal fun SharedCsvImportHost(
    viewModel: MainViewModel,
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
) {
    val inbox: CsvImportInbox = koinInject()
    val profileRepository: UserProfileRepository = koinInject()
    // Activity-scoped and keyed apart from the Daily Routines instance, whose in-app import is unchanged.
    val csvViewModel: RoutineCsvViewModel = koinViewModel(key = "shared-routine-csv-import")
    val scope = rememberCoroutineScope()
    val controller = remember(viewModel, navController, snackbarHostState, csvViewModel, inbox, scope) {
        SharedCsvImportController(viewModel, navController, snackbarHostState, csvViewModel, inbox, scope)
    }
    val inboxState by inbox.state.collectAsState()
    val busyRejections by inbox.busyRejections.collectAsState()
    val importState by csvViewModel.importState.collectAsState()

    // Cleared only after the message was shown: clearing first would restart this effect
    // and cancel the snackbar. Another rejection meanwhile restarts it with a fresh one.
    LaunchedEffect(busyRejections) {
        if (busyRejections > 0) {
            snackbarHostState.showSnackbar(getString(Res.string.routine_csv_import_busy))
            inbox.takeBusyRejections()
        }
    }

    // Take a ready delivery once the active profile is known (cold start runs startup first).
    LaunchedEffect(inboxState) {
        val offer = (inboxState as? CsvImportInbox.State.Ready)?.offer ?: return@LaunchedEffect
        withTimeoutOrNull(PROFILE_READY_TIMEOUT_MS) {
            profileRepository.activeProfileContext.first { it is ActiveProfileContext.Ready }
        }
        controller.take(offer)
    }

    // A written import ends the delivery; the builder replaces the count dialog.
    LaunchedEffect(importState) {
        val imported = importState as? RoutineCsvImportUiState.Imported ?: return@LaunchedEffect
        controller.onImported(imported)
    }

    val dialogState = importState
    if (dialogState != null && dialogState !is RoutineCsvImportUiState.Imported) {
        RoutineCsvImportDialog(
            state = dialogState,
            onSelectMode = csvViewModel::selectMode,
            onConfirm = controller::confirm,
            onDismiss = controller::dismiss,
        )
    }

    if (controller.showWorkoutDialog) {
        AlertDialog(
            onDismissRequest = { controller.showWorkoutDialog = false },
            title = { Text(stringResource(Res.string.workout_in_progress)) },
            text = { Text(stringResource(Res.string.stop_before_editing)) },
            confirmButton = {
                TextButton(onClick = { controller.showWorkoutDialog = false }) {
                    Text(stringResource(Res.string.action_ok))
                }
            },
        )
    }
}

/** The decisions behind [SharedCsvImportHost]; all calls run on the main thread. */
private class SharedCsvImportController(
    private val viewModel: MainViewModel,
    private val navController: NavHostController,
    private val snackbarHostState: SnackbarHostState,
    private val csvViewModel: RoutineCsvViewModel,
    private val inbox: CsvImportInbox,
    private val scope: CoroutineScope,
) {
    var showWorkoutDialog by mutableStateOf(false)

    /** The user already agreed, for this import, to discard the open editor's draft. */
    private var leaveConsented = false

    fun take(offer: CsvImportOffer) {
        if (!inbox.claim(offer)) return
        csvViewModel.holdSharedFileDelivery { inbox.finish(offer.deliveryId) }
        leaveConsented = false
        if (workoutActive()) {
            csvViewModel.releaseSharedFileDelivery()
            showWorkoutDialog = true
            return
        }
        when (val result = offer.result) {
            is CsvImportDeliveryResult.Read -> csvViewModel.previewImport(result.content)
            CsvImportDeliveryResult.TooLarge -> csvViewModel.onFileTooLarge()
            CsvImportDeliveryResult.Unreadable -> csvViewModel.onFileUnreadable()
        }
    }

    fun dismiss() {
        csvViewModel.dismissImport()
        // A dismiss while the write runs is ignored and keeps the delivery.
        if (csvViewModel.importState.value == null) csvViewModel.releaseSharedFileDelivery()
    }

    fun confirm() {
        if (workoutActive()) {
            dismiss()
            showWorkoutDialog = true
            return
        }
        val preview = csvViewModel.importState.value as? RoutineCsvImportUiState.Preview ?: return
        val editing = RoutineEditorLeaveGate.registeredRoutineId()
        val replacesDirtyDraft = editing != null &&
            RoutineEditorLeaveGate.isDirty() &&
            preview.plan.overwriteRoutineIds.any { RoutineIdentity.matches(it, editing) }
        if (replacesDirtyDraft) {
            // Consent comes before the write: Cancel writes nothing and keeps the draft.
            RoutineEditorLeaveGate.requestLeave(onLeft = {
                leaveConsented = true
                csvViewModel.confirmImport()
            })
        } else {
            csvViewModel.confirmImport()
        }
    }

    fun onImported(imported: RoutineCsvImportUiState.Imported) {
        csvViewModel.dismissImport()
        csvViewModel.releaseSharedFileDelivery()
        scope.launch { openImportedRoutine(imported) }
    }

    /**
     * The only way into the builder, the snackbar's Open included: waits for the committed
     * routine, then opens it only while the import's profile is active and no workout runs.
     */
    private suspend fun openImportedRoutine(imported: RoutineCsvImportUiState.Imported) {
        val readiness = withTimeoutOrNull(NAV_READY_TIMEOUT_MS) {
            combine(viewModel.routines, viewModel.activeProfileId) { routines, profileId ->
                csvImportReadiness(routines, profileId, imported)
            }.first { it != CsvImportReadiness.WAIT }
        } ?: CsvImportReadiness.WAIT
        when {
            readiness == CsvImportReadiness.READY && openableRoutine(imported) != null -> navigateToImported(imported)
            // Saved, but the list has not shown it yet: Open tries again.
            readiness == CsvImportReadiness.WAIT -> showSavedSnackbar(imported, offerOpen = true)
            // The profile changed or a workout started: never open it into that state.
            else -> showSavedSnackbar(imported, offerOpen = false)
        }
    }

    private fun navigateToImported(imported: RoutineCsvImportUiState.Imported) {
        val open: () -> Unit = {
            // Rechecked after the discard dialog too: the user may have taken a while.
            val routine = openableRoutine(imported)
            if (routine == null) {
                scope.launch { showSavedSnackbar(imported, offerOpen = false) }
            } else {
                // An open editor of a routine this import replaced shows the old content.
                val replacesOpenEditor = RoutineEditorLeaveGate.registeredRoutineId()
                    ?.let { editing -> imported.routineIds.any { RoutineIdentity.matches(it, editing) } } == true
                navController.navigate(NavigationRoutes.RoutineEditor.createRoute(routine.id)) {
                    if (replacesOpenEditor) popUpTo(NavigationRoutes.RoutineEditor.route) { inclusive = true }
                }
                scope.launch { showBuilderSnackbar(imported) }
            }
        }
        if (leaveConsented) {
            open()
        } else {
            RoutineEditorLeaveGate.requestLeave(
                onLeft = open,
                onCancelled = { scope.launch { showSavedSnackbar(imported, offerOpen = true) } },
            )
        }
    }

    /** The routine was saved but is not being shown; [offerOpen] when trying again can help. */
    private suspend fun showSavedSnackbar(imported: RoutineCsvImportUiState.Imported, offerOpen: Boolean) {
        val action = snackbarHostState.showSnackbar(
            message = getString(Res.string.routine_csv_saved_daily, imported.routineNames.firstOrNull().orEmpty()),
            actionLabel = if (offerOpen) getString(Res.string.action_open) else null,
            withDismissAction = offerOpen,
            duration = SnackbarDuration.Long,
        )
        if (action == SnackbarResult.ActionPerformed) openImportedRoutine(imported)
    }

    private suspend fun showBuilderSnackbar(imported: RoutineCsvImportUiState.Imported) {
        val name = imported.routineNames.firstOrNull().orEmpty()
        val message = if (imported.routineCount > 1) {
            getString(Res.string.routine_csv_imported_builder_more, name, imported.routineCount - 1)
        } else {
            getString(Res.string.routine_csv_imported_builder, name)
        }
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
    }

    /** The committed first routine, when it may be opened right now; null otherwise. */
    private fun openableRoutine(imported: RoutineCsvImportUiState.Imported): Routine? {
        if (workoutActive() || viewModel.activeProfileId.value != imported.profileId) return null
        return importedRoutine(viewModel.routines.value, imported)
    }

    private fun workoutActive(): Boolean = viewModel.isWorkoutActive || viewModel.isInWorkoutSessionNow()
}

internal enum class CsvImportReadiness { READY, WAIT, PROFILE_CHANGED }

/**
 * Whether the first imported routine can be shown: the import's profile must still be the
 * active one, and the active profile's list must hold the committed write (not the copy it
 * replaced, which has the same id).
 */
internal fun csvImportReadiness(
    routines: List<Routine>,
    activeProfileId: String,
    imported: RoutineCsvImportUiState.Imported,
): CsvImportReadiness = when {
    activeProfileId != imported.profileId -> CsvImportReadiness.PROFILE_CHANGED
    importedRoutine(routines, imported) != null -> CsvImportReadiness.READY
    else -> CsvImportReadiness.WAIT
}

/** The first imported routine as [routines] holds it, once that copy is the committed write. */
internal fun importedRoutine(routines: List<Routine>, imported: RoutineCsvImportUiState.Imported): Routine? {
    val id = imported.routineIds.firstOrNull() ?: return null
    return routines.firstOrNull { RoutineIdentity.matches(it.id, id) && (it.updatedAt ?: 0L) >= imported.committedAfter }
}

/** How long a written import waits for the routine list before saying where the routine is. */
private const val NAV_READY_TIMEOUT_MS = 5_000L

/** How long a delivery waits for the active profile before the preview reports it. */
private const val PROFILE_READY_TIMEOUT_MS = 15_000L
