package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.domain.model.HeuristicStatistics
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.DeviceInfo
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages the 4 BLE polling loops and their lifecycle for MetricPollingEngine extraction (Phase 11).
 *
 * Polling loops:
 * - Monitor: 10-20Hz (no delay on success, BLE response time rate-limits)
 * - Diagnostic: 1Hz (500ms interval) for keep-alive and fault detection
 * - Heuristic: 4Hz (250ms interval) for force telemetry
 * - Heartbeat: 0.5Hz (2000ms interval) for connection keep-alive
 *
 * Critical invariant (Issue #222): [stopMonitorOnly] MUST preserve diagnostic, heuristic,
 * and heartbeat polling. Each loop has an independent Job reference for selective cancellation.
 *
 * Timeout disconnect (POLL-03): After [BleConstants.Timing.MAX_CONSECUTIVE_TIMEOUTS] consecutive
 * monitor read timeouts, [onConnectionLost] is invoked to trigger a full disconnect.
 */
class MetricPollingEngine(
    private val scope: CoroutineScope,
    private val bleQueue: BleOperationQueue,
    private val monitorProcessor: MonitorDataProcessor,
    private val handleDetector: HandleStateDetector,
    private val onMetricEmit: (WorkoutMetric) -> Boolean,
    private val onHeuristicData: (HeuristicStatistics) -> Unit,
    private val onConnectionLost: suspend () -> Unit,
    private val logRepo: ConnectionLogRepository? = null,
) {
    private val log = Logger.withTag("MetricPollingEngine")

    // Characteristic references from BleConstants
    private val txCharacteristic = BleConstants.txCharacteristic
    private val monitorCharacteristic = BleConstants.monitorCharacteristic
    private val diagnosticCharacteristic = BleConstants.diagnosticCharacteristic
    private val heuristicCharacteristic = BleConstants.heuristicCharacteristic

    // Job references — each independently cancellable for selective stop (Issue #222)
    private var monitorPollingJob: Job? = null
    private var diagnosticPollingJob: Job? = null
    private var heuristicPollingJob: Job? = null
    private var heartbeatJob: Job? = null

    private var monitorSampleLogsRemaining = 3
    private var diagnosticSampleLogsRemaining = 3
    private var heuristicSampleLogsRemaining = 3
    private var heartbeatSampleLogsRemaining = 3

    // Monitor polling mutex — prevents concurrent monitor loops
    private val monitorPollingMutex = Mutex()

    // Diagnostic state
    internal var diagnosticPollCount: Long = 0
        private set
    internal var lastDiagnosticFaults: List<Short>? = null
        private set

    // Timeout tracking for POLL-03
    private var consecutiveTimeouts = 0

    /** Polling loop type for test job state inspection. */
    enum class PollingType { MONITOR, DIAGNOSTIC, HEURISTIC, HEARTBEAT }

    /** Test helper: check if a specific polling job is active. */
    internal fun isJobActive(type: PollingType): Boolean = when (type) {
        PollingType.MONITOR -> monitorPollingJob?.isActive == true
        PollingType.DIAGNOSTIC -> diagnosticPollingJob?.isActive == true
        PollingType.HEURISTIC -> heuristicPollingJob?.isActive == true
        PollingType.HEARTBEAT -> heartbeatJob?.isActive == true
    }

    // ===== Test helpers (internal, not part of public API) =====

    internal fun startFakeJobs() {
        monitorPollingJob?.cancel()
        monitorPollingJob = scope.launch {
            while (true) {
                delay(Long.MAX_VALUE)
            }
        }
        diagnosticPollingJob?.cancel()
        diagnosticPollingJob = scope.launch {
            while (true) {
                delay(Long.MAX_VALUE)
            }
        }
        heuristicPollingJob?.cancel()
        heuristicPollingJob = scope.launch {
            while (true) {
                delay(Long.MAX_VALUE)
            }
        }
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(Long.MAX_VALUE)
            }
        }
    }

    internal fun startFakeJob(type: PollingType) {
        when (type) {
            PollingType.MONITOR -> {
                monitorPollingJob?.cancel()
                monitorPollingJob = scope.launch {
                    while (true) {
                        delay(Long.MAX_VALUE)
                    }
                }
            }

            PollingType.DIAGNOSTIC -> {
                diagnosticPollingJob?.cancel()
                diagnosticPollingJob = scope.launch {
                    while (true) {
                        delay(Long.MAX_VALUE)
                    }
                }
            }

            PollingType.HEURISTIC -> {
                heuristicPollingJob?.cancel()
                heuristicPollingJob = scope.launch {
                    while (true) {
                        delay(Long.MAX_VALUE)
                    }
                }
            }

            PollingType.HEARTBEAT -> {
                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (true) {
                        delay(Long.MAX_VALUE)
                    }
                }
            }
        }
    }

    internal fun restartAllFake() {
        startFakeJob(PollingType.MONITOR)
        if (diagnosticPollingJob?.isActive != true) startFakeJob(PollingType.DIAGNOSTIC)
        if (heartbeatJob?.isActive != true) startFakeJob(PollingType.HEARTBEAT)
        if (heuristicPollingJob?.isActive != true) startFakeJob(PollingType.HEURISTIC)
    }

    internal fun restartDiagnosticAndHeartbeatFake() {
        if (diagnosticPollingJob?.isActive != true) startFakeJob(PollingType.DIAGNOSTIC)
        if (heartbeatJob?.isActive != true) startFakeJob(PollingType.HEARTBEAT)
    }

    internal fun incrementDiagnosticCount() {
        diagnosticPollCount++
    }
    internal fun simulateTimeout() {
        consecutiveTimeouts++
    }
    internal fun simulateSuccessfulRead() {
        consecutiveTimeouts = 0
    }

    internal suspend fun checkTimeoutThreshold() {
        if (consecutiveTimeouts >= BleConstants.Timing.MAX_CONSECUTIVE_TIMEOUTS) {
            onConnectionLost()
        }
    }

    // ===== Public API =====

    /** Start all 4 polling loops. Called from startObservingNotifications(). */
    fun startAll(peripheral: Peripheral) {
        log.i { "Starting all polling loops" }
        logPollingLifecycle(
            loopName = "all",
            phase = "startAll_requested",
            intervalMs = null,
            previousJobState = pollingJobStates(),
        )
        startMonitorPolling(peripheral)
        startDiagnosticPolling(peripheral)
        startHeuristicPolling(peripheral)
        startHeartbeat(peripheral)
        logPollingLifecycle(
            loopName = "all",
            phase = "startAll_launched",
            intervalMs = null,
            previousJobState = pollingJobStates(),
        )
    }

    /**
     * Start monitor polling loop for real-time position/load data.
     *
     * Monitor characteristic MUST be polled (not notified). Uses Mutex to prevent
     * concurrent monitor loops. NO fixed delay on success — BLE response time
     * naturally rate-limits (~10-20ms).
     *
     * @param forAutoStart If true, enables handle detection for Just Lift auto-start.
     */
    fun startMonitorPolling(peripheral: Peripheral, forAutoStart: Boolean = false) {
        val previousJobState = jobState(monitorPollingJob)
        // Reset monitor processing state for new session
        val previousCount = monitorProcessor.notificationCount
        monitorProcessor.resetForNewSession()
        log.i { "Monitor processor reset (previous session: $previousCount notifications)" }
        logPollingLifecycle(
            loopName = "monitor",
            phase = "start_requested forAutoStart=$forAutoStart",
            intervalMs = 0L,
            previousJobState = previousJobState,
        )

        if (forAutoStart) {
            handleDetector.enable(autoStart = true)
            log.i {
                "Monitor polling for AUTO-START - waiting for handles at rest (pos < ${BleConstants.Thresholds.HANDLE_REST_THRESHOLD}mm), vel threshold=${BleConstants.Thresholds.AUTO_START_VELOCITY_THRESHOLD}mm/s"
            }
        }

        // Cancel existing job before starting new one
        monitorPollingJob?.cancel()

        monitorPollingJob = scope.launch {
            // Mutex ensures only one polling loop runs at a time.
            // Do NOT add `if (mutex.isLocked) return` — causes race condition (pitfall #4).
            monitorPollingMutex.withLock {
                var failCount = 0
                var successCount = 0L
                consecutiveTimeouts = 0
                log.i {
                    "Starting SEQUENTIAL monitor polling (timeout=${BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS}ms, forAutoStart=$forAutoStart)"
                }

                // Auto-start packet capture in debug builds for hardware validation
                if (DeviceInfo.isDebugBuild && !BlePacketCapture.isCapturing) {
                    BlePacketCapture.startCapture(desc = "auto-capture on monitor poll start")
                    log.i { "BlePacketCapture auto-started (debug build). Use adb logcat | grep CAPTURE_HEX to view." }
                }

                try {
                    while (isActive) {
                        try {
                            val data = withTimeoutOrNull(BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS) {
                                bleQueue.read { peripheral.read(monitorCharacteristic) }
                            }

                            if (data != null) {
                                successCount++
                                consecutiveTimeouts = 0
                                logPollingSample("monitor", data, successCount, null)
                                if (successCount == 1L || successCount % 500 == 0L) {
                                    log.i { "Monitor poll SUCCESS #$successCount, data size: ${data.size}" }
                                }
                                parseMonitorData(data)
                                failCount = 0
                                // NO DELAY on success — BLE response time naturally rate-limits
                            } else {
                                consecutiveTimeouts++
                                if (consecutiveTimeouts <= 3 || consecutiveTimeouts % 10 == 0) {
                                    log.w {
                                        "Monitor read timed out (${BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS}ms) - consecutive: $consecutiveTimeouts"
                                    }
                                }
                                // POLL-03: Disconnect after too many consecutive timeouts
                                if (consecutiveTimeouts >= BleConstants.Timing.MAX_CONSECUTIVE_TIMEOUTS) {
                                    log.e { "Too many consecutive timeouts ($consecutiveTimeouts), triggering disconnect" }
                                    scope.launch { onConnectionLost() }
                                    return@withLock
                                }
                                delay(50)
                            }
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                            consecutiveTimeouts++
                            log.w { "Monitor read timeout exception - consecutive: $consecutiveTimeouts" }
                            delay(50)
                        } catch (e: Exception) {
                            failCount++
                            consecutiveTimeouts = 0
                            if (failCount <= 5 || failCount % 50 == 0) {
                                log.w { "Monitor poll FAILED #$failCount: ${e.message}" }
                            }
                            delay(50)
                        }
                    }
                } catch (e: Exception) {
                    log.e { "Monitor polling stopped: ${e.message}" }
                }
                log.i { "Monitor polling ended (reads: $successCount, failures: $failCount, timeouts: $consecutiveTimeouts)" }
                logPollingLifecycle(
                    loopName = "monitor",
                    phase = "ended reads=$successCount failures=$failCount timeouts=$consecutiveTimeouts",
                    intervalMs = 0L,
                    previousJobState = "ending",
                )
            }
        }
    }

    /**
     * Poll DIAGNOSTIC characteristic every 500ms for keep-alive and health monitoring.
     */
    fun startDiagnosticPolling(peripheral: Peripheral) {
        val previousJobState = jobState(diagnosticPollingJob)
        logPollingLifecycle(
            loopName = "diagnostic",
            phase = "start_requested",
            intervalMs = BleConstants.Timing.DIAGNOSTIC_POLL_INTERVAL_MS,
            previousJobState = previousJobState,
        )
        diagnosticPollingJob?.cancel()
        diagnosticPollingJob = scope.launch {
            log.d { "Starting SEQUENTIAL diagnostic polling (${BleConstants.Timing.DIAGNOSTIC_POLL_INTERVAL_MS}ms interval)" }
            var successfulReads = 0L
            var failedReads = 0L
            diagnosticPollCount = 0
            lastDiagnosticFaults = null

            while (isActive) {
                try {
                    val data = withTimeoutOrNull(BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS) {
                        bleQueue.read { peripheral.read(diagnosticCharacteristic) }
                    }

                    if (data != null) {
                        successfulReads++
                        diagnosticPollCount++
                        logPollingSample("diagnostic", data, successfulReads, null)
                        if (diagnosticPollCount == 1L || diagnosticPollCount % BleConstants.Timing.DIAGNOSTIC_LOG_EVERY == 0L) {
                            log.d { "Diagnostic poll #$diagnosticPollCount (bytes=${data.size}, failed=$failedReads)" }
                        }
                        parseDiagnosticData(data)
                    } else {
                        failedReads++
                    }

                    delay(BleConstants.Timing.DIAGNOSTIC_POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    failedReads++
                    if (failedReads <= 5 || failedReads % 20 == 0L) {
                        log.w { "Diagnostic poll failed #$failedReads: ${e.message}" }
                    }
                    delay(BleConstants.Timing.DIAGNOSTIC_POLL_INTERVAL_MS)
                }
            }
            log.d { "Diagnostic polling ended (success: $successfulReads, failed: $failedReads)" }
            logPollingLifecycle(
                loopName = "diagnostic",
                phase = "ended success=$successfulReads failed=$failedReads",
                intervalMs = BleConstants.Timing.DIAGNOSTIC_POLL_INTERVAL_MS,
                previousJobState = "ending",
            )
        }
    }

    /**
     * Poll HEURISTIC characteristic every 250ms (4Hz) for force telemetry.
     */
    fun startHeuristicPolling(peripheral: Peripheral) {
        val previousJobState = jobState(heuristicPollingJob)
        logPollingLifecycle(
            loopName = "heuristic",
            phase = "start_requested",
            intervalMs = BleConstants.Timing.HEURISTIC_POLL_INTERVAL_MS,
            previousJobState = previousJobState,
        )
        heuristicPollingJob?.cancel()
        heuristicPollingJob = scope.launch {
            log.d { "Starting SEQUENTIAL heuristic polling (${BleConstants.Timing.HEURISTIC_POLL_INTERVAL_MS}ms interval / 4Hz)" }
            var successfulReads = 0L
            var failedReads = 0L

            while (isActive) {
                try {
                    val data = withTimeoutOrNull(BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS) {
                        bleQueue.read { peripheral.read(heuristicCharacteristic) }
                    }

                    if (data != null && data.isNotEmpty()) {
                        successfulReads++
                        logPollingSample("heuristic", data, successfulReads, null)
                        if (successfulReads % 100 == 0L) {
                            log.v { "Heuristic poll #$successfulReads (failed: $failedReads)" }
                        }
                        parseHeuristicData(data)
                    } else {
                        failedReads++
                        if (failedReads <= 3) {
                            log.v { "Heuristic read returned null/empty" }
                        }
                    }

                    delay(BleConstants.Timing.HEURISTIC_POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    failedReads++
                    if (failedReads <= 5 || failedReads % 50 == 0L) {
                        log.w { "Heuristic poll failed #$failedReads: ${e.message}" }
                    }
                    delay(BleConstants.Timing.HEURISTIC_POLL_INTERVAL_MS)
                }
            }
            log.d { "Heuristic polling ended (success: $successfulReads, failed: $failedReads)" }
            logPollingLifecycle(
                loopName = "heuristic",
                phase = "ended success=$successfulReads failed=$failedReads",
                intervalMs = BleConstants.Timing.HEURISTIC_POLL_INTERVAL_MS,
                previousJobState = "ending",
            )
        }
    }

    /**
     * Start heartbeat to keep BLE connection alive.
     * Read-then-write pattern: tries to read TX characteristic first, falls back to no-op write.
     * Issue #222 v15.1: V-Form requires WriteType.WithResponse.
     */
    fun startHeartbeat(peripheral: Peripheral) {
        val previousJobState = jobState(heartbeatJob)
        logPollingLifecycle(
            loopName = "heartbeat",
            phase = "start_requested",
            intervalMs = BleConstants.Timing.HEARTBEAT_INTERVAL_MS,
            previousJobState = previousJobState,
        )
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            log.d {
                "Starting BLE heartbeat (interval=${BleConstants.Timing.HEARTBEAT_INTERVAL_MS}ms, read timeout=${BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS}ms)"
            }
            while (isActive) {
                delay(BleConstants.Timing.HEARTBEAT_INTERVAL_MS)

                val readSucceeded = try {
                    withTimeoutOrNull(BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS) {
                        performHeartbeatRead(peripheral)
                    } ?: false
                } catch (e: Exception) {
                    log.e { "Heartbeat read attempt crashed: ${e.message}" }
                    false
                }

                if (!readSucceeded) {
                    sendHeartbeatNoOp(peripheral)
                }
            }
        }
    }

    /**
     * Stop ALL polling loops and reset diagnostic state.
     * Maps to KableBleRepository.stopPolling().
     */
    fun stopAll() {
        val timestamp = currentTimeMillis()
        log.d { "STOP_DEBUG: [$timestamp] stopAll() called" }
        logPollingLifecycle(
            loopName = "all",
            phase = "stopAll_requested",
            intervalMs = null,
            previousJobState = pollingJobStates(),
        )
        log.d {
            "STOP_DEBUG: Job states before cancel - monitor=${monitorPollingJob?.isActive}, " +
                "diagnostic=${diagnosticPollingJob?.isActive}, heuristic=${heuristicPollingJob?.isActive}, " +
                "heartbeat=${heartbeatJob?.isActive}"
        }

        // Log workout analysis (position range from HandleStateDetector)
        if (handleDetector.minPositionSeen != Double.MAX_VALUE &&
            handleDetector.maxPositionSeen != Double.MIN_VALUE
        ) {
            log.i { "========== WORKOUT ANALYSIS ==========" }
            log.i { "Position range: min=${handleDetector.minPositionSeen}, max=${handleDetector.maxPositionSeen}" }
            log.i { "Detection thresholds (auto-start mode uses lower velocity):" }
            log.i {
                "  Handle grab: pos > ${BleConstants.Thresholds.HANDLE_GRABBED_THRESHOLD} + velocity > ${if (handleDetector.isAutoStartMode) BleConstants.Thresholds.AUTO_START_VELOCITY_THRESHOLD else BleConstants.Thresholds.VELOCITY_THRESHOLD}${if (handleDetector.isAutoStartMode) " (auto-start)" else ""}"
            }
            log.i { "  Handle release: pos < ${BleConstants.Thresholds.HANDLE_REST_THRESHOLD}" }
            log.i { "======================================" }
        }

        // Auto-stop packet capture when polling ends
        if (BlePacketCapture.isCapturing) {
            BlePacketCapture.stopCapture()
        }

        monitorPollingJob?.cancel()
        diagnosticPollingJob?.cancel()
        heuristicPollingJob?.cancel()
        heartbeatJob?.cancel()

        monitorPollingJob = null
        diagnosticPollingJob = null
        heuristicPollingJob = null
        heartbeatJob = null
        diagnosticPollCount = 0
        lastDiagnosticFaults = null
        consecutiveTimeouts = 0

        val afterCancel = currentTimeMillis()
        log.d { "STOP_DEBUG: [$afterCancel] Jobs cancelled (took ${afterCancel - timestamp}ms)" }
        logPollingLifecycle(
            loopName = "all",
            phase = "stopAll_completed durationMs=${afterCancel - timestamp}",
            intervalMs = null,
            previousJobState = pollingJobStates(),
        )
    }

    /**
     * Stop monitor polling only — diagnostic, heuristic, and heartbeat CONTINUE.
     * Issue #222: Used during bodyweight exercises to prevent BLE link degradation.
     */
    fun stopMonitorOnly() {
        log.d { "Stopping monitor polling only - diagnostic polling + heartbeat continue" }
        logPollingLifecycle(
            loopName = "monitor",
            phase = "stopMonitorOnly_requested",
            intervalMs = null,
            previousJobState = jobState(monitorPollingJob),
        )
        monitorPollingJob?.cancel()
        monitorPollingJob = null
        log.d {
            "Monitor-only stop: diagnostic=${diagnosticPollingJob?.isActive}, " +
                "heuristic=${heuristicPollingJob?.isActive}, heartbeat=${heartbeatJob?.isActive}"
        }
    }

    /**
     * Restart all polling loops with conditional restart for non-monitor loops.
     * Monitor is always restarted. Others are only restarted if not already active.
     * Maps to KableBleRepository.startActiveWorkoutPolling().
     */
    fun restartAll(peripheral: Peripheral) {
        log.i { "Restarting all polling loops" }
        logPollingLifecycle(
            loopName = "all",
            phase = "restartAll_requested",
            intervalMs = null,
            previousJobState = pollingJobStates(),
        )
        log.d {
            "Issue #222 v16: Polling job states before restart - " +
                "monitor=${monitorPollingJob?.isActive}, " +
                "diagnostic=${diagnosticPollingJob?.isActive}, " +
                "heuristic=${heuristicPollingJob?.isActive}, " +
                "heartbeat=${heartbeatJob?.isActive}"
        }

        // Always restart monitor (forAutoStart=false for active workout)
        startMonitorPolling(peripheral, forAutoStart = false)

        // Conditionally restart others only if not already running
        if (diagnosticPollingJob?.isActive != true) {
            log.d { "Issue #222 v16: Restarting diagnostic polling" }
            startDiagnosticPolling(peripheral)
        }
        if (heartbeatJob?.isActive != true) {
            log.d { "Issue #222 v16: Restarting heartbeat" }
            startHeartbeat(peripheral)
        }
        if (heuristicPollingJob?.isActive != true) {
            log.d { "Issue #222 v16: Restarting heuristic polling" }
            startHeuristicPolling(peripheral)
        }
    }

    /**
     * Restart diagnostic polling and heartbeat only (not monitor).
     * Issue #222 v10: Maintains BLE link during rest between bodyweight sets.
     */
    fun restartDiagnosticAndHeartbeat(peripheral: Peripheral) {
        log.d { "Restarting diagnostic polling + heartbeat (Issue #222 v10)" }
        logPollingLifecycle(
            loopName = "diagnostic+heartbeat",
            phase = "restart_requested",
            intervalMs = null,
            previousJobState = pollingJobStates(),
        )

        if (diagnosticPollingJob?.isActive != true) {
            startDiagnosticPolling(peripheral)
        } else {
            log.d { "Diagnostic polling already active - skip restart" }
        }

        if (heartbeatJob?.isActive != true) {
            startHeartbeat(peripheral)
        } else {
            log.d { "Heartbeat already active - skip restart" }
        }
    }

    // ===== Private helpers =====

    /**
     * Parse monitor data by delegating to MonitorDataProcessor and HandleStateDetector.
     * Processing pipeline: read -> parse -> process -> detect -> emit.
     */
    private fun parseMonitorData(data: ByteArray) {
        // Hardware validation: capture raw bytes for protocol analysis (debug builds only)
        BlePacketCapture.onPacket(data)

        val packet = parseMonitorPacket(data)
        if (packet == null) {
            log.w { "Monitor data too short: ${data.size} bytes" }
            return
        }

        try {
            val metric = monitorProcessor.process(packet) ?: return
            handleDetector.processMetric(metric)
            onMetricEmit(metric)
        } catch (e: Exception) {
            log.e { "Error parsing monitor data: ${e.message}" }
        }
    }

    /**
     * Parse diagnostic data — detect fault changes and log.
     */
    private fun parseDiagnosticData(bytes: ByteArray) {
        try {
            val packet = parseDiagnosticPacket(bytes) ?: return

            val faultSnapshot = packet.faults
            val faultsChanged = lastDiagnosticFaults == null || lastDiagnosticFaults != faultSnapshot
            if (faultsChanged) {
                log.i { "DIAGNOSTIC update: faults=$faultSnapshot temps=${packet.temps.map { it.toInt() }}" }
                lastDiagnosticFaults = faultSnapshot
            }

            if (packet.hasFaults) {
                log.w { "DIAGNOSTIC FAULTS DETECTED: ${packet.faults}" }
            }
        } catch (e: Exception) {
            log.e { "Failed to parse diagnostic data: ${e.message}" }
        }
    }

    /**
     * Parse heuristic data and emit to callback.
     */
    private fun parseHeuristicData(bytes: ByteArray) {
        try {
            val stats = parseHeuristicPacket(bytes, timestamp = currentTimeMillis()) ?: return
            onHeuristicData(stats)
        } catch (e: Exception) {
            log.v { "Failed to parse heuristic data: ${e.message}" }
        }
    }

    /**
     * Perform heartbeat read (TX characteristic). Typically fails because TX is write-only,
     * which triggers the no-op write fallback. Returns true if read succeeded.
     */
    private suspend fun performHeartbeatRead(peripheral: Peripheral): Boolean = try {
        val data = bleQueue.read { peripheral.read(txCharacteristic) }
        logPollingSample("heartbeat", data, null, "read")
        log.v { "Heartbeat read succeeded (TX char)" }
        true
    } catch (e: Exception) {
        log.d { "Heartbeat read failed (expected): ${e.message}" }
        false
    }

    /**
     * Send heartbeat no-op write as fallback when read fails.
     * Issue #222 v15.1: V-Form requires WriteType.WithResponse.
     */
    private suspend fun sendHeartbeatNoOp(peripheral: Peripheral) {
        try {
            bleQueue.writeSimple(peripheral, txCharacteristic, BleConstants.HEARTBEAT_NO_OP, WriteType.WithResponse)
            logPollingSample("heartbeat", BleConstants.HEARTBEAT_NO_OP, null, "no_op_write")
            log.v { "Heartbeat no-op write sent" }
        } catch (e: Exception) {
            log.w { "Heartbeat no-op write failed: ${e.message}" }
        }
    }

    private fun logPollingLifecycle(loopName: String, phase: String, intervalMs: Long?, previousJobState: String) {
        logRepo?.debug(
            LogEventType.ISSUE_232_POLLING,
            "Polling $loopName $phase",
            details = "loop=$loopName, phase=$phase, previousJobState=$previousJobState, intervalMs=${intervalMs ?: "n/a"}",
        )
    }

    private fun logPollingSample(loopName: String, data: ByteArray, sampleIndex: Long?, operation: String?) {
        val remaining = when (loopName) {
            "monitor" -> monitorSampleLogsRemaining
            "diagnostic" -> diagnosticSampleLogsRemaining
            "heuristic" -> heuristicSampleLogsRemaining
            "heartbeat" -> heartbeatSampleLogsRemaining
            else -> 0
        }
        if (remaining <= 0) return

        when (loopName) {
            "monitor" -> monitorSampleLogsRemaining--
            "diagnostic" -> diagnosticSampleLogsRemaining--
            "heuristic" -> heuristicSampleLogsRemaining--
            "heartbeat" -> heartbeatSampleLogsRemaining--
        }

        logRepo?.debug(
            LogEventType.ISSUE_232_POLLING,
            "Bounded polling sample: $loopName",
            details = "loop=$loopName, operation=${operation ?: "read"}, sample=${sampleIndex ?: "n/a"}, size=${data.size}, hex=${formatHex(data)}",
        )
    }

    private fun pollingJobStates(): String = "monitor=${jobState(monitorPollingJob)}, diagnostic=${jobState(diagnosticPollingJob)}, heuristic=${jobState(heuristicPollingJob)}, heartbeat=${jobState(heartbeatJob)}"

    private fun jobState(job: Job?): String = when {
        job == null -> "null"
        job.isActive -> "active"
        job.isCancelled -> "cancelled"
        job.isCompleted -> "completed"
        else -> "inactive"
    }

    private fun formatHex(bytes: ByteArray): String = if (bytes.isEmpty()) {
        "<empty>"
    } else {
        bytes.joinToString(" ") { it.toVitruvianHex() }
    }
}
