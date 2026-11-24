package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.maintenance.CompactionReport
import com.devil.phoenixproject.data.maintenance.CompactionResult
import com.devil.phoenixproject.data.maintenance.TelemetryCompactionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the "Telemetry storage cleanup" sheet (issue #1425, prototype D1–D5). */
sealed class TelemetryCleanupUiState {
    data object Idle : TelemetryCleanupUiState()

    data class Checking(val checked: Int, val total: Int) : TelemetryCleanupUiState()

    data class Ready(val report: CompactionReport) : TelemetryCleanupUiState()

    data class Blocked(val reason: BlockedReason) : TelemetryCleanupUiState()

    data class Cleaning(val checked: Int, val total: Int) : TelemetryCleanupUiState()

    data class Done(val result: CompactionResult) : TelemetryCleanupUiState()

    data class Failed(val message: String?) : TelemetryCleanupUiState()
}

enum class BlockedReason { ACTIVE_WORKOUT, BACKUP_RUNNING }

/**
 * State holder for the manual telemetry cleanup flow (issue #1425 P4).
 *
 * G0 (I3): opening the sheet while a workout session is active yields [BlockedReason.ACTIVE_WORKOUT];
 * a running backup/restore yields [BlockedReason.BACKUP_RUNNING]. [runBeforeManualBackup] is the
 * I7 pre-backup cleanup (`compact(vacuum = false)`): it NEVER propagates failure — a cleanup hiccup
 * must not block the user's backup (signoff #5), it is only logged.
 */
class TelemetryCleanupController(
    private val service: TelemetryCompactionService,
    private val workoutSessionGuard: () -> Boolean,
    private val isBackupRunning: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<TelemetryCleanupUiState>(TelemetryCleanupUiState.Idle)
    val state: StateFlow<TelemetryCleanupUiState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var stopRequested = false

    /** Opens the sheet: starts the read-only check. */
    fun open() {
        if (workoutSessionGuard()) {
            _state.value = TelemetryCleanupUiState.Blocked(BlockedReason.ACTIVE_WORKOUT)
            return
        }
        if (isBackupRunning()) {
            _state.value = TelemetryCleanupUiState.Blocked(BlockedReason.BACKUP_RUNNING)
            return
        }
        stopRequested = false
        activeJob?.cancel()
        activeJob = scope.launch {
            _state.value = TelemetryCleanupUiState.Checking(0, 0)
            val result = runCatching {
                service.analyze(
                    progress = { checked, total ->
                        _state.value = TelemetryCleanupUiState.Checking(checked, total)
                    },
                )
            }
            _state.value = result.fold(
                onSuccess = { TelemetryCleanupUiState.Ready(it) },
                onFailure = { TelemetryCleanupUiState.Failed(it.message) },
            )
        }
    }

    /** Runs the cleanup pass shown by the current [TelemetryCleanupUiState.Ready]. */
    fun cleanUp() {
        if (workoutSessionGuard()) {
            _state.value = TelemetryCleanupUiState.Blocked(BlockedReason.ACTIVE_WORKOUT)
            return
        }
        stopRequested = false
        activeJob?.cancel()
        activeJob = scope.launch {
            _state.value = TelemetryCleanupUiState.Cleaning(0, 0)
            val result = runCatching {
                service.compact(
                    vacuum = true,
                    progress = { checked, total ->
                        _state.value = TelemetryCleanupUiState.Cleaning(checked, total)
                    },
                    stopRequested = { stopRequested },
                )
            }
            _state.value = result.fold(
                onSuccess = { TelemetryCleanupUiState.Done(it) },
                onFailure = { TelemetryCleanupUiState.Failed(it.message) },
            )
        }
    }

    /**
     * Single stop action (D2): sets are atomic, so the pass finishes the current set and stops
     * before the next one.
     */
    fun stopAfterThisSet() {
        stopRequested = true
    }

    fun dismiss() {
        activeJob?.cancel()
        activeJob = null
        _state.value = TelemetryCleanupUiState.Idle
    }

    /**
     * I7 / signoff #5: run `compact(vacuum = false)` before a manual full backup. Failure or
     * abort never blocks the export — the backup always goes ahead. Runs before BOTH Save and
     * Share (the Settings backup dialog calls this on each path).
     */
    suspend fun runBeforeManualBackup() {
        runCatching { service.compact(vacuum = false) }
    }
}
