package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.data.repository.LogLevel
import com.devil.phoenixproject.data.repository.MachineSafetyConnectResult
import com.devil.phoenixproject.data.repository.MachineSafetyHazardCause
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    ) : MachineSafetyUiState {
        val identity: MachineSafetyHazardIdentity
            get() = MachineSafetyHazardIdentity(document.trainerAddress, document.generation)
    }
}

data class MachineSafetyHazardIdentity(
    val trainerAddress: String,
    val generation: Long,
) {
    init {
        require(trainerAddress.isNotBlank())
        require(generation > 0L)
    }
}

enum class MachineSafetyRecoveryRequestResult {
    STARTED,
    ALREADY_IN_PROGRESS,
    NO_VISIBLE_HAZARD,
    STALE_HAZARD,
}

/**
 * Durable, trainer-identity-bound safety recovery. A transport ACK is never treated as unload,
 * with one exception: [resolveArmedExecution] clears this process's own hidden arm row after a
 * clean teardown RESET that succeeded while still connected to the armed trainer. A row that is
 * visible, or that this process did not arm (for example after a relaunch), still needs
 * the user's explicit acknowledgement through [acknowledgeUnloaded].
 */
class MachineSafetyCoordinator(
    private val repository: MachineSafetyHazardRepository,
    private val transport: MachineSafetyTransport,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val sessionIdFactory: () -> String = { "safety-${Random.nextLong()}" },
    private val persistMachineArming: Boolean = true,
    /** Connection-log sink; every barrier decision is written here so a log export names the cause. */
    private val logRepo: ConnectionLogRepository? = null,
) {
    private val mutex = Mutex()
    private val _uiState = MutableStateFlow<MachineSafetyUiState>(MachineSafetyUiState.Hidden)
    val uiState: StateFlow<MachineSafetyUiState> = _uiState.asStateFlow()
    private var recoveryJob: Job? = null
    private var acknowledgementInProgress: MachineSafetyHazardIdentity? = null
    private var nextGeneration = 0L

    /** The hidden arm row this coordinator last persisted; only it may be resolved by a clean teardown. */
    private var armedDocument: MachineSafetyHazardDocument? = null

    suspend fun restoreOnStartup() = surfaceStoredHazard()

    /**
     * Show the most recent stored hazard, e.g. when a machine start was refused by the barrier.
     * Does nothing while a warning is already visible, so an in-flight recovery is never replaced.
     * A refusal caused only by [liveExecutionId]'s own hidden arm row is a replacement-start
     * race over a live set, not a hazard, so it is not surfaced.
     */
    suspend fun surfaceStoredHazard(liveExecutionId: Long? = null): Unit = mutex.withLock {
        if (_uiState.value is MachineSafetyUiState.Visible) return
        val results = try {
            repository.loadAll()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            listOf(MachineSafetyLoadResult.Rejected(MachineSafetyRejection.CORRUPT_JSON, null))
        }
        val records = results.mapNotNull { result ->
            when (result) {
                is MachineSafetyLoadResult.Loaded -> result.document
                is MachineSafetyLoadResult.Rejected -> rejectedDocument(result)
                MachineSafetyLoadResult.Missing -> null
            }
        }
        val armed = armedDocument
        val onlyLiveArm = liveExecutionId != null && armed != null && armed.executionId == liveExecutionId &&
            results.all { (it as? MachineSafetyLoadResult.Loaded)?.document?.generation == armed.generation }
        if (onlyLiveArm) return
        records.maxByOrNull { it.updatedAtEpochMs }?.let { newest ->
            show(newest, records.any { doc -> doc.sessionId.startsWith("rejected-") })
            logSafety(
                LogLevel.WARNING,
                "Stored machine-safety hazard shown; new sets stay blocked until the user confirms the trainer is at rest",
                newest,
                liveExecutionId?.let { "refusedExecutionId=$it" },
            )
        }
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
            // A visible loss replaces that trainer's arm row and is never cleared by a clean teardown.
            if (!showRecoveryUi) {
                armedDocument = persisted
            } else if (armedDocument?.trainerAddress == persisted.trainerAddress) {
                armedDocument = null
            }
            if (showRecoveryUi) {
                show(persisted)
                logSafety(LogLevel.WARNING, "Machine-safety hazard recorded", persisted)
            } else {
                logSafety(LogLevel.DEBUG, "Machine set armed: durable safety obligation written before the load command", persisted)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logSafety(LogLevel.ERROR, "Machine-safety obligation could not be persisted", persisted, error.message)
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
        return !persistMachineArming || recordMachineSessionArmed(
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
                cause = MachineSafetyHazardCause.SET_END_UNCONFIRMED,
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
                if (deleted) {
                    armedDocument = null
                    logSafety(LogLevel.DEBUG, "Machine set ended with a confirmed stop; arm row resolved", armed)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * A dismissed warning remains a durable start barrier until physical acknowledgement.
     * Serialized with [resolveArmedExecution], so a start never reads a row a resolve is clearing.
     */
    suspend fun canStartMachine(): Boolean = mutex.withLock { canStartMachineLocked() }

    private suspend fun canStartMachineLocked(): Boolean = try {
        val results = repository.loadAll()
        val storedRows = results.count { it !is MachineSafetyLoadResult.Missing }
        val visible = _uiState.value as? MachineSafetyUiState.Visible
        val allowed = storedRows == 0 && visible == null
        if (!allowed) {
            val newestStored = results
                .filterIsInstance<MachineSafetyLoadResult.Loaded>()
                .maxByOrNull { it.document.updatedAtEpochMs }
                ?.document
            logSafety(
                LogLevel.WARNING,
                "Machine start refused: unresolved machine-safety hazard (no command sent to the trainer)",
                visible?.document ?: newestStored,
                "storedRows=$storedRows,warningVisible=${visible != null}",
            )
        }
        allowed
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        logSafety(LogLevel.ERROR, "Machine start refused: machine-safety store unreadable", null, error.message)
        false
    }

    suspend fun recordUnexpectedDisconnect(
        trainerAddress: String?,
        trainerName: String? = null,
    ): Boolean = mutex.withLock {
        if (trainerAddress.isNullOrBlank()) return false
        (_uiState.value as? MachineSafetyUiState.Visible)?.let { visible ->
            logSafety(LogLevel.DEBUG, "Unexpected disconnect while a machine-safety warning is already visible", visible.document)
            return true
        }

        val loadResult = try {
            repository.load(trainerAddress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MachineSafetyLoadResult.Rejected(MachineSafetyRejection.CORRUPT_JSON, trainerAddress)
        }
        val durable = when (loadResult) {
            is MachineSafetyLoadResult.Loaded -> loadResult.document
            is MachineSafetyLoadResult.Rejected -> rejectedDocument(loadResult)
            MachineSafetyLoadResult.Missing -> armedDocument?.takeIf { it.trainerAddress == trainerAddress }
        } ?: run {
            logRepo?.log(
                LogLevel.DEBUG,
                LogEventType.MACHINE_SAFETY,
                "Unexpected disconnect with no armed set on this trainer; no hazard recorded",
                trainerName,
                trainerAddress,
            )
            return false
        }
        val loss = durable.copy(
            trainerName = trainerName ?: durable.trainerName,
            updatedAtEpochMs = nowEpochMs(),
            cause = MachineSafetyHazardCause.CONNECTION_LOST,
            phase = MachineSafetyPhase.UNRESOLVED,
            lastConnectResult = MachineSafetyConnectResult.NONE,
            lastWriteResult = MachineSafetyWriteResult.NONE,
            recoveryAttemptToken = 0L,
        )
        armedDocument = null
        try {
            repository.replace(loss)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the in-memory warning visible so the process remains fail closed.
        }
        show(loss, restoreWasRejected = loadResult is MachineSafetyLoadResult.Rejected)
        logSafety(LogLevel.WARNING, "Connection lost with a machine obligation pending; hazard recorded and new sets blocked", loss)
        true
    }

    suspend fun requestReleaseRecovery(
        identity: MachineSafetyHazardIdentity,
    ): MachineSafetyRecoveryRequestResult = mutex.withLock {
        val visible = _uiState.value as? MachineSafetyUiState.Visible
            ?: return MachineSafetyRecoveryRequestResult.NO_VISIBLE_HAZARD
        if (visible.document.identity != identity) {
            return MachineSafetyRecoveryRequestResult.STALE_HAZARD
        }
        if (acknowledgementInProgress == identity) {
            return MachineSafetyRecoveryRequestResult.ALREADY_IN_PROGRESS
        }
        if (recoveryJob?.isActive == true) return MachineSafetyRecoveryRequestResult.ALREADY_IN_PROGRESS
        recoveryJob = scope.launch { recoverVisibleHazard(identity) }
        return MachineSafetyRecoveryRequestResult.STARTED
    }

    private suspend fun recoverVisibleHazard(identity: MachineSafetyHazardIdentity) {
        val connecting = mutex.withLock {
            val current = _uiState.value as? MachineSafetyUiState.Visible ?: return
            if (current.document.identity != identity) return
            val document = current.document.copy(
                updatedAtEpochMs = nowEpochMs(),
                phase = MachineSafetyPhase.CONNECTING,
                recoveryAttemptToken = current.document.recoveryAttemptToken + 1L,
            )
            if (!persistIfCurrentLocked(identity, document)) return
            document
        }
        val token = connecting.recoveryAttemptToken
        logSafety(LogLevel.INFO, "Release recovery requested by user; looking for the hazard's trainer", connecting)
        val connected = transport.connectedTrainerAddress
        val connectResult = if (connected == identity.trainerAddress) {
            Result.success(Unit)
        } else if (connected != null) {
            Result.failure(IllegalStateException("wrong trainer"))
        } else {
            transport.connectMatchingTrainer(identity.trainerAddress)
        }
        if (!isCurrent(identity, token)) return
        if (connectResult.isFailure || transport.connectedTrainerAddress != identity.trainerAddress) {
            val failure = connecting.copy(
                updatedAtEpochMs = nowEpochMs(),
                phase = MachineSafetyPhase.RELEASE_REQUEST_FAILED,
                lastConnectResult = if (connected != null) MachineSafetyConnectResult.WRONG_TRAINER else MachineSafetyConnectResult.FAILED,
                lastWriteResult = MachineSafetyWriteResult.NOT_CONNECTED,
            )
            persistIfCurrent(identity, failure)
            logSafety(
                LogLevel.WARNING,
                "Release recovery could not reach the hazard's trainer; no stop was requested",
                failure,
                connected?.let { "connectedTrainer=$it" },
            )
            return
        }
        val write = try {
            transport.stopWorkout()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("stop failed"))
        }
        if (!isCurrent(identity, token)) return
        val disconnectedDuringWrite = transport.connectedTrainerAddress != identity.trainerAddress
        val outcome = if (write.isSuccess && !disconnectedDuringWrite) {
            connecting.copy(
                updatedAtEpochMs = nowEpochMs(),
                phase = MachineSafetyPhase.RELEASE_REQUEST_SENT,
                lastConnectResult = MachineSafetyConnectResult.MATCHING_READY,
                lastWriteResult = MachineSafetyWriteResult.TRANSPORT_ACK,
            )
        } else {
            connecting.copy(
                updatedAtEpochMs = nowEpochMs(),
                phase = MachineSafetyPhase.RELEASE_REQUEST_FAILED,
                lastConnectResult = MachineSafetyConnectResult.MATCHING_READY,
                lastWriteResult = if (disconnectedDuringWrite) {
                    MachineSafetyWriteResult.DISCONNECTED_DURING_WRITE
                } else {
                    MachineSafetyWriteResult.TRANSPORT_FAIL
                },
            )
        }
        persistIfCurrent(identity, outcome)
        if (outcome.phase == MachineSafetyPhase.RELEASE_REQUEST_SENT) {
            logSafety(LogLevel.INFO, "Stop requested over Bluetooth and acknowledged by transport; physical load still unknown", outcome)
        } else {
            logSafety(LogLevel.WARNING, "Stop request failed; hazard remains", outcome, write.exceptionOrNull()?.message)
        }
    }

    suspend fun hideTemporarily(): Unit = mutex.withLock {
        (_uiState.value as? MachineSafetyUiState.Visible)?.let { visible ->
            _uiState.value = MachineSafetyUiState.Hidden
            logSafety(LogLevel.DEBUG, "Machine-safety warning hidden until the next machine start; barrier stays in place", visible.document)
        }
    }

    suspend fun acknowledgeUnloaded(identity: MachineSafetyHazardIdentity) {
        val recoveryToCancel = mutex.withLock {
            val visible = _uiState.value as? MachineSafetyUiState.Visible ?: return
            if (visible.document.identity != identity || acknowledgementInProgress != null) return
            acknowledgementInProgress = identity
            recoveryJob
        }
        try {
            recoveryToCancel?.cancelAndJoin()
            mutex.withLock {
                val visible = _uiState.value as? MachineSafetyUiState.Visible ?: return@withLock
                if (visible.document.identity != identity || acknowledgementInProgress != identity) {
                    return@withLock
                }
                if (repository.deleteIfGenerationMatches(identity.trainerAddress, identity.generation) &&
                    (_uiState.value as? MachineSafetyUiState.Visible)?.document?.identity == identity
                ) {
                    if (armedDocument?.identity == identity) armedDocument = null
                    _uiState.value = MachineSafetyUiState.Hidden
                    logSafety(LogLevel.INFO, "User confirmed handles at rest; machine-safety hazard cleared", visible.document)
                } else {
                    logSafety(LogLevel.WARNING, "Acknowledgement did not clear the hazard (record changed); warning stays", visible.document)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keep the exact warning visible and the start gate closed.
            logSafety(LogLevel.ERROR, "Acknowledgement failed to clear the hazard; warning stays", null, error.message)
        } finally {
            withContext(NonCancellable) {
                recoveryToCancel?.cancelAndJoin()
                mutex.withLock {
                    if (acknowledgementInProgress == identity) acknowledgementInProgress = null
                    if (recoveryJob === recoveryToCancel && recoveryToCancel?.isCompleted == true) {
                        recoveryJob = null
                    }
                }
            }
        }
    }

    private suspend fun persistIfCurrent(
        identity: MachineSafetyHazardIdentity,
        document: MachineSafetyHazardDocument,
    ): Boolean = mutex.withLock {
        persistIfCurrentLocked(identity, document)
    }

    private suspend fun persistIfCurrentLocked(
        identity: MachineSafetyHazardIdentity,
        document: MachineSafetyHazardDocument,
    ): Boolean {
        val current = _uiState.value as? MachineSafetyUiState.Visible ?: return false
        return !(current.document.identity != identity ||
                document.identity != identity ||
                current.document.recoveryAttemptToken > document.recoveryAttemptToken) && try {
            repository.replace(document)
            show(document, current.restoreWasRejected)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun isCurrent(identity: MachineSafetyHazardIdentity, token: Long): Boolean = mutex.withLock {
        val current = _uiState.value as? MachineSafetyUiState.Visible ?: return false
        current.document.identity == identity && current.document.recoveryAttemptToken == token
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

    private val MachineSafetyHazardDocument.identity: MachineSafetyHazardIdentity
        get() = MachineSafetyHazardIdentity(trainerAddress, generation)

    private fun logSafety(
        level: LogLevel,
        message: String,
        document: MachineSafetyHazardDocument?,
        extraDetails: String? = null,
    ) {
        val sink = logRepo ?: return
        val recordDetails = document?.let {
            "generation=${it.generation},cause=${it.cause},phase=${it.phase}," +
                "connect=${it.lastConnectResult},write=${it.lastWriteResult},kind=${it.workoutKind}"
        }
        sink.log(
            level = level,
            eventType = LogEventType.MACHINE_SAFETY,
            message = message,
            deviceName = document?.trainerName,
            deviceAddress = document?.trainerAddress,
            details = listOfNotNull(recordDetails, extraDetails).joinToString("; ").ifEmpty { null },
        )
    }
}
