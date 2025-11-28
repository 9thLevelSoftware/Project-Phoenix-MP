package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.SampleStatus
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import co.touchlab.kermit.Logger
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Kable-based BLE Repository implementation for Vitruvian machines.
 * Uses Kotlin Multiplatform Kable library for unified BLE across all platforms.
 */
@OptIn(ExperimentalUuidApi::class)
class KableBleRepository : BleRepository {

    private val log = Logger.withTag("KableBleRepository")
    private val logRepo = ConnectionLogRepository.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Nordic UART Service UUIDs
    companion object {
        private val NUS_SERVICE_UUID = Uuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_TX_UUID = Uuid.parse("6e400002-b5a3-f393-e0a9-e50e24dcca9e")  // Write
        private val NUS_RX_UUID = Uuid.parse("6e400003-b5a3-f393-e0a9-e50e24dcca9e")  // Notify
        private val MONITOR_UUID = Uuid.parse("90e991a6-c548-44ed-969b-eb541014eae3") // Read/Notify

        // Connection settings
        private const val CONNECTION_RETRY_COUNT = 3
        private const val CONNECTION_RETRY_DELAY_MS = 100L
        private const val DESIRED_MTU = 512

        // Handle detection thresholds (from Nordic implementation - proven working)
        private const val HANDLE_GRABBED_THRESHOLD = 8.0    // Position > 8.0 = handles grabbed
        private const val HANDLE_REST_THRESHOLD = 5.0       // Position < 5.0 = handles at rest
        private const val VELOCITY_THRESHOLD = 100.0        // Velocity > 100 units/s = significant movement

        // Sample validation
        private const val POSITION_SPIKE_THRESHOLD = 50000  // BLE error filter
        private const val MIN_POSITION = -1000              // Valid position range
        private const val MAX_POSITION = 1000               // Valid position range

        // Timing constants
        private const val HEARTBEAT_INTERVAL_MS = 2000L
        private const val HEARTBEAT_READ_TIMEOUT_MS = 1500L
        private const val MONITOR_POLL_INTERVAL_MS = 100L
        private const val DELOAD_EVENT_DEBOUNCE_MS = 2000L

        // Heartbeat no-op command (MUST be 4 bytes)
        private val HEARTBEAT_NO_OP = byteArrayOf(0x00, 0x00, 0x00, 0x00)
    }

    // Kable characteristic references
    private val txCharacteristic = characteristicOf(
        service = NUS_SERVICE_UUID,
        characteristic = NUS_TX_UUID
    )
    private val rxCharacteristic = characteristicOf(
        service = NUS_SERVICE_UUID,
        characteristic = NUS_RX_UUID
    )
    private val monitorCharacteristic = characteristicOf(
        service = NUS_SERVICE_UUID,
        characteristic = MONITOR_UUID
    )

    // State flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _handleState = MutableStateFlow(HandleState())
    override val handleState: StateFlow<HandleState> = _handleState.asStateFlow()

    private val _metricsFlow = MutableSharedFlow<WorkoutMetric>(replay = 1)
    override val metricsFlow: Flow<WorkoutMetric> = _metricsFlow.asSharedFlow()

    private val _repEvents = MutableSharedFlow<RepNotification>()
    override val repEvents: Flow<RepNotification> = _repEvents.asSharedFlow()

    // Handle activity state (4-state machine for Just Lift mode)
    private val _handleActivityState = MutableStateFlow(HandleActivityState.WaitingForRest)
    override val handleActivityState: StateFlow<HandleActivityState> = _handleActivityState.asStateFlow()

    // Deload event flow (for Just Lift safety recovery)
    private val _deloadOccurredEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val deloadOccurredEvents: Flow<Unit> = _deloadOccurredEvents.asSharedFlow()

    // Reconnection request flow (for Android BLE bug workaround)
    private val _reconnectionRequested = MutableSharedFlow<ReconnectionRequest>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val reconnectionRequested: Flow<ReconnectionRequest> = _reconnectionRequested.asSharedFlow()

    // Command response flow (for awaitResponse() protocol handshake)
    private val _commandResponses = MutableSharedFlow<UByte>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commandResponses: Flow<UByte> = _commandResponses.asSharedFlow()

    // Kable objects
    private var peripheral: Peripheral? = null
    private val discoveredAdvertisements = mutableMapOf<String, Advertisement>()
    private var scanJob: kotlinx.coroutines.Job? = null

    // Handle detection
    private var handleDetectionEnabled = false

    // Connected device info (for logging)
    private var connectedDeviceName: String = ""
    private var connectedDeviceAddress: String = ""

    // Data parsing state (for spike filtering)
    private var lastGoodPosA = 0
    private var lastGoodPosB = 0

    // Velocity calculation state
    private var lastPositionA = 0
    private var lastPositionB = 0
    private var lastTimestamp = 0L

    // Handle detection state tracking
    private var minPositionSeen = Double.MAX_VALUE
    private var maxPositionSeen = Double.MIN_VALUE

    // Deload event debouncing
    private var lastDeloadEventTime = 0L

    // Monitor notification counter (for diagnostic logging)
    private var monitorNotificationCount = 0L

    // Heartbeat job
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    // Mutex for thread-safe state updates
    private val stateMutex = Mutex()

    // Flag to track explicit disconnect (to avoid auto-reconnect)
    private var isExplicitDisconnect = false

    override suspend fun startScanning() {
        log.i { "Starting BLE scan for Vitruvian devices" }
        logRepo.info(LogEventType.SCAN_START, "Starting BLE scan for Vitruvian devices")

        _scannedDevices.value = emptyList()
        discoveredAdvertisements.clear()
        _connectionState.value = ConnectionState.Scanning

        scanJob = Scanner {
            // No specific filters - we'll filter by name
        }
            .advertisements
            .filter { advertisement ->
                val name = advertisement.name ?: return@filter false
                name.startsWith("Vee_") || name.startsWith("VIT")
            }
            .onEach { advertisement ->
                val name = advertisement.name ?: return@onEach
                val identifier = advertisement.identifier.toString()

                // Only log if this is a new device
                if (!discoveredAdvertisements.containsKey(identifier)) {
                    log.d { "Discovered device: $name ($identifier) RSSI: ${advertisement.rssi}" }
                    logRepo.info(
                        LogEventType.DEVICE_FOUND,
                        "Found Vitruvian device",
                        name,
                        identifier,
                        "RSSI: ${advertisement.rssi} dBm"
                    )
                }

                // Store advertisement reference
                discoveredAdvertisements[identifier] = advertisement

                // Update scanned devices list
                val device = ScannedDevice(
                    name = name,
                    address = identifier,
                    rssi = advertisement.rssi
                )
                val currentDevices = _scannedDevices.value.toMutableList()
                val existingIndex = currentDevices.indexOfFirst { it.address == identifier }
                if (existingIndex >= 0) {
                    currentDevices[existingIndex] = device
                } else {
                    currentDevices.add(device)
                }
                _scannedDevices.value = currentDevices.sortedByDescending { it.rssi }
            }
            .catch { e ->
                log.e { "Scan error: ${e.message}" }
                logRepo.error(LogEventType.ERROR, "BLE scan failed", details = e.message)
                _connectionState.value = ConnectionState.Error("Scan failed: ${e.message}")
            }
            .launchIn(scope)
    }

    override suspend fun stopScanning() {
        log.i { "Stopping BLE scan" }
        logRepo.info(
            LogEventType.SCAN_STOP,
            "BLE scan stopped",
            details = "Found ${discoveredAdvertisements.size} Vitruvian device(s)"
        )
        scanJob?.cancel()
        scanJob = null
        if (_connectionState.value == ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override suspend fun connect(device: ScannedDevice) {
        log.i { "Connecting to device: ${device.name}" }
        logRepo.info(
            LogEventType.CONNECT_START,
            "Connecting to device",
            device.name,
            device.address
        )
        _connectionState.value = ConnectionState.Connecting

        val advertisement = discoveredAdvertisements[device.address]
        if (advertisement == null) {
            log.e { "Advertisement not found for device: ${device.address}" }
            logRepo.error(
                LogEventType.CONNECT_FAIL,
                "Device not found in scanned list",
                device.name,
                device.address
            )
            _connectionState.value = ConnectionState.Error("Device not found")
            return
        }

        // Store device info for logging
        connectedDeviceName = device.name
        connectedDeviceAddress = device.address

        try {
            stopScanning()

            peripheral = Peripheral(advertisement)

            // Observe connection state
            peripheral?.state
                ?.onEach { state ->
                    when (state) {
                        is State.Connecting -> {
                            _connectionState.value = ConnectionState.Connecting
                        }
                        is State.Connected -> {
                            logRepo.info(
                                LogEventType.CONNECT_SUCCESS,
                                "Device connected successfully",
                                connectedDeviceName,
                                connectedDeviceAddress
                            )
                            _connectionState.value = ConnectionState.Connected(
                                deviceName = device.name,
                                deviceAddress = device.address
                            )
                            // Launch onDeviceReady in a coroutine since we're in a non-suspend context
                            scope.launch { onDeviceReady() }
                        }
                        is State.Disconnecting -> {
                            log.d { "Disconnecting from device" }
                            logRepo.info(
                                LogEventType.DISCONNECT,
                                "Device disconnecting",
                                connectedDeviceName,
                                connectedDeviceAddress
                            )
                        }
                        is State.Disconnected -> {
                            // Capture device info BEFORE clearing state (needed for reconnection)
                            val deviceName = connectedDeviceName
                            val deviceAddress = connectedDeviceAddress

                            logRepo.info(
                                LogEventType.DISCONNECT,
                                "Device disconnected",
                                deviceName,
                                deviceAddress
                            )

                            // Stop heartbeat and reset state
                            heartbeatJob?.cancel()
                            heartbeatJob = null
                            _connectionState.value = ConnectionState.Disconnected
                            peripheral = null
                            connectedDeviceName = ""
                            connectedDeviceAddress = ""

                            // Request auto-reconnect if this was NOT an explicit disconnect
                            if (!isExplicitDisconnect && deviceAddress.isNotEmpty()) {
                                log.i { "🔄 Requesting auto-reconnect to $deviceName ($deviceAddress)" }
                                scope.launch {
                                    _reconnectionRequested.emit(
                                        ReconnectionRequest(
                                            deviceName = deviceName,
                                            deviceAddress = deviceAddress,
                                            reason = "unexpected_disconnect",
                                            timestamp = currentTimeMillis()
                                        )
                                    )
                                }
                            }
                            isExplicitDisconnect = false  // Reset for next connection
                        }
                    }
                }
                ?.launchIn(scope)

            // Connection with retry logic
            var lastException: Exception? = null
            for (attempt in 1..CONNECTION_RETRY_COUNT) {
                try {
                    log.d { "Connection attempt $attempt of $CONNECTION_RETRY_COUNT" }
                    peripheral?.connect()
                    log.i { "Connection initiated to ${device.name}" }
                    return // Success, exit retry loop
                } catch (e: Exception) {
                    lastException = e
                    log.w { "Connection attempt $attempt failed: ${e.message}" }
                    if (attempt < CONNECTION_RETRY_COUNT) {
                        delay(CONNECTION_RETRY_DELAY_MS)
                    }
                }
            }

            // All retries failed
            throw lastException ?: Exception("Connection failed after $CONNECTION_RETRY_COUNT attempts")

        } catch (e: Exception) {
            log.e { "Connection failed: ${e.message}" }
            logRepo.error(
                LogEventType.CONNECT_FAIL,
                "Failed to connect to device",
                device.name,
                device.address,
                e.message
            )
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
            peripheral = null
            connectedDeviceName = ""
            connectedDeviceAddress = ""
        }
    }

    /**
     * Called when the device is connected and ready.
     * Requests MTU, starts observing notifications, and starts heartbeat.
     */
    private suspend fun onDeviceReady() {
        val p = peripheral ?: return

        // Request MTU
        try {
            logRepo.debug(LogEventType.MTU_CHANGED, "Requesting MTU $DESIRED_MTU")
            // Note: Kable handles MTU negotiation automatically, but we can request a specific size
            // The actual MTU may be different depending on the device
        } catch (e: Exception) {
            log.w { "MTU request failed: ${e.message}" }
        }

        logRepo.info(
            LogEventType.SERVICE_DISCOVERED,
            "Device ready, starting notifications and heartbeat",
            connectedDeviceName,
            connectedDeviceAddress
        )

        startObservingNotifications()
        startHeartbeat()
    }

    /**
     * Start the heartbeat to keep the BLE connection alive.
     * Uses read-then-write pattern: tries to read first, falls back to write if read fails.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            log.d { "Starting BLE heartbeat (interval=${HEARTBEAT_INTERVAL_MS}ms, read timeout=${HEARTBEAT_READ_TIMEOUT_MS}ms)" }
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)

                val p = peripheral
                if (p == null) {
                    log.w { "Heartbeat: peripheral is null, stopping" }
                    break
                }

                // ATTEMPT READ FIRST with timeout
                val readSucceeded = try {
                    kotlinx.coroutines.withTimeoutOrNull(HEARTBEAT_READ_TIMEOUT_MS) {
                        performHeartbeatRead(p)
                    } ?: false
                } catch (e: Exception) {
                    log.e { "Heartbeat read attempt crashed: ${e.message}" }
                    false
                }

                // FALLBACK TO WRITE if read failed
                if (!readSucceeded) {
                    sendHeartbeatNoOp(p)
                }
            }
        }
    }

    /**
     * Perform heartbeat read on the TX characteristic.
     * Returns true if read succeeded, false otherwise.
     */
    private suspend fun performHeartbeatRead(p: Peripheral): Boolean {
        return try {
            p.read(txCharacteristic)
            log.v { "Heartbeat read succeeded" }
            true
        } catch (e: Exception) {
            log.w { "Heartbeat read failed: ${e.message}" }
            false
        }
    }

    /**
     * Send heartbeat no-op write as fallback when read fails.
     * Uses 4-byte no-op command (MUST be exactly 4 bytes).
     */
    private suspend fun sendHeartbeatNoOp(p: Peripheral) {
        try {
            p.write(txCharacteristic, HEARTBEAT_NO_OP, WriteType.WithoutResponse)
            log.v { "Heartbeat no-op write sent" }
        } catch (e: Exception) {
            log.w { "Heartbeat no-op write failed: ${e.message}" }
        }
    }

    private fun startObservingNotifications() {
        val p = peripheral ?: return

        logRepo.info(
            LogEventType.NOTIFICATION,
            "Enabling RX notifications",
            connectedDeviceName,
            connectedDeviceAddress
        )

        // Observe RX characteristic for notifications
        scope.launch {
            try {
                p.observe(rxCharacteristic)
                    .catch { e ->
                        log.e { "RX observation error: ${e.message}" }
                        logRepo.error(
                            LogEventType.ERROR,
                            "RX notification error",
                            connectedDeviceName,
                            connectedDeviceAddress,
                            e.message
                        )
                    }
                    .collect { data ->
                        logRepo.debug(
                            LogEventType.NOTIFICATION,
                            "RX notification received",
                            details = "Size: ${data.size} bytes"
                        )
                        processIncomingData(data)
                    }
            } catch (e: Exception) {
                log.e { "Failed to observe RX: ${e.message}" }
                logRepo.error(
                    LogEventType.ERROR,
                    "Failed to enable RX notifications",
                    connectedDeviceName,
                    connectedDeviceAddress,
                    e.message
                )
            }
        }

        // Poll monitor characteristic for real-time metrics (heartbeat)
        scope.launch {
            try {
                while (_connectionState.value is ConnectionState.Connected) {
                    try {
                        val data = p.read(monitorCharacteristic)
                        parseMonitorData(data)
                    } catch (e: Exception) {
                        // Monitor characteristic might not be available on all devices
                        log.d { "Monitor read failed: ${e.message}" }
                    }
                    delay(100)
                }
            } catch (e: Exception) {
                log.e { "Monitor polling stopped: ${e.message}" }
            }
        }
    }

    override suspend fun disconnect() {
        log.i { "Disconnecting (explicit)" }
        isExplicitDisconnect = true  // Mark as explicit disconnect to prevent auto-reconnect
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            peripheral?.disconnect()
        } catch (e: Exception) {
            log.e { "Disconnect error: ${e.message}" }
        }
        peripheral = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun setColorScheme(schemeIndex: Int) {
        log.d { "Setting color scheme: $schemeIndex" }
        // Color scheme command - implementation depends on machine protocol
    }

    override suspend fun sendWorkoutCommand(command: ByteArray) {
        val p = peripheral
        if (p == null) {
            log.w { "Not connected - cannot send command" }
            logRepo.warning(
                LogEventType.ERROR,
                "Cannot send command - not connected"
            )
            return
        }

        try {
            // Use WriteType.WithoutResponse for faster writes (matches Nordic behavior)
            p.write(txCharacteristic, command, WriteType.WithoutResponse)
            log.d { "Command sent: ${command.size} bytes" }
            logRepo.debug(
                LogEventType.COMMAND_SENT,
                "Sending command",
                connectedDeviceName,
                connectedDeviceAddress,
                "Size: ${command.size} bytes, Data: ${command.joinToString(" ") { it.toHexString() }}"
            )
        } catch (e: Exception) {
            log.e { "Failed to send command: ${e.message}" }
            logRepo.error(
                LogEventType.ERROR,
                "Failed to send command",
                connectedDeviceName,
                connectedDeviceAddress,
                e.message
            )
        }
    }

    override fun enableHandleDetection(enabled: Boolean) {
        handleDetectionEnabled = enabled
        log.d { "Handle detection enabled: $enabled" }
        // Reset state machine when enabling
        if (enabled) {
            _handleActivityState.value = HandleActivityState.WaitingForRest
            minPositionSeen = Double.MAX_VALUE
            maxPositionSeen = Double.MIN_VALUE
        }
    }

    override fun resetHandleActivityState() {
        log.d { "Resetting handle activity state to WaitingForRest" }
        _handleActivityState.value = HandleActivityState.WaitingForRest
        minPositionSeen = Double.MAX_VALUE
        maxPositionSeen = Double.MIN_VALUE
    }

    private fun processIncomingData(data: ByteArray) {
        if (data.isEmpty()) return

        // Extract opcode (first byte) for command response tracking
        val opcode = data[0].toUByte()
        log.d { "RX notification: opcode=0x${opcode.toString(16).padStart(2, '0')}, size=${data.size}" }
        _commandResponses.tryEmit(opcode)

        // Route to specific parsers
        when (opcode.toInt()) {
            0x01 -> if (data.size >= 16) parseMetricsPacket(data)
            0x02 -> if (data.size >= 5) parseRepNotification(data)
            // Other opcodes can be handled here as needed
        }
    }

    /**
     * Wait for a specific response opcode with timeout.
     * Used for protocol handshakes that require acknowledgment.
     *
     * @param expectedOpcode The opcode to wait for
     * @param timeoutMs Timeout in milliseconds (default 5000ms)
     * @return true if the expected opcode was received, false on timeout
     */
    suspend fun awaitResponse(expectedOpcode: UByte, timeoutMs: Long = 5000L): Boolean {
        return try {
            val opcodeHex = expectedOpcode.toString(16).uppercase().padStart(2, '0')
            log.d { "⏳ Waiting for response opcode 0x$opcodeHex (timeout: ${timeoutMs}ms)" }

            val result = withTimeoutOrNull(timeoutMs) {
                commandResponses.filter { it == expectedOpcode }.first()
            }

            if (result != null) {
                log.d { "✅ Received expected response opcode 0x$opcodeHex" }
                true
            } else {
                log.w { "⏱️ Timeout waiting for response opcode 0x$opcodeHex" }
                false
            }
        } catch (e: Exception) {
            val opcodeHex = expectedOpcode.toString(16).uppercase().padStart(2, '0')
            log.e { "Error waiting for response opcode 0x$opcodeHex: ${e.message}" }
            false
        }
    }

    /**
     * Parse monitor characteristic data with full velocity calculation and status flag detection.
     * This is the CRITICAL function for Just Lift mode handle detection.
     */
    private fun parseMonitorData(data: ByteArray) {
        if (data.size < 16) {
            log.w { "Monitor data too short: ${data.size} bytes" }
            return
        }

        try {
            // Increment counter for diagnostic logging
            monitorNotificationCount++
            if (monitorNotificationCount % 100 == 0L) {
                log.i { "📊 MONITOR NOTIFICATION #$monitorNotificationCount" }
            }

            // Monitor characteristic data parsing (LITTLE-ENDIAN format)
            // f0 (0-1) = ticks low
            // f1 (2-3) = ticks high
            // f2 (4-5) = PosA
            // f4 (8-9) = LoadA * 100
            // f5 (10-11) = PosB
            // f7 (14-15) = LoadB * 100

            val f0 = getUInt16LE(data, 0)  // ticks low
            val f1 = getUInt16LE(data, 2)  // ticks high
            var posA = getUInt16LE(data, 4)
            val loadARaw = getUInt16LE(data, 8)
            var posB = getUInt16LE(data, 10)
            val loadBRaw = getUInt16LE(data, 14)

            // Reconstruct 32-bit tick counter
            val ticks = f0 + (f1 shl 16)

            // ===== SPIKE FILTERING =====
            // BLE transmission errors produce values > 50000
            // Per official app documentation, valid range is -1000 to +1000 mm
            if (posA > POSITION_SPIKE_THRESHOLD) {
                posA = lastGoodPosA
            } else {
                lastGoodPosA = posA
            }

            if (posB > POSITION_SPIKE_THRESHOLD) {
                posB = lastGoodPosB
            } else {
                lastGoodPosB = posB
            }

            // Load in kg (device sends kg * 100)
            val loadA = loadARaw / 100f
            val loadB = loadBRaw / 100f

            // ===== STATUS FLAG PROCESSING =====
            var status = 0
            if (data.size >= 18) {
                status = getUInt16LE(data, 16)
                processStatusFlags(status)
            }

            // ===== SAMPLE VALIDATION =====
            if (!validateSample(posA, loadA, posB, loadB)) {
                return  // Skip invalid sample
            }

            // ===== VELOCITY CALCULATION =====
            val currentTime = currentTimeMillis()
            val velocityA = if (lastTimestamp > 0L) {
                val deltaTime = (currentTime - lastTimestamp) / 1000.0
                val deltaPos = posA - lastPositionA
                if (deltaTime > 0) kotlin.math.abs(deltaPos / deltaTime) else 0.0
            } else 0.0

            val velocityB = if (lastTimestamp > 0L) {
                val deltaTime = (currentTime - lastTimestamp) / 1000.0
                val deltaPos = posB - lastPositionB
                if (deltaTime > 0) kotlin.math.abs(deltaPos / deltaTime) else 0.0
            } else 0.0

            // Update tracking state for next velocity calculation
            lastPositionA = posA
            lastPositionB = posB
            lastTimestamp = currentTime

            // Create metric with CALCULATED velocity
            val metric = WorkoutMetric(
                timestamp = currentTime,
                loadA = loadA,
                loadB = loadB,
                positionA = posA,
                positionB = posB,
                ticks = ticks,
                velocityA = velocityA,
                velocityB = velocityB,
                status = status
            )

            scope.launch { _metricsFlow.emit(metric) }

            // ===== SIMPLE HANDLE STATE (for backward compatibility) =====
            if (handleDetectionEnabled) {
                val activeThreshold = 500
                val leftDetected = posA > activeThreshold
                val rightDetected = posB > activeThreshold
                val currentState = _handleState.value
                if (currentState.leftDetected != leftDetected || currentState.rightDetected != rightDetected) {
                    _handleState.value = HandleState(leftDetected, rightDetected)
                }

                // ===== 4-STATE HANDLE STATE MACHINE (for Just Lift mode) =====
                val newActivityState = analyzeHandleActivityState(metric)
                if (newActivityState != _handleActivityState.value) {
                    log.d { "Handle activity state changed: ${_handleActivityState.value} -> $newActivityState" }
                    _handleActivityState.value = newActivityState
                }
            }

        } catch (e: Exception) {
            log.e { "Error parsing monitor data: ${e.message}" }
        }
    }

    /**
     * Process status flags from bytes 16-17 of monitor data.
     * Handles deload detection and safety events.
     */
    private fun processStatusFlags(status: Int) {
        if (status == 0) return

        val sampleStatus = SampleStatus(status)

        if (sampleStatus.isDeloadOccurred()) {
            log.w { "MACHINE STATUS: DELOAD_OCCURRED flag set - Status: 0x${status.toString(16)}" }

            // Emit deload event (debounced) for repository/ViewModel to handle
            val now = currentTimeMillis()
            if (now - lastDeloadEventTime > DELOAD_EVENT_DEBOUNCE_MS) {
                lastDeloadEventTime = now
                scope.launch {
                    log.d { "DELOAD_OCCURRED: Emitting event" }
                    _deloadOccurredEvents.emit(Unit)
                }
            }
        }

        if (sampleStatus.isDeloadWarn()) {
            log.w { "MACHINE STATUS: DELOAD_WARN - Status: 0x${status.toString(16)}" }
        }

        if (sampleStatus.isSpotterActive()) {
            log.d { "MACHINE STATUS: SPOTTER_ACTIVE - Status: 0x${status.toString(16)}" }
        }
    }

    /**
     * Validate sample data is within acceptable ranges.
     */
    private fun validateSample(posA: Int, loadA: Float, posB: Int, loadB: Float): Boolean {
        // Official app range: -1000 to +1000 mm
        if (posA < MIN_POSITION || posA > MAX_POSITION ||
            posB < MIN_POSITION || posB > MAX_POSITION) {
            log.w { "Position out of range: posA=$posA, posB=$posB (valid: $MIN_POSITION to $MAX_POSITION)" }
            return false
        }
        return true
    }

    /**
     * Analyze handle state for Just Lift mode auto-start.
     * Implements 4-state machine: WaitingForRest -> Released -> Grabbed -> Active
     *
     * State transitions:
     * - WaitingForRest: Initial state, waiting for handles to be at rest
     * - Released (SetComplete): Handles at rest, armed for grab detection
     * - Active: Handles grabbed and moving (workout started)
     */
    private fun analyzeHandleActivityState(metric: WorkoutMetric): HandleActivityState {
        val posA = metric.positionA.toDouble()
        val posB = metric.positionB.toDouble()
        val velocityA = metric.velocityA
        val velocityB = metric.velocityB

        // Track position range for post-workout tuning diagnostics
        minPositionSeen = minOf(minPositionSeen, minOf(posA, posB))
        maxPositionSeen = maxOf(maxPositionSeen, maxOf(posA, posB))

        val currentState = _handleActivityState.value

        // Check both handles - support single-handle exercises
        val handleAGrabbed = posA > HANDLE_GRABBED_THRESHOLD
        val handleBGrabbed = posB > HANDLE_GRABBED_THRESHOLD
        val handleAMoving = velocityA > VELOCITY_THRESHOLD
        val handleBMoving = velocityB > VELOCITY_THRESHOLD

        return when (currentState) {
            HandleActivityState.WaitingForRest -> {
                // MUST see handles at rest before arming grab detection
                // This prevents immediate auto-start if cables already have tension
                if (posA < HANDLE_REST_THRESHOLD && posB < HANDLE_REST_THRESHOLD) {
                    log.d { "Handles at REST (posA=$posA, posB=$posB < $HANDLE_REST_THRESHOLD) - auto-start now ARMED" }
                    HandleActivityState.SetComplete  // SetComplete acts as "Released/Armed" state
                } else {
                    HandleActivityState.WaitingForRest
                }
            }
            HandleActivityState.SetComplete -> {
                // Check if EITHER handle is grabbed and moving (for single-handle exercises)
                val aActive = handleAGrabbed && handleAMoving
                val bActive = handleBGrabbed && handleBMoving

                if (aActive || bActive) {
                    val activeHandle = when {
                        aActive && bActive -> "both"
                        aActive -> "A"
                        else -> "B"
                    }
                    log.i { "GRAB CONFIRMED: handle=$activeHandle (posA=$posA, posB=$posB, velA=$velocityA, velB=$velocityB)" }
                    HandleActivityState.Active
                } else {
                    HandleActivityState.SetComplete
                }
            }
            HandleActivityState.Active -> {
                // Consider released only if BOTH handles are at rest
                // This prevents false release during single-handle exercises
                val aReleased = posA < HANDLE_REST_THRESHOLD
                val bReleased = posB < HANDLE_REST_THRESHOLD

                if (aReleased && bReleased) {
                    log.d { "RELEASE DETECTED: posA=$posA, posB=$posB < $HANDLE_REST_THRESHOLD" }
                    HandleActivityState.SetComplete
                } else {
                    HandleActivityState.Active
                }
            }
        }
    }

    /**
     * Parse metrics packet from RX notifications (0x01 command).
     * Uses big-endian byte order for this packet type.
     */
    private fun parseMetricsPacket(data: ByteArray) {
        if (data.size < 16) return

        try {
            // RX notification metrics use big-endian byte order
            val positionA = getUInt16BE(data, 2)
            val positionB = getUInt16BE(data, 4)
            val loadA = getUInt16BE(data, 6)
            val loadB = getUInt16BE(data, 8)
            val velocityA = getUInt16BE(data, 10)
            val velocityB = getUInt16BE(data, 12)

            val currentTime = currentTimeMillis()
            val metric = WorkoutMetric(
                timestamp = currentTime,
                loadA = loadA / 10f,
                loadB = loadB / 10f,
                positionA = positionA,
                positionB = positionB,
                velocityA = (velocityA - 32768).toDouble(),
                velocityB = (velocityB - 32768).toDouble()
            )

            scope.launch { _metricsFlow.emit(metric) }

            if (handleDetectionEnabled) {
                val activeThreshold = 500
                val leftDetected = positionA > activeThreshold
                val rightDetected = positionB > activeThreshold
                val currentState = _handleState.value
                if (currentState.leftDetected != leftDetected || currentState.rightDetected != rightDetected) {
                    _handleState.value = HandleState(leftDetected, rightDetected)
                }

                // Also analyze handle activity state for Just Lift mode
                val newActivityState = analyzeHandleActivityState(metric)
                if (newActivityState != _handleActivityState.value) {
                    log.d { "Handle activity state changed (RX): ${_handleActivityState.value} -> $newActivityState" }
                    _handleActivityState.value = newActivityState
                }
            }
        } catch (e: Exception) {
            log.e { "Error parsing metrics: ${e.message}" }
        }
    }

    /**
     * Parse rep notification with support for TWO packet formats (Issue #187):
     *
     * LEGACY FORMAT (6+ bytes, used in Beta 4, Samsung devices):
     * - Bytes 0-1: topCounter (u16) - concentric completions
     * - Bytes 2-3: (unused)
     * - Bytes 4-5: completeCounter (u16) - eccentric completions
     *
     * OFFICIAL APP FORMAT (24 bytes, Little Endian):
     * - Bytes 0-3:   up (Int/u32) - up counter (concentric completions)
     * - Bytes 4-7:   down (Int/u32) - down counter (eccentric completions)
     * - Bytes 8-11:  rangeTop (Float) - maximum ROM boundary
     * - Bytes 12-15: rangeBottom (Float) - minimum ROM boundary
     * - Bytes 16-17: repsRomCount (Short/u16) - Warmup reps with proper ROM
     * - Bytes 18-19: repsRomTotal (Short/u16) - Total reps regardless of ROM
     * - Bytes 20-21: repsSetCount (Short/u16) - WORKING SET REP COUNT
     * - Bytes 22-23: repsSetTotal (Short/u16) - Total reps in set
     *
     * Note: data[0] is the opcode (0x02), so rep data starts at index 1
     */
    private fun parseRepNotification(data: ByteArray) {
        // Minimum 7 bytes: 1 opcode + 6 rep data (legacy format)
        if (data.size < 7) {
            log.w { "Rep notification too short: ${data.size} bytes (minimum 7)" }
            return
        }

        try {
            val currentTime = currentTimeMillis()
            val notification: RepNotification

            // Check if we have full 24-byte rep data (+ 1 byte opcode = 25 total)
            if (data.size >= 25) {
                // FULL 24-byte packet - parse all fields (skip opcode at index 0)
                val upCounter = getInt32LE(data, 1)
                val downCounter = getInt32LE(data, 5)
                val rangeTop = getFloatLE(data, 9)
                val rangeBottom = getFloatLE(data, 13)
                val repsRomCount = getUInt16LE(data, 17)
                val repsSetCount = getUInt16LE(data, 21)

                log.d { "Rep notification (24-byte format):" }
                log.d { "  up=$upCounter, down=$downCounter" }
                log.d { "  repsRomCount=$repsRomCount (warmup), repsSetCount=$repsSetCount (working)" }
                log.d { "  hex=${data.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }}" }

                notification = RepNotification(
                    topCounter = upCounter,
                    completeCounter = downCounter,
                    repsRomCount = repsRomCount,
                    repsSetCount = repsSetCount,
                    rangeTop = rangeTop,
                    rangeBottom = rangeBottom,
                    isLegacyFormat = false,
                    timestamp = currentTime
                )
            } else {
                // LEGACY 6-byte packet (Beta 4 format, Samsung devices) - parse u16 counters
                // Skip opcode at index 0
                val topCounter = getUInt16LE(data, 1)
                val completeCounter = getUInt16LE(data, 5)

                log.w { "Rep notification (LEGACY 6-byte format - Issue #187 fallback):" }
                log.w { "  top=$topCounter, complete=$completeCounter" }
                log.w { "  hex=${data.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }}" }

                notification = RepNotification(
                    topCounter = topCounter,
                    completeCounter = completeCounter,
                    repsRomCount = 0,  // Not available in legacy format
                    repsSetCount = 0,  // Not available in legacy format
                    rangeTop = 0f,
                    rangeBottom = 0f,
                    isLegacyFormat = true,
                    timestamp = currentTime
                )
            }

            val emitted = _repEvents.tryEmit(notification)
            log.d { "🔥 Emitted rep event: success=$emitted, legacy=${notification.isLegacyFormat}" }
        } catch (e: Exception) {
            log.e { "Error parsing rep notification: ${e.message}" }
        }
    }

    /**
     * Read unsigned 16-bit integer in LITTLE-ENDIAN format (LSB first).
     * This is the correct format for Vitruvian BLE protocol.
     */
    private fun getUInt16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    /**
     * Read unsigned 16-bit integer in BIG-ENDIAN format (MSB first).
     * Used for some packet types.
     */
    private fun getUInt16BE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    /**
     * Read signed 32-bit integer in LITTLE-ENDIAN format.
     * Used for rep counters in 24-byte packets.
     */
    private fun getInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
               ((data[offset + 1].toInt() and 0xFF) shl 8) or
               ((data[offset + 2].toInt() and 0xFF) shl 16) or
               ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * Read 32-bit float in LITTLE-ENDIAN format.
     * Used for ROM boundaries in 24-byte rep packets.
     */
    private fun getFloatLE(data: ByteArray, offset: Int): Float {
        val bits = getInt32LE(data, offset)
        return Float.fromBits(bits)
    }

    /**
     * Convert a Byte to a two-character uppercase hex string (KMP-compatible).
     */
    private fun Byte.toHexString(): String {
        val hex = "0123456789ABCDEF"
        val value = this.toInt() and 0xFF
        return "${hex[value shr 4]}${hex[value and 0x0F]}"
    }

    /**
     * Get current time in milliseconds (KMP-compatible).
     */
    private fun currentTimeMillis(): Long {
        return Clock.System.now().toEpochMilliseconds()
    }
}
