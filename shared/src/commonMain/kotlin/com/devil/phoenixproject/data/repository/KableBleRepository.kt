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
import co.touchlab.kermit.Logger
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

import com.devil.phoenixproject.data.ble.requestHighPriority

import com.devil.phoenixproject.util.HardwareDetection

/**
 * Kable-based BLE Repository implementation for Vitruvian machines.
 * Uses Kotlin Multiplatform Kable library for unified BLE across all platforms.
 */
@OptIn(ExperimentalUuidApi::class)
class KableBleRepository : BleRepository {

    private val log = Logger.withTag("KableBleRepository")
    private val logRepo = ConnectionLogRepository.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Nordic UART Service UUIDs (matching parent repo BleConstants.kt)
    companion object {
        // Primary Service
        private val NUS_SERVICE_UUID = Uuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

        // Primary Characteristic UUIDs
        private val NUS_TX_UUID = Uuid.parse("6e400002-b5a3-f393-e0a9-e50e24dcca9e")  // Write (RX on device)
        private val NUS_RX_UUID = Uuid.parse("6e400003-b5a3-f393-e0a9-e50e24dcca9e")  // Notify (commands)
        private val MONITOR_UUID = Uuid.parse("90e991a6-c548-44ed-969b-eb541014eae3") // Poll (position/load) - NOT notifiable!
        private val REPS_UUID = Uuid.parse("8308f2a6-0875-4a94-a86f-5c5c5e1b068a")    // Notify (rep events)

        // Additional Characteristic UUIDs (from parent repo - complete protocol coverage)
        private val DIAGNOSTIC_UUID = Uuid.parse("5fa538ec-d041-42f6-bbd6-c30d475387b7")  // Poll (keep-alive + diagnostics)
        private val HEURISTIC_UUID = Uuid.parse("c7b73007-b245-4503-a1ed-9e4e97eb9802")   // Poll (phase statistics at 4Hz)
        private val VERSION_UUID = Uuid.parse("74e994ac-0e80-4c02-9cd0-76cb31d3959b")     // Notify (firmware version)
        private val MODE_UUID = Uuid.parse("67d0dae0-5bfc-4ea2-acc9-ac784dee7f29")        // Notify (mode changes)
        @Suppress("unused") // Reserved for OTA update feature
        private val UPDATE_STATE_UUID = Uuid.parse("383f7276-49af-4335-9072-f01b0f8acad6") // Notify (update state)
        @Suppress("unused") // Reserved for OTA update feature
        private val BLE_UPDATE_REQUEST_UUID = Uuid.parse("ef0e485a-8749-4314-b1be-01e57cd1712e") // Notify (update request)
        @Suppress("unused") // Reserved for auth feature
        private val UNKNOWN_AUTH_UUID = Uuid.parse("36e6c2ee-21c7-404e-aa9b-f74ca4728ad4") // Notify (auth - web apps use this)

        // Device Information Service (DIS) - standard BLE service for firmware version
        private val DIS_SERVICE_UUID = Uuid.parse("0000180a-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_REVISION_UUID = Uuid.parse("00002a26-0000-1000-8000-00805f9b34fb")

        // Workout command characteristic UUIDs (official app protocol - fan-out)
        private val WORKOUT_CMD_UUIDS = listOf(
            Uuid.parse("6d094aa3-b60d-4916-8a55-8ed73fb9f6a5"),
            Uuid.parse("6d094aa3-b60d-4916-8a55-8ed73fb9f6a6"),
            Uuid.parse("6d094aa3-b60d-4916-8a55-8ed73fb9f6a7"),
            Uuid.parse("6d094aa3-b60d-4916-8a55-8ed73fb9f6a8"),
            Uuid.parse("6d094aa3-b60d-4916-8a55-8ed73fb9f6a9"),
            Uuid.parse("6d094aa3-b60d-4916-8a55-8ed73fb9f6aa"),
            Uuid.parse("6d094aa3-b60d-4916-8a55-8ed73fb9f6ab"),
            Uuid.parse("6d094aa3-b60d-4916-8a55-8ed73fb9f6ac")
        )

        // Connection settings
        private const val CONNECTION_RETRY_COUNT = 3
        private const val CONNECTION_RETRY_DELAY_MS = 100L
        private const val DESIRED_MTU = 247  // Match parent repo (needs 100+ for 96-byte program frames)

        // Handle detection thresholds (from parent repo - proven working)
        // Position values are in mm (raw / 10.0f), so thresholds are in mm
        private const val HANDLE_GRABBED_THRESHOLD = 8.0    // Position > 8.0mm = handles grabbed
        private const val HANDLE_REST_THRESHOLD = 5.0       // Position < 5.0mm = handles at rest
        // Velocity is in mm/s (calculated from mm positions)
        // With 20Hz polling (50ms), 5mm movement per frame = 100 mm/s
        private const val VELOCITY_THRESHOLD = 100.0        // Velocity > 100 mm/s = significant movement

        // Velocity smoothing (Issue #204, #214)
        // EMA alpha: 0.3 = balanced smoothing (faster response during direction changes)
        // Higher alpha reduces zero-crossing dwell time during eccentric/concentric transitions
        // which prevents false stall detection during controlled tempo movements
        private const val VELOCITY_SMOOTHING_ALPHA = 0.3

        // Time-based grab detection fallback for slow poll rates
        // If position is sustained above grab threshold for this duration, consider grabbed
        // regardless of velocity (compensates for slow BLE polling affecting velocity calc)
        private const val SUSTAINED_GRAB_TIME_MS = 300L     // 300ms sustained position = grab detected

        // Sample validation
        @Suppress("unused") // Reserved for future spike detection
        private const val POSITION_SPIKE_THRESHOLD = 50000  // BLE error filter
        private const val MIN_POSITION = -1000              // Valid position range
        private const val MAX_POSITION = 1000               // Valid position range
        private const val POSITION_JUMP_THRESHOLD = 20.0f   // Max allowed position change between samples (mm)

        // Timing constants
        private const val HEARTBEAT_INTERVAL_MS = 2000L
        private const val HEARTBEAT_READ_TIMEOUT_MS = 1500L
        private const val DELOAD_EVENT_DEBOUNCE_MS = 2000L
        private const val DIAGNOSTIC_POLL_INTERVAL_MS = 500L  // Keep-alive polling (matching parent)
        private const val HEURISTIC_POLL_INTERVAL_MS = 250L   // 4Hz phase statistics (matching parent)

        // Heartbeat no-op command (MUST be 4 bytes)
        private val HEARTBEAT_NO_OP = byteArrayOf(0x00, 0x00, 0x00, 0x00)
    }

    // Kable characteristic references - PRIMARY (required for basic operation)
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
    private val repsCharacteristic = characteristicOf(
        service = NUS_SERVICE_UUID,
        characteristic = REPS_UUID
    )

    // Kable characteristic references - SECONDARY (for complete protocol coverage)
    private val diagnosticCharacteristic = characteristicOf(
        service = NUS_SERVICE_UUID,
        characteristic = DIAGNOSTIC_UUID
    )
    private val heuristicCharacteristic = characteristicOf(
        service = NUS_SERVICE_UUID,
        characteristic = HEURISTIC_UUID
    )
    private val versionCharacteristic = characteristicOf(
        service = NUS_SERVICE_UUID,
        characteristic = VERSION_UUID
    )
    private val modeCharacteristic = characteristicOf(
        service = NUS_SERVICE_UUID,
        characteristic = MODE_UUID
    )

    // DIS characteristics for firmware version (standard BLE service)
    private val firmwareRevisionCharacteristic = characteristicOf(
        service = DIS_SERVICE_UUID,
        characteristic = FIRMWARE_REVISION_UUID
    )

    // Workout command characteristics (official app protocol - fan-out)
    // Try each until one works, then cache the working one
    private val workoutCmdCharacteristics = WORKOUT_CMD_UUIDS.map { uuid ->
        characteristicOf(service = NUS_SERVICE_UUID, characteristic = uuid)
    }

    // Cache the working workout command characteristic (discovered at runtime)
    // null = not yet discovered, use fan-out to find working one
    private var workingWorkoutCmdCharacteristic: com.juul.kable.Characteristic? = null

    // State flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _handleState = MutableStateFlow(HandleState())
    override val handleState: StateFlow<HandleState> = _handleState.asStateFlow()

    // Monitor data flow - CRITICAL: Need buffer for high-frequency emissions!
    // Matching parent repo: extraBufferCapacity=64 for ~640ms of data at 10ms/sample
    private val _metricsFlow = MutableSharedFlow<WorkoutMetric>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val metricsFlow: Flow<WorkoutMetric> = _metricsFlow.asSharedFlow()

    // Rep events flow - needs buffer to prevent dropped notifications
    private val _repEvents = MutableSharedFlow<RepNotification>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
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

    // Data parsing state (for spike filtering) - Float for mm precision (Issue #197)
    private var lastGoodPosA = 0.0f
    private var lastGoodPosB = 0.0f

    // Velocity calculation state - Float for mm precision (Issue #197)
    private var lastPositionA = 0.0f
    private var lastPositionB = 0.0f
    private var lastTimestamp = 0L

    // Smoothed velocity for stall detection (Issue #204, #214)
    // Uses EMA to prevent false stall resets from position jitter
    private var smoothedVelocityA = 0.0
    private var smoothedVelocityB = 0.0

    // Handle detection state tracking
    private var minPositionSeen = Double.MAX_VALUE
    private var maxPositionSeen = Double.MIN_VALUE

    // Grab/release threshold timers for hysteresis (matching parent repo)
    // These prevent false triggers from momentary position spikes
    private var forceAboveGrabThresholdStart: Long? = null
    private var forceBelowReleaseThresholdStart: Long? = null

    // Monitor polling job (for explicit control)
    private var monitorPollingJob: kotlinx.coroutines.Job? = null

    // Diagnostic polling job (500ms keep-alive)
    private var diagnosticPollingJob: kotlinx.coroutines.Job? = null

    // Heuristic polling job (4Hz phase statistics)
    private var heuristicPollingJob: kotlinx.coroutines.Job? = null

    // Deload event debouncing
    private var lastDeloadEventTime = 0L

    // Poll rate diagnostics
    private var pollIntervalSum = 0L
    private var pollIntervalCount = 0L
    private var maxPollInterval = 0L
    private var minPollInterval = Long.MAX_VALUE

    // Monitor notification counter (for diagnostic logging)
    private var monitorNotificationCount = 0L

    // Heartbeat job
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    // Detected firmware version (from DIS or proprietary characteristic)
    private var detectedFirmwareVersion: String? = null

    // Strict validation flag (filters >20mm position jumps)
    private var strictValidationEnabled = true

    // Flag to track explicit disconnect (to avoid auto-reconnect)
    private var isExplicitDisconnect = false

    // Flag to track if we ever successfully connected (for auto-reconnect logic)
    // This prevents auto-reconnect from firing on the initial Disconnected state
    // when a Peripheral is first created (before connect() is even called)
    private var wasEverConnected = false

    override suspend fun startScanning() {
        log.i { "Starting BLE scan for Vitruvian devices" }
        logRepo.info(LogEventType.SCAN_START, "Starting BLE scan for Vitruvian devices")

        _scannedDevices.value = emptyList()
        discoveredAdvertisements.clear()
        _connectionState.value = ConnectionState.Scanning

        scanJob = Scanner {
            // No specific filters - we'll filter manually
        }
            .advertisements
            .onEach { advertisement ->
                // Debug logging for all advertisements
                log.d { "RAW ADV: name=${advertisement.name}, id=${advertisement.identifier}, uuids=${advertisement.uuids}, rssi=${advertisement.rssi}" }
            }
            .filter { advertisement ->
                // Filter by name if available
                val name = advertisement.name
                if (name != null) {
                    val isVitruvian = name.startsWith("Vee_") || name.startsWith("VIT")
                    if (isVitruvian) {
                        log.i { "Found Vitruvian by name: $name" }
                    }
                    return@filter isVitruvian
                }

                // Check for Vitruvian service UUIDs (mServiceUuids)
                val serviceUuids = advertisement.uuids
                val hasVitruvianServiceUuid = serviceUuids.any { uuid ->
                    val uuidStr = uuid.toString().lowercase()
                    uuidStr.startsWith("0000fef3") ||
                    uuidStr == "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
                }

                if (hasVitruvianServiceUuid) {
                    log.i { "Found Vitruvian by service UUID: ${advertisement.identifier}" }
                    return@filter true
                }

                // CRITICAL: Check for FEF3 service data
                // The Vitruvian device advertises FEF3 in serviceData, not serviceUuids!
                // In Kable, serviceData is accessed differently - try to get FEF3 directly
                val fef3Uuid = try {
                    Uuid.parse("0000fef3-0000-1000-8000-00805f9b34fb")
                } catch (_: Exception) {
                    null
                }

                val hasVitruvianServiceData = if (fef3Uuid != null) {
                    // Try to get data for FEF3 service UUID
                    val fef3Data = advertisement.serviceData(fef3Uuid)
                    if (fef3Data != null && fef3Data.isNotEmpty()) {
                        log.i { "Found Vitruvian by FEF3 serviceData: ${advertisement.identifier}, data size: ${fef3Data.size}" }
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }

                hasVitruvianServiceData
            }
            .onEach { advertisement ->
                val identifier = advertisement.identifier.toString()
                // Use name if available, otherwise use identifier as placeholder
                val name = advertisement.name ?: "Vitruvian ($identifier)"

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
                // Return to Disconnected instead of Error for scan failures - user can retry
                _connectionState.value = ConnectionState.Disconnected
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
            // Return to Disconnected - device may have gone out of range, user can retry
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        // Store device info for logging
        connectedDeviceName = device.name
        connectedDeviceAddress = device.address

        try {
            stopScanning()

            // Create peripheral - MTU request is handled in onDeviceReady() via platform-specific extension
            // The onServicesDiscovered callback in Kable doesn't have requestMtu in common code
            peripheral = Peripheral(advertisement)

            // Observe connection state
            peripheral?.state
                ?.onEach { state ->
                    when (state) {
                        is State.Connecting -> {
                            _connectionState.value = ConnectionState.Connecting
                        }
                        is State.Connected -> {
                            // Mark that we successfully connected (for auto-reconnect logic)
                            wasEverConnected = true
                            log.i { "✅ Connection established to ${device.name}" }
                            logRepo.info(
                                LogEventType.CONNECT_SUCCESS,
                                "Device connected successfully",
                                connectedDeviceName,
                                connectedDeviceAddress
                            )
                            _connectionState.value = ConnectionState.Connected(
                                deviceName = device.name,
                                deviceAddress = device.address,
                                hardwareModel = HardwareDetection.detectModel(device.name)
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
                            // Capture device info and connection state BEFORE clearing
                            val deviceName = connectedDeviceName
                            val deviceAddress = connectedDeviceAddress
                            val hadConnection = wasEverConnected

                            // Only process disconnect if we were actually connected
                            if (hadConnection) {
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
                            } else {
                                // This is the initial Disconnected state when Peripheral is created
                                // Don't reset state or peripheral - we're about to call connect()
                                log.d { "Peripheral initial state: Disconnected (awaiting connect() call)" }
                                return@onEach  // Skip the rest of this handler
                            }

                            // Request auto-reconnect ONLY if:
                            // 1. We were previously connected (wasEverConnected)
                            // 2. This was NOT an explicit disconnect
                            // 3. We have a valid device address
                            if (hadConnection && !isExplicitDisconnect && deviceAddress.isNotEmpty()) {
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

                            // Reset flags for next connection cycle
                            isExplicitDisconnect = false
                            wasEverConnected = false
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
            // Return to Disconnected instead of Error - connection failures are retryable
            _connectionState.value = ConnectionState.Disconnected
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

        // Request High Connection Priority (Android only - via expect/actual extension)
        // Critical for maintaining ~20Hz polling rate without lag
        p.requestHighPriority()

        // Request MTU - Kable handles MTU negotiation automatically
        logRepo.debug(LogEventType.MTU_CHANGED, "Requesting MTU $DESIRED_MTU")

        // Dump discovered services for debugging
        try {
            log.i { "📋 Attempting to enumerate discovered services..." }
            // Note: Kable's services property returns List<DiscoveredService>?
            // We'll log what we can to understand the device's GATT structure
            val servicesStr = p.services.toString()
            log.i { "📋 Services: $servicesStr" }
        } catch (e: Exception) {
            log.e { "Failed to enumerate services: ${e.message}" }
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
     * Perform heartbeat read on the MONITOR characteristic (not TX).
     * TX is write-only and can't be read. Monitor reads are already working.
     * Returns true if read succeeded, false otherwise.
     */
    private suspend fun performHeartbeatRead(p: Peripheral): Boolean {
        return try {
            p.read(monitorCharacteristic)
            log.v { "Heartbeat read succeeded (monitor char)" }
            true
        } catch (e: Exception) {
            log.w { "Heartbeat read failed: ${e.message}" }
            false
        }
    }

    /**
     * Send heartbeat no-op write as fallback when read fails.
     * Uses 4-byte no-op command (MUST be exactly 4 bytes).
     * Tries WriteWithResponse first, then WithoutResponse.
     */
    private suspend fun sendHeartbeatNoOp(p: Peripheral) {
        try {
            p.write(txCharacteristic, HEARTBEAT_NO_OP, WriteType.WithResponse)
            log.v { "Heartbeat no-op write sent (WithResponse)" }
        } catch (_: Exception) {
            try {
                p.write(txCharacteristic, HEARTBEAT_NO_OP, WriteType.WithoutResponse)
                log.v { "Heartbeat no-op write sent (WithoutResponse)" }
            } catch (e2: Exception) {
                log.w { "Heartbeat no-op write failed: ${e2.message}" }
            }
        }
    }

    private fun startObservingNotifications() {
        val p = peripheral ?: return

        logRepo.info(
            LogEventType.NOTIFICATION,
            "Enabling BLE notifications and starting polling (matching parent repo)",
            connectedDeviceName,
            connectedDeviceAddress
        )

        // ===== FIRMWARE VERSION READ (best effort) =====
        // Try to read firmware version from Device Information Service
        scope.launch {
            tryReadFirmwareVersion(p)
            tryReadVitruvianVersion(p)
        }

        // ===== CORE NOTIFICATIONS =====

        // Observe RX characteristic for command responses
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

        // Observe REPS characteristic for rep completion events (CRITICAL for rep counting!)
        scope.launch {
            try {
                log.i { "Starting REPS characteristic notifications (rep events)" }
                p.observe(repsCharacteristic)
                    .catch { e ->
                        log.e { "Reps observation error: ${e.message}" }
                        logRepo.error(
                            LogEventType.ERROR,
                            "Reps notification error",
                            connectedDeviceName,
                            connectedDeviceAddress,
                            e.message
                        )
                    }
                    .collect { data ->
                        log.d { "REPS notification received: ${data.size} bytes" }
                        parseRepsCharacteristicData(data)
                    }
            } catch (e: Exception) {
                log.e { "Failed to observe Reps: ${e.message}" }
                logRepo.error(
                    LogEventType.ERROR,
                    "Failed to enable Reps notifications",
                    connectedDeviceName,
                    connectedDeviceAddress,
                    e.message
                )
            }
        }

        // Observe VERSION characteristic (for firmware info logging)
        scope.launch {
            try {
                log.d { "Starting VERSION characteristic notifications" }
                p.observe(versionCharacteristic)
                    .catch { e -> log.w { "Version observation error (non-fatal): ${e.message}" } }
                    .collect { data ->
                        val hexString = data.joinToString(" ") { it.toHexString() }
                        log.i { "╔════════════════════════════════════════╗" }
                        log.i { "║  VERSION CHARACTERISTIC DATA RECEIVED   ║" }
                        log.i { "║  Size: ${data.size} bytes, Hex: $hexString" }
                        log.i { "╚════════════════════════════════════════╝" }
                    }
            } catch (e: Exception) {
                log.d { "VERSION notifications not available (expected): ${e.message}" }
            }
        }

        // Observe MODE characteristic (for mode change logging)
        scope.launch {
            try {
                log.d { "Starting MODE characteristic notifications" }
                p.observe(modeCharacteristic)
                    .catch { e -> log.w { "Mode observation error (non-fatal): ${e.message}" } }
                    .collect { data ->
                        log.d { "MODE notification: ${data.size} bytes" }
                    }
            } catch (e: Exception) {
                log.d { "MODE notifications not available (expected): ${e.message}" }
            }
        }

        // ===== POLLING (NOT notifications - these chars are ReadableCharacteristics) =====

        // MONITOR characteristic - use POLLING only (NOT notifications)
        // Per parent repo: "SAMPLE_CHAR is NOT a NotifiableCharacteristic!"
        log.i { "Starting MONITOR characteristic polling (real-time metrics)" }
        startMonitorPolling(p)

        // DIAGNOSTIC characteristic - 500ms keep-alive polling
        // Maintains connection and provides fault/temperature data
        log.i { "Starting DIAGNOSTIC characteristic polling (500ms keep-alive)" }
        startDiagnosticPolling(p)

        // HEURISTIC characteristic - 4Hz phase statistics polling
        // Provides concentric/eccentric statistics for UI
        log.i { "Starting HEURISTIC characteristic polling (4Hz phase stats)" }
        startHeuristicPolling(p)
    }

    /**
     * Try to read firmware version from Device Information Service (DIS).
     * This is purely diagnostic - failures are logged but don't affect connection.
     */
    private suspend fun tryReadFirmwareVersion(p: Peripheral) {
        try {
            val data = withTimeoutOrNull(2000L) {
                p.read(firmwareRevisionCharacteristic)
            }
            if (data != null && data.isNotEmpty()) {
                detectedFirmwareVersion = data.decodeToString().trim()
                log.i { "╔════════════════════════════════════════╗" }
                log.i { "║  🔧 FIRMWARE VERSION: $detectedFirmwareVersion" }
                log.i { "╚════════════════════════════════════════╝" }
                logRepo.info(
                    LogEventType.CONNECT_SUCCESS,
                    "Firmware version detected: $detectedFirmwareVersion",
                    connectedDeviceName,
                    connectedDeviceAddress
                )
            }
        } catch (e: Exception) {
            log.d { "Device Information Service not available (expected): ${e.message}" }
        }
    }

    /**
     * Try to read proprietary Vitruvian VERSION characteristic.
     * Contains hardware/firmware info in a proprietary format.
     */
    private suspend fun tryReadVitruvianVersion(p: Peripheral) {
        try {
            val data = withTimeoutOrNull(2000L) {
                p.read(versionCharacteristic)
            }
            if (data != null && data.isNotEmpty()) {
                val hexString = data.joinToString(" ") { it.toHexString() }
                log.i { "Vitruvian VERSION characteristic: ${data.size} bytes - $hexString" }
            }
        } catch (e: Exception) {
            log.d { "Vitruvian VERSION characteristic not readable (expected): ${e.message}" }
        }
    }

    /**
     * Poll DIAGNOSTIC characteristic every 500ms for keep-alive and health monitoring.
     * Matches official app interval. Uses suspend-based reads.
     */
    private fun startDiagnosticPolling(p: Peripheral) {
        diagnosticPollingJob?.cancel()
        diagnosticPollingJob = scope.launch {
            log.d { "🔄 Starting SEQUENTIAL diagnostic polling (${DIAGNOSTIC_POLL_INTERVAL_MS}ms interval - matches official app)" }
            var successfulReads = 0L
            var failedReads = 0L

            while (_connectionState.value is ConnectionState.Connected && isActive) {
                try {
                    val data = withTimeoutOrNull(HEARTBEAT_READ_TIMEOUT_MS) {
                        p.read(diagnosticCharacteristic)
                    }

                    if (data != null) {
                        successfulReads++
                        if (successfulReads % 100 == 0L) {
                            log.v { "📊 Diagnostic poll #$successfulReads (failed: $failedReads)" }
                        }
                        parseDiagnosticData(data)
                    } else {
                        failedReads++
                    }

                    // Fixed 500ms interval for keep-alive purposes
                    delay(DIAGNOSTIC_POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    failedReads++
                    if (failedReads <= 5 || failedReads % 20 == 0L) {
                        log.w { "❌ Diagnostic poll failed #$failedReads: ${e.message}" }
                    }
                    delay(DIAGNOSTIC_POLL_INTERVAL_MS)
                }
            }
            log.d { "📊 Diagnostic polling ended (success: $successfulReads, failed: $failedReads)" }
        }
    }

    /**
     * Poll HEURISTIC characteristic at 4Hz (250ms) for phase statistics.
     * Provides concentric/eccentric statistics matching official app.
     */
    private fun startHeuristicPolling(p: Peripheral) {
        heuristicPollingJob?.cancel()
        heuristicPollingJob = scope.launch {
            log.d { "🔄 Starting SEQUENTIAL heuristic polling (${HEURISTIC_POLL_INTERVAL_MS}ms interval / 4Hz - matches official app)" }

            while (_connectionState.value is ConnectionState.Connected && isActive) {
                try {
                    val data = withTimeoutOrNull(HEARTBEAT_READ_TIMEOUT_MS) {
                        p.read(heuristicCharacteristic)
                    }

                    if (data != null) {
                        parseHeuristicData(data)
                    }

                    // Poll every 250ms (4Hz) - matching official app
                    delay(HEURISTIC_POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    log.v { "Heuristic poll error (non-fatal): ${e.message}" }
                    delay(HEURISTIC_POLL_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * Parse diagnostic data from DIAGNOSTIC/PROPERTY characteristic.
     * Contains fault codes and temperature readings.
     */
    private fun parseDiagnosticData(bytes: ByteArray) {
        try {
            if (bytes.size < 20) return

            // Little-endian parsing (matching parent repo)
            // Bytes 0-3 contain uptime seconds (reserved for future use)
            // val seconds = getInt32LE(bytes, 0)

            // Parse 4 fault codes (shorts)
            val faults = mutableListOf<Short>()
            for (i in 0 until 4) {
                val offset = 4 + (i * 2)
                val fault = ((bytes[offset].toInt() and 0xFF) or
                        ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()
                faults.add(fault)
            }

            // Parse 8 temperature readings (bytes)
            val temps = mutableListOf<Byte>()
            for (i in 0 until 8) {
                temps.add(bytes[12 + i])
            }

            val containsFaults = faults.any { it != 0.toShort() }
            if (containsFaults) {
                log.w { "⚠️ DIAGNOSTIC FAULTS DETECTED: $faults" }
            }

            // Could expose this as a flow if UI needs it
            // For now, just log for diagnostics
        } catch (e: Exception) {
            log.e { "Failed to parse diagnostic data: ${e.message}" }
        }
    }

    /**
     * Parse heuristic data from HEURISTIC characteristic.
     * Contains concentric/eccentric phase statistics.
     */
    private fun parseHeuristicData(bytes: ByteArray) {
        try {
            if (bytes.size < 48) return

            // Parse 6 floats for concentric stats (24 bytes)
            // Parse 6 floats for eccentric stats (24 bytes)
            // Format: kgAvg, kgMax, velAvg, velMax, wattAvg, wattMax

            // Could expose this as a flow if UI needs phase statistics
            // For now, the polling maintains BLE connection activity
        } catch (e: Exception) {
            log.v { "Failed to parse heuristic data: ${e.message}" }
        }
    }

    /**
     * Poll MONITOR characteristic for real-time position/load data.
     * Per parent repo: "SAMPLE_CHAR is NOT a NotifiableCharacteristic!
     * Per official Vitruvian app analysis, Sample data MUST be polled via readCharacteristic()"
     *
     * CRITICAL: Uses withTimeout to prevent hangs if BLE stack doesn't respond.
     * Parent repo uses withTimeoutOrNull(HEARTBEAT_READ_TIMEOUT_MS) for the same reason.
     * NO fixed delay between successful reads - natural rate-limiting by BLE response time.
     *
     * @param forAutoStart If true, enables handle detection with WaitingForRest state (for Just Lift auto-start).
     *                     If false, sets handle state to Active (for active workout monitoring).
     */
    private fun startMonitorPolling(p: Peripheral, forAutoStart: Boolean = false) {
        // Reset position tracking for new workout/session
        minPositionSeen = Double.MAX_VALUE
        maxPositionSeen = Double.MIN_VALUE

        // Reset notification counter for this session
        val previousCount = monitorNotificationCount
        monitorNotificationCount = 0L
        log.i { "📊 Monitor notifications reset (previous session: $previousCount notifications)" }

        if (forAutoStart) {
            // AUTO-START MODE: Initialize handle state machine
            // Start in WaitingForRest state - must see handles at rest (low position) before arming grab detection
            // This prevents immediate auto-start if cables already have tension
            _handleActivityState.value = HandleActivityState.WaitingForRest
            forceAboveGrabThresholdStart = null
            forceBelowReleaseThresholdStart = null
            handleDetectionEnabled = true
            log.i { "🎯 Monitor polling for AUTO-START - waiting for handles at rest (pos < ${HANDLE_REST_THRESHOLD}mm)" }
        } else {
            // ACTIVE WORKOUT MODE: Skip state machine initialization, set to Active
            // Workout is already running, no need for grab detection
            _handleActivityState.value = HandleActivityState.Active
            handleDetectionEnabled = false
            log.i { "🏋️ Monitor polling for ACTIVE WORKOUT (handle detection disabled)" }
        }

        // Cancel any existing polling job before starting new one
        monitorPollingJob?.cancel()

        // Start sequential polling using suspend-based reads (official app approach)
        monitorPollingJob = scope.launch {
            var failCount = 0
            var successCount = 0L
            var consecutiveTimeouts = 0
            log.i { "🔄 Starting SEQUENTIAL monitor polling (with timeout=${HEARTBEAT_READ_TIMEOUT_MS}ms, forAutoStart=$forAutoStart)" }

            try {
                while (_connectionState.value is ConnectionState.Connected && isActive) {
                    try {
                        // CRITICAL: Wrap read in timeout to prevent indefinite hangs
                        // BLE stack can sometimes fail to return success/failure callback
                        // This matches parent repo's withTimeoutOrNull pattern
                        val data = withTimeoutOrNull(HEARTBEAT_READ_TIMEOUT_MS) {
                            p.read(monitorCharacteristic)
                        }

                        if (data != null) {
                            // Success - parse data and continue immediately
                            successCount++
                            consecutiveTimeouts = 0
                            if (successCount == 1L || successCount % 500 == 0L) {
                                log.i { "📊 Monitor poll SUCCESS #$successCount, data size: ${data.size}" }
                            }
                            parseMonitorData(data)
                            failCount = 0
                            // NO DELAY on success - BLE response time naturally rate-limits (~10-20ms)
                        } else {
                            // Timeout - BLE stack hung, continue polling
                            consecutiveTimeouts++
                            if (consecutiveTimeouts <= 3 || consecutiveTimeouts % 10 == 0) {
                                log.w { "⏱️ Monitor read timed out (${HEARTBEAT_READ_TIMEOUT_MS}ms) - consecutive: $consecutiveTimeouts" }
                            }
                            // Small delay after timeout to avoid tight loop
                            delay(50)
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        // Explicit timeout exception (shouldn't happen with withTimeoutOrNull, but safety)
                        consecutiveTimeouts++
                        log.w { "⏱️ Monitor read timeout exception - consecutive: $consecutiveTimeouts" }
                        delay(50)
                    } catch (e: Exception) {
                        // Kable exceptions (IOException, GattException, etc.)
                        failCount++
                        consecutiveTimeouts = 0
                        if (failCount <= 5 || failCount % 50 == 0) {
                            log.w { "❌ Monitor poll FAILED #$failCount: ${e.message}" }
                        }
                        // Delay on failure to prevent tight error loops
                        delay(50)
                    }
                }
            } catch (e: Exception) {
                log.e { "Monitor polling stopped: ${e.message}" }
            }
            log.i { "📊 Monitor polling ended (reads: $successCount, failures: $failCount, timeouts: $consecutiveTimeouts)" }
        }
    }

    override suspend fun disconnect() {
        log.i { "Disconnecting (explicit)" }
        isExplicitDisconnect = true  // Mark as explicit disconnect to prevent auto-reconnect

        // Cancel all polling jobs
        heartbeatJob?.cancel()
        heartbeatJob = null
        monitorPollingJob?.cancel()
        monitorPollingJob = null
        diagnosticPollingJob?.cancel()
        diagnosticPollingJob = null
        heuristicPollingJob?.cancel()
        heuristicPollingJob = null

        // Clear cached workout command characteristic (will rediscover on next connect)
        workingWorkoutCmdCharacteristic = null

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

        val commandHex = command.joinToString(" ") { it.toHexString() }

        // STRATEGY: Try workout command characteristics fan-out (matching official app protocol)
        // 1. If we have a cached working characteristic, use it
        // 2. Otherwise, try each workout command characteristic until one succeeds
        // 3. Fall back to NUS TX characteristic as last resort

        // Step 1: Try cached working characteristic if available
        workingWorkoutCmdCharacteristic?.let { cachedChar ->
            try {
                p.write(cachedChar, command, WriteType.WithoutResponse)
                log.d { "Command sent via cached workout char: ${command.size} bytes" }
                logRepo.debug(
                    LogEventType.COMMAND_SENT,
                    "Command sent (cached workout char)",
                    connectedDeviceName,
                    connectedDeviceAddress,
                    "Size: ${command.size} bytes"
                )
                return
            } catch (e: Exception) {
                log.w { "Cached workout char failed, will try fan-out: ${e.message}" }
                workingWorkoutCmdCharacteristic = null  // Clear cache
            }
        }

        // Step 2: Try each workout command characteristic (official app fan-out)
        for ((index, char) in workoutCmdCharacteristics.withIndex()) {
            try {
                p.write(char, command, WriteType.WithoutResponse)
                workingWorkoutCmdCharacteristic = char  // Cache for future use
                log.i { "✅ Workout command sent via char #$index: ${command.size} bytes" }
                logRepo.debug(
                    LogEventType.COMMAND_SENT,
                    "Command sent (workout char #$index)",
                    connectedDeviceName,
                    connectedDeviceAddress,
                    "Size: ${command.size} bytes"
                )
                return
            } catch (e: Exception) {
                log.v { "Workout cmd char #$index failed: ${e.message}" }
                // Continue to next characteristic
            }
        }

        // Step 3: Fall back to NUS TX characteristic (original method)
        log.d { "All workout chars failed, falling back to NUS TX" }
        try {
            // Try WriteWithResponse first (some devices don't support WithoutResponse)
            try {
                p.write(txCharacteristic, command, WriteType.WithResponse)
                log.d { "Command sent via NUS TX (WithResponse): ${command.size} bytes" }
            } catch (e: Exception) {
                log.d { "WithResponse failed, trying WithoutResponse: ${e.message}" }
                p.write(txCharacteristic, command, WriteType.WithoutResponse)
                log.d { "Command sent via NUS TX (WithoutResponse): ${command.size} bytes" }
            }
            logRepo.debug(
                LogEventType.COMMAND_SENT,
                "Command sent (NUS TX fallback)",
                connectedDeviceName,
                connectedDeviceAddress,
                "Size: ${command.size} bytes, Data: $commandHex"
            )
        } catch (e: Exception) {
            log.e { "Failed to send command (all methods): ${e.message}" }
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
        log.i { "🎮 Handle detection ${if (enabled) "ENABLED" else "DISABLED"}" }
        if (enabled) {
            // Start/restart monitor polling with forAutoStart=true to arm the state machine
            val p = peripheral
            if (p != null) {
                startMonitorPolling(p, forAutoStart = true)
            } else {
                // No peripheral connected, just set the state for when it connects
                handleDetectionEnabled = true
                _handleActivityState.value = HandleActivityState.WaitingForRest
                handleStateLogCounter = 0L
                minPositionSeen = Double.MAX_VALUE
                maxPositionSeen = Double.MIN_VALUE
                forceAboveGrabThresholdStart = null
                forceBelowReleaseThresholdStart = null
                log.i { "🎮 Handle state machine reset (no peripheral - will arm when connected)" }
            }
        } else {
            // Disable handle detection but keep polling for metrics
            handleDetectionEnabled = false
            log.i { "🎮 Handle detection disabled (polling continues for metrics)" }
        }
    }

    override fun resetHandleActivityState() {
        log.d { "Resetting handle activity state to WaitingForRest" }
        _handleActivityState.value = HandleActivityState.WaitingForRest
        minPositionSeen = Double.MAX_VALUE
        maxPositionSeen = Double.MIN_VALUE
        forceAboveGrabThresholdStart = null
        forceBelowReleaseThresholdStart = null
    }

    override fun enableJustLiftWaitingMode() {
        log.i { "🎯 Enabling Just Lift waiting mode - ready for next set" }
        log.i { "   Detection thresholds: grab pos>${HANDLE_GRABBED_THRESHOLD}mm + vel>${VELOCITY_THRESHOLD}mm/s, release pos<${HANDLE_REST_THRESHOLD}mm" }

        // Reset position tracking for diagnostics
        minPositionSeen = Double.MAX_VALUE
        maxPositionSeen = Double.MIN_VALUE

        // Reset grab/release timers for hysteresis
        forceAboveGrabThresholdStart = null
        forceBelowReleaseThresholdStart = null

        // Reset handle state log counter
        handleStateLogCounter = 0L

        // Start in WaitingForRest state - must see handles at rest before arming grab detection
        _handleActivityState.value = HandleActivityState.WaitingForRest

        // Enable handle detection for the state machine
        handleDetectionEnabled = true
    }

    override fun restartMonitorPolling() {
        log.i { "🔄 Restarting monitor polling to clear machine fault state" }
        val p = peripheral
        if (p != null) {
            // Restart polling WITHOUT arming auto-start (forAutoStart=false)
            // This clears the machine's danger zone alarm but doesn't enable grab detection
            startMonitorPolling(p, forAutoStart = false)
        } else {
            log.w { "Cannot restart monitor polling - peripheral is null" }
        }
    }

    override fun startActiveWorkoutPolling() {
        log.i { "🏋️ Starting active workout polling (no auto-start)" }
        val p = peripheral
        if (p != null) {
            // Start polling for active workout (forAutoStart=false)
            // Handle state is set to Active, no grab detection
            startMonitorPolling(p, forAutoStart = false)
        } else {
            log.w { "Cannot start active workout polling - peripheral is null" }
        }
    }

    override fun stopPolling() {
        val timestamp = currentTimeMillis()
        log.d { "STOP_DEBUG: [$timestamp] stopPolling() called" }

        // Log analysis from workout (position range for diagnostics)
        // Matches logic from old VitruvianBleManager.kt
        if (minPositionSeen != Double.MAX_VALUE && maxPositionSeen != Double.MIN_VALUE) {
            log.i { "========== WORKOUT ANALYSIS ==========" }
            log.i { "Position range: min=$minPositionSeen, max=$maxPositionSeen" }
            log.i { "v0.5.1-beta detection thresholds:" }
            log.i { "  Handle grab: pos > $HANDLE_GRABBED_THRESHOLD + velocity > $VELOCITY_THRESHOLD" }
            log.i { "  Handle release: pos < $HANDLE_REST_THRESHOLD" }
            log.i { "======================================" }
        }

        monitorPollingJob?.cancel()
        diagnosticPollingJob?.cancel()
        heuristicPollingJob?.cancel()
        heartbeatJob?.cancel()

        monitorPollingJob = null
        diagnosticPollingJob = null
        heuristicPollingJob = null
        heartbeatJob = null

        val afterCancel = currentTimeMillis()
        log.d { "STOP_DEBUG: [$afterCancel] Jobs cancelled (took ${afterCancel - timestamp}ms)" }
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
    @Suppress("unused") // Reserved for future protocol handshake commands
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
            val posARaw = getInt16LE(data, 4)  // Signed 16-bit for position (Issue #197)
            val loadARaw = getUInt16LE(data, 8)
            val posBRaw = getInt16LE(data, 10)  // Signed 16-bit for position (Issue #197)
            val loadBRaw = getUInt16LE(data, 14)

            // Reconstruct 32-bit tick counter
            val ticks = f0 + (f1 shl 16)

            // Position values scaled to millimeters (Issue #197)
            // Raw values divided by 10.0f to get mm precision
            var posA = posARaw / 10.0f
            var posB = posBRaw / 10.0f

            // ===== SPIKE FILTERING =====
            // BLE transmission errors produce values > 5000mm (was > 50000 raw units)
            // Per official app documentation, valid range is -1000 to +1000 mm
            // Spike threshold is now 20f mm (was 200 raw units / 10 = 20mm)
            val SPIKE_THRESHOLD_MM = 5000f  // Values > 5000mm are BLE errors
            if (kotlin.math.abs(posA) > SPIKE_THRESHOLD_MM) {
                posA = lastGoodPosA
            } else {
                lastGoodPosA = posA
            }

            if (kotlin.math.abs(posB) > SPIKE_THRESHOLD_MM) {
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
            val pollIntervalMs = if (lastTimestamp > 0L) currentTime - lastTimestamp else 0L

            // ===== POLL RATE DIAGNOSTICS =====
            // Parent repo achieves ~10-20ms poll intervals with Nordic BLE
            // If we're seeing >30ms consistently, connection priority may not be set
            if (pollIntervalMs > 0) {
                pollIntervalSum += pollIntervalMs
                pollIntervalCount++
                if (pollIntervalMs > maxPollInterval) maxPollInterval = pollIntervalMs
                if (pollIntervalMs < minPollInterval) minPollInterval = pollIntervalMs

                // Log every 100 samples with statistics
                if (pollIntervalCount % 100 == 0L) {
                    val avgInterval = pollIntervalSum / pollIntervalCount
                    log.i { "📊 POLL RATE: avg=${avgInterval}ms, min=${minPollInterval}ms, max=${maxPollInterval}ms, count=$pollIntervalCount" }
                    if (avgInterval > 30) {
                        log.w { "⚠️ SLOW POLL RATE: ${avgInterval}ms avg (expected <20ms). Check connection priority!" }
                    }
                }

                // Warn on individual slow polls (but not spam)
                if (pollIntervalMs > 50 && pollIntervalCount % 20 == 0L) {
                    log.w { "⚠️ Slow poll: ${pollIntervalMs}ms (sample #$pollIntervalCount)" }
                }
            }

            // Calculate raw velocity (SIGNED for proper EMA smoothing - Issue #204, #214)
            // Using signed velocity allows jitter oscillations (+2, -3, +1mm) to average toward 0
            val rawVelocityA = if (lastTimestamp > 0L) {
                val deltaTime = (currentTime - lastTimestamp) / 1000.0
                val deltaPos = posA - lastPositionA
                if (deltaTime > 0) deltaPos / deltaTime else 0.0
            } else 0.0

            val rawVelocityB = if (lastTimestamp > 0L) {
                val deltaTime = (currentTime - lastTimestamp) / 1000.0
                val deltaPos = posB - lastPositionB
                if (deltaTime > 0) deltaPos / deltaTime else 0.0
            } else 0.0

            // Apply Exponential Moving Average (EMA) smoothing (Issue #204, #214)
            // This prevents false stall detection during controlled tempo movements
            // and reduces sensitivity to BLE position jitter
            smoothedVelocityA = VELOCITY_SMOOTHING_ALPHA * rawVelocityA +
                    (1 - VELOCITY_SMOOTHING_ALPHA) * smoothedVelocityA
            smoothedVelocityB = VELOCITY_SMOOTHING_ALPHA * rawVelocityB +
                    (1 - VELOCITY_SMOOTHING_ALPHA) * smoothedVelocityB

            // Update tracking state for next velocity calculation
            lastPositionA = posA
            lastPositionB = posB
            lastTimestamp = currentTime

            // Create metric with SMOOTHED velocity (absolute value for backwards compatibility)
            val metric = WorkoutMetric(
                timestamp = currentTime,
                loadA = loadA,
                loadB = loadB,
                positionA = posA,
                positionB = posB,
                ticks = ticks,
                velocityA = smoothedVelocityA,  // Smoothed, signed velocity
                velocityB = smoothedVelocityB,  // Smoothed, signed velocity
                status = status
            )

            // Use tryEmit for non-blocking emission (matching parent repo)
            val emitted = _metricsFlow.tryEmit(metric)
            if (!emitted && monitorNotificationCount % 100 == 0L) {
                log.w { "Failed to emit metric - buffer full? Count: $monitorNotificationCount" }
            }

            // ===== SIMPLE HANDLE STATE (for backward compatibility) =====
            if (handleDetectionEnabled) {
                val activeThreshold = 50.0f  // 50mm threshold (was 500 raw units / 10 = 50mm)
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
     * Position values are in millimeters (Issue #197).
     */
    private fun validateSample(posA: Float, loadA: Float, posB: Float, loadB: Float): Boolean {
        // Official app range: -1000 to +1000 mm (Float for mm precision - Issue #197)
        if (posA !in MIN_POSITION.toFloat()..MAX_POSITION.toFloat() ||
            posB !in MIN_POSITION.toFloat()..MAX_POSITION.toFloat()) {
            log.w { "Position out of range: posA=$posA, posB=$posB (valid: $MIN_POSITION to $MAX_POSITION mm)" }
            return false
        }

        // STRICT VALIDATION: Filter >20mm jumps between samples (matching parent repo)
        // This catches BLE glitches that produce sudden position changes
        if (strictValidationEnabled && lastTimestamp > 0L) {
            val jumpA = kotlin.math.abs(posA - lastPositionA)
            val jumpB = kotlin.math.abs(posB - lastPositionB)
            if (jumpA > POSITION_JUMP_THRESHOLD || jumpB > POSITION_JUMP_THRESHOLD) {
                log.w { "⚠️ Position jump filtered: jumpA=${jumpA}mm, jumpB=${jumpB}mm (threshold: ${POSITION_JUMP_THRESHOLD}mm)" }
                return false
            }
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
    // Counter for periodic diagnostic logging
    private var handleStateLogCounter = 0L

    /**
     * 4-state handle activity machine matching parent repo v0.5.1-beta:
     *
     * State transitions:
     * - WaitingForRest → SetComplete: When both handles < 5mm (armed)
     * - SetComplete/Moving → Active: When position > 8mm AND velocity > 100mm/s (GRAB DETECTED)
     * - SetComplete/Moving → Moving: When position > 8mm but no velocity (intermediate)
     * - SetComplete/Moving → SetComplete: When position <= 8mm (back to rest)
     * - Active → SetComplete: When both handles < 5mm (RELEASE DETECTED)
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

        // Check handles - support single-handle exercises
        val handleAGrabbed = posA > HANDLE_GRABBED_THRESHOLD
        val handleBGrabbed = posB > HANDLE_GRABBED_THRESHOLD
        val handleAMoving = velocityA > VELOCITY_THRESHOLD
        val handleBMoving = velocityB > VELOCITY_THRESHOLD

        // Periodic diagnostic logging (every 200 samples at high poll rate)
        handleStateLogCounter++
        if (handleStateLogCounter % 200 == 0L) {
            log.i { "🎯 HANDLE STATE: $currentState | posA=${posA.format(1)}mm posB=${posB.format(1)}mm | velA=${velocityA.format(0)} velB=${velocityB.format(0)} | thresholds: rest<$HANDLE_REST_THRESHOLD grab>$HANDLE_GRABBED_THRESHOLD vel>$VELOCITY_THRESHOLD" }
        }

        return when (currentState) {
            HandleActivityState.WaitingForRest -> {
                // MUST see handles at rest before arming grab detection
                // This prevents immediate auto-start if cables already have tension
                if (posA < HANDLE_REST_THRESHOLD && posB < HANDLE_REST_THRESHOLD) {
                    log.i { "✅ Handles at REST (posA=$posA, posB=$posB < $HANDLE_REST_THRESHOLD) - auto-start now ARMED" }
                    HandleActivityState.SetComplete  // SetComplete = "Released/Armed" state
                } else {
                    HandleActivityState.WaitingForRest
                }
            }

            HandleActivityState.SetComplete, HandleActivityState.Moving -> {
                // Check if EITHER handle is grabbed AND moving (for single-handle exercises)
                val aActive = handleAGrabbed && handleAMoving
                val bActive = handleBGrabbed && handleBMoving

                // Time-based fallback: If position is sustained above threshold for SUSTAINED_GRAB_TIME_MS,
                // consider grabbed regardless of velocity. This compensates for slow BLE polling
                // that results in artificially low velocity calculations.
                val currentTime = currentTimeMillis()
                val sustainedGrab = if (handleAGrabbed || handleBGrabbed) {
                    if (forceAboveGrabThresholdStart == null) {
                        forceAboveGrabThresholdStart = currentTime
                    }
                    val holdTime = currentTime - forceAboveGrabThresholdStart!!
                    holdTime >= SUSTAINED_GRAB_TIME_MS
                } else {
                    forceAboveGrabThresholdStart = null
                    false
                }

                when {
                    aActive || bActive -> {
                        // GRAB CONFIRMED - position AND velocity thresholds met (fast path)
                        forceAboveGrabThresholdStart = null  // Reset timer
                        val activeHandle = when {
                            aActive && bActive -> "both"
                            aActive -> "A"
                            else -> "B"
                        }
                        log.i { "🔥 GRAB CONFIRMED (velocity): handle=$activeHandle (posA=${posA.format(1)}, posB=${posB.format(1)}, velA=${velocityA.format(0)}, velB=${velocityB.format(0)})" }
                        HandleActivityState.Active
                    }
                    sustainedGrab -> {
                        // GRAB CONFIRMED via time-based fallback (position held for 300ms)
                        forceAboveGrabThresholdStart = null  // Reset timer
                        val activeHandle = when {
                            handleAGrabbed && handleBGrabbed -> "both"
                            handleAGrabbed -> "A"
                            else -> "B"
                        }
                        log.i { "🔥 GRAB CONFIRMED (sustained ${SUSTAINED_GRAB_TIME_MS}ms): handle=$activeHandle (posA=${posA.format(1)}, posB=${posB.format(1)})" }
                        HandleActivityState.Active
                    }
                    handleAGrabbed || handleBGrabbed -> {
                        // Position extended but no velocity yet - transition to Moving (intermediate state)
                        if (currentState != HandleActivityState.Moving) {
                            log.d { "📍 Moving (no velocity yet): posA=${posA.format(1)}, posB=${posB.format(1)}, waiting for velocity or ${SUSTAINED_GRAB_TIME_MS}ms hold" }
                        }
                        HandleActivityState.Moving
                    }
                    else -> {
                        // Back to rest position
                        forceAboveGrabThresholdStart = null  // Reset timer
                        HandleActivityState.SetComplete
                    }
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

    private fun Double.format(decimals: Int): String {
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        return ((this * factor).toLong() / factor).toString()
    }

    /**
     * Parse metrics packet from RX notifications (0x01 command).
     * Uses big-endian byte order for this packet type.
     * Position values scaled to mm (Issue #197).
     */
    private fun parseMetricsPacket(data: ByteArray) {
        if (data.size < 16) return

        try {
            // RX notification metrics use big-endian byte order
            val positionARaw = getUInt16BE(data, 2)
            val positionBRaw = getUInt16BE(data, 4)
            val loadA = getUInt16BE(data, 6)
            val loadB = getUInt16BE(data, 8)
            val velocityA = getUInt16BE(data, 10)
            val velocityB = getUInt16BE(data, 12)

            // Scale position to mm (Issue #197)
            val positionA = positionARaw / 10.0f
            val positionB = positionBRaw / 10.0f

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

            // Use tryEmit for non-blocking emission (matching parent repo)
            _metricsFlow.tryEmit(metric)

            if (handleDetectionEnabled) {
                val activeThreshold = 50.0f  // 50mm threshold (was 500 raw / 10)
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
     * Parse rep notification from RX characteristic (with opcode prefix).
     * Note: data[0] is the opcode (0x02), so rep data starts at index 1.
     * See parseRepsCharacteristicData() for direct REPS characteristic parsing.
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

                log.d { "Rep notification (24-byte format, RX):" }
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
            log.d { "🔥 Emitted rep event (RX): success=$emitted, legacy=${notification.isLegacyFormat}" }
        } catch (e: Exception) {
            log.e { "Error parsing rep notification: ${e.message}" }
        }
    }

    /**
     * Parse rep data from REPS characteristic notifications (NO opcode prefix).
     * Called when the dedicated REPS_UUID characteristic sends notifications.
     *
     * OFFICIAL APP FORMAT (24 bytes, Little Endian, NO opcode):
     * - Bytes 0-3:   up (Int/u32) - up counter (concentric completions)
     * - Bytes 4-7:   down (Int/u32) - down counter (eccentric completions)
     * - Bytes 8-11:  rangeTop (Float) - maximum ROM boundary
     * - Bytes 12-15: rangeBottom (Float) - minimum ROM boundary
     * - Bytes 16-17: repsRomCount (Short/u16) - Warmup reps with proper ROM
     * - Bytes 18-19: repsRomTotal (Short/u16) - Total reps regardless of ROM
     * - Bytes 20-21: repsSetCount (Short/u16) - WORKING SET REP COUNT
     * - Bytes 22-23: repsSetTotal (Short/u16) - Total reps in set
     *
     * LEGACY FORMAT (6 bytes):
     * - Bytes 0-1: topCounter (u16) - concentric completions
     * - Bytes 2-3: (unused)
     * - Bytes 4-5: completeCounter (u16) - eccentric completions
     */
    private fun parseRepsCharacteristicData(data: ByteArray) {
        if (data.size < 6) {
            log.w { "REPS characteristic data too short: ${data.size} bytes (minimum 6)" }
            return
        }

        try {
            val currentTime = currentTimeMillis()
            val notification: RepNotification

            // Log raw data for debugging
            log.i { "🔥 REPS CHAR notification: ${data.size} bytes" }
            log.d { "  hex=${data.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }}" }

            // Check if we have full 24-byte rep data (NO opcode prefix from REPS characteristic)
            if (data.size >= 24) {
                // FULL 24-byte packet - parse all fields (data starts at offset 0)
                val upCounter = getInt32LE(data, 0)
                val downCounter = getInt32LE(data, 4)
                val rangeTop = getFloatLE(data, 8)
                val rangeBottom = getFloatLE(data, 12)
                val repsRomCount = getUInt16LE(data, 16)
                val repsSetCount = getUInt16LE(data, 20)

                log.i { "🔥 REPS (24-byte official format):" }
                log.i { "  up=$upCounter, down=$downCounter" }
                log.i { "  repsRomCount=$repsRomCount (warmup), repsSetCount=$repsSetCount (working)" }
                log.i { "  rangeTop=$rangeTop, rangeBottom=$rangeBottom" }

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
                // LEGACY 6-byte packet (data starts at offset 0)
                val topCounter = getUInt16LE(data, 0)
                val completeCounter = getUInt16LE(data, 4)

                log.w { "🔥 REPS (LEGACY 6-byte format):" }
                log.w { "  top=$topCounter, complete=$completeCounter" }

                notification = RepNotification(
                    topCounter = topCounter,
                    completeCounter = completeCounter,
                    repsRomCount = 0,
                    repsSetCount = 0,
                    rangeTop = 0f,
                    rangeBottom = 0f,
                    isLegacyFormat = true,
                    timestamp = currentTime
                )
            }

            val emitted = _repEvents.tryEmit(notification)
            log.i { "🔥 Emitted rep event (REPS char): success=$emitted, legacy=${notification.isLegacyFormat}, repsSetCount=${notification.repsSetCount}" }
        } catch (e: Exception) {
            log.e { "Error parsing REPS characteristic data: ${e.message}" }
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
     * Read signed 16-bit integer in LITTLE-ENDIAN format (LSB first).
     * Used for position values which can be negative (Issue #197).
     */
    private fun getInt16LE(data: ByteArray, offset: Int): Int {
        val unsigned = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        // Sign-extend from 16-bit to 32-bit
        return if (unsigned >= 0x8000) unsigned - 0x10000 else unsigned
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
