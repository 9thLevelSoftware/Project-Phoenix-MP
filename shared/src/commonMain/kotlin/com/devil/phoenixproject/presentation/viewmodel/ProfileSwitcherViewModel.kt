package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ProfileContextRecoveryException
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.WorkoutState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RootProfileOperationKind { SWITCH, CREATE, RECOVERY }

data class RootProfileOperation(
    val token: Long,
    val kind: RootProfileOperationKind,
    val targetProfileId: String? = null,
)

enum class ProfileOverlayError {
    SWITCH_FAILED,
    CREATE_FAILED,
    RECOVERY_RETRY_FAILED,

    /** FP-6: a switch was refused because a workout is still in progress. */
    SWITCH_BLOCKED_DURING_WORKOUT,
}

data class ProfileSwitcherUiState(
    val showSwitcher: Boolean = false,
    val showAddDialog: Boolean = false,
    val recoveryRequired: Boolean = false,
    val operation: RootProfileOperation? = null,
    val error: ProfileOverlayError? = null,
)

class ProfileSwitcherViewModel(
    private val profiles: UserProfileRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileSwitcherUiState())
    val uiState: StateFlow<ProfileSwitcherUiState> = _uiState.asStateFlow()

    private var nextToken = 0L
    private var operationJob: Job? = null

    fun openSwitcher() {
        _uiState.update { state ->
            if (state.recoveryRequired) state else state.copy(showSwitcher = true, error = null)
        }
    }

    fun dismissSwitcher() {
        if (profiles.activeProfileContext.value is ActiveProfileContext.Switching) return
        _uiState.update { state ->
            if (state.operation != null || state.recoveryRequired) {
                state
            } else {
                ProfileSwitcherUiState()
            }
        }
    }

    fun openAddDialog() {
        if (profiles.activeProfileContext.value !is ActiveProfileContext.Ready) return
        _uiState.update { state ->
            if (!state.showSwitcher || state.operation != null || state.recoveryRequired) {
                state
            } else {
                state.copy(showAddDialog = true, error = null)
            }
        }
    }

    fun dismissAddDialog() {
        if (profiles.activeProfileContext.value is ActiveProfileContext.Switching) return
        _uiState.update { state ->
            if (state.operation != null || state.recoveryRequired) {
                state
            } else {
                state.copy(showAddDialog = false, error = null)
            }
        }
    }

    /**
     * FP-6: switching profiles mid-workout would leave the running set's writes
     * straddling two profiles, so the switch is refused while [workoutState] is
     * anything but [WorkoutState.Idle]. The refusal is visible: the sheet stays
     * open carrying [ProfileOverlayError.SWITCH_BLOCKED_DURING_WORKOUT], never a
     * silent no-op. The caller passes the live workout state because the workout
     * engine is owned by MainViewModel, not by the DI graph this ViewModel is
     * built from.
     */
    fun switchProfile(profileId: String, workoutState: WorkoutState) {
        val targetProfileId = profileId.trim()
        if (targetProfileId.isEmpty()) return
        val ready = profiles.activeProfileContext.value as? ActiveProfileContext.Ready ?: return
        if (ready.profile.id == targetProfileId || !_uiState.value.showSwitcher) return
        // Refuse BEFORE any operation starts: a switch mid-workout would split the
        // running set's writes across two profiles (FP-6). The sheet stays open
        // carrying the error so the refusal is visible, never a silent no-op.
        if (workoutState != WorkoutState.Idle) {
            _uiState.update { it.copy(error = ProfileOverlayError.SWITCH_BLOCKED_DURING_WORKOUT) }
            return
        }
        val operation = beginOperation(RootProfileOperationKind.SWITCH, targetProfileId) ?: return
        launchOwned(operation) {
            try {
                profiles.setActiveProfile(targetProfileId)
                finishOwned(operation.token) { ProfileSwitcherUiState() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProfileContextRecoveryException) {
                enterRecoveryIfOwned(operation.token, error)
            } catch (error: Exception) {
                finishOwned(operation.token) { state ->
                    state.copy(operation = null, error = ProfileOverlayError.SWITCH_FAILED)
                }
            }
        }
    }

    /**
     * Creating a profile also ACTIVATES it, so it is a profile switch and gets the
     * same FP-6 mid-workout refusal as [switchProfile]: the add dialog stays open
     * carrying [ProfileOverlayError.SWITCH_BLOCKED_DURING_WORKOUT].
     */
    fun createAndActivateProfile(name: String, colorIndex: Int, workoutState: WorkoutState) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        if (profiles.activeProfileContext.value !is ActiveProfileContext.Ready) return
        if (!_uiState.value.showAddDialog) return
        if (workoutState != WorkoutState.Idle) {
            _uiState.update { it.copy(error = ProfileOverlayError.SWITCH_BLOCKED_DURING_WORKOUT) }
            return
        }
        val operation = beginOperation(RootProfileOperationKind.CREATE) ?: return
        launchOwned(operation) {
            try {
                profiles.createAndActivateProfile(trimmedName, colorIndex)
                finishOwned(operation.token) { ProfileSwitcherUiState() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProfileContextRecoveryException) {
                enterRecoveryIfOwned(operation.token, error)
            } catch (error: Exception) {
                finishOwned(operation.token) { state ->
                    state.copy(operation = null, error = ProfileOverlayError.CREATE_FAILED)
                }
            }
        }
    }

    fun requireRecovery(error: ProfileContextRecoveryException) {
        enterRecovery(error)
    }

    fun retryRecovery() {
        val operation = beginOperation(
            kind = RootProfileOperationKind.RECOVERY,
            allowDuringRecovery = true,
        ) ?: return
        launchOwned(operation) {
            try {
                profiles.reconcileActiveProfileContext()
                finishOwned(operation.token) { ProfileSwitcherUiState() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                finishOwned(operation.token) { state ->
                    state.copy(
                        operation = null,
                        recoveryRequired = true,
                        error = ProfileOverlayError.RECOVERY_RETRY_FAILED,
                    )
                }
            }
        }
    }

    private fun beginOperation(
        kind: RootProfileOperationKind,
        targetProfileId: String? = null,
        allowDuringRecovery: Boolean = false,
    ): RootProfileOperation? {
        val state = _uiState.value
        if (state.operation != null) return null
        if (state.recoveryRequired != allowDuringRecovery) return null
        val operation = RootProfileOperation(++nextToken, kind, targetProfileId)
        _uiState.value = state.copy(operation = operation, error = null)
        return operation
    }

    private fun launchOwned(
        operation: RootProfileOperation,
        block: suspend () -> Unit,
    ) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                finishOwned(operation.token) { state -> state.copy(operation = null) }
            }
        }
        operationJob = job
        job.start()
    }

    private inline fun finishOwned(
        token: Long,
        transform: (ProfileSwitcherUiState) -> ProfileSwitcherUiState,
    ) {
        _uiState.update { state ->
            if (state.operation?.token == token) transform(state) else state
        }
    }

    private fun enterRecoveryIfOwned(token: Long, error: ProfileContextRecoveryException) {
        if (_uiState.value.operation?.token == token) enterRecovery(error)
    }

    private fun enterRecovery(error: ProfileContextRecoveryException) {
        Logger.e(error) { "PROFILE_CONTEXT: root recovery required" }
        operationJob?.cancel()
        _uiState.value = ProfileSwitcherUiState(recoveryRequired = true)
    }
}
