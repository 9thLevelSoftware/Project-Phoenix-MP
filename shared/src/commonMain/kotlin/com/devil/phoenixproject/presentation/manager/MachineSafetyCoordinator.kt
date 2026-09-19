package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.MachineSafetyConnectResult
import com.devil.phoenixproject.data.repository.MachineSafetyHazardDocument
import com.devil.phoenixproject.data.repository.MachineSafetyHazardRepository
import com.devil.phoenixproject.data.repository.MachineSafetyLoadResult
import com.devil.phoenixproject.data.repository.MachineSafetyPhase
import com.devil.phoenixproject.data.repository.MachineSafetyPhysicalRelease
import com.devil.phoenixproject.data.repository.MachineSafetyRejection
import com.devil.phoenixproject.data.repository.MachineSafetyWorkoutKind
import com.devil.phoenixproject.data.repository.MachineSafetyWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

interface MachineSafetyTransport {
    val connectedTrainerAddress: String?
    suspend fun connectMatchingTrainer(trainerAddress: String): Result<Unit>
    suspend fun stopWorkout(): Result<Unit>
}

sealed interface MachineSafetyUiState {
    data object Hidden : MachineSafetyUiState
    data class Visible(
        val document: MachineSafetyHazardDocument,
        val restoreWasRejected: Boolean = false,
        val blockedMachineStart: Boolean = true,
    ) : MachineSafetyUiState
}

/** Durable, trainer-identity-bound safety recovery. Never equates transport ACK with unload. */
class MachineSafetyCoordinator(
    private val repository: MachineSafetyHazardRepository,
    private val transport: MachineSafetyTransport,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val sessionIdFactory: () -> String = { "safety-${Random.nextLong()}" },
    private val persistMachineArming: Boolean = true,
) {
    private val mutex = Mutex()
    private val _uiState = MutableStateFlow<MachineSafetyUiState>(MachineSafetyUiState.Hidden)
    val uiState: StateFlow<MachineSafetyUiState> = _uiState.asStateFlow()
    private var recoveryJob: Job? = null
    private var nextGeneration = 0L
    private var interruptedWorkoutResumeAuthorized = false

    /** The hidden arm row this coordinator last persisted; only it may be resolved by a clean teardown. */
    private var armedDocument: MachineSafetyHazardDocument? = null

    suspend fun restoreOnStartup() = surfaceStoredHazard()

    /** Show the most recent stored hazard, e.g. when a machine start was refused by the barrier. */
    suspend fun surfaceStoredHazard() {
        val results = try { repository.loadAll() } catch (_: Exception) {
            listOf(MachineSafetyLoadResult.Rejected(MachineSafetyRejection.CORRUPT_JSON, null))
        }
        val records = results.mapNotNull { result ->
            when (result) {
                is MachineSafetyLoadResult.Loaded -> result.document
                is MachineSafetyLoadResult.Rejected -> rejectedDocument(result)
                MachineSafetyLoadResult.Missing -> null
            }
        }
        records.maxByOrNull { it.updatedAtEpochMs }?.let { show(it, records.any { doc -> doc.sessionId.startsWith("rejected-") }) }
    }

    suspend fun recordMachineSessionArmed(
        document: MachineSafetyHazardDocument,
        showRecoveryUi: Boolean = false,
    ): Boolean = mutex.withLock {
        if (document.trainerAddress.isBlank()) return false
        val safeGeneration = maxOf(document.generation, nextGeneration + 1L)
        val persisted = document.copy(generation = safeGeneration, phase = MachineSafetyPhase.UNRESOLVED)
        return try {
            repository.replace(persisted)
            nextGeneration = safeGeneration
            // A visible loss replaces the arm row and must never be cleared by a clean teardown.
            armedDocument = if (showRecoveryUi) null else persisted
            if (showRecoveryUi) {
                // A new visible loss owns RESET-only recovery. Never let a continuation
                // authorization granted for an earlier hidden execution cross this boundary.
                interruptedWorkoutResumeAuthorized = false
                show(persisted)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Establish the durable obligation before a machine command can load force.
     * A missing/unknown trainer identity is an intentional fail-closed result.
     */
    suspend fun armBeforeMachineCommand(
        executionId: Long,
        profileId: String?,
        kind: MachineSafetyWorkoutKind,
    ): Boolean {
        val trainerAddress = transport.connectedTrainerAddress ?: return false
        if (!persistMachineArming) return true
        return recordMachineSessionArmed(
            MachineSafetyHazardDocument(
                generation = nextGeneration + 1L,
                trainerAddress = trainerAddress,
                sessionId = sessionIdFactory(),
                executionId = executionId,
                profileId = profileId,
                workoutKind = kind,
                createdAtEpochMs = nowEpochMs(),
                updatedAtEpochMs = nowEpochMs(),
                phase = MachineSafetyPhase.UNRESOLVED,
                physicalRelease = MachineSafetyPhysicalRelease.UNKNOWN,
            ),
        )
    }

    /**
     * Clear the hidden arm row for [executionId] after its set ended through a successful
     * RESET while still connected to the same trainer. Only the exact row this coordinator
     * armed (same trainer, generation and execution) is deleted; a visible loss, a newer
     * arm, or a different connected trainer leaves the durable barrier in place.
     */
    suspend fun resolveArmedExecution(executionId: Long): Boolean = mutex.withLock {
        val armed = armedDocument ?: return false
        if (armed.executionId != executionId) return false
        if (_uiState.value !is MachineSafetyUiState.Hidden) return false
        if (transport.connectedTrainerAddress != armed.trainerAddress) return false
        return try {
            val stored = (repository.load(armed.trainerAddress) as? MachineSafetyLoadResult.Loaded)?.document
            if (stored == null || stored.generation != armed.generation || stored.executionId != executionId) return false
            repository.deleteIfGenerationMatches(armed.trainerAddress, armed.generation).also { deleted ->
                if (deleted) armedDocument = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Authorize the already interrupted execution to rebuild its current set after a
     * transport reconnect. This is deliberately separate from dismissal: it is a
     * one-shot continuation for an existing execution, not permission for a new start.
     * A visible safety warning never takes this path; its RESET-only recovery remains
     * owned by [requestReleaseRecovery].
     */
    fun authorizeInterruptedWorkoutResume() {
        if (_uiState.value is MachineSafetyUiState.Hidden) {
            interruptedWorkoutResumeAuthorized = true
        }
    }

    /** A dismissed warning remains a durable start barrier until physical acknowledgement. */
    suspend fun canStartMachine(): Boolean = try {
        val hasUnresolvedHazard = repository.loadAll().any { result ->
            when (result) {
                is MachineSafetyLoadResult.Loaded -> true
                is MachineSafetyLoadResult.Rejected -> true
                MachineSafetyLoadResult.Missing -> false
            }
        }
        if (!hasUnresolvedHazard) {
            interruptedWorkoutResumeAuthorized = false
            true
        } else if (interruptedWorkoutResumeAuthorized && _uiState.value is MachineSafetyUiState.Hidden) {
            interruptedWorkoutResumeAuthorized = false
            true
        } else {
            interruptedWorkoutResumeAuthorized = false
            false
        }
    } catch (_: Exception) {
        false
    }

    suspend fun recordConnectionLost(
        trainerAddress: String?,
        trainerName: String? = null,
        sessionId: String = sessionIdFactory(),
        profileId: String? = null,
        kind: MachineSafetyWorkoutKind = MachineSafetyWorkoutKind.UNKNOWN,
        executionId: Long? = null,
    ): Boolean {
        if (trainerAddress.isNullOrBlank()) return false
        val now = nowEpochMs()
        val document = MachineSafetyHazardDocument(
            generation = nextGeneration + 1L,
            trainerAddress = trainerAddress,
            trainerName = trainerName,
            sessionId = sessionId,
            executionId = executionId,
            profileId = profileId,
            workoutKind = kind,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            phase = MachineSafetyPhase.UNRESOLVED,
            physicalRelease = MachineSafetyPhysicalRelease.UNKNOWN,
        )
        return recordMachineSessionArmed(document, showRecoveryUi = true)
    }

    fun requestReleaseRecovery() {
        val visible = _uiState.value as? MachineSafetyUiState.Visible ?: return
        if (recoveryJob?.isActive == true) return
        recoveryJob = scope.launch {
            val token = visible.document.recoveryAttemptToken + 1L
            val connecting = visible.document.copy(
                updatedAtEpochMs = nowEpochMs(),
                phase = MachineSafetyPhase.CONNECTING,
                recoveryAttemptToken = token,
            )
            if (!persistIfCurrent(visible.document.generation, connecting)) return@launch
            val connected = transport.connectedTrainerAddress
            val connectResult = if (connected == visible.document.trainerAddress) {
                Result.success(Unit)
            } else if (connected != null) {
                Result.failure(IllegalStateException("wrong trainer"))
            } else {
                transport.connectMatchingTrainer(visible.document.trainerAddress)
            }
            if (!isCurrent(visible.document.generation, token)) return@launch
            if (connectResult.isFailure || transport.connectedTrainerAddress != visible.document.trainerAddress) {
                val failure = connecting.copy(
                    updatedAtEpochMs = nowEpochMs(),
                    phase = MachineSafetyPhase.RELEASE_REQUEST_FAILED,
                    lastConnectResult = if (connected != null) MachineSafetyConnectResult.WRONG_TRAINER else MachineSafetyConnectResult.FAILED,
                    lastWriteResult = MachineSafetyWriteResult.NOT_CONNECTED,
                )
                persistIfCurrent(visible.document.generation, failure)
                return@launch
            }
            val write = try { transport.stopWorkout() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { Result.failure(IllegalStateException("stop failed")) }
            if (!isCurrent(visible.document.generation, token)) return@launch
            val disconnectedDuringWrite = transport.connectedTrainerAddress != visible.document.trainerAddress
            val outcome = if (write.isSuccess && !disconnectedDuringWrite) connecting.copy(
                updatedAtEpochMs = nowEpochMs(),
                phase = MachineSafetyPhase.RELEASE_REQUEST_SENT,
                lastConnectResult = MachineSafetyConnectResult.MATCHING_READY,
                lastWriteResult = MachineSafetyWriteResult.TRANSPORT_ACK,
            ) else connecting.copy(
                updatedAtEpochMs = nowEpochMs(),
                phase = MachineSafetyPhase.RELEASE_REQUEST_FAILED,
                lastConnectResult = MachineSafetyConnectResult.MATCHING_READY,
                lastWriteResult = if (disconnectedDuringWrite) {
                    MachineSafetyWriteResult.DISCONNECTED_DURING_WRITE
                } else {
                    MachineSafetyWriteResult.TRANSPORT_FAIL
                },
            )
            persistIfCurrent(visible.document.generation, outcome)
        }
    }

    fun hideTemporarily() { if (_uiState.value is MachineSafetyUiState.Visible) _uiState.value = MachineSafetyUiState.Hidden }

    fun acknowledgeUnloaded(generation: Long) {
        val visible = _uiState.value as? MachineSafetyUiState.Visible ?: return
        if (visible.document.generation != generation) return
        scope.launch {
            if (repository.deleteIfGenerationMatches(visible.document.trainerAddress, generation)) {
                _uiState.value = MachineSafetyUiState.Hidden
            }
        }
    }

    private suspend fun persistIfCurrent(generation: Long, document: MachineSafetyHazardDocument): Boolean = mutex.withLock {
        val current = _uiState.value as? MachineSafetyUiState.Visible ?: return false
        if (current.document.generation != generation || current.document.recoveryAttemptToken > document.recoveryAttemptToken) return false
        return try { repository.replace(document); show(document); true } catch (_: Exception) { false }
    }

    private suspend fun isCurrent(generation: Long, token: Long): Boolean = mutex.withLock {
        val current = _uiState.value as? MachineSafetyUiState.Visible ?: return false
        current.document.generation == generation && current.document.recoveryAttemptToken == token
    }

    private fun show(document: MachineSafetyHazardDocument, restoreWasRejected: Boolean = false) {
        nextGeneration = maxOf(nextGeneration, document.generation)
        _uiState.value = MachineSafetyUiState.Visible(document, restoreWasRejected)
    }

    private fun rejectedDocument(result: MachineSafetyLoadResult.Rejected): MachineSafetyHazardDocument {
        val now = nowEpochMs()
        return MachineSafetyHazardDocument(
            generation = nextGeneration + 1L,
            trainerAddress = result.trainerAddress ?: "unknown-trainer",
            sessionId = "rejected-${now}",
            workoutKind = MachineSafetyWorkoutKind.UNKNOWN,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            phase = MachineSafetyPhase.UNRESOLVED,
            physicalRelease = MachineSafetyPhysicalRelease.UNKNOWN,
        )
    }
}
